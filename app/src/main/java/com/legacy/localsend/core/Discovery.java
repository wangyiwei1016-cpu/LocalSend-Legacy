package com.legacy.localsend.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 局域网设备发现：UDP 组播（224.0.0.167:53317）广播 + 监听。
 *
 * 协议（v2）：
 *   - 启动时向组播组发送 announcement（announce: true）；
 *   - 收到 announce: true 的包后，用 LsClient.register 回注册（TCP /register），再回调 onDeviceFound；
 *   - 收到 announce: false 的包直接回调 onDeviceFound；
 *   - 用 fingerprint 过滤自己；
 *   - 每 5 秒重播一次 announcement；
 *   - 组播不可用（部分 Android 设备/网络禁用组播）时只回调 onDiscoveryError，线程不退出、不崩溃。
 *
 * 注意：Android 上要真正收到组播包，UI/Service 层需要持有 WifiManager.MulticastLock，
 * 那是 android.* API，按项目约束不能放在 core 里。
 */
public class Discovery {

    public interface Listener {
        void onDeviceFound(DeviceInfo info, String ip);

        void onDiscoveryError(String message);
    }

    private static final String MULTICAST_GROUP = "224.0.0.167";
    private static final int DEFAULT_PORT = 53317;
    private static final long ANNOUNCE_INTERVAL_MS = 5000L;
    private static final int RECEIVE_BUFFER_SIZE = 65536;

    private final int port;

    private volatile MulticastSocket socket;
    private volatile InetAddress group;
    private volatile boolean running;
    private volatile DeviceInfo self;
    private volatile Listener listener;
    private volatile List<String> localAddresses;
    private Thread receiverThread;
    private Thread announcerThread;
    private volatile boolean announceErrorReported;

    public Discovery(int port) {
        this.port = port > 0 && port <= 65535 ? port : DEFAULT_PORT;
    }

