package com.legacy.localsend.core;

import java.io.IOException;

/**
 * 带 HTTP 状态码的请求体异常：让 HTTP 层把“请求体过大 / 被截断 / chunked 格式非法”等
 * 映射成 413 或 400，而不是笼统的 500。
 *
 * 只用于服务端解析请求体，不用于客户端。
 */
final class HttpStatusException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int status;

    HttpStatusException(int status, String message) {
        super(message);
        this.status = status;
    }

    int getStatus() {
        return status;
    }
}
