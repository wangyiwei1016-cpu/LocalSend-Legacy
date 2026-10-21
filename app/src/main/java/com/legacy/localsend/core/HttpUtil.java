package com.legacy.localsend.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 内部 HTTP 工具类：UTF-8 编解码、行/头解析、URL 查询串编解码、SHA-256、原始 Socket 客户端。
 *
 * 兼容性：只使用 Java 5/6 标准库（java.io / java.net / java.util / java.security），
 * 不使用 java.nio.file、StandardCharsets、Objects 等 Java 7+ 或 API &gt; 8 的设施，
 * 可在 Android 2.2 (API 8) 的 Dalvik 上运行。
 */
final class HttpUtil {

    static final String UTF8 = "UTF-8";

    /** 普通控制请求的连接超时。 */
    static final int CONNECT_TIMEOUT_MS = 5000;

    /** 读取 HTTP 响应的默认超时。 */
    static final int READ_TIMEOUT_MS = 30000;

    /** 响应体读取上限，防止被对端撑爆内存（仅用于控制类响应）。 */
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private static final int MAX_HEADER_COUNT = 128;

    private HttpUtil() {
    }

    // ------------------------------------------------------------------
    // 字节 / 字符串
    // ------------------------------------------------------------------

    static byte[] utf8(String text) {
        if (text == null) {
            return new byte[0];
        }
        try {
            return text.getBytes(UTF8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 不可用", e);
        }
    }

    static String utf8(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        return utf8(data, 0, data.length);
    }

    static String utf8(byte[] data, int offset, int length) {
        try {
            return new String(data, offset, length, UTF8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 不可用", e);
        }
    }

    // ------------------------------------------------------------------
    // 行 / 头
    // ------------------------------------------------------------------

    /**
     * 读取一行（以 LF 结尾，自动去掉 CR）。到达流末尾且没有任何数据时返回 null。
     * 必须在带缓冲的输入流上调用。
     */
    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        while (true) {
            int c = in.read();
            if (c < 0) {
                if (sb.length() == 0) {
                    return null;
                }
                return sb.toString();
            }
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            if (sb.length() > 16 * 1024) {
                throw new IOException("HTTP 头部行过长");
            }
            sb.append((char) c);
        }
    }

    /** 读取 HTTP 头，直到空行。头名统一小写；重复头用逗号合并。 */
    static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        for (int i = 0; i < MAX_HEADER_COUNT; i++) {
            String line = readLine(in);
            if (line == null) {
                throw new IOException("HTTP 头不完整");
            }
            if (line.length() == 0) {
                return headers;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            String old = headers.get(name);
            if (old == null) {
                headers.put(name, value);
            } else {
                headers.put(name, old + "," + value);
            }
        }
        throw new IOException("HTTP 头过多");
    }

    // ------------------------------------------------------------------
    // URL 编解码
    // ------------------------------------------------------------------

    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<String, String>();
        if (rawQuery == null || rawQuery.length() == 0) {
            return query;
        }
        int start = 0;
        while (start <= rawQuery.length()) {
            int amp = rawQuery.indexOf('&', start);
            String pair = amp < 0 ? rawQuery.substring(start) : rawQuery.substring(start, amp);
            if (pair.length() > 0) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                query.put(percentDecode(key), percentDecode(value));
            }
            if (amp < 0) {
                break;
            }
            start = amp + 1;
        }
        return query;
    }

    /** 对查询参数值做 URL 编码（空格会变成 +，parseQuery 会还原）。 */
    static String encodeQueryParam(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, UTF8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 不可用", e);
        }
    }

    /** 百分号解码（'+' 视为空格），结果按 UTF-8 解释。 */
    static String percentDecode(String text) {
        if (text == null || text.length() == 0) {
            return "";
        }
        String normalized = text.replace('+', ' ');
        byte[] src = utf8(normalized);
        ByteArrayOutputStream out = new ByteArrayOutputStream(src.length);
        int i = 0;
        while (i < src.length) {
            int b = src[i] & 0xFF;
            if (b == '%' && i + 2 < src.length) {
                int hi = Character.digit((char) (src[i + 1] & 0xFF), 16);
                int lo = Character.digit((char) (src[i + 2] & 0xFF), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            out.write(b);
            i++;
        }
        return utf8(out.toByteArray());
    }

    // ------------------------------------------------------------------
    // SHA-256
    // ------------------------------------------------------------------

    static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // 所有 JVM / Android 都内置 SHA-256，理论上不可达。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    static String sha256Hex(byte[] data) {
        return toHex(sha256Digest().digest(data));
    }

    static String toHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2] = digits[b >>> 4];
            out[i * 2 + 1] = digits[b & 0x0F];
        }
        return new String(out);
    }

    // ------------------------------------------------------------------
    // 原始 Socket HTTP 客户端（只用控制类请求；文件上传由 LsClient 流式处理）
    // ------------------------------------------------------------------

    /** 一个 HTTP 响应。 */
    static final class HttpResult {
        final int status;
        final byte[] body;
        final Map<String, String> headers;

        HttpResult(int status, byte[] body, Map<String, String> headers) {
            this.status = status;
            this.body = body == null ? new byte[0] : body;
            this.headers = headers == null ? new LinkedHashMap<String, String>() : headers;
        }

        String bodyText() {
            return utf8(body);
        }
    }

    static Socket openSocket(String host, int port, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            socket.setTcpNoDelay(true);
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    /**
     * 发送一个完整（非流式）HTTP 请求并读取响应。请求头固定 Connection: close。
     *
     * @param pathAndQuery 例如 /api/localsend/v2/register?pin=1234
     * @param body         请求体，可为 null（无请求体）
     */
    static HttpResult request(String method, String host, int port, String pathAndQuery,
                              byte[] body, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        Socket socket = openSocket(host, port, connectTimeoutMs, readTimeoutMs);
        try {
            StringBuilder head = new StringBuilder(256);
            head.append(method).append(' ').append(pathAndQuery).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host).append(':').append(port).append("\r\n");
            head.append("User-Agent: LocalSendLegacy/1.0\r\n");
            head.append("Accept: application/json\r\n");
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
            return readResponse(socket.getInputStream(), MAX_RESPONSE_BYTES);
        } finally {
            closeQuietly(socket);
        }
    }

    /** 从输入流读取一个 HTTP 响应（支持 Content-Length / chunked / 关闭即结束，跳过 100-continue 等临时响应）。 */
    static HttpResult readResponse(InputStream rawIn, int maxBody) throws IOException {
        InputStream in = rawIn instanceof BufferedInputStream
                ? rawIn
                : new BufferedInputStream(rawIn, 8192);
        while (true) {
            String statusLine = readLine(in);
            if (statusLine == null) {
                throw new IOException("连接被对方关闭");
            }
            if (statusLine.length() == 0) {
                continue;
            }
            int status = parseStatusLine(statusLine);
            Map<String, String> headers = readHeaders(in);
            if (status >= 100 && status < 200) {
                // 临时响应（如 100 Continue），继续读最终响应。
                continue;
            }
            byte[] body = readResponseBody(in, headers, maxBody);
            return new HttpResult(status, body, headers);
        }
    }

    private static int parseStatusLine(String statusLine) throws IOException {
        int first = statusLine.indexOf(' ');
        if (first < 0) {
            throw new IOException("非法状态行: " + statusLine);
        }
        int second = statusLine.indexOf(' ', first + 1);
        String code = second < 0 ? statusLine.substring(first + 1) : statusLine.substring(first + 1, second);
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            throw new IOException("非法状态码: " + statusLine);
        }
    }

    private static byte[] readResponseBody(InputStream in, Map<String, String> headers, int maxBody)
            throws IOException {
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.US).indexOf("chunked") >= 0) {
            return readChunked(in, maxBody);
        }
        String contentLength = headers.get("content-length");
        if (contentLength != null && contentLength.length() > 0) {
            long length;
            try {
                length = Long.parseLong(contentLength.trim());
            } catch (NumberFormatException e) {
                throw new IOException("非法 Content-Length: " + contentLength);
            }
            if (length < 0 || length > maxBody) {
                throw new IOException("响应体过大或长度非法: " + length);
            }
            return readFully(in, (int) length);
        }
        return readUntilEof(in, maxBody);
    }

    private static byte[] readChunked(InputStream in, int maxBody) throws IOException {
        ChunkedInputStream chunked = new ChunkedInputStream(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while ((n = chunked.read(buffer, 0, buffer.length)) != -1) {
            total += n;
            if (total > maxBody) {
                throw new IOException("响应体过大");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(data, offset, length - offset);
            if (n < 0) {
                throw new IOException("响应体被截断");
            }
            offset += n;
        }
        return data;
    }

    private static byte[] readUntilEof(InputStream in, int maxBody) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > maxBody) {
                throw new IOException("响应体过大");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // 关闭辅助（显式写重载，兼容 Java 6 的 Socket/ServerSocket 没有实现 Closeable）
    // ------------------------------------------------------------------

    static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    static void closeQuietly(ServerSocket serverSocket) {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    static void closeQuietly(java.net.DatagramSocket datagramSocket) {
        if (datagramSocket != null) {
            try {
                datagramSocket.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    static void closeQuietly(OutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