    /**
     * 开始监听组播并周期广播。绑定端口失败抛 IOException；加入组播组失败只回调 onDiscoveryError。
     */
    public void start(DeviceInfo self, Listener listener) throws IOException {
        if (running) {
            return;
        }
        if (self == null) {
            throw new IOException("设备信息不能为空");
        }
        this.self = self;
        this.listener = listener;

        InetAddress groupAddress = InetAddress.getByName(MULTICAST_GROUP);
        MulticastSocket multicastSocket = new MulticastSocket(null);
        try {
            multicastSocket.setReuseAddress(true);
            multicastSocket.bind(new InetSocketAddress(this.port));
            multicastSocket.setTimeToLive(4);
            try {
                // 允许收到本机其它进程/本进程 socket 的包（自己发的会按 fingerprint 过滤）。
                multicastSocket.setLoopbackMode(false);
            } catch (SocketException ignored) {
                // 某些老设备不支持，忽略。
            }
            try {
                multicastSocket.joinGroup(groupAddress);
            } catch (IOException e) {
                // 组播接收不可用，但发送通常仍然可用：不阻断启动，只报错。
                notifyError("加入组播组失败（仍会尝试发送广播）: " + e.getMessage());
            }
        } catch (IOException e) {
            HttpUtil.closeQuietly(multicastSocket);
            throw e;
        }

        this.socket = multicastSocket;
        this.group = groupAddress;
        this.running = true;
        this.localAddresses = localIPv4Addresses();

        Thread receiver = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        }, "ls-discovery-rx");
        receiver.setDaemon(true);
        this.receiverThread = receiver;
        receiver.start();

        Thread announcer = new Thread(new Runnable() {
            @Override
            public void run() {
                announceLoop();
            }
        }, "ls-discovery-tx");
        announcer.setDaemon(true);
        this.announcerThread = announcer;
        announcer.start();
    }

    public void stop() {
        running = false;
        MulticastSocket multicastSocket = this.socket;
        this.socket = null;
        if (multicastSocket != null) {
            InetAddress groupAddress = this.group;
            if (groupAddress != null) {
                try {
                    multicastSocket.leaveGroup(groupAddress);
                } catch (Exception ignored) {
                    // ignore
                }
            }
            HttpUtil.closeQuietly(multicastSocket);
        }
        Thread receiver = this.receiverThread;
        if (receiver != null) {
            receiver.interrupt();
            joinQuietly(receiver, 800);
        }
        Thread announcer = this.announcerThread;
        if (announcer != null) {
            announcer.interrupt();
            joinQuietly(announcer, 800);
        }
        this.receiverThread = null;
        this.announcerThread = null;
        this.listener = null;
        this.self = null;
        this.localAddresses = null;
    }

    /** 立即广播一次自己的设备信息。 */
    public void announce() {
        DeviceInfo selfInfo = this.self;
        MulticastSocket multicastSocket = this.socket;
        InetAddress groupAddress = this.group;
        if (!running || selfInfo == null || multicastSocket == null || groupAddress == null) {
            return;
        }
        try {
            byte[] data = HttpUtil.utf8(Json.stringify(buildAnnounce(selfInfo)));
            DatagramPacket packet = new DatagramPacket(data, data.length, groupAddress, this.port);
            multicastSocket.send(packet);
            announceErrorReported = false;
        } catch (IOException e) {
            reportAnnounceError(e);
        } catch (RuntimeException e) {
            reportAnnounceError(e);
        }
    }

    /** 当前设备的 IPv4 地址列表（Wi-Fi/热点）。API 8 上返回空列表而不是崩溃。 */
    public static List<String> localIPv4Addresses() {
        try {
            return NetUtil.enumerateIpv4();
        } catch (Throwable ignored) {
            return new ArrayList<String>();
        }
    }

    // ==================================================================
    // 组播收发
    // ==================================================================

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        while (running) {
            try {
                MulticastSocket multicastSocket = this.socket;
                if (multicastSocket == null) {
                    break;
                }
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                multicastSocket.receive(packet);
                String senderIp = packet.getAddress() == null ? "" : packet.getAddress().getHostAddress();
                handlePacket(packet.getData(), packet.getOffset(), packet.getLength(), senderIp,
                        this.self, this.listener);
            } catch (IOException e) {
                if (!running) {
                    break;
                }
                notifyError("接收组播包失败: " + e.getMessage());
                sleepQuietly(500);
            } catch (RuntimeException e) {
                if (!running) {
                    break;
                }
                notifyError("处理组播包异常: " + e);
                sleepQuietly(500);
            }
        }
    }

    private void announceLoop() {
        announce();
        while (running) {
            sleepQuietly(ANNOUNCE_INTERVAL_MS);
            if (!running) {
                break;
            }
            announce();
        }
    }

    /**
     * 处理一个组播包。包级可见：便于在桌面单元测试里直接注入 announcement，
     * 不依赖虚拟机的组播支持。
     */
    void handlePacket(byte[] data, int offset, int length, String senderIp,
                      DeviceInfo selfInfo, Listener callback) {
        if (data == null || length <= 0) {
            return;
        }
        Map<String, Object> map;
        try {
            map = Json.parseObject(HttpUtil.utf8(data, offset, length));
        } catch (Exception e) {
            // 不是我们的包（或其它版本的包），忽略。
            return;
        }
        if (map == null) {
            return;
        }
        // v2.2 协议文档里 announcement 带 announce:true，UDP 兜底响应带 announce:false；
        // 但最新官方实现已经去掉了该字段（所有组播消息都是 announcement），
        // 因此字段缺失时按“announcement”处理。
        boolean hasFlag = map.containsKey("announce") || map.containsKey("announcement");
        boolean isAnnounce = hasFlag
                ? Json.optBoolean(map, "announce", Json.optBoolean(map, "announcement", true))
                : true;
        DeviceInfo peer = DeviceInfo.fromMap(map);
        if (peer == null) {
            return;
        }
        String selfFingerprint = selfInfo == null || selfInfo.fingerprint == null
                ? ""
                : selfInfo.fingerprint;
        if (selfFingerprint.length() > 0 && selfFingerprint.equals(peer.fingerprint)) {
            return; // 自己发的包
        }
        if (isFromSelfAddress(peer, senderIp, selfInfo)) {
            return;
        }
        if (isAnnounce) {
            respondToAnnounce(peer, senderIp, selfInfo, callback);
        } else {
            notifyDeviceFound(callback, peer, senderIp);
        }
    }

    /** fingerprint 为空时的兜底：源 IP 是自己的某个网卡地址且端口一致，视为自己。 */
    private boolean isFromSelfAddress(DeviceInfo peer, String senderIp, DeviceInfo selfInfo) {
        if (selfInfo == null || senderIp == null || senderIp.length() == 0) {
            return false;
        }
        int selfPort = selfInfo.port > 0 ? selfInfo.port : DEFAULT_PORT;
        if (peer.port != selfPort || peer.port <= 0) {
            return false;
        }
        List<String> addresses = this.localAddresses;
        return addresses != null && addresses.contains(senderIp);
    }

    private void respondToAnnounce(final DeviceInfo peer, final String senderIp,
                                   final DeviceInfo selfInfo, final Listener callback) {
        final int peerPort = peer.port > 0 ? peer.port : DEFAULT_PORT;
        if (selfInfo == null) {
            notifyDeviceFound(callback, peer, senderIp);
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                DeviceInfo discovered = null;
                try {
                    discovered = LsClient.register(selfInfo, senderIp, peerPort);
                } catch (IOException e) {
                    notifyError(callback, "向 " + senderIp + " 回注册失败: " + e.getMessage());
                }
                if (discovered != null) {
                    if (discovered.port <= 0) {
                        discovered.port = peerPort;
                    }
                    notifyDeviceFound(callback, discovered, senderIp);
                } else {
                    // 回注册失败（例如对端只开 HTTPS）也要让 UI 看到设备，便于提示用户。
                    notifyDeviceFound(callback, peer, senderIp);
                }
            }
        }, "ls-discovery-register");
        thread.setDaemon(true);
        thread.start();
    }

    private void notifyDeviceFound(Listener callback, DeviceInfo info, String ip) {
        if (callback == null || info == null) {
            return;
        }
        try {
            callback.onDeviceFound(info, ip);
        } catch (RuntimeException ignored) {
            // UI 回调异常不影响发现线程。
        }
    }

    private void notifyError(String message) {
        notifyError(this.listener, message);
    }

    private void notifyError(Listener callback, String message) {
        if (callback == null) {
            return;
        }
        try {
            callback.onDiscoveryError(message);
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    private void reportAnnounceError(Throwable error) {
        if (announceErrorReported) {
            return;
        }
        announceErrorReported = true;
        notifyError("发送组播广播失败: " + error.getMessage());
    }

    // ==================================================================
    // announcement JSON
    // ==================================================================

    /** 构造 v2 announcement（包级可见，便于单测）。 */
    static Map<String, Object> buildAnnounce(DeviceInfo self) {
        Map<String, Object> map = Json.newObject();
        map.put("alias", self.alias == null ? "" : self.alias);
        map.put("version", "2.0");
        if (self.deviceModel != null && self.deviceModel.length() > 0) {
            map.put("deviceModel", self.deviceModel);
        }
        map.put("deviceType", self.deviceType == null ? "mobile" : self.deviceType);
        map.put("fingerprint", self.fingerprint == null ? "" : self.fingerprint);
        map.put("port", Long.valueOf(self.port > 0 ? self.port : DEFAULT_PORT));
        map.put("protocol", "http");
        map.put("download", Boolean.FALSE);
        map.put("announce", Boolean.TRUE);
        return map;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
