package com.legacy.localsend.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * LocalSend v2/v1 HTTP 服务器（明文 HTTP，兼容 Android 2.x 老系统的 TLS 能力）。
 *
 * 端点：
 *   GET  /api/localsend/v2/info
 *   POST /api/localsend/v2/register
 *   POST /api/localsend/v2/prepare-upload
 *   POST /api/localsend/v2/upload
 *   POST /api/localsend/v2/cancel
 *   v1: GET/POST /api/localsend/v1/info、/register、/send-request、/send、/cancel
 *
 * 协议要点：
 *   - 同一时刻只允许一个接收会话，冲突返回 409；
 *   - 每个文件有独立 token，/upload 校验 sessionId + fileId + token + 发送方 IP；
 *   - 上传流式写盘并增量计算 SHA-256，prepare 里给了 sha256 且不匹配时返回 422；
 *   - 收到 /register 后回调 listener.onPeerDiscovered，实现双向发现；
 *   - 请求支持 Content-Length / chunked / Expect: 100-continue，响应统一 Connection: close。
 */
public class LsServer {

    private static final String PREFIX_V2 = "/api/localsend/v2";
    private static final String PREFIX_V1 = "/api/localsend/v1";

    /** 控制类 JSON 请求体上限（register / prepare-upload）。 */
    private static final long MAX_CONTROL_BODY = 2L * 1024L * 1024L;

    private static final int STATE_ACTIVE = 0;
    private static final int STATE_COMPLETED = 1;
    private static final int STATE_FAILED = 2;

    private static final int BUFFER_SIZE = 32 * 1024;

    /** 同一 IP 连续输错 PIN 的最大次数（超过后返回 429，与官方实现一致）。 */
    private static final int MAX_PIN_ATTEMPTS = 3;

    private final ServerConfig config;
    private final Object sessionLock = new Object();
    private final Object pinLock = new Object();
    private final Map<String, Integer> pinFailures = new LinkedHashMap<String, Integer>();
    private final HttpServer.Handler handler;

    private Session activeSession;
    private HttpServer httpServer;
    private volatile boolean running;
    private volatile int boundPort;

    public LsServer(ServerConfig config) {
        this.config = config == null ? new ServerConfig() : config;
        this.handler = new HttpServer.Handler() {
            @Override
            public HttpResponse handle(HttpRequest request) {
                return dispatchSafely(request);
            }
        };
    }

    /** 绑定端口并开始接收请求；端口被占用时抛 IOException。 */
    public void start() throws IOException {
        if (running) {
            return;
        }
        int port = config.port;
        if (port < 0 || port > 65535) {
            throw new IOException("非法端口: " + port);
        }
        HttpServer server = new HttpServer(handler);
        server.start(port);
        this.httpServer = server;
        this.boundPort = server.getPort();
        this.running = true;
        log("HTTP 服务器已启动，端口 " + boundPort);
    }

    /** 停止服务器并释放端口。 */
    public void stop() {
        running = false;
        HttpServer server = this.httpServer;
        this.httpServer = null;
        if (server != null) {
            server.stop();
        }
        Session session;
        synchronized (sessionLock) {
            session = activeSession;
            activeSession = null;
        }
        if (session != null) {
            session.state = STATE_FAILED;
            abortSessionFiles(session);
            boolean notify;
            synchronized (sessionLock) {
                notify = !session.failedNotified;
                session.failedNotified = true;
            }
            if (notify) {
                TransferListener listener = config.listener;
                if (listener != null) {
                    try {
                        listener.onReceiveFailed(session.id, "服务器已停止");
                    } catch (RuntimeException e) {
                        log("onReceiveFailed 回调异常: " + e);
                    }
                }
            }
        }
        log("HTTP 服务器已停止");
    }

    public boolean isRunning() {
        return running && httpServer != null;
    }

    public int getPort() {
        int port = boundPort;
        return port > 0 ? port : config.port;
    }

