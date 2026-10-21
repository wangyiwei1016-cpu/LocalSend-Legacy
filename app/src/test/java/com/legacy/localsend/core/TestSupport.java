package com.legacy.localsend.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 单元测试公共工具：临时目录 StorageProvider、录音式 TransferListener、原始 HTTP 客户端。
 */
final class TestSupport {

    private TestSupport() {
    }

    // ------------------------------------------------------------------
    // 通用
    // ------------------------------------------------------------------

    static byte[] utf8(String text) {
        return HttpUtil.utf8(text);
    }

    static String text(byte[] bytes) {
        return HttpUtil.utf8(bytes);
    }

    static String sha256(String text) {
        return HttpUtil.sha256Hex(utf8(text));
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return text(out.toByteArray());
    }

    static byte[] readFileBytes(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    deleteRecursively(children[i]);
                }
            }
        }
        file.delete();
    }

    static File newTempDir(String prefix) throws IOException {
        File dir = File.createTempFile(prefix, ".dir");
        if (!dir.delete()) {
            throw new IOException("无法创建临时目录: " + dir);
        }
        if (!dir.mkdirs()) {
            throw new IOException("无法创建临时目录: " + dir);
        }
        return dir;
    }

    // ------------------------------------------------------------------
    // 测试用 StorageProvider：写临时目录
    // ------------------------------------------------------------------

    static final class TempStorage implements StorageProvider {

        final File dir;
        final List<FileMeta> allocated = Collections.synchronizedList(new ArrayList<FileMeta>());
        final List<File> committed = Collections.synchronizedList(new ArrayList<File>());

        TempStorage(File dir) {
            this.dir = dir;
        }

        @Override
        public IncomingFile allocate(FileMeta meta) throws IOException {
            final String safeName = FileMeta.sanitizeFileName(meta.fileName);
            final File target = new File(dir, safeName);
            File temp = null;
            for (int i = 0; i < 100; i++) {
                String suffix = i == 0 ? ".part" : "." + i + ".part";
                File candidate = new File(dir, safeName + suffix);
                if (!candidate.exists()) {
                    temp = candidate;
                    break;
                }
            }
            if (temp == null) {
                throw new IOException("无法分配临时文件");
            }
            final File tempFile = temp;
            allocated.add(meta);
            return new IncomingFile() {
                private OutputStream out;
                private boolean finished;

                @Override
                public FileMeta getMeta() {
                    return meta;
                }

                @Override
                public OutputStream openStream() throws IOException {
                    out = new FileOutputStream(tempFile);
                    return out;
                }

                @Override
                public void commit() throws IOException {
                    if (out != null) {
                        out.flush();
                        out.close();
                        out = null;
                    }
                    if (target.exists() && !target.delete()) {
                        throw new IOException("无法覆盖已有文件: " + target);
                    }
                    if (!tempFile.renameTo(target)) {
                        FileInputStream in = new FileInputStream(tempFile);
                        try {
                            FileOutputStream copy = new FileOutputStream(target);
                            try {
                                byte[] buffer = new byte[8192];
                                int n;
                                while ((n = in.read(buffer)) != -1) {
                                    copy.write(buffer, 0, n);
                                }
                            } finally {
                                copy.close();
                            }
                        } finally {
                            in.close();
                        }
                        tempFile.delete();
                    }
                    finished = true;
                    committed.add(target);
                }

                @Override
                public void abort() {
                    try {
                        if (out != null) {
                            out.close();
                        }
                    } catch (IOException ignored) {
                        // ignore
                    }
                    out = null;
                    if (!finished) {
                        tempFile.delete();
                    }
                }

                @Override
                public String describe() {
                    return target.getAbsolutePath();
                }
            };
        }
    }

    // ------------------------------------------------------------------
    // 测试用 TransferListener：记录所有回调
    // ------------------------------------------------------------------

    static final class RecordingListener implements TransferListener {

        volatile boolean acceptRequest = true;
        volatile ReceiveRequest receiveRequest;

        volatile String completedSessionId;
        volatile List<IncomingFile> completedFiles = new ArrayList<IncomingFile>();
        volatile boolean receiveComplete;

        volatile String failedSessionId;
        volatile String failedReason;

        volatile DeviceInfo discoveredPeer;
        volatile String discoveredIp;

        volatile long lastReceiveBytes;
        volatile long lastReceiveTotal;
        volatile long lastSendBytes;
        volatile long lastSendTotal;
        final List<String> sendProgress = Collections.synchronizedList(new ArrayList<String>());

        volatile boolean sendComplete;
        volatile boolean sendFailed;
        volatile String sendFailedReason;

        final List<String> logs = Collections.synchronizedList(new ArrayList<String>());
        final BlockingQueue<String> events = new LinkedBlockingQueue<String>();

        @Override
        public boolean onReceiveRequest(ReceiveRequest request) {
            this.receiveRequest = request;
            return acceptRequest;
        }

        @Override
        public void onReceiveProgress(String sessionId, FileMeta file, long bytesReceived, long totalBytes) {
            this.lastReceiveBytes = bytesReceived;
            this.lastReceiveTotal = totalBytes;
        }

        @Override
        public void onReceiveComplete(String sessionId, List<IncomingFile> files) {
            this.completedSessionId = sessionId;
            this.completedFiles = new ArrayList<IncomingFile>(files);
            this.receiveComplete = true;
            events.offer("receive-complete");
        }

        @Override
        public void onReceiveFailed(String sessionId, String reason) {
            this.failedSessionId = sessionId;
            this.failedReason = reason;
            events.offer("receive-failed");
        }

        @Override
        public void onSendProgress(DeviceInfo target, FileMeta file, long bytesSent, long totalBytes) {
            this.lastSendBytes = bytesSent;
            this.lastSendTotal = totalBytes;
            sendProgress.add((file == null ? "null" : file.displayName()) + ":" + bytesSent + "/" + totalBytes);
        }

        @Override
        public void onSendComplete(DeviceInfo target, List<FileMeta> files) {
            this.sendComplete = true;
            events.offer("send-complete");
        }

        @Override
        public void onSendFailed(DeviceInfo target, String reason) {
            this.sendFailed = true;
            this.sendFailedReason = reason;
            events.offer("send-failed");
        }

        @Override
        public void onPeerDiscovered(DeviceInfo info, String ip) {
            this.discoveredPeer = info;
            this.discoveredIp = ip;
            events.offer("peer-discovered");
        }

        @Override
        public void onLog(String message) {
            logs.add(message);
        }

        boolean awaitEvent(String event, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                String received = events.poll(remaining, TimeUnit.MILLISECONDS);
                if (received == null) {
                    return false;
                }
                if (received.equals(event)) {
                    return true;
                }
            }
        }
    }

    static final class DiscoveryRecordingListener implements Discovery.Listener {

        final BlockingQueue<DeviceInfo> found = new LinkedBlockingQueue<DeviceInfo>();
        final BlockingQueue<String> foundIps = new LinkedBlockingQueue<String>();
        final BlockingQueue<String> errors = new LinkedBlockingQueue<String>();

        @Override
        public void onDeviceFound(DeviceInfo info, String ip) {
            found.offer(info);
            foundIps.offer(ip == null ? "" : ip);
        }

        @Override
        public void onDiscoveryError(String message) {
            errors.offer(message == null ? "" : message);
        }

        DeviceInfo awaitFound(long timeoutMs) throws InterruptedException {
            return found.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    // ------------------------------------------------------------------
    // 测试用 HTTP 客户端（原始 Socket，方便精确构造请求）
    // ------------------------------------------------------------------

    static HttpUtil.HttpResult http(int port, String method, String pathAndQuery, byte[] body)
            throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(15000);
        try {
            String path = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
            StringBuilder head = new StringBuilder(256);
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: 127.0.0.1:").append(port).append("\r\n");
            head.append("User-Agent: LocalSendLegacyTest/1.0\r\n");
            head.append("Connection: close\r\n");
            if (body != null) {
                head.append("Content-Type: application/json; charset=utf-8\r\n");
                head.append("Content-Length: ").append(body.length).append("\r\n");
            }
            head.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(utf8(head.toString()));
            if (body != null) {
                out.write(body);
            }
            out.flush();
            return HttpUtil.readResponse(socket.getInputStream(), 4 * 1024 * 1024);
        } finally {
            socket.close();
        }
    }

    static HttpUtil.HttpResult postJson(int port, String path, String json) throws IOException {
        return http(port, "POST", path, utf8(json));
    }

    static Map<String, Object> jsonObject(HttpUtil.HttpResult result) throws Exception {
        assertNotNull("响应体不应为空", result.body);
        return Json.parseObject(result.bodyText());
    }

    /** 用 Transfer-Encoding: chunked 发送请求体并读取响应。 */
    static HttpUtil.HttpResult postChunked(int port, String pathAndQuery, byte[] data, int chunkSize)
            throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(15000);
        try {
            String path = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
            StringBuilder head = new StringBuilder(256);
            head.append("POST ").append(path).append(" HTTP/1.1\r\n");
            head.append("Host: 127.0.0.1:").append(port).append("\r\n");
            head.append("Transfer-Encoding: chunked\r\n");
            head.append("Content-Type: application/octet-stream\r\n");
            head.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(utf8(head.toString()));
            int offset = 0;
            while (offset < data.length) {
                int length = Math.min(chunkSize, data.length - offset);
                out.write(utf8(Integer.toHexString(length) + "\r\n"));
                out.write(data, offset, length);
                out.write(utf8("\r\n"));
                offset += length;
            }
            out.write(utf8("0\r\n\r\n"));
            out.flush();
            return HttpUtil.readResponse(socket.getInputStream(), 4 * 1024 * 1024);
        } finally {
            socket.close();
        }
    }

    /**
     * 只发送请求头和（可选的）部分请求体，然后读取响应。
     * 用于构造“Content-Length 声明过大但没发 body”“chunked 请求体被截断”这类异常请求。
     *
     * @param extraHeaderLines 额外的请求头行（每行需以 CRLF 结尾），可为 null
     * @param partialBody      只发送一部分的请求体，可为 null
     * @param shutdownOutput   发完部分请求体后关闭输出方向（模拟发送端断开 chunked 流）
     */
    static HttpUtil.HttpResult requestPartial(int port, String method, String pathAndQuery,
                                              String extraHeaderLines, byte[] partialBody,
                                              boolean shutdownOutput) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(15000);
        try {
            String path = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
            StringBuilder head = new StringBuilder(256);
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: 127.0.0.1:").append(port).append("\r\n");
            head.append("User-Agent: LocalSendLegacyTest/1.0\r\n");
            head.append("Connection: close\r\n");
            if (extraHeaderLines != null && extraHeaderLines.length() > 0) {
                head.append(extraHeaderLines);
            }
            head.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(utf8(head.toString()));
            if (partialBody != null && partialBody.length > 0) {
                out.write(partialBody);
            }
            out.flush();
            if (shutdownOutput) {
                socket.shutdownOutput();
            }
            return HttpUtil.readResponse(socket.getInputStream(), 4 * 1024 * 1024);
        } finally {
            socket.close();
        }
    }

    // ------------------------------------------------------------------
    // 准备请求 JSON 构造
    // ------------------------------------------------------------------

    static Map<String, Object> fileMap(String id, String fileName, long size, String sha256) {
        Map<String, Object> file = Json.newObject();
        file.put("id", id);
        file.put("fileName", fileName);
        file.put("size", Long.valueOf(size));
        file.put("fileType", "application/octet-stream");
        if (sha256 != null) {
            file.put("sha256", sha256);
        }
        return file;
    }

    static String prepareJson(DeviceInfo sender, Map<String, Object> files) {
        Map<String, Object> body = Json.newObject();
        Map<String, Object> info = Json.newObject();
        if (sender != null) {
            info = sender.toMap();
        } else {
            info.put("alias", "Sender");
            info.put("version", "2.0");
            info.put("fingerprint", "sender-fp");
            info.put("port", Long.valueOf(53317));
            info.put("protocol", "http");
        }
        body.put("info", info);
        Map<String, Object> filesOut = Json.newObject();
        for (Map.Entry<String, Object> entry : files.entrySet()) {
            filesOut.put(entry.getKey(), entry.getValue());
        }
        body.put("files", filesOut);
        return Json.stringify(body);
    }

    static Map<String, Object> singleFile(String id, String fileName, long size, String sha256) {
        Map<String, Object> files = Json.newObject();
        files.put(id, fileMap(id, fileName, size, sha256));
        return files;
    }

    static DeviceInfo senderDevice() {
        DeviceInfo sender = new DeviceInfo("Sender", "TestModel", "sender-fp", 53317);
        sender.protocol = "http";
        return sender;
    }

    static void assertStatus(int expected, HttpUtil.HttpResult result) {
        assertEquals("HTTP 状态码不符（body=" + result.bodyText() + "）", expected, result.status);
    }

    static void assertFileContent(File file, byte[] expected) throws IOException {
        assertTrue("文件不存在: " + file, file.isFile());
        assertEquals("文件内容不符", text(expected), text(readFileBytes(file)));
    }
}
