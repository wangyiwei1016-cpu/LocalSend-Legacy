package com.legacy.localsend.ui;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.legacy.localsend.R;
import com.legacy.localsend.core.DeviceInfo;
import com.legacy.localsend.core.Discovery;
import com.legacy.localsend.core.FileMeta;
import com.legacy.localsend.core.IncomingFile;
import com.legacy.localsend.core.LsClient;
import com.legacy.localsend.core.LsServer;
import com.legacy.localsend.core.ReceiveRequest;
import com.legacy.localsend.core.SendFile;
import com.legacy.localsend.core.ServerConfig;
import com.legacy.localsend.core.TransferListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前台服务：持有 LsServer + Discovery + 设备列表，负责启动/停止服务、
 * 设备发现去重、接收确认（切主线程弹框并阻塞等待，60 秒超时）、后台发送文件。
 *
 * 所有 UiListener 回调都在主线程触发。
 */
public class ServerService extends Service implements TransferListener, Discovery.Listener {

    public static final String ACTION_START_SERVER = "com.legacy.localsend.action.START_SERVER";
    public static final String ACTION_STOP_SERVER = "com.legacy.localsend.action.STOP_SERVER";

    private static final String TAG = "LocalSendService";
    private static final long RECEIVE_CONFIRM_TIMEOUT_MS = 60000L;
    private static final long PROGRESS_NOTIFY_INTERVAL_MS = 800L;

    private static volatile ServerService instance;

    /** 界面回调，全部在主线程调用。 */
    public interface UiListener {
        void onStatusChanged(boolean running, String error);

        void onDevicesChanged(List<DeviceEntry> devices);

        void onReceiveConfirm(ReceiveRequest request, int requestId);

        /** 接收确认已被取消/超时，界面应关闭确认框。 */
        void onReceiveConfirmDismiss();

        void onReceiveProgress(FileMeta file, long bytesReceived, long totalBytes);

        void onReceiveComplete(List<IncomingFile> files);

        void onReceiveFailed(String reason);

        void onSendProgress(DeviceInfo target, FileMeta file, long bytesSent, long totalBytes);

        void onSendComplete(DeviceInfo target, List<FileMeta> files);

        void onSendFailed(DeviceInfo target, String reason);

        void onToast(String message, boolean longDuration);

        void onError(String title, String message);
    }

    /** 设备列表项：设备信息 + 它所在的 IP。 */
    public static final class DeviceEntry {
        public final DeviceInfo info;
        public final String ip;

        DeviceEntry(DeviceInfo info, String ip) {
            this.info = info == null ? new DeviceInfo() : info;
            this.ip = ip == null ? "" : ip;
        }

        public String key() {
            if (info.fingerprint != null && info.fingerprint.length() > 0) {
                return "fp:" + info.fingerprint;
            }
            return "ip:" + ip;
        }

        public String displayName() {
            return info.displayName();
        }

        public boolean isHttps() {
            return info.isHttps();
        }
    }

    private static final class PendingConfirm {
        private boolean decided;
        private boolean accepted;

        synchronized boolean await(long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!decided) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0L) {
                    return false;
                }
                try {
                    wait(remain);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return accepted;
        }

        synchronized void decide(boolean value) {
            if (decided) {
                return;
            }
            decided = true;
            accepted = value;
            notifyAll();
        }