    public ServerConfig getConfig() {
        return config;
    }

    /** 本机对外广播/回应的设备信息。 */
    public DeviceInfo getDeviceInfo() {
        DeviceInfo info = new DeviceInfo(config.alias, config.deviceModel, config.fingerprint, getPort());
        info.deviceType = config.deviceType == null ? "mobile" : config.deviceType;
        info.protocol = config.protocol == null ? "http" : config.protocol;
        info.download = config.download;
        return info;
    }

    // ==================================================================
    // 路由
    // ==================================================================

    private HttpResponse dispatchSafely(HttpRequest request) {
        try {
            return dispatch(request);
        } catch (HttpStatusException e) {
            // 请求体过大 413 / 被截断或 chunked 非法 400：是发送端的错，不能算 500。
            log("请求体错误(" + e.getStatus() + "): " + e.getMessage());
            return HttpResponse.text(e.getStatus(), e.getMessage());
        } catch (Json.JsonException e) {
            log("JSON 解析失败: " + e.getMessage());
            return HttpResponse.text(400, "Invalid body");
        } catch (IOException e) {
            log("请求处理失败: " + e.getMessage());
            return HttpResponse.text(500, "Internal Server Error");
        } catch (RuntimeException e) {
            log("请求处理异常: " + e);
            return HttpResponse.text(500, "Internal Server Error");
        }
    }

