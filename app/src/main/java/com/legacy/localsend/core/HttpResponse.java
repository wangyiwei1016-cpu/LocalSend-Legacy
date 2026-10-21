package com.legacy.localsend.core;

import java.util.Map;

/**
 * 内部 HTTP 响应。响应统一带 Connection: close，写完即关闭连接。
 */
final class HttpResponse {

    private final int status;
    private final String contentType;
    private final byte[] body;

    private HttpResponse(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body == null ? new byte[0] : body;
    }

    static HttpResponse text(int status, String body) {
        return new HttpResponse(status, "text/plain; charset=utf-8", HttpUtil.utf8(body == null ? "" : body));
    }

    static HttpResponse json(int status, Map<String, Object> body) {
        return json(status, Json.stringify(body));
    }

    static HttpResponse json(int status, String jsonText) {
        return new HttpResponse(status, "application/json; charset=utf-8",
                HttpUtil.utf8(jsonText == null ? "{}" : jsonText));
    }

    static HttpResponse empty(int status) {
        return new HttpResponse(status, null, new byte[0]);
    }

    int getStatus() {
        return status;
    }

    String getContentType() {
        return contentType;
    }

    byte[] getBody() {
        return body;
    }

    static String reason(int status) {
        switch (status) {
            case 100:
                return "Continue";
            case 200:
                return "OK";
            case 204:
                return "No Content";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 409:
                return "Conflict";
            case 413:
                return "Payload Too Large";
            case 422:
                return "Unprocessable Entity";
            case 429:
                return "Too Many Requests";
            case 500:
                return "Internal Server Error";
            case 501:
                return "Not Implemented";
            default:
                return "Status";
        }
    }
}