        synchronized boolean isDecided() {
            return decided;
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, DeviceEntry> devices = new LinkedHashMap<String, DeviceEntry>();
    private final Map<Integer, PendingConfirm> pendingConfirms = new LinkedHashMap<Integer, PendingConfirm>();
    private final Object serverLock = new Object();

    private Prefs prefs;
    private AndroidStorage storage;
    private LsServer server;
    private Discovery discovery;
    private WifiManager.MulticastLock multicastLock;
    private boolean multicastLockHeld;
    private volatile boolean running;
    private volatile String statusMessage;
    private volatile List<String> localIps = new ArrayList<String>();
    private volatile UiListener ui;
    private volatile boolean uiVisible;
    private volatile boolean sending;
    private volatile LsClient activeSendClient;
    private volatile boolean sendFailureReported;
    private volatile long lastProgressNotify;
    private boolean foregroundStarted;
    private int nextConfirmId = 1;

    public static ServerService getInstance() {
        return instance;
    }

    public static void start(Context context) {
        if (context == null) {
            return;
        }
        try {
            context.startService(new Intent(context, ServerService.class).setAction(ACTION_START_SERVER));
        } catch (Throwable ignored) {
            // 服务启动失败时界面会通过按钮重试
        }
    }

    public static void stop(Context context) {
        if (context == null) {
            return;
        }
        try {
            context.startService(new Intent(context, ServerService.class).setAction(ACTION_STOP_SERVER));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 设置保存后重启服务（先停后启，等老服务处理完停止动作）。 */
    public static void restart(final Context context) {
        if (context == null) {
            return;
        }
        stop(context);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                start(context);
            }
        }, 600L);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = Prefs.get(this);
        storage = new AndroidStorage(this);
        Notifications.ensureChannel(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foregroundStarted) {
            foregroundStarted = true;
            try {
                startForeground(Notifications.ID_FOREGROUND,
                        Notifications.build(this, getString(R.string.notif_running_title),
                                getString(R.string.notif_booting), true));
            } catch (Throwable t) {
                Log.w(TAG, "startForeground failed", t);
            }
        }
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP_SERVER.equals(action)) {
            stopServer();
            foregroundStarted = false;
            try {
                stopForeground(true);
            } catch (Throwable ignored) {
                // 忽略
            }
            stopSelf();
        } else {
            // 包括 ACTION_START_SERVER、null（被系统重启）
            startServer();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        if (foregroundStarted) {
            foregroundStarted = false;
            try {
                stopForeground(true);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 对外 API（主线程调用）
    // ------------------------------------------------------------------

    public void setUiListener(UiListener listener) {
        this.ui = listener;
        this.uiVisible = listener != null;
        if (listener != null) {
            // 界面可见时不再需要旧的事件通知（避免状态栏出现两个图标）
            Notifications.cancelEvent(getApplicationContext());
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    UiListener current = ui;
                    if (current == null) {
                        return;
                    }
                    current.onStatusChanged(running, statusMessage);
                    current.onDevicesChanged(getDevices());
                }
            });
        }
    }

