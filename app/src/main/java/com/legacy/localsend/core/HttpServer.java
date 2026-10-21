package com.legacy.localsend.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 极简 HTTP/1.1 服务器：ServerSocket + 每连接一个线程。
 *
 * 支持：Content-Length 请求体、chunked 请求体、Expect: 100-continue；
 * 响应统一 Connection: close（写完响应后关闭连接），避免老设备上的 keep-alive 兼容问题。
 */
final class HttpServer {

    interface Handler {
        HttpResponse handle(HttpRequest request) throws IOException;
    }

    private static final int READ_TIMEOUT_MS = 30000;
    private static final int DRAIN_TIMEOUT_MS = 400;
    private static final int MAX_CONNECTIONS = 64;

    private final Handler handler;
    private final Set<Socket> openSockets = Collections.synchronizedSet(new HashSet<Socket>());
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile int activeConnections;

    HttpServer(Handler handler) {
        this.handler = handler;
    }

    void start(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        try {
            socket.bind(new InetSocketAddress(port), 50);
        } catch (IOException e) {
            HttpUtil.closeQuietly(socket);
            throw e;
        }
        this.serverSocket = socket;
        this.running = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "ls-http-accept");
        thread.setDaemon(true);
        this.acceptThread = thread;
        thread.start();
    }

    int getPort() {
        ServerSocket socket = this.serverSocket;
        return socket == null ? -1 : socket.getLocalPort();
    }

    boolean isRunning() {
        return running;
    }

    void stop() {
        running = false;
        HttpUtil.closeQuietly(serverSocket);
        synchronized (openSockets) {
            for (Socket socket : openSockets) {
                HttpUtil.closeQuietly(socket);
            }
            openSockets.clear();
        }
        Thread thread = acceptThread;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        acceptThread = null;
    }

    private void acceptLoop() {
        while (running) {
            Socket accepted;
            try {
                ServerSocket server = this.serverSocket;
                if (server == null) {
                    break;
                }
                accepted = server.accept();
            } catch (IOException e) {
                if (!running) {
                    break;
                }
                sleepQuietly(50);
                continue;
            }
            final Socket socket = accepted;
            if (activeConnections >= MAX_CONNECTIONS) {
                HttpUtil.closeQuietly(socket);
                continue;
            }
            activeConnections++;
            openSockets.add(socket);
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        handleConnection(socket);
                    } finally {
                        activeConnections--;
                    }
                }
            }, "ls-http-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handleConnection(Socket socket) {
        InputStream rawIn = null;
        OutputStream rawOut = null;
        try {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            rawIn = new BufferedInputStream(socket.getInputStream(), 8192);
            rawOut = new BufferedOutputStream(socket.getOutputStream(), 8192);
            String remoteIp = socket.getInetAddress() == null
                    ? ""
                    : socket.getInetAddress().getHostAddress();

            HttpRequest request;
            try {
                request = readRequest(rawIn, remoteIp);
            } catch (IOException e) {
                writeResponse(rawOut, HttpResponse.text(400, "Bad Request"));
                rawOut.flush();
                return;
            }
            if (request == null) {
                return;
            }

            if (request.hasBody() && request.isExpectContinue()) {
                rawOut.write(HttpUtil.utf8("HTTP/1.1 100 Continue\r\n\r\n"));
                rawOut.flush();
            }

            HttpResponse response;
            try {
                response = handler.handle(request);
                if (response == null) {
                    response = HttpResponse.text(500, "Internal Server Error");
                }
            } catch (Exception e) {
                response = HttpResponse.text(500, "Internal Server Error");
            }

            writeResponse(rawOut, response);
            rawOut.flush();

            // 响应已经发出；尽量读掉未消费的请求体，避免 close 时 RST 吞掉响应。
            try {
                socket.setSoTimeout(DRAIN_TIMEOUT_MS);
            } catch (SocketException ignored) {
                // ignore
            }
            request.drain(256 * 1024);
        } catch (IOException ignored) {
            // 连接级异常（对端关闭等）不需要向上报。
        } finally {
            openSockets.remove(socket);
            HttpUtil.closeQuietly(rawIn);
            HttpUtil.closeQuietly(rawOut);
            HttpUtil.closeQuietly(socket);
        }
    }

    private HttpRequest readRequest(InputStream in, String remoteIp) throws IOException {
        String requestLine = null;
        for (int i = 0; i < 3; i++) {
            requestLine = HttpUtil.readLine(in);
            if (requestLine == null) {
                return null;
            }
            if (requestLine.length() > 0) {
                break;
            }
        }
        if (requestLine == null || requestLine.length() == 0) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            throw new IOException("非法请求行: " + requestLine);
        }
        String method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];

        int queryIndex = target.indexOf('?');
        String rawPath = queryIndex >= 0 ? target.substring(0, queryIndex) : target;
        String rawQuery = queryIndex >= 0 ? target.substring(queryIndex + 1) : "";
        String path = HttpUtil.percentDecode(rawPath);
        if (path.length() == 0) {
            path = "/";
        }

        Map<String, String> headers = HttpUtil.readHeaders(in);
        String transferEncoding = headers.get("transfer-encoding");
        boolean chunked = transferEncoding != null
                && transferEncoding.toLowerCase(Locale.US).indexOf("chunked") >= 0;

        long contentLength = -1;
        String contentLengthHeader = headers.get("content-length");
        if (contentLengthHeader != null && contentLengthHeader.trim().length() > 0) {
            try {
                contentLength = Long.parseLong(contentLengthHeader.trim());
            } catch (NumberFormatException e) {
                throw new IOException("非法 Content-Length: " + contentLengthHeader);
            }
            if (contentLength < 0) {
                throw new IOException("非法 Content-Length: " + contentLengthHeader);
            }
        }

        String expect = headers.get("expect");
        boolean expectContinue = expect != null
                && expect.toLowerCase(Locale.US).indexOf("100-continue") >= 0;

        Map<String, String> query = HttpUtil.parseQuery(rawQuery);
        return new HttpRequest(method, path, query, headers, in, contentLength, chunked,
                expectContinue, remoteIp);
    }

    private static void writeResponse(OutputStream out, HttpResponse response) throws IOException {
        int status = response.getStatus();
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(status).append(' ').append(HttpResponse.reason(status)).append("\r\n");
        head.append("Server: LocalSendLegacy\r\n");
        String contentType = response.getContentType();
        if (contentType != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
        }
        byte[] body = response.getBody();
        boolean bodyAllowed = status != 204 && status != 304;
        if (bodyAllowed) {
            head.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        out.write(HttpUtil.utf8(head.toString()));
        if (bodyAllowed && body != null && body.length > 0) {
            out.write(body);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