    private HttpResponse dispatch(HttpRequest request) throws IOException, Json.JsonException {
        String path = request.getPath();
        if (path == null) {
            path = "";
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String method = request.getMethod();
        boolean get = "GET".equals(method);
        boolean post = "POST".equals(method);

        if (get && (PREFIX_V2 + "/info").equals(path)) {
            return handleInfo(request, false);
        }
        if (post && (PREFIX_V2 + "/register").equals(path)) {
            return handleRegister(request, false);
        }
        if (post && (PREFIX_V2 + "/prepare-upload").equals(path)) {
            return handlePrepareUpload(request, false);
        }
        if (post && (PREFIX_V2 + "/upload").equals(path)) {
            return handleUpload(request, false);
        }
        if (post && (PREFIX_V2 + "/cancel").equals(path)) {
            return handleCancel(request, false);
        }

        if (config.enableV1) {
            if (get && (PREFIX_V1 + "/info").equals(path)) {
                return handleInfo(request, true);
            }
            if (post && (PREFIX_V1 + "/register").equals(path)) {
                return handleRegister(request, true);
            }
            if (post && (PREFIX_V1 + "/send-request").equals(path)) {
                return handlePrepareUpload(request, true);
            }
            if (post && (PREFIX_V1 + "/send").equals(path)) {
                return handleUpload(request, true);
            }
            if (post && (PREFIX_V1 + "/cancel").equals(path)) {
                return handleCancel(request, true);
            }
        }
        return HttpResponse.text(404, "Not Found");
    }

    // ==================================================================
    // /info
    // ==================================================================

    private HttpResponse handleInfo(HttpRequest request, boolean v1) {
        if (v1) {
            return HttpResponse.json(200, v1InfoMap());
        }
        return HttpResponse.json(200, getDeviceInfo().toMap());
    }

    private Map<String, Object> v1InfoMap() {
        Map<String, Object> map = Json.newObject();
        map.put("alias", config.alias == null ? "" : config.alias);
        map.put("deviceModel", config.deviceModel == null ? "" : config.deviceModel);
        map.put("deviceType", config.deviceType == null ? "mobile" : config.deviceType);
        return map;
    }

    // ==================================================================
    // /register
    // ==================================================================

    private HttpResponse handleRegister(HttpRequest request, boolean v1) throws IOException, Json.JsonException {
        Map<String, Object> body = Json.parseObject(request.readBodyString(MAX_CONTROL_BODY));
        DeviceInfo peer = DeviceInfo.fromMap(body);
        if (peer == null) {
            return HttpResponse.text(400, "Invalid body");
        }
        if (peer.alias == null) {
            peer.alias = "";
        }
        if (peer.fingerprint == null) {
            peer.fingerprint = "";
        }
        TransferListener listener = config.listener;
        if (listener != null) {
            try {
                listener.onPeerDiscovered(peer, request.getRemoteIp());
            } catch (RuntimeException e) {
                log("onPeerDiscovered 回调异常: " + e);
            }
        }
        if (v1) {
            return HttpResponse.json(200, v1InfoMap());
        }
        return HttpResponse.json(200, getDeviceInfo().toMap());
    }

    // ==================================================================
    // /prepare-upload（v1: /send-request）
    // ==================================================================

    private HttpResponse handlePrepareUpload(HttpRequest request, boolean v1)
            throws IOException, Json.JsonException {
        if (!v1) {
            HttpResponse pinError = checkPin(request);
            if (pinError != null) {
                return pinError;
            }
        }

        Map<String, Object> body = Json.parseObject(request.readBodyString(MAX_CONTROL_BODY));
        DeviceInfo sender = DeviceInfo.fromMap(Json.optObject(body, "info"));
        if (sender == null) {
            sender = new DeviceInfo();
        }
        Map<String, Object> filesMap = Json.optObject(body, "files");

        final List<FileMeta> metas = new ArrayList<FileMeta>();
        final List<String> ids = new ArrayList<String>();
        final List<Boolean> sizeKnown = new ArrayList<Boolean>();
        if (filesMap != null) {
            for (Iterator<Map.Entry<String, Object>> it = filesMap.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, Object> entry = it.next();
                Object value = entry.getValue();
                if (!(value instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> fileMap = (Map<String, Object>) value;
                FileMeta meta = FileMeta.fromMap(fileMap);
                if (meta == null) {
                    continue;
                }
                String id = meta.id;
                if (isEmpty(id)) {
                    id = entry.getKey();
                }
                if (isEmpty(id)) {
                    id = newId();
                }
                meta.id = id;
                meta.fileName = FileMeta.sanitizeFileName(meta.fileName);
                if (v1) {
                    meta.fileType = v1MimeType(meta.fileName, meta.fileType);
                }
                metas.add(meta);
                ids.add(id);
                sizeKnown.add(Boolean.valueOf(fileMap.containsKey("size")));
            }
        }
        if (metas.isEmpty()) {
            // 没有可接收的文件：协议里 400 表示请求体无效（官方实现同样返回 400）。
            return HttpResponse.text(400, "No files provided");
        }
        // 声明的文件大小校验：负数非法；超过 maxFileBytes 直接 413（不建立会话、不分配磁盘）。
        for (int i = 0; i < metas.size(); i++) {
            if (!sizeKnown.get(i).booleanValue()) {
                continue;
            }
            long declaredSize = metas.get(i).size;
            if (declaredSize < 0) {
                return HttpResponse.text(400, "Invalid file size");
            }
            if (config.maxFileBytes > 0 && declaredSize > config.maxFileBytes) {
                return HttpResponse.text(413, "File too large");
            }
        }
        if (config.storage == null) {
            return HttpResponse.text(500, "Server storage not configured");
        }

        final String senderIp = request.getRemoteIp();
        Session session = beginSession(senderIp, sender, v1);
        if (session == null) {
            return HttpResponse.text(409, "Blocked by another session");
        }

        boolean accepted;
        try {
            accepted = decideReceive(sender, senderIp, metas, request.getQueryParam("pin"));
        } catch (RuntimeException e) {
            log("接收决策异常: " + e);
            accepted = false;
        }
        if (!accepted) {
            clearSession(session, STATE_FAILED);
            return HttpResponse.text(403, "Rejected");
        }
        if (session.state != STATE_ACTIVE) {
            abortSessionFiles(session);
            return HttpResponse.text(400, "Cancelled");
        }

        try {
            for (int i = 0; i < metas.size(); i++) {
                FileMeta meta = metas.get(i);
                IncomingFile target = config.storage.allocate(meta);
                if (target == null) {
                    throw new IOException("StorageProvider.allocate 返回 null");
                }
                FileEntry fileEntry = new FileEntry(ids.get(i), newId(), meta, target,
                        sizeKnown.get(i).booleanValue());
                session.files.put(fileEntry.id, fileEntry);
                session.allocated.add(target);
            }
        } catch (IOException e) {
            notifyReceiveFailed(session, "无法创建接收文件: " + e.getMessage());
            return HttpResponse.text(500, "Cannot allocate storage");
        } catch (RuntimeException e) {
            notifyReceiveFailed(session, "无法创建接收文件: " + e);
            return HttpResponse.text(500, "Cannot allocate storage");
        }
        if (session.state != STATE_ACTIVE) {
            abortSessionFiles(session);
            return HttpResponse.text(400, "Cancelled");
        }

        Map<String, Object> responseFiles = Json.newObject();
        for (Iterator<FileEntry> it = session.files.values().iterator(); it.hasNext();) {
            FileEntry fileEntry = it.next();
            responseFiles.put(fileEntry.id, fileEntry.token);
        }
        log("接收会话 " + session.id + " 开始：文件 " + metas.size() + " 个，来自 " + senderIp);
        if (v1) {
            return HttpResponse.json(200, responseFiles);
        }
        Map<String, Object> response = Json.newObject();
        response.put("sessionId", session.id);
        response.put("files", responseFiles);
        return HttpResponse.json(200, response);
    }

    private HttpResponse checkPin(HttpRequest request) {
        String required = config.pin;
        if (required == null || required.length() == 0) {
            return null;
        }
        String remoteIp = request.getRemoteIp();
        synchronized (pinLock) {
            Integer recorded = pinFailures.get(remoteIp);
            int failures = recorded == null ? 0 : recorded.intValue();
            if (failures >= MAX_PIN_ATTEMPTS) {
                return HttpResponse.text(429, "Too many requests");
            }
            String supplied = request.getQueryParam("pin");
            if (supplied != null && supplied.equals(required)) {
                pinFailures.remove(remoteIp);
                return null;
            }
            if (supplied != null) {
                // 只有真正发来（错误）PIN 才计数；完全没带 PIN 不计数。
                pinFailures.put(remoteIp, Integer.valueOf(failures + 1));
            }
            return HttpResponse.text(401, supplied == null ? "PIN required" : "Invalid PIN");
        }
    }

    private boolean decideReceive(DeviceInfo sender, String senderIp, List<FileMeta> metas, String pin) {
        if (config.autoAccept) {
            return true;
        }
        TransferListener listener = config.listener;
        if (listener == null) {
            return false;
        }
        ReceiveRequest receiveRequest = new ReceiveRequest(sender, senderIp, metas, pin);
        return listener.onReceiveRequest(receiveRequest);
    }

    // ==================================================================
    // /upload（v1: /send）
    // ==================================================================

    private HttpResponse handleUpload(HttpRequest request, boolean v1) {
        String fileId = request.getQueryParam("fileId");
        String token = request.getQueryParam("token");
        String sessionId = request.getQueryParam("sessionId");
        if (isEmpty(fileId) || isEmpty(token)) {
            return HttpResponse.text(400, "Missing parameters");
        }
        if (!v1 && isEmpty(sessionId)) {
            return HttpResponse.text(400, "Missing sessionId");
        }

        Session session;
        synchronized (sessionLock) {
            session = activeSession;
        }
        if (session == null) {
            return HttpResponse.text(403, "Invalid session");
        }
        if (!v1 && !session.id.equals(sessionId)) {
            return HttpResponse.text(409, "Blocked by another session");
        }
        if (!session.senderIp.equals(request.getRemoteIp())) {
            return HttpResponse.text(403, "Invalid IP address");
        }
        if (session.state != STATE_ACTIVE) {
            return HttpResponse.text(409, "Session is not active");
        }

        FileEntry entry = session.files.get(fileId);
        if (entry == null) {
            return HttpResponse.text(403, "Invalid file id");
        }
        if (!entry.token.equals(token)) {
            return HttpResponse.text(403, "Invalid token");
        }
        synchronized (entry) {
            if (entry.completed) {
                return HttpResponse.text(400, "File already uploaded");
            }
            if (entry.writing) {
                return HttpResponse.text(409, "File upload in progress");
            }
            entry.writing = true;
        }

        try {
            long declaredSize = entry.sizeKnown ? entry.meta.size : -1L;
            long maxFileBytes = config.maxFileBytes > 0 ? config.maxFileBytes : config.maxRequestBytes;
            long maxRequestBytes = config.maxRequestBytes;

            if (entry.sizeKnown) {
                if (declaredSize < 0) {
                    return failUpload(session, entry, 400, "Invalid file size");
                }
                if (declaredSize > maxFileBytes) {
                    return failUpload(session, entry, 413, "File too large");
                }
            }
            long contentLength = request.getContentLength();
            if (!request.isChunked()) {
                if (contentLength < 0) {
                    return HttpResponse.text(400, "Missing Content-Length");
                }
                if (contentLength > maxFileBytes) {
                    // 提前拒绝：不读请求体、不写盘。
                    return failUpload(session, entry, 413, "File too large");
                }
                if (entry.sizeKnown && contentLength != declaredSize) {
                    // Content-Length 与 prepare 声明的大小不符：提前拒绝，不读请求体、不写盘。
                    return failUpload(session, entry, 400, "Size mismatch");
                }
            }

            UploadFailure failure = null;
            MessageDigest digest = HttpUtil.sha256Digest();
            OutputStream out = null;
            long total = 0;
            long expectedTotal = entry.sizeKnown
                    ? declaredSize
                    : (contentLength >= 0 ? contentLength : -1L);
            try {
                out = entry.target.openStream();
                InputStream in = request.getBodyStream();
                byte[] buffer = new byte[BUFFER_SIZE];
                while (true) {
                    if (session.state != STATE_ACTIVE) {
                        throw new SessionCancelledException();
                    }
                    int n;
                    try {
                        n = in.read(buffer);
                    } catch (IOException e) {
                        // chunked 被截断 / chunk 格式非法：是发送端的请求错误，不是服务器 500。
                        throw new UploadFailure(400, "请求体读取失败: " + e.getMessage());
                    }
                    if (n < 0) {
                        break;
                    }
                    if (n == 0) {
                        continue;
                    }
                    long next = total + (long) n;
                    // 关键防护：严格按 prepare-upload 声明的大小计数。
                    // 超过声明大小立即停止（不写盘、不再继续读），防止恶意发送端用 chunked 超量写盘。
                    if (entry.sizeKnown && next > declaredSize) {
                        throw new UploadFailure(400, "Size exceeds declared size");
                    }
                    if (next > maxFileBytes) {
                        throw new UploadFailure(413, "File too large");
                    }
                    if (maxRequestBytes > 0 && next > maxRequestBytes) {
                        throw new UploadFailure(413, "Request body too large");
                    }
                    try {
                        out.write(buffer, 0, n);
                    } catch (IOException e) {
                        throw new UploadFailure(500, "写入文件失败: " + e.getMessage());
                    }
                    digest.update(buffer, 0, n);
                    total = next;
                    notifyReceiveProgress(session, entry, total, expectedTotal);
                }
                try {
                    out.flush();
                } catch (IOException e) {
                    throw new UploadFailure(500, "写入文件失败: " + e.getMessage());
                }
            } catch (SessionCancelledException e) {
                failure = new UploadFailure(400, "Transfer cancelled");
            } catch (UploadFailure e) {
                failure = e;
            } catch (IOException e) {
                // openStream / close 等存储层异常
                failure = new UploadFailure(500, "写入文件失败: " + e.getMessage());
            } finally {
                HttpUtil.closeQuietly(out);
                entry.writing = false;
            }

            if (failure == null) {
                if (!request.isChunked() && contentLength >= 0 && total != contentLength) {
                    failure = new UploadFailure(400, "Request body truncated");
                } else if (entry.sizeKnown && total != declaredSize) {
                    failure = new UploadFailure(400, "Size mismatch");
                } else if (entry.meta.sha256 != null && entry.meta.sha256.trim().length() > 0) {
                    String actual = HttpUtil.toHex(digest.digest());
                    if (!actual.equalsIgnoreCase(entry.meta.sha256.trim())) {
                        failure = new UploadFailure(422, "Checksum mismatch");
                    }
                }
            }
            if (failure != null) {
                return failUpload(session, entry, failure.status, failure.getMessage());
            }

            try {
                entry.target.commit();
            } catch (IOException e) {
                return failUpload(session, entry, 500, "保存文件失败: " + e.getMessage());
            }
            entry.completed = true;
            entry.writing = false;

            if (markCompleteIfDone(session)) {
                notifyReceiveComplete(session);
            }
            return HttpResponse.empty(200);
        } finally {
            entry.writing = false;
        }
    }

    private boolean markCompleteIfDone(Session session) {
        synchronized (sessionLock) {
            boolean allDone = true;
            for (Iterator<FileEntry> it = session.files.values().iterator(); it.hasNext();) {
                if (!it.next().completed) {
                    allDone = false;
                    break;
                }
            }
            if (!allDone || session.state != STATE_ACTIVE) {
                return false;
            }
            session.state = STATE_COMPLETED;
            if (activeSession == session) {
                activeSession = null;
            }
            return true;
        }
    }

    // ==================================================================
    // /cancel
    // ==================================================================

    private HttpResponse handleCancel(HttpRequest request, boolean v1) {
        String sessionId = request.getQueryParam("sessionId");
        Session session;
        synchronized (sessionLock) {
            session = activeSession;
        }
        if (session == null) {
            return HttpResponse.empty(200);
        }
        if (!v1 && !isEmpty(sessionId) && !session.id.equals(sessionId)) {
            return HttpResponse.empty(200);
        }
        if (!session.senderIp.equals(request.getRemoteIp())) {
            return HttpResponse.text(403, "Invalid IP address");
        }
        notifyReceiveFailed(session, "发送方已取消");
        return HttpResponse.empty(200);
    }

    // ==================================================================
    // 会话管理
    // ==================================================================

    private Session beginSession(String senderIp, DeviceInfo sender, boolean v1) {
        synchronized (sessionLock) {
            if (activeSession != null) {
                return null;
            }
            Session session = new Session(newId(), senderIp, sender, v1);
            activeSession = session;
            return session;
        }
    }

    private void clearSession(Session session, int state) {
        synchronized (sessionLock) {
            if (activeSession == session) {
                activeSession = null;
            }
        }
        session.state = state;
    }

    private void notifyReceiveFailed(Session session, String reason) {
        boolean notify;
        synchronized (sessionLock) {
            if (session.state == STATE_COMPLETED) {
                return;
            }
            notify = !session.failedNotified;
            session.failedNotified = true;
            if (activeSession == session) {
                activeSession = null;
            }
        }
        session.state = STATE_FAILED;
        abortSessionFiles(session);
        if (notify) {
            log("接收会话 " + session.id + " 失败: " + reason);
            TransferListener listener = config.listener;
            if (listener != null) {
                try {
                    listener.onReceiveFailed(session.id, reason);
                } catch (RuntimeException e) {
                    log("onReceiveFailed 回调异常: " + e);
                }
            }
        }
    }

    private HttpResponse failUpload(Session session, FileEntry entry, int status, String reason) {
        if (entry != null) {
            entry.writing = false;
            entry.abort();
        }
        notifyReceiveFailed(session, reason);
        return HttpResponse.text(status, reason == null ? "Upload failed" : reason);
    }

    private void abortSessionFiles(Session session) {
        List<IncomingFile> files;
        synchronized (sessionLock) {
            files = new ArrayList<IncomingFile>(session.allocated);
        }
        for (int i = 0; i < files.size(); i++) {
            try {
                files.get(i).abort();
            } catch (RuntimeException ignored) {
                // ignore
            }
        }
    }

    private void notifyReceiveComplete(Session session) {
        List<IncomingFile> files = new ArrayList<IncomingFile>();
        for (Iterator<FileEntry> it = session.files.values().iterator(); it.hasNext();) {
            files.add(it.next().target);
        }
        log("接收会话 " + session.id + " 完成：" + files.size() + " 个文件");
        TransferListener listener = config.listener;
        if (listener != null) {
            try {
                listener.onReceiveComplete(session.id, files);
            } catch (RuntimeException e) {
                log("onReceiveComplete 回调异常: " + e);
            }
        }
    }

    private void notifyReceiveProgress(Session session, FileEntry entry, long received, long total) {
        TransferListener listener = config.listener;
        if (listener == null) {
            return;
        }
        try {
            listener.onReceiveProgress(session.id, entry.meta, received, total);
        } catch (RuntimeException e) {
            log("onReceiveProgress 回调异常: " + e);
        }
    }

    private void log(String message) {
        TransferListener listener = config.listener;
        if (listener == null) {
            return;
        }
        try {
            listener.onLog(message);
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String v1MimeType(String fileName, String fileType) {
        if (fileType != null && fileType.indexOf('/') >= 0) {
            return fileType;
        }
        String guessed = SendFile.guessMimeType(fileName);
        if (!"application/octet-stream".equals(guessed)) {
            return guessed;
        }
        if (fileType == null) {
            return "application/octet-stream";
        }
        String lower = fileType.toLowerCase(Locale.US);
        if ("image".equals(lower)) {
            return "image/*";
        }
        if ("video".equals(lower)) {
            return "video/*";
        }
        if ("pdf".equals(lower)) {
            return "application/pdf";
        }
        if ("text".equals(lower)) {
            return "text/plain";
        }
        if ("audio".equals(lower)) {
            return "audio/*";
        }
        return "application/octet-stream";
    }

    // ==================================================================
    // 内部数据结构
    // ==================================================================

    private static final class Session {
        final String id;
        final String senderIp;
        final DeviceInfo sender;
        final boolean v1;
        final Map<String, FileEntry> files = new LinkedHashMap<String, FileEntry>();
        final List<IncomingFile> allocated = new ArrayList<IncomingFile>();
        volatile int state = STATE_ACTIVE;
        boolean failedNotified;

        Session(String id, String senderIp, DeviceInfo sender, boolean v1) {
            this.id = id;
            this.senderIp = senderIp;
            this.sender = sender;
            this.v1 = v1;
        }
    }

    private static final class FileEntry {
        final String id;
        final String token;
        final FileMeta meta;
        final IncomingFile target;
        final boolean sizeKnown;
        volatile boolean completed;
        volatile boolean writing;

        FileEntry(String id, String token, FileMeta meta, IncomingFile target, boolean sizeKnown) {
            this.id = id;
            this.token = token;
            this.meta = meta;
            this.target = target;
            this.sizeKnown = sizeKnown;
        }

        void abort() {
            if (completed) {
                return;
            }
            try {
                target.abort();
            } catch (RuntimeException ignored) {
                // ignore
            }
        }
    }

    private static final class UploadFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;

        UploadFailure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final class SessionCancelledException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
