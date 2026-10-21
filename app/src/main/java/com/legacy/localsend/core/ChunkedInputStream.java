package com.legacy.localsend.core;

import java.io.IOException;
import java.io.InputStream;

/**
 * HTTP/1.1 chunked 请求/响应体解码流（Transfer-Encoding: chunked）。
 * 只使用 Java 5/6 标准库。
 */
final class ChunkedInputStream extends InputStream {

    private final InputStream in;
    private long remainingInChunk;
    private boolean eof;
    private boolean needChunkTerminator;

    ChunkedInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        if (n < 0) {
            return -1;
        }
        return one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (eof) {
            return -1;
        }
        if (length == 0) {
            return 0;
        }
        if (remainingInChunk <= 0) {
            if (!nextChunk()) {
                return -1;
            }
        }
        int want = (int) Math.min((long) length, remainingInChunk);
        int n = in.read(buffer, offset, want);
        if (n < 0) {
            throw new IOException("chunked 数据被截断");
        }
        remainingInChunk -= n;
        return n;
    }

    @Override
    public int available() throws IOException {
        if (remainingInChunk > 0) {
            return (int) Math.min(remainingInChunk, Integer.MAX_VALUE);
        }
        return 0;
    }

    /** 读取下一个 chunk 的大小；返回 false 表示整个 body 已结束。 */
    private boolean nextChunk() throws IOException {
        if (needChunkTerminator) {
            consumeChunkTerminator();
            needChunkTerminator = false;
        }
        String line = HttpUtil.readLine(in);
        if (line == null) {
            throw new IOException("chunked 数据意外结束");
        }
        int semicolon = line.indexOf(';');
        String hex = semicolon >= 0 ? line.substring(0, semicolon) : line;
        hex = hex.trim();
        long size;
        try {
            size = Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            throw new IOException("非法的 chunk 长度: " + hex);
        }
        if (size < 0) {
            throw new IOException("非法的 chunk 长度: " + hex);
        }
        if (size == 0) {
            // 读掉 trailer（直到空行）。
            while (true) {
                String trailer = HttpUtil.readLine(in);
                if (trailer == null || trailer.length() == 0) {
                    break;
                }
            }
            eof = true;
            return false;
        }
        remainingInChunk = size;
        needChunkTerminator = true;
        return true;
    }

    private void consumeChunkTerminator() throws IOException {
        int c = in.read();
        if (c == '\r') {
            int c2 = in.read();
            if (c2 != '\n') {
                throw new IOException("chunk 结尾格式非法");
            }
        } else if (c != '\n') {
            throw new IOException("chunk 结尾格式非法");
        }
    }
}
