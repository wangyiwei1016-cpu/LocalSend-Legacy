package com.legacy.localsend.core;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * LsServer v2 端点测试：prepare-upload/upload/info/register/cancel，
 * 以及 PIN / 409 / 403 / 422 / path traversal / chunked / 100-continue / 并行上传。
 */
public class LsServerV2Test {

    private File tempDir;
    private TestSupport.TempStorage storage;
    private TestSupport.RecordingListener listener;
    private LsServer server;
    private final List<LsServer> startedServers = new ArrayList<LsServer>();

    @Before
    public void setUp() throws Exception {
        tempDir = TestSupport.newTempDir("ls-server");
        storage = new TestSupport.TempStorage(tempDir);
        listener = new TestSupport.RecordingListener();
        server = startServer(newConfig());
    }

    @After
    public void tearDown() {
        for (int i = startedServers.size() - 1; i >= 0; i--) {
            try {
                startedServers.get(i).stop();
            } catch (RuntimeException ignored) {
                // ignore
            }
        }
        startedServers.clear();
        TestSupport.deleteRecursively(tempDir);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private ServerConfig newConfig() {
        ServerConfig config = new ServerConfig();
        config.port = 0;
        config.alias = "TestDevice";
        config.deviceModel = "TestModel";
        config.fingerprint = "test-fp";
        config.storage = storage;
        config.listener = listener;
        config.autoAccept = false;
        config.enableV1 = true;
        return config;
    }

    private LsServer startServer(ServerConfig config) throws IOException {
        LsServer started = new LsServer(config);
        started.start();
        startedServers.add(started);
        return started;
    }

    private int port() {
        return server.getPort();
    }

    private Map<String, Object> prepare(String json) throws Exception {
        HttpUtil.HttpResult result = TestSupport.postJson(port(),
                "/api/localsend/v2/prepare-upload", json);
        TestSupport.assertStatus(200, result);
        return TestSupport.jsonObject(result);
    }

    private static String sessionIdOf(Map<String, Object> response) {
        return Json.optString(response, "sessionId", null);
    }

    private static String tokenOf(Map<String, Object> response, String fileId) {
        Map<String, Object> files = Json.optObject(response, "files");
        return files == null ? null : Json.optString(files, fileId, null);
    }

    private static String uploadPath(String sessionId, String fileId, String token) {
        return "/api/localsend/v2/upload?sessionId=" + HttpUtil.encodeQueryParam(sessionId)
                + "&fileId=" + HttpUtil.encodeQueryParam(fileId)
                + "&token=" + HttpUtil.encodeQueryParam(token);
    }

    // ------------------------------------------------------------------
    // 基础端点
    // ------------------------------------------------------------------

    @Test
    public void infoReturnsDeviceInfo() throws Exception {
        HttpUtil.HttpResult result = TestSupport.http(port(), "GET",
                "/api/localsend/v2/info", null);
        TestSupport.assertStatus(200, result);
        Map<String, Object> info = TestSupport.jsonObject(result);
        assertEquals("TestDevice", info.get("alias"));
        assertEquals("TestModel", info.get("deviceModel"));
        assertEquals("test-fp", info.get("fingerprint"));
        assertEquals("2.0", info.get("version"));
        assertEquals(Long.valueOf(port()), info.get("port"));
        assertEquals("http", info.get("protocol"));
    }

    @Test
    public void registerNotifiesPeerDiscovered() throws Exception {
        DeviceInfo peer = new DeviceInfo("PeerAlias", "PeerPC", "peer-fp", 53317);
        peer.protocol = "http";
        HttpUtil.HttpResult result = TestSupport.postJson(port(),
                "/api/localsend/v2/register", Json.stringify(peer.toMap()));
        TestSupport.assertStatus(200, result);
        Map<String, Object> response = TestSupport.jsonObject(result);
        assertEquals("TestDevice", response.get("alias"));

        assertTrue(listener.awaitEvent("peer-discovered", 3000));
        assertNotNull(listener.discoveredPeer);
        assertEquals("PeerAlias", listener.discoveredPeer.alias);
        assertEquals("peer-fp", listener.discoveredPeer.fingerprint);
        assertEquals("127.0.0.1", listener.discoveredIp);
    }

    @Test
    public void unknownRouteReturns404() throws Exception {
        TestSupport.assertStatus(404, TestSupport.http(port(), "GET", "/nope", null));
        TestSupport.assertStatus(404, TestSupport.http(port(), "POST",
                "/api/localsend/v2/unknown", TestSupport.utf8("{}")));
    }

    // ------------------------------------------------------------------
    // 完整接收流程
    // ------------------------------------------------------------------

    @Test
    public void prepareAndUploadHappyPath() throws Exception {
        byte[] content = TestSupport.utf8("hello localsend 老手机");
        Map<String, Object> response = prepare(TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("file-1", "hello.txt", content.length,
                        HttpUtil.sha256Hex(content))));
        String sessionId = sessionIdOf(response);
        String token = tokenOf(response, "file-1");
        assertNotNull(sessionId);
        assertNotNull(token);

        HttpUtil.HttpResult upload = TestSupport.http(port(), "POST",
                uploadPath(sessionId, "file-1", token), content);
        TestSupport.assertStatus(200, upload);

        assertTrue(listener.awaitEvent("receive-complete", 3000));
        assertEquals(sessionId, listener.completedSessionId);
        assertEquals(1, listener.completedFiles.size());
        assertEquals(content.length, listener.lastReceiveBytes);
        assertEquals(content.length, listener.lastReceiveTotal);

        File saved = new File(tempDir, "hello.txt");
        TestSupport.assertFileContent(saved, content);
        assertEquals("Sender", listener.receiveRequest.sender.alias);
        assertEquals("127.0.0.1", listener.receiveRequest.senderIp);
        assertEquals(1, listener.receiveRequest.files.size());
    }

