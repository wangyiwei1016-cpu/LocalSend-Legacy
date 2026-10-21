package com.legacy.localsend.core;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** LsServer + LsClient 本机回环收发测试。 */
public class LsClientServerTest {

    private File receiveDir;
    private File sendDir;
    private TestSupport.TempStorage storage;
    private TestSupport.RecordingListener receiveListener;
    private TestSupport.RecordingListener sendListener;
    private LsServer server;
    private DeviceInfo self;

    @Before
    public void setUp() throws Exception {
        receiveDir = TestSupport.newTempDir("ls-recv");
        sendDir = TestSupport.newTempDir("ls-send");
        storage = new TestSupport.TempStorage(receiveDir);
        receiveListener = new TestSupport.RecordingListener();
        sendListener = new TestSupport.RecordingListener();
        self = new DeviceInfo("OldPhone", "Nokia N8", "self-fp", 53317);
        self.protocol = "http";

        ServerConfig config = new ServerConfig();
        config.port = 0;
        config.alias = "TestDevice";
        config.deviceModel = "TestModel";
        config.fingerprint = "test-fp";
        config.storage = storage;
        config.listener = receiveListener;
        config.autoAccept = false;
        config.enableV1 = true;
        server = new LsServer(config);
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
        TestSupport.deleteRecursively(receiveDir);
        TestSupport.deleteRecursively(sendDir);
    }

    private File writeSendFile(String name, byte[] content) throws IOException {
        File file = new File(sendDir, name);
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content);
        } finally {
            out.close();
        }
        return file;
    }

    private DeviceInfo target() {
        DeviceInfo target = new DeviceInfo("Peer", "PC", "peer-fp", server.getPort());
        target.protocol = "http";
        return target;
    }

    @Test
    public void sendTwoFilesOverLoopback() throws Exception {
        byte[] firstContent = TestSupport.utf8("第一个文件的内容 first file");
        byte[] secondContent = TestSupport.utf8("second file content 第二个");
        File firstFile = writeSendFile("first.txt", firstContent);
        File secondFile = writeSendFile("second.txt", secondContent);
        SendFile first = SendFile.fromFile(firstFile);
        first.meta.sha256 = HttpUtil.sha256Hex(firstContent);
        SendFile second = SendFile.fromFile(secondFile);

        List<SendFile> files = new ArrayList<SendFile>();
        files.add(first);
        files.add(second);

        LsClient client = new LsClient(self);
        client.send(target(), "127.0.0.1", files, sendListener);

        assertTrue("发送端应收到 onSendComplete", sendListener.sendComplete);
        assertFalse(sendListener.sendFailed);
        assertEquals("最后一个文件的进度应到达文件末尾",
                secondContent.length, sendListener.lastSendBytes);
        assertEquals(secondContent.length, sendListener.lastSendTotal);

        assertTrue("接收端应收到 onReceiveComplete",
                receiveListener.awaitEvent("receive-complete", 5000));
        assertEquals(2, receiveListener.completedFiles.size());
        TestSupport.assertFileContent(new File(receiveDir, "first.txt"), firstContent);
        TestSupport.assertFileContent(new File(receiveDir, "second.txt"), secondContent);
        assertEquals(self.alias, receiveListener.receiveRequest.sender.alias);
    }

    @Test
    public void httpsTargetThrowsExplanatoryIOException() throws Exception {
        byte[] content = TestSupport.utf8("x");
        SendFile sendFile = SendFile.fromFile(writeSendFile("x.txt", content));
        List<SendFile> files = new ArrayList<SendFile>();
        files.add(sendFile);

        DeviceInfo httpsTarget = target();
        httpsTarget.protocol = "https";
        LsClient client = new LsClient(self);
        try {
            client.send(httpsTarget, "127.0.0.1", files, sendListener);
            fail("目标为 https 时应抛 IOException");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
            assertTrue("异常信息应提示加密/HTTPS 问题: " + expected.getMessage(),
                    expected.getMessage().indexOf("HTTPS") >= 0
                            || expected.getMessage().indexOf("加密") >= 0);
        }
        assertFalse(sendListener.sendComplete);
        assertFalse("没有开始传输，不应有接收完成的文件",
                receiveListener.receiveComplete);
    }

    @Test
    public void cancelFromAnotherThreadInterruptsSend() throws Exception {
        final CountDownLatch streamOpened = new CountDownLatch(1);
        final byte[] payload = new byte[32 * 1024];
        FileSource slowSource = new FileSource() {
            @Override
            public long length() {
                return payload.length;
            }

            @Override
            public InputStream openStream() throws IOException {
                streamOpened.countDown();
                try {
                    Thread.sleep(600);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ByteArrayInputStream(payload);
            }
        };
        FileMeta meta = new FileMeta("slow-1", "slow.bin", payload.length,
                "application/octet-stream");
        SendFile sendFile = new SendFile(meta, slowSource);
        List<SendFile> files = new ArrayList<SendFile>();
        files.add(sendFile);

        final LsClient client = new LsClient(self);
        final IOException[] failure = new IOException[1];
        Thread sender = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    client.send(target(), "127.0.0.1", files, sendListener);
                } catch (IOException e) {
                    failure[0] = e;
                }
            }
        });
        sender.start();
        assertTrue("上传线程应已打开文件流", streamOpened.await(5, TimeUnit.SECONDS));
        client.cancel();
        sender.join(15000);
        assertFalse("发送线程应已结束", sender.isAlive());
        assertNotNull("取消后应抛 IOException", failure[0]);
        assertTrue("发送端应收到 onSendFailed", sendListener.sendFailed);
    }

    @Test
    public void staticRegisterAndFetchInfo() throws Exception {
        DeviceInfo registered = LsClient.register(self, "127.0.0.1", server.getPort());
        assertNotNull(registered);
        assertEquals("TestDevice", registered.alias);
        assertEquals(server.getPort(), registered.port);
        assertEquals("test-fp", registered.fingerprint);

        DeviceInfo fetched = LsClient.fetchInfo(self, "127.0.0.1", server.getPort());
        assertNotNull(fetched);
        assertEquals("TestDevice", fetched.alias);
    }
}
