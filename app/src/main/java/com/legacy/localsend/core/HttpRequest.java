package com.legacy.localsend.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个已解析 HTTP 请求（请求行 + 头 + 惰性请求体流）。
 * 由 HttpServer 创建；请求体可以按 Content-Length 或 chunked 流式读取。
 */
final class HttpRequest {

    private final String method;
    private final String path;
    private final Map<String, String> query;
    private final Map<String, String> headers;
    private final InputStream bodyStream;
    private final long contentLength;
    private final boolean chunked;
    private final boolean expectContinue;
    private final String remoteIp;

    HttpRequest(String method, String path, Map<String, String> query, Map<String, String> headers,
                InputStream rawBody, long contentLength, boolean chunked, boolean expectContinue,
                String remoteIp) {
        this.method = method;
        this.path = path;
        this.query = query == null ? new LinkedHashMap<String, String>() : query;
        this.headers = headers == null ? new LinkedHashMap<String, String>() : headers;
        this.contentLength = contentLength;
        this.chunked = chunked;
        this.expectContinue = expectContinue;
        this.remoteIp = remoteIp == null ? "" : remoteIp;
        if (chunked) {
            this.bodyStream = new ChunkedInputStream(rawBody);
        } else if (contentLength >= 0) {
            this.bodyStream = new FixedLengthInputStream(rawBody, contentLength);
        } else {
            this.bodyStream = new ByteArrayInputStream(new byte[0]);
        }
    }

    String getMethod() {
        return method;
    }

    String getPath() {
        return path;
    }

    String getRemoteIp() {
        return remoteIp;
    }

    String getQueryParam(String name) {
        return query.get(name);
    }

    String getHeader(String name) {
        if (name == null) {
            return null;
        }
        return headers.get(name.toLowerCase(java.util.Locale.US));
    }

    long getContentLength() {
        return contentLength;
    }

    boolean isChunked() {
        return chunked;
    }

    boolean hasBody() {
        return chunked || contentLength > 0;
    }

    boolean isExpectContinue() {
        return expectContinue;
    }

    InputStream getBodyStream() {
        return bodyStream;
    }

    /** 一次性读取整个请求体（用于 register / prepare 这类小 JSON 请求体）。 */
    byte[] readBodyBytes(long maxBytes) throws IOException {
        if (chunked) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while (true) {
                try {
                    n = bodyStream.read(buffer, 0, buffer.length);
                } catch (IOException e) {
                    // chunked 被截断 / chunk 长度非法：发送端请求错误 → 400。
                    throw new HttpStatusException(400, "请求体读取失败: " + e.getMessage());
                }
                if (n < 0) {
                    break;
                }
                total += n;
                if (total > maxBytes) {
                    throw new HttpStatusException(413, "请求体过大");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
        if (contentLength < 0) {
            return new byte[0];
        }
        if (contentLength > maxBytes) {
            throw new HttpStatusException(413, "请求体过大");
        }
        int length = (int) contentLength;
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n;
            try {
                n = bodyStream.read(data, offset, length - offset);
            } catch (IOException e) {
                throw new HttpStatusException(400, "请求体读取失败: " + e.getMessage());
            }
            if (n < 0) {
                throw new HttpStatusException(400, "请求体被截断");
            }
            offset += n;
        }
        return data;
    }

    String readBodyString(long maxBytes) throws IOException {
        return HttpUtil.utf8(readBodyBytes(maxBytes));
    }

    /**
     * 在响应发出之后尽量读掉未消费的请求体，避免关闭连接时 TCP 复位把响应吞掉。
     * 出错时忽略。
     */
    void drain(long limit) {
        try {
            byte[] buffer = new byte[4096];
            long total = 0;
            while (total < limit) {
                int n = bodyStream.read(buffer, 0, buffer.length);
                if (n < 0) {
                    break;
                }
                total += n;
            }
        } catch (IOException ignored) {
            // ignore
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    /** 把底层输入包装成最多只能读 contentLength 字节的流。 */
    private static final class FixedLengthInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        FixedLengthInputStream(InputStream in, long length) {
            this.in = in;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b < 0) {
                return -1;
            }
            remaining--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            if (length == 0) {
                return 0;
            }
            int want = (int) Math.min((long) length, remaining);
            int n = in.read(buffer, offset, want);
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public int available() throws IOException {
            long available = in.available();
            return (int) Math.min(Math.min(available, remaining), Integer.MAX_VALUE);
        }
    }
}