    @Test
    public void rejectedTransferReturns403AndReleasesSession() throws Exception {
        listener.acceptRequest = false;
        Map<String, Object> files = TestSupport.singleFile("f1", "x.txt", 1, null);
        HttpUtil.HttpResult result = TestSupport.postJson(port(),
                "/api/localsend/v2/prepare-upload",
                TestSupport.prepareJson(TestSupport.senderDevice(), files));
        TestSupport.assertStatus(403, result);
        assertFalse(listener.receiveComplete);

        // 拒绝后不应残留活动会话：下一次请求应能正常进入。
        listener.acceptRequest = true;
        HttpUtil.HttpResult second = TestSupport.postJson(port(),
                "/api/localsend/v2/prepare-upload",
                TestSupport.prepareJson(TestSupport.senderDevice(), files));
        TestSupport.assertStatus(200, second);
    }

    @Test
    public void pinRequiredAndValidated() throws Exception {
        ServerConfig config = newConfig();
        config.pin = "1234";
        LsServer pinned = startServer(config);
        int pinnedPort = pinned.getPort();
        String json = TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "a.txt", 3, null));

        TestSupport.assertStatus(401, TestSupport.postJson(pinnedPort,
                "/api/localsend/v2/prepare-upload", json));
        TestSupport.assertStatus(401, TestSupport.postJson(pinnedPort,
                "/api/localsend/v2/prepare-upload?pin=0000", json));
        TestSupport.assertStatus(200, TestSupport.postJson(pinnedPort,
                "/api/localsend/v2/prepare-upload?pin=1234", json));
    }

    @Test
    public void tooManyWrongPinsReturns429() throws Exception {
        ServerConfig config = newConfig();
        config.pin = "1234";
        LsServer pinned = startServer(config);
        String json = TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "a.txt", 3, null));
        for (int i = 0; i < 3; i++) {
            TestSupport.assertStatus(401, TestSupport.postJson(pinned.getPort(),
                    "/api/localsend/v2/prepare-upload?pin=wrong", json));
        }
        // 连续输错 3 次后，即使 PIN 正确也返回 429（与官方实现一致的防爆破策略）。
        TestSupport.assertStatus(429, TestSupport.postJson(pinned.getPort(),
                "/api/localsend/v2/prepare-upload?pin=1234", json));
    }

    @Test
    public void secondSessionGets409UntilFirstCompletes() throws Exception {
        byte[] content = TestSupport.utf8("session-one");
        Map<String, Object> response = prepare(TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "one.txt", content.length, null)));
        String sessionId = sessionIdOf(response);
        String token = tokenOf(response, "f1");

        HttpUtil.HttpResult second = TestSupport.postJson(port(),
                "/api/localsend/v2/prepare-upload",
                TestSupport.prepareJson(TestSupport.senderDevice(),
                        TestSupport.singleFile("f2", "two.txt", 3, null)));
        TestSupport.assertStatus(409, second);

        // 完成第一个会话后应重新可用。
        TestSupport.assertStatus(200, TestSupport.http(port(), "POST",
                uploadPath(sessionId, "f1", token), content));
        assertTrue(listener.awaitEvent("receive-complete", 3000));

        HttpUtil.HttpResult third = TestSupport.postJson(port(),
                "/api/localsend/v2/prepare-upload",
                TestSupport.prepareJson(TestSupport.senderDevice(),
                        TestSupport.singleFile("f3", "three.txt", 3, null)));
        TestSupport.assertStatus(200, third);
    }

    @Test
    public void uploadWithWrongSessionIdReturns409() throws Exception {
        Map<String, Object> response = prepare(TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "x.txt", 3, null)));
        String token = tokenOf(response, "f1");
        TestSupport.assertStatus(409, TestSupport.http(port(), "POST",
                uploadPath("bogus-session", "f1", token), TestSupport.utf8("abc")));
    }

    @Test
    public void uploadWithWrongTokenReturns403() throws Exception {
        Map<String, Object> response = prepare(TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "x.txt", 3, null)));
        String sessionId = sessionIdOf(response);
        TestSupport.assertStatus(403, TestSupport.http(port(), "POST",
                uploadPath(sessionId, "f1", "wrong-token"), TestSupport.utf8("abc")));
        assertFalse(listener.receiveComplete);
    }

    @Test
    public void checksumMismatchReturns422AndFailsSession() throws Exception {
        byte[] actual = TestSupport.utf8("hallo");
        byte[] declaredContent = TestSupport.utf8("hello");
        Map<String, Object> response = prepare(TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "bad.txt", actual.length,
                        HttpUtil.sha256Hex(declaredContent))));
        String sessionId = sessionIdOf(response);
        String token = tokenOf(response, "f1");

        HttpUtil.HttpResult upload = TestSupport.http(port(), "POST",
                uploadPath(sessionId, "f1", token), actual);
        TestSupport.assertStatus(422, upload);
        assertTrue(listener.awaitEvent("receive-failed", 3000));
        assertFalse("校验失败的半成品不应提交", new File(tempDir, "bad.txt").exists());
    }

    @Test
    public void pathTraversalFileNameIsSanitized() throws Exception {
        byte[] content = TestSupport.utf8("evil");
        Map<String, Object> response = prepare(TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "../../evil.txt", content.length,
                        HttpUtil.sha256Hex(content))));
        String sessionId = sessionIdOf(response);
        String token = tokenOf(response, "f1");

        assertEquals("StorageProvider 应收到清洗后的文件名",
                FileMeta.sanitizeFileName("../../evil.txt"),
                storage.allocated.get(0).fileName);
        assertTrue(storage.allocated.get(0).fileName.indexOf('/') < 0);
        assertTrue(storage.allocated.get(0).fileName.indexOf('\\') < 0);

        TestSupport.assertStatus(200, TestSupport.http(port(), "POST",
                uploadPath(sessionId, "f1", token), content));
        assertTrue(listener.awaitEvent("receive-complete", 3000));

        List<File> committed = storage.committed;
        assertEquals(1, committed.size());
        assertEquals("文件必须直接落在接收目录内，不能穿越出去",
                tempDir.getAbsoluteFile(), committed.get(0).getParentFile().getAbsoluteFile());
        assertTrue(committed.get(0).getName().indexOf("evil") >= 0);
        TestSupport.assertFileContent(committed.get(0), content);
    }

    @Test
    public void cancelFailsSessionAndAllowsNewOne() throws Exception {
        Map<String, Object> response = prepare(TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "c.txt", 5, null)));
        String sessionId = sessionIdOf(response);

        HttpUtil.HttpResult cancel = TestSupport.http(port(), "POST",
                "/api/localsend/v2/cancel?sessionId=" + sessionId, null);
        TestSupport.assertStatus(200, cancel);
        assertTrue(listener.awaitEvent("receive-failed", 3000));
        assertNotNull(listener.failedReason);
        assertFalse(listener.receiveComplete);

        HttpUtil.HttpResult second = TestSupport.postJson(port(),
                "/api/localsend/v2/prepare-upload",
                TestSupport.prepareJson(TestSupport.senderDevice(),
                        TestSupport.singleFile("f2", "d.txt", 5, null)));
        TestSupport.assertStatus(200, second);
    }

    @Test
    public void uploadWithoutContentLengthAndWithoutChunkedReturns400() throws Exception {
        Map<String, Object> response = prepare(TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "x.txt", 3, null)));
        String sessionId = sessionIdOf(response);
        String token = tokenOf(response, "f1");
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port()), 5000);
        socket.setSoTimeout(10000);
        try {
            OutputStream out = socket.getOutputStream();
            out.write(TestSupport.utf8("POST " + uploadPath(sessionId, "f1", token)
                    + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"));
            out.flush();
            HttpUtil.HttpResult result = HttpUtil.readResponse(socket.getInputStream(), 1024 * 1024);
            TestSupport.assertStatus(400, result);
        } finally {
            socket.close();
        }
    }

    // ------------------------------------------------------------------
    // M1：声明大小 / 磁盘写入上限防护
    // ------------------------------------------------------------------

    @Test
    public void contentLengthMismatchDeclaredSizeReturns400() throws Exception {
        byte[] actual = TestSupport.utf8("0123456789"); // 10 字节，但 prepare 声明只有 5
        Map<String, Object> response = prepare(TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "tiny.txt", 5, null)));
        String sessionId = sessionIdOf(response);
        String token = tokenOf(response, "f1");
        assertNotNull(token);

        HttpUtil.HttpResult upload = TestSupport.http(port(), "POST",
                uploadPath(sessionId, "f1", token), actual);
        TestSupport.assertStatus(400, upload);
        assertTrue(listener.awaitEvent("receive-failed", 3000));
        assertFalse("声明大小不符时不能提交文件", new File(tempDir, "tiny.txt").exists());
        assertTrue("不应残留半成品文件: " + tempDir, isEmptyDir(tempDir));
    }

    @Test
    public void chunkedUploadExceedingDeclaredSizeReturns400AndAborts() throws Exception {
        byte[] actual = TestSupport.utf8("0123456789"); // 10 字节，声明只有 5
        Map<String, Object> response = prepare(TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "bomb.bin", 5, null)));
        String sessionId = sessionIdOf(response);
        String token = tokenOf(response, "f1");

        HttpUtil.HttpResult upload = TestSupport.postChunked(port(),
                uploadPath(sessionId, "f1", token), actual, 3);
        TestSupport.assertStatus(400, upload);
        assertTrue(listener.awaitEvent("receive-failed", 3000));
        assertFalse("超声明大小的上传不能落盘", new File(tempDir, "bomb.bin").exists());
        assertTrue("超限后应 abort 半成品: " + tempDir, isEmptyDir(tempDir));

        // 会话应已释放，可以接受下一轮传输。
        HttpUtil.HttpResult next = TestSupport.postJson(port(),
                "/api/localsend/v2/prepare-upload",
                TestSupport.prepareJson(TestSupport.senderDevice(),
                        TestSupport.singleFile("f2", "next.txt", 3, null)));
        TestSupport.assertStatus(200, next);
    }

    @Test
    public void prepareRejectsDeclaredSizeAboveMaxFileBytes() throws Exception {
        ServerConfig config = newConfig();
        config.maxFileBytes = 1024L;
        LsServer limited = startServer(config);
        String tooBig = TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "big.bin", 2048, null));
        TestSupport.assertStatus(413, TestSupport.postJson(limited.getPort(),
                "/api/localsend/v2/prepare-upload", tooBig));
        assertNull("被 413 拒绝时不应询问用户", listener.receiveRequest);

        String ok = TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f2", "small.bin", 512, null));
        TestSupport.assertStatus(200, TestSupport.postJson(limited.getPort(),
                "/api/localsend/v2/prepare-upload", ok));
    }

    @Test
    public void truncatedChunkedUploadReturns400() throws Exception {
        Map<String, Object> response = prepare(TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "trunc.bin", 10, null)));
        String sessionId = sessionIdOf(response);
        String token = tokenOf(response, "f1");

        byte[] partial = TestSupport.utf8("3\r\nabc\r\n"); // 只有 3 字节，且没有结束 chunk
        HttpUtil.HttpResult upload = TestSupport.requestPartial(port(), "POST",
                uploadPath(sessionId, "f1", token),
                "Transfer-Encoding: chunked\r\n", partial, true);
        TestSupport.assertStatus(400, upload);
        assertTrue(listener.awaitEvent("receive-failed", 3000));
        assertFalse(new File(tempDir, "trunc.bin").exists());
    }

    // ------------------------------------------------------------------
    // M3：控制请求体错误码（413/400，而不是 500）
    // ------------------------------------------------------------------

    @Test
    public void oversizedControlBodyReturns413() throws Exception {
        // 只发请求头不发 body：Content-Length 超过 2 MiB 的内部控制体上限。
        int tooLarge = 2 * 1024 * 1024 + 16;
        HttpUtil.HttpResult result = TestSupport.requestPartial(port(), "POST",
                "/api/localsend/v2/register",
                "Content-Type: application/json\r\nContent-Length: " + tooLarge + "\r\n",
                null, false);
        TestSupport.assertStatus(413, result);
    }

    @Test
    public void truncatedChunkedControlBodyReturns400() throws Exception {
        // register 的 chunked 请求体中途断开（没有 0 长度结束块）：400 而不是 500。
        byte[] partial = TestSupport.utf8("5\r\nhello\r\n");
        HttpUtil.HttpResult result = TestSupport.requestPartial(port(), "POST",
                "/api/localsend/v2/register", "Transfer-Encoding: chunked\r\n", partial, true);
        TestSupport.assertStatus(400, result);
    }

    @Test
    public void truncatedContentLengthControlBodyReturns400() throws Exception {
        // Content-Length 声明 100 但只发 10 字节就断开：400 而不是 500。
        byte[] partial = TestSupport.utf8("0123456789");
        HttpUtil.HttpResult result = TestSupport.requestPartial(port(), "POST",
                "/api/localsend/v2/register", "Content-Length: 100\r\n", partial, true);
        TestSupport.assertStatus(400, result);
    }

    @Test
    public void invalidContentLengthReturns400() throws Exception {
        // 非数字的 Content-Length：HTTP 层直接 400，不进入业务处理、不崩。
        HttpUtil.HttpResult result = TestSupport.requestPartial(port(), "POST",
                "/api/localsend/v2/register", "Content-Length: not-a-number\r\n", null, true);
        TestSupport.assertStatus(400, result);
    }

    private static boolean isEmptyDir(File dir) {
        File[] files = dir.listFiles();
        return files == null || files.length == 0;
    }

    // ------------------------------------------------------------------
    // chunked / 100-continue / 并行
    // ------------------------------------------------------------------

    @Test
    public void uploadWithChunkedBodyWorks() throws Exception {
        byte[] content = TestSupport.utf8("chunked 分块传输内容");
        Map<String, Object> response = prepare(TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "chunked.txt", content.length,
                        HttpUtil.sha256Hex(content))));
        String sessionId = sessionIdOf(response);
        String token = tokenOf(response, "f1");

        HttpUtil.HttpResult upload = TestSupport.postChunked(port(),
                uploadPath(sessionId, "f1", token), content, 5);
        TestSupport.assertStatus(200, upload);
        assertTrue(listener.awaitEvent("receive-complete", 3000));
        TestSupport.assertFileContent(new File(tempDir, "chunked.txt"), content);
    }

    @Test
    public void uploadWithExpect100ContinueWorks() throws Exception {
        byte[] content = TestSupport.utf8("expect-100");
        Map<String, Object> response = prepare(TestSupport.prepareJson(TestSupport.senderDevice(),
                TestSupport.singleFile("f1", "expect.txt", content.length, null)));
        String sessionId = sessionIdOf(response);
        String token = tokenOf(response, "f1");

        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port()), 5000);
        socket.setSoTimeout(10000);
        try {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            String head = "POST " + uploadPath(sessionId, "f1", token) + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "Content-Length: " + content.length + "\r\n"
                    + "Expect: 100-continue\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(TestSupport.utf8(head));
            out.flush();

            String interim = HttpUtil.readLine(in);
            assertNotNull(interim);
            assertTrue("应收到 100 Continue，实际: " + interim, interim.indexOf(" 100 ") >= 0);
            String blank = HttpUtil.readLine(in);
            assertEquals("", blank);

            out.write(content);
            out.flush();
            HttpUtil.HttpResult result = HttpUtil.readResponse(in, 1024 * 1024);
            TestSupport.assertStatus(200, result);
        } finally {
            socket.close();
        }
        assertTrue(listener.awaitEvent("receive-complete", 3000));
        TestSupport.assertFileContent(new File(tempDir, "expect.txt"), content);
    }

    @Test
    public void parallelUploadsOfTwoFilesCompleteSession() throws Exception {
        final byte[] first = new byte[64 * 1024];
        final byte[] second = new byte[64 * 1024];
        for (int i = 0; i < first.length; i++) {
            first[i] = (byte) (i & 0xFF);
            second[i] = (byte) ((i * 7) & 0xFF);
        }
        Map<String, Object> files = Json.newObject();
        files.put("p1", TestSupport.fileMap("p1", "p1.bin", first.length,
                HttpUtil.sha256Hex(first)));
        files.put("p2", TestSupport.fileMap("p2", "p2.bin", second.length,
                HttpUtil.sha256Hex(second)));
        final Map<String, Object> response = prepare(TestSupport.prepareJson(
                TestSupport.senderDevice(), files));
        final String sessionId = sessionIdOf(response);
        final String token1 = tokenOf(response, "p1");
        final String token2 = tokenOf(response, "p2");
        assertNotNull(token1);
        assertNotNull(token2);

        final int[] statuses = new int[]{-1, -1};
        Thread thread1 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    statuses[0] = TestSupport.http(port(), "POST",
                            uploadPath(sessionId, "p1", token1), first).status;
                } catch (IOException e) {
                    statuses[0] = -1;
                }
            }
        });
        Thread thread2 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    statuses[1] = TestSupport.http(port(), "POST",
                            uploadPath(sessionId, "p2", token2), second).status;
                } catch (IOException e) {
                    statuses[1] = -1;
                }
            }
        });
        thread1.start();
        thread2.start();
        thread1.join(15000);
        thread2.join(15000);

        assertEquals(200, statuses[0]);
        assertEquals(200, statuses[1]);
        assertTrue(listener.awaitEvent("receive-complete", 5000));
        assertEquals(2, listener.completedFiles.size());
        TestSupport.assertFileContent(new File(tempDir, "p1.bin"), first);
        TestSupport.assertFileContent(new File(tempDir, "p2.bin"), second);
    }

    // ------------------------------------------------------------------
    // v1 端点：用 HttpURLConnection 模拟老客户端
    // ------------------------------------------------------------------

    @Test
    public void v1EndpointsWorkWithHttpURLConnection() throws Exception {
        int port = port();

        // GET /api/localsend/v1/info
        HttpURLConnection connection = openConnection(port, "GET",
                "/api/localsend/v1/info?fingerprint=abc");
        assertEquals(200, connection.getResponseCode());
        Map<String, Object> info = Json.parseObject(readBody(connection));
        assertEquals("TestDevice", info.get("alias"));
        connection.disconnect();

        // POST /api/localsend/v1/register
        connection = openConnection(port, "POST", "/api/localsend/v1/register");
        assertEquals(200, postJson(connection,
                "{\"alias\":\"OldPeer\",\"deviceModel\":\"Windows\","
                        + "\"deviceType\":\"desktop\",\"fingerprint\":\"old-fp\"}"));
        assertTrue(listener.awaitEvent("peer-discovered", 3000));
        assertEquals("OldPeer", listener.discoveredPeer.alias);
        connection.disconnect();

        // POST /api/localsend/v1/send-request
        String sendRequestBody = "{\"info\":{\"alias\":\"OldPeer\",\"deviceModel\":\"Windows\","
                + "\"deviceType\":\"desktop\"},\"files\":{\"old-1\":{\"id\":\"old-1\","
                + "\"fileName\":\"old.txt\",\"size\":9,\"fileType\":\"text\"}}}";
        connection = openConnection(port, "POST", "/api/localsend/v1/send-request");
        assertEquals(200, postJson(connection, sendRequestBody));
        Map<String, Object> tokens = Json.parseObject(readBody(connection));
        String token = Json.optString(tokens, "old-1", null);
        assertNotNull("v1 send-request 应返回 fileId -> token 映射", token);
        connection.disconnect();

        // POST /api/localsend/v1/send（二进制）
        byte[] content = TestSupport.utf8("old-data!");
        connection = openConnection(port, "POST",
                "/api/localsend/v1/send?fileId=old-1&token=" + HttpUtil.encodeQueryParam(token));
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setFixedLengthStreamingMode(content.length);
        connection.connect();
        OutputStream out = connection.getOutputStream();
        out.write(content);
        out.close();
        assertEquals(200, connection.getResponseCode());
        connection.disconnect();

        assertTrue(listener.awaitEvent("receive-complete", 3000));
        assertEquals(1, listener.completedFiles.size());
        TestSupport.assertFileContent(new File(tempDir, "old.txt"), content);

        // v1 cancel（没有活动会话时幂等返回 200）
        connection = openConnection(port, "POST", "/api/localsend/v1/cancel");
        assertEquals(200, postJson(connection, "{}"));
        connection.disconnect();
    }

    @Test
    public void v1CancelStopsActiveSession() throws Exception {
        int port = port();
        HttpURLConnection connection = openConnection(port, "POST",
                "/api/localsend/v1/send-request");
        assertEquals(200, postJson(connection,
                "{\"info\":{\"alias\":\"OldPeer\",\"deviceType\":\"desktop\"},"
                        + "\"files\":{\"old-2\":{\"id\":\"old-2\",\"fileName\":\"c.txt\","
                        + "\"size\":3,\"fileType\":\"text\"}}}"));
        connection.disconnect();

        connection = openConnection(port, "POST", "/api/localsend/v1/cancel");
        assertEquals(200, postJson(connection, "{}"));
        connection.disconnect();
        assertTrue(listener.awaitEvent("receive-failed", 3000));
        assertNull(listener.completedSessionId);
    }

    // ------------------------------------------------------------------
    // HttpURLConnection 辅助
    // ------------------------------------------------------------------

    private static HttpURLConnection openConnection(int port, String method, String path)
            throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        connection.setUseCaches(false);
        return connection;
    }

    private static int postJson(HttpURLConnection connection, String json) throws IOException {
        byte[] data = TestSupport.utf8(json);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setFixedLengthStreamingMode(data.length);
        connection.connect();
        OutputStream out = connection.getOutputStream();
        out.write(data);
        out.close();
        return connection.getResponseCode();
    }

    private static String readBody(HttpURLConnection connection) throws IOException {
        InputStream in = connection.getResponseCode() >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        if (in == null) {
            return "";
        }
        try {
            return TestSupport.readAll(in);
        } finally {
            in.close();
        }
    }
}