    public boolean isRunning() {
        return running;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public int getPort() {
        return prefs == null ? Prefs.DEFAULT_PORT : prefs.getPort();
    }

    public String getAlias() {
        return prefs == null ? Prefs.defaultAlias() : prefs.getAlias();
    }

    public String getLocalIpDisplay() {
        String ips = joinIps(localIps);
        if (ips != null) {
            return ips;
        }
        String fallback = UiUtil.localIpDisplay(getApplicationContext());
        return fallback == null ? getString(R.string.ip_unknown) : fallback;
    }

    public String getReceiveDirectory() {
        return prefs == null ? "" : prefs.getReceiveDirectory();
    }

    public List<DeviceEntry> getDevices() {
        synchronized (devices) {
            return new ArrayList<DeviceEntry>(devices.values());
        }
    }

    /** 接收确认结果回填（由 Activity 调用）。 */
    public void answerReceive(int requestId, boolean accepted) {
        PendingConfirm pending;
        synchronized (pendingConfirms) {
            pending = pendingConfirms.get(Integer.valueOf(requestId));
        }
        if (pending != null) {
            pending.decide(accepted);
        }
    }

    public synchronized void startServer() {
        if (running) {
            return;
        }
        statusMessage = null;
        ServerConfig config = new ServerConfig();
        config.port = prefs.getPort();
        config.alias = prefs.getAlias();
        config.deviceModel = prefs.getDeviceModel();
        config.deviceType = "mobile";
        config.fingerprint = prefs.getFingerprint();
        config.protocol = "http";
        config.download = false;
        config.autoAccept = prefs.isAutoAccept();
        config.pin = "";
        config.storage = storage;
        config.listener = this;

        LsServer newServer;
        try {
            newServer = new LsServer(config);
            newServer.start();
        } catch (UnsupportedOperationException e) {
            String detail = e.getMessage() == null ? "未实现" : e.getMessage();
            statusMessage = getString(R.string.core_not_ready, detail);
            running = false;
            server = null;
            Log.w(TAG, "core server not ready", e);
            updateForegroundNotification(getString(R.string.notif_core_missing_text));
            notifyStatus();
            return;
        } catch (IOException e) {
            statusMessage = getString(R.string.msg_start_failed, e.getMessage() == null ? "端口可能被占用" : e.getMessage());
            running = false;
            server = null;
            Log.w(TAG, "server start failed", e);
            updateForegroundNotification(getString(R.string.notif_core_missing_text));
            notifyStatus();
            return;
        } catch (Throwable t) {
            statusMessage = getString(R.string.msg_start_failed, String.valueOf(t));
            running = false;
            server = null;
            Log.w(TAG, "server start crashed", t);
            updateForegroundNotification(getString(R.string.notif_core_missing_text));
            notifyStatus();
            return;
        }

        server = newServer;
        running = true;
        acquireMulticastLock();
        refreshLocalIps();
        DeviceInfo self = selfInfo();
        try {
            self = newServer.getDeviceInfo();
        } catch (Throwable ignored) {
            // 用本地构造的信息兜底
        }
        try {
            Discovery newDiscovery = new Discovery(config.port);
            newDiscovery.start(self, this);
            discovery = newDiscovery;
        } catch (Throwable t) {
            Log.w(TAG, "discovery start failed", t);
        }
        updateForegroundNotification(foregroundText());
        notifyStatus();
        refreshLocalIps();
    }

    public synchronized void stopServer() {
        if (discovery != null) {
            try {
                discovery.stop();
            } catch (Throwable ignored) {
                // 忽略
            }
            discovery = null;
        }
        if (server != null) {
            try {
                server.stop();
            } catch (Throwable ignored) {
                // 忽略
            }
            server = null;
        }
        releaseMulticastLock();
        running = false;
        synchronized (devices) {
            devices.clear();
        }
        notifyDevices();
        notifyStatus();
        updateForegroundNotification(getString(R.string.notif_stopped));
    }

    /** 重新广播一次并刷新本机 IP。 */
    public void rescan() {
        refreshLocalIps();
        if (discovery != null) {
            try {
                discovery.announce();
            } catch (Throwable t) {
                Log.w(TAG, "announce failed", t);
            }
        }
        notifyStatus();
        notifyDevices();
        toast(getString(R.string.toast_rescan));
    }

    /** 手动添加设备：支持 "1.2.3.4" 或 "1.2.3.4:53317"。 */
    public void addManualDevice(final String address) {
        if (address == null || address.trim().length() == 0) {
            toast(getString(R.string.toast_manual_empty));
            return;
        }
        String input = address.trim();
        String host = input;
        int port = prefs.getPort();
        int colon = input.lastIndexOf(':');
        if (colon > 0) {
            String portPart = input.substring(colon + 1);
            try {
                int parsed = Integer.parseInt(portPart);
                if (parsed >= Prefs.MIN_PORT && parsed <= Prefs.MAX_PORT) {
                    port = parsed;
                    host = input.substring(0, colon);
                }
            } catch (NumberFormatException ignored) {
                // 不是端口，保留原字符串
            }
        }
        final String targetIp = host;
        final int targetPort = port;
        toast(getString(R.string.toast_manual_connecting, targetIp + ":" + targetPort));
        new Thread(new Runnable() {
            @Override
            public void run() {
                DeviceInfo self = selfInfo();
                DeviceInfo found = null;
                try {
                    found = LsClient.fetchInfo(self, targetIp, targetPort);
                } catch (Throwable t) {
                    Log.w(TAG, "fetchInfo failed", t);
                }
                if (found == null) {
                    try {
                        found = LsClient.register(self, targetIp, targetPort);
                    } catch (Throwable t) {
                        Log.w(TAG, "register failed", t);
                    }
                }
                if (found == null) {
                    toast(getString(R.string.toast_manual_failed, targetIp));
                    return;
                }
                mergeDevice(found, targetIp);
                toast(targetIp + " " + found.displayName());
            }
        }).start();
    }

    /** 后台线程发送文件。 */
    public void sendFiles(final DeviceInfo target, final String targetIp, final List<SendFile> files) {
        if (files == null || files.isEmpty()) {
            toast(getString(R.string.toast_no_files));
            return;
        }
        if (target == null || targetIp == null || targetIp.length() == 0) {
            toast(getString(R.string.toast_select_device));
            return;
        }
        if (target.isHttps()) {
            notifyError(getString(R.string.https_title), getString(R.string.https_unsupported));
            return;
        }
        if (sending) {
            toast(getString(R.string.toast_send_busy));
            return;
        }
        sending = true;
        sendFailureReported = false;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                LsClient client = createSendClient();
                activeSendClient = client;
                try {
                    client.send(target, targetIp, files, ServerService.this);
                } catch (UnsupportedOperationException e) {
                    reportSendFailure(target, getString(R.string.core_not_ready, String.valueOf(e.getMessage())));
                } catch (IOException e) {
                    reportSendFailure(target, friendlySendError(e));
                } catch (Throwable t) {
                    reportSendFailure(target, friendlySendError(t));
                } finally {
                    activeSendClient = null;
                    sending = false;
                }
            }
        });
        thread.setName("localsend-send");
        thread.start();
    }

    public void cancelSend() {
        LsClient client = activeSendClient;
        if (client != null) {
            try {
                client.cancel();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        toast(getString(R.string.toast_send_cancelled));
    }

    // ------------------------------------------------------------------
    // TransferListener（在核心的工作线程上被调用）
    // ------------------------------------------------------------------

    @Override
    public boolean onReceiveRequest(final ReceiveRequest request) {
        final boolean autoAccept = prefs.isAutoAccept();
        if (!uiVisible || ui == null) {
            // 界面不可见：按自动接收设置决定
            final String sender = request == null ? "未知设备" : request.displayName();
            if (autoAccept) {
                Log.i(TAG, "auto accept from " + sender);
            } else {
                Log.i(TAG, "reject from " + sender + " (ui not visible, autoAccept off)");
            }
            if (ui != null) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        UiListener listener = ui;
                        if (listener != null) {
                            listener.onToast(autoAccept
                                    ? getString(R.string.toast_auto_accepted, sender)
                                    : getString(R.string.toast_rejected_background, sender), true);
                        }
                    }
                });
            }
            return autoAccept;
        }

        final int requestId = nextConfirmId();
        final PendingConfirm pending = new PendingConfirm();
        synchronized (pendingConfirms) {
            pendingConfirms.put(Integer.valueOf(requestId), pending);
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                UiListener listener = ui;
                if (listener == null || !uiVisible) {
                    pending.decide(false);
                    removePendingConfirm(requestId);
                    return;
                }
                listener.onReceiveConfirm(request, requestId);
            }
        });
        boolean accepted = pending.await(RECEIVE_CONFIRM_TIMEOUT_MS);
        removePendingConfirm(requestId);
        if (!accepted && !pending.isDecided()) {
            // 超时：让界面关掉还在显示的确认框
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    UiListener listener = ui;
                    if (listener != null) {
                        listener.onReceiveConfirmDismiss();
                    }
                }
            });
        }
        return accepted;
    }

    @Override
    public void onReceiveProgress(final String sessionId, final FileMeta file, final long bytesReceived, final long totalBytes) {
        maybeUpdateProgressNotification(getString(R.string.recv_progress_title) + " "
                + (file == null ? "" : file.displayName()));
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                UiListener listener = ui;
                if (listener != null) {
                    listener.onReceiveProgress(file, bytesReceived, totalBytes);
                }
            }
        });
    }

    @Override
    public void onReceiveComplete(final String sessionId, final List<IncomingFile> files) {
        final int count = files == null ? 0 : files.size();
        final String location;
        if (files != null && !files.isEmpty()) {
            location = files.get(0).describe();
        } else {
            location = getString(R.string.notif_stopped);
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                updateForegroundNotification(foregroundText());
                UiListener listener = ui;
                if (listener != null) {
                    listener.onReceiveComplete(files);
                } else {
                    // 界面不在前台时也记录历史，下次打开可以查看/打开/删除
                    HistoryStore.addReceived(ServerService.this, files);
                    Notifications.notifyEvent(ServerService.this,
                            getString(R.string.notif_recv_done_title),
                            getString(R.string.notif_recv_done_text, Integer.valueOf(count), location));
                }
            }
        });
    }

    @Override
    public void onReceiveFailed(final String sessionId, final String reason) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                updateForegroundNotification(foregroundText());
                UiListener listener = ui;
                if (listener != null) {
                    listener.onReceiveFailed(reason);
                } else {
                    Notifications.notifyEvent(ServerService.this,
                            getString(R.string.recv_failed_title), String.valueOf(reason));
                }
            }
        });
    }

    @Override
    public void onSendProgress(final DeviceInfo target, final FileMeta file, final long bytesSent, final long totalBytes) {
        maybeUpdateProgressNotification(getString(R.string.send_progress_title) + " "
                + (file == null ? "" : file.displayName()));
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                UiListener listener = ui;
                if (listener != null) {
                    listener.onSendProgress(target, file, bytesSent, totalBytes);
                }
            }
        });
    }

    @Override
    public void onSendComplete(final DeviceInfo target, final List<FileMeta> files) {
        sendFailureReported = true;
        final int count = files == null ? 0 : files.size();
        final String targetName = target == null ? "" : target.displayName();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                updateForegroundNotification(foregroundText());
                UiListener listener = ui;
                if (listener != null) {
                    listener.onSendComplete(target, files);
                } else {
                    Notifications.notifyEvent(ServerService.this,
                            getString(R.string.notif_send_done_title),
                            getString(R.string.notif_send_done_text, targetName, Integer.valueOf(count)));
                }
            }
        });
    }

    @Override
    public void onSendFailed(final DeviceInfo target, final String reason) {
        sendFailureReported = true;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                updateForegroundNotification(foregroundText());
                UiListener listener = ui;
                if (listener != null) {
                    listener.onSendFailed(target, reason);
                } else {
                    Notifications.notifyEvent(ServerService.this,
                            getString(R.string.send_failed_title), String.valueOf(reason));
                }
            }
        });
    }

    @Override
    public void onPeerDiscovered(DeviceInfo info, String ip) {
        mergeDevice(info, ip);
    }

    @Override
    public void onLog(String message) {
        Log.d(TAG, String.valueOf(message));
    }

    // ------------------------------------------------------------------
    // Discovery.Listener
    // ------------------------------------------------------------------

    @Override
    public void onDeviceFound(DeviceInfo info, String ip) {
        mergeDevice(info, ip);
    }

    @Override
    public void onDiscoveryError(final String message) {
        Log.w(TAG, "discovery error: " + message);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                UiListener listener = ui;
                if (listener != null) {
                    listener.onToast(getString(R.string.msg_discovery_error, String.valueOf(message)), false);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private void acquireMulticastLock() {
        if (multicastLockHeld) {
            return;
        }
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return;
            }
            multicastLock = wifiManager.createMulticastLock(TAG);
            multicastLock.acquire();
            multicastLockHeld = true;
            Log.d(TAG, "multicast lock acquired");
        } catch (Throwable t) {
            Log.w(TAG, "multicast lock acquire failed", t);
        }
    }

    private void releaseMulticastLock() {
        if (!multicastLockHeld) {
            return;
        }
        try {
            if (multicastLock != null) {
                multicastLock.release();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        multicastLock = null;
        multicastLockHeld = false;
    }

    private DeviceInfo selfInfo() {
        DeviceInfo info = new DeviceInfo(prefs.getAlias(), prefs.getDeviceModel(), prefs.getFingerprint(), prefs.getPort());
        info.deviceType = "mobile";
        info.protocol = "http";
        info.download = false;
        return info;
    }

    private int nextConfirmId() {
        int id;
        synchronized (pendingConfirms) {
            id = nextConfirmId++;
            if (nextConfirmId > 1000000) {
                nextConfirmId = 1;
            }
        }
        return id;
    }

    private void removePendingConfirm(int requestId) {
        synchronized (pendingConfirms) {
            pendingConfirms.remove(Integer.valueOf(requestId));
        }
    }

    private void refreshLocalIps() {
        List<String> ips = new ArrayList<String>();
        // 核心用 NetworkInterface 枚举网卡（API 9+），API 8 上会抛错，先按版本跳过
        if (Build.VERSION.SDK_INT >= 9) {
            try {
                List<String> fromCore = Discovery.localIPv4Addresses();
                if (fromCore != null) {
                    ips.addAll(fromCore);
                }
            } catch (Throwable ignored) {
                // 组播/网卡枚举失败不影响服务
            }
        }
        // WifiManager(API 1) + 网卡枚举兜底
        List<String> fromUi = UiUtil.localIPv4Addresses(this);
        for (int i = 0; i < fromUi.size(); i++) {
            String address = fromUi.get(i);
            if (!ips.contains(address)) {
                ips.add(address);
            }
        }
        localIps = ips;
    }

    private static String joinIps(List<String> ips) {
        if (ips == null || ips.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ips.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ips.get(i));
        }
        return sb.toString();
    }

    private void mergeDevice(DeviceInfo info, String ip) {
        if (info == null) {
            return;
        }
        String fingerprint = info.fingerprint;
        if (fingerprint != null && fingerprint.length() > 0 && fingerprint.equals(prefs.getFingerprint())) {
            return; // 自己
        }
        final String deviceIp = ip == null ? "" : ip;
        boolean changed = false;
        synchronized (devices) {
            String existingKey = null;
            for (Map.Entry<String, DeviceEntry> entry : devices.entrySet()) {
                DeviceEntry current = entry.getValue();
                // 同 IP 还要求端口相同才算同一设备（同 IP 不同端口可能是两台不同的 LocalSend 实例）
                if (current.ip.equals(deviceIp) && current.info.port == info.port) {
                    existingKey = entry.getKey();
                    break;
                }
                if (fingerprint != null && fingerprint.length() > 0
                        && fingerprint.equals(current.info.fingerprint)) {
                    existingKey = entry.getKey();
                    break;
                }
            }
            if (existingKey != null) {
                DeviceEntry old = devices.remove(existingKey);
                if (old == null || !sameDevice(old.info, info) || !old.ip.equals(deviceIp)) {
                    changed = true;
                }
            } else {
                changed = true;
            }
            devices.put(deviceKey(info, deviceIp), new DeviceEntry(info.copy(), deviceIp));
        }
        if (changed) {
            notifyDevices();
            Log.d(TAG, "device merged: " + info.displayName() + " @ " + deviceIp);
        }
    }

    private static String deviceKey(DeviceInfo info, String ip) {
        if (info != null && info.fingerprint != null && info.fingerprint.length() > 0) {
            return "fp:" + info.fingerprint;
        }
        int port = info == null ? 0 : info.port;
        return "ip:" + ip + ":" + port;
    }

    private static boolean sameDevice(DeviceInfo a, DeviceInfo b) {
        if (a == null || b == null) {
            return false;
        }
        return equalsText(a.alias, b.alias)
                && equalsText(a.protocol, b.protocol)
                && equalsText(a.deviceModel, b.deviceModel)
                && a.port == b.port;
    }

    private static boolean equalsText(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void notifyDevices() {
        final List<DeviceEntry> snapshot = getDevices();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                UiListener listener = ui;
                if (listener != null) {
                    listener.onDevicesChanged(snapshot);
                }
            }
        });
    }

    private void notifyStatus() {
        final boolean isRunning = running;
        final String message = statusMessage;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                UiListener listener = ui;
                if (listener != null) {
                    listener.onStatusChanged(isRunning, message);
                }
            }
        });
    }

    private String foregroundText() {
        if (statusMessage != null && statusMessage.length() > 0) {
            return statusMessage;
        }
        return getString(R.string.notif_running_text, Integer.valueOf(prefs.getPort()),
                isRunning() ? getString(R.string.state_running) : getString(R.string.state_stopped));
    }

    private void updateForegroundNotification(String text) {
        if (!foregroundStarted) {
            return;
        }
        try {
            Notification notification = Notifications.build(this,
                    getString(R.string.notif_running_title), text, true);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(Notifications.ID_FOREGROUND, notification);
            }
        } catch (Throwable t) {
            Log.w(TAG, "update notification failed", t);
        }
    }

    private void maybeUpdateProgressNotification(String text) {
        long now = System.currentTimeMillis();
        if (now - lastProgressNotify < PROGRESS_NOTIFY_INTERVAL_MS) {
            return;
        }
        lastProgressNotify = now;
        updateForegroundNotification(text);
    }

    private LsClient createSendClient() {
        // 带上本机别名/指纹，对端显示本机信息更准确
        try {
            return new LsClient(selfInfo());
        } catch (Throwable t) {
            return new LsClient();
        }
    }

    private String friendlySendError(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        String lower = message == null ? "" : message.toLowerCase();
        if (lower.indexOf("https") >= 0 || lower.indexOf("tls") >= 0
                || lower.indexOf("ssl") >= 0 || message.indexOf("加密") >= 0) {
            return getString(R.string.https_unsupported);
        }
        if (message == null || message.length() == 0) {
            message = throwable == null ? "未知错误" : throwable.getClass().getName();
        }
        return getString(R.string.msg_send_failed, message);
    }

    private void reportSendFailure(final DeviceInfo target, final String reason) {
        if (sendFailureReported) {
            return;
        }
        sendFailureReported = true;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                updateForegroundNotification(foregroundText());
                Notifications.notifyEvent(ServerService.this, getString(R.string.send_failed_title), String.valueOf(reason));
                UiListener listener = ui;
                if (listener != null) {
                    listener.onSendFailed(target, reason);
                }
            }
        });
    }

    private void toast(final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                UiListener listener = ui;
                if (listener != null) {
                    listener.onToast(message, false);
                }
            }
        });
    }

    private void notifyError(final String title, final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                UiListener listener = ui;
                if (listener != null) {
                    listener.onError(title, message);
                } else {
                    Notifications.notifyEvent(ServerService.this, title, message);
                }
            }
        });
    }
}
