package com.legacy.localsend.core;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Discovery 组播发现逻辑测试（注入组播包，避免依赖虚拟机/网络是否支持组播）。 */
public class DiscoveryTest {

    private File tempDir;
    private TestSupport.TempStorage storage;
    private TestSupport.RecordingListener serverListener;
    private LsServer server;
    private Discovery discovery;
    private DeviceInfo self;

    @Before
    public void setUp() throws Exception {
        tempDir = TestSupport.newTempDir("ls-discovery");
        storage = new TestSupport.TempStorage(tempDir);
        serverListener = new TestSupport.RecordingListener();

        ServerConfig config = new ServerConfig();
        config.port = 0;
        config.alias = "PeerServer";
        config.fingerprint = "peer-server-fp";
        config.storage = storage;
        config.listener = serverListener;
        config.autoAccept = true;
        server = new LsServer(config);
        server.start();

        discovery = new Discovery(53317);
        self = new DeviceInfo("Me", "MyPhone", "self-fp", 53317);
    }

    @After
    public void tearDown() {
        if (discovery != null) {
            discovery.stop();
        }
        if (server != null) {
            server.stop();
        }
        TestSupport.deleteRecursively(tempDir);
    }

    @Test
    public void announcementContainsRequiredFields() throws Exception {
        DeviceInfo info = new DeviceInfo("Nice Orange", "Samsung", "fp-1", 53317);
        info.deviceType = "mobile";
        Map<String, Object> announce = Discovery.buildAnnounce(info);

        assertEquals("Nice Orange", announce.get("alias"));
        assertEquals("2.0", announce.get("version"));
        assertEquals("Samsung", announce.get("deviceModel"));
        assertEquals("mobile", announce.get("deviceType"));
        assertEquals("fp-1", announce.get("fingerprint"));
        assertEquals(Long.valueOf(53317), announce.get("port"));
        assertEquals("http", announce.get("protocol"));
        assertEquals(Boolean.FALSE, announce.get("download"));
        assertEquals(Boolean.TRUE, announce.get("announce"));

        // 关键：announce 必须是合法 JSON，且带 announce:true。
        Map<String, Object> reparsed = Json.parseObject(Json.stringify(announce));
        assertEquals(Boolean.TRUE, reparsed.get("announce"));
    }

    @Test
    public void announceFalseCallbackDirectly() throws Exception {
        DeviceInfo peer = new DeviceInfo("DirectPeer", "PC", "peer-fp", 53317);
        Map<String, Object> announce = Discovery.buildAnnounce(peer);
        announce.put("announce", Boolean.FALSE);
        byte[] data = TestSupport.utf8(Json.stringify(announce));

        TestSupport.DiscoveryRecordingListener listener =
                new TestSupport.DiscoveryRecordingListener();
        discovery.handlePacket(data, 0, data.length, "192.168.1.20", self, listener);
        DeviceInfo found = listener.awaitFound(3000);
        assertNotNull("announce:false 应直接回调 onDeviceFound", found);
        assertEquals("DirectPeer", found.alias);
        assertEquals("192.168.1.20", listener.foundIps.poll(1, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    public void announceTrueRegistersBackThenCallback() throws Exception {
        DeviceInfo peer = new DeviceInfo("AnnouncedPeer", "PC", "peer-fp", server.getPort());
        Map<String, Object> announce = Discovery.buildAnnounce(peer);
        byte[] data = TestSupport.utf8(Json.stringify(announce));

        TestSupport.DiscoveryRecordingListener listener =
                new TestSupport.DiscoveryRecordingListener();
        discovery.handlePacket(data, 0, data.length, "127.0.0.1", self, listener);

        DeviceInfo found = listener.awaitFound(5000);
        assertNotNull("收到 announce:true 后应回调 onDeviceFound", found);
        // 回调的是 /register 的响应（PeerServer 的设备信息），端口为 HTTP 服务端口。
        assertEquals("PeerServer", found.alias);
        assertEquals(server.getPort(), found.port);

        // 对方服务器也应通过 /register 发现了我方。
        assertTrue(serverListener.awaitEvent("peer-discovered", 3000));
        assertEquals("Me", serverListener.discoveredPeer.alias);
    }

    @Test
    public void ownAnnouncementIsIgnored() throws Exception {
        Map<String, Object> announce = Discovery.buildAnnounce(self);
        announce.put("announce", Boolean.FALSE);
        byte[] data = TestSupport.utf8(Json.stringify(announce));

        TestSupport.DiscoveryRecordingListener listener =
                new TestSupport.DiscoveryRecordingListener();
        discovery.handlePacket(data, 0, data.length, "127.0.0.1", self, listener);
        assertNull("自己发的包应按 fingerprint 过滤", listener.awaitFound(500));
    }

    @Test
    public void malformedPacketIsIgnored() throws Exception {
        TestSupport.DiscoveryRecordingListener listener =
                new TestSupport.DiscoveryRecordingListener();
        byte[] data = TestSupport.utf8("this is not json");
        discovery.handlePacket(data, 0, data.length, "192.168.1.30", self, listener);
        assertNull(listener.awaitFound(300));

        byte[] empty = new byte[0];
        discovery.handlePacket(empty, 0, 0, "192.168.1.30", self, listener);
        assertNull(listener.awaitFound(200));
    }

    @Test
    public void localIPv4AddressesNeverThrows() {
        List<String> addresses = Discovery.localIPv4Addresses();
        assertNotNull(addresses);
        for (int i = 0; i < addresses.size(); i++) {
            String address = addresses.get(i);
            assertNotNull(address);
            assertTrue("不应返回回环地址: " + address, !address.startsWith("127."));
        }
    }

    @Test
    public void packetWithoutAnnounceFlagIsTreatedAsAnnouncement() throws Exception {
        // 最新官方 LocalSend 的组播消息已经不再带 announce 字段。
        Map<String, Object> message = Json.newObject();
        message.put("alias", "NewPeer");
        message.put("version", "2.2");
        message.put("fingerprint", "new-peer-fp");
        message.put("port", Long.valueOf(server.getPort()));
        message.put("protocol", "http");
        message.put("download", Boolean.FALSE);
        byte[] data = TestSupport.utf8(Json.stringify(message));

        TestSupport.DiscoveryRecordingListener listener =
                new TestSupport.DiscoveryRecordingListener();
        discovery.handlePacket(data, 0, data.length, "127.0.0.1", self, listener);
        DeviceInfo found = listener.awaitFound(5000);
        assertNotNull("没有 announce 字段的组播包应按 announcement 处理并回注册", found);
        assertEquals("PeerServer", found.alias);
    }

    @Test
    public void startAndStopDoNotThrow() throws Exception {
        int port = 52000 + new Random().nextInt(2000);
        Discovery running = new Discovery(port);
        try {
            running.start(self, new TestSupport.DiscoveryRecordingListener());
        } catch (IOException e) {
            Assume.assumeNoException("本机组播不可用，跳过 start/stop 测试", e);
            return;
        } finally {
            // start 成功或失败都确保释放资源。
            if (running != null) {
                running.stop();
            }
        }
    }
}
