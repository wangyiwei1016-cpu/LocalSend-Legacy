package com.legacy.localsend.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LocalSend v2 发送端（明文 HTTP 客户端）：register / fetchInfo / prepare-upload / upload / cancel。
 *
 * 执行流程（{@link #send}）：
 *   POST /api/localsend/v2/prepare-upload  -> 拿到 sessionId 和每个文件的 token
 *   POST /api/localsend/v2/upload?...       -> 逐文件流式上传（进度回调）
 *   任何一步失败都尽力 POST /api/localsend/v2/cancel，并回调 onSendFailed
 *
 * 说明：Android 2.x 的系统 TLS 只支持 TLS 1.0，无法与只开 HTTPS 的新版 LocalSend 通信，
 * 因此目标为 https 时这里直接抛出带中文说明的 IOException，由 UI 提示对方关闭加密。
 */
public class LsClient {

    private static final int DEFAULT_PORT = 53317;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /**
     * 读取控制响应（register / prepare-upload）的超时。
     * 需要覆盖接收端 UI 询问用户“是否接收”的等待时间（本项目 UI 上限 60 秒）。
     */
    private static final int READ_TIMEOUT_MS = 90000;

    private static final int UPLOAD_READ_TIMEOUT_MS = 120000;
    private static final int CANCEL_TIMEOUT_MS = 3000;
    private static final int BUFFER_SIZE = 32 * 1024;

    private static final String PATH_V2 = "/api/localsend/v2";
    private static final String PATH_V1 = "/api/localsend/v1";

    private static final String HTTPS_MESSAGE =
            "对方设备启用了加密传输（HTTPS）。Android 2.x 只支持 TLS 1.0，无法与 TLS 1.2 对端握手；"
                    + "请在对方 LocalSend 的设置里关闭“加密（Encryption）”，然后重试。";

    private volatile boolean cancelled;
    private volatile Socket currentSocket;
    private volatile DeviceInfo self;
    private volatile String pin = "";

    public LsClient() {
        this(null);
    }

    /**
     * @param self 本机设备信息（别名/指纹等），用于 prepare-upload 里的 info 字段；可为 null。
     */
    public LsClient(DeviceInfo self) {
        this.self = self;
    }

    public void setSelf(DeviceInfo self) {
        this.self = self;
    }

    /** 设置接收方要求的 PIN（非空时自动加到 prepare-upload 的 ?pin= 上）。 */
    public void setPin(String pin) {
        this.pin = pin == null ? "" : pin;
    }

    /** 中断正在进行的发送（可从其他线程调用）。 */
    public void cancel() {
        cancelled = true;
        Socket socket = currentSocket;
        if (socket != null) {
            HttpUtil.closeQuietly(socket);
        }
    }

    /**
     * 把文件发送给目标设备（阻塞调用，必须在后台线程执行）。
     * 目标协议为 https 时直接抛 IOException（需要在 UI 上给出明确提示）。
     */
    public void send(DeviceInfo target, String targetIp, List<SendFile> files,
                     TransferListener listener) throws IOException {
        if (target == null) {
            throw new IOException("目标设备为空");
        }
        if (targetIp == null || targetIp.length() == 0) {
            throw new IOException("目标 IP 地址为空");
        }
        if (target.isHttps()) {
            throw new IOException(HTTPS_MESSAGE);
        }
        if (files == null || files.isEmpty()) {
            throw new IOException("没有要发送的文件");
        }
        cancelled = false;
        try {
            sendInternal(target, targetIp, files, listener);
        } catch (IOException e) {
            notifySendFailed(target, e.getMessage(), listener);
            throw e;
        } catch (RuntimeException e) {
            notifySendFailed(target, String.valueOf(e), listener);
            throw new IOException("发送失败: " + e);
        }
    }

    private void sendInternal(DeviceInfo target, String targetIp, List<SendFile> files,
                              TransferListener listener) throws IOException {
        int port = target.port > 0 ? target.port : DEFAULT_PORT;
        DeviceInfo selfInfo = buildSelf();

        List<String> ids = new ArrayList<String>();
        List<FileMeta> metas = new ArrayList<FileMeta>();
        Map<String, Object> requestBody = Json.newObject();
        requestBody.put("info", selfInfo.toMap());
        Map<String, Object> filesMap = Json.newObject();
        for (int i = 0; i < files.size(); i++) {
            SendFile sendFile = files.get(i);
            if (sendFile == null || sendFile.meta == null || sendFile.source == null) {
                throw new IOException("待发送文件信息不完整");
            }
            String id = sendFile.meta.id;
            if (id == null || id.length() == 0) {
                id = UUID.randomUUID().toString().replace("-", "");
                sendFile.meta.id = id;
            }
            ids.add(id);
            metas.add(sendFile.meta);
            Map<String, Object> fileMap = sendFile.meta.toMap();
            fileMap.put("id", id);
            filesMap.put(id, fileMap);
        }
        requestBody.put("files", filesMap);

        String preparePath = PATH_V2 + "/prepare-upload";
        String currentPin = pin;
        if (currentPin != null && currentPin.length() > 0) {
            preparePath = preparePath + "?pin=" + HttpUtil.encodeQueryParam(currentPin);
        }
        HttpUtil.HttpResult prepareResult = HttpUtil.request("POST", targetIp, port, preparePath,
                HttpUtil.utf8(Json.stringify(requestBody)), CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        if (prepareResult.status == 204) {
            // 对方表示不需要传输（例如请求被部分接受后没有剩余文件）。
            notifySendComplete(target, metas, listener);
            return;
        }
        if (prepareResult.status != 200) {
            throw new IOException(prepareErrorMessage(prepareResult));
        }

        Map<String, Object> response;
        try {
            response = Json.parseObject(prepareResult.bodyText());
        } catch (Json.JsonException e) {
            throw new IOException("对方返回的 prepare-upload 响应无法解析: " + e.getMessage());
        }
        String sessionId = Json.optString(response, "sessionId", null);
        if (sessionId == null || sessionId.length() == 0) {
            throw new IOException("对方响应缺少 sessionId");
        }
        Map<String, Object> tokens = Json.optObject(response, "files");

        try {
            for (int i = 0; i < files.size(); i++) {
                if (cancelled) {
                    throw new IOException("发送已取消");
                }
                String token = tokens == null ? null : Json.optString(tokens, ids.get(i), null);
                if (token == null || token.length() == 0) {
                    throw new IOException("对方没有为文件分配上传令牌: " + metas.get(i).displayName());
                }
                uploadFile(target, targetIp, port, sessionId, ids.get(i), token, files.get(i), listener);
            }
        } catch (IOException e) {
            tryCancel(targetIp, port, sessionId);
            throw e;
        } catch (RuntimeException e) {
            tryCancel(targetIp, port, sessionId);
            throw new IOException("发送失败: " + e);
        }
        notifySendComplete(target, metas, listener);
    }

    /** 上传单个文件（流式，不把文件读进内存）。 */
    private void uploadFile(DeviceInfo target, String targetIp, int port, String sessionId,
                            String fileId, String token, SendFile sendFile,
                            TransferListener listener) throws IOException {
        long length = sendFile.source.length();
        if (length < 0) {
            throw new IOException("无法确定文件长度: " + sendFile.meta.displayName());
        }
        StringBuilder path = new StringBuilder(192);
        path.append(PATH_V2).append("/upload?sessionId=").append(HttpUtil.encodeQueryParam(sessionId))
                .append("&fileId=").append(HttpUtil.encodeQueryParam(fileId))
                .append("&token=").append(HttpUtil.encodeQueryParam(token));

        Socket socket = null;
        InputStream data = null;
        try {
            socket = HttpUtil.openSocket(targetIp, port, CONNECT_TIMEOUT_MS, UPLOAD_READ_TIMEOUT_MS);
            currentSocket = socket;
            if (cancelled) {
                throw new IOException("发送已取消");
            }
            OutputStream out = socket.getOutputStream();
            StringBuilder head = new StringBuilder(256);
            head.append("POST ").append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(targetIp).append(':').append(port).append("\r\n");
            head.append("User-Agent: LocalSendLegacy/1.0\r\n");
            head.append("Content-Type: application/octet-stream\r\n");
            head.append("Content-Length: ").append(length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(HttpUtil.utf8(head.toString()));

            data = sendFile.source.openStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            long sent = 0;
            while (true) {
                if (cancelled) {
                    throw new IOException("发送已取消");
                }
                int n = data.read(buffer);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }
                out.write(buffer, 0, n);
                sent += n;
                notifySendProgress(target, sendFile.meta, sent, length, listener);
            }
            out.flush();
            if (sent != length) {
                throw new IOException("读取到的文件长度与声明不一致: " + sent + "/" + length);
            }
            HttpUtil.HttpResult result = HttpUtil.readResponse(socket.getInputStream(),
                    HttpUtil.MAX_RESPONSE_BYTES);
            if (result.status != 200) {
                throw new IOException(uploadErrorMessage(result, sendFile.meta));
            }
        } finally {
            HttpUtil.closeQuietly(data);
            HttpUtil.closeQuietly(socket);
            currentSocket = null;
        }
    }

    private void tryCancel(String targetIp, int port, String sessionId) {
        cancelled = true;
        Socket socket = currentSocket;
        if (socket != null) {
            HttpUtil.closeQuietly(socket);
        }
        if (sessionId == null || sessionId.length() == 0) {
            return;
        }
        try {
            HttpUtil.request("POST", targetIp, port,
                    PATH_V2 + "/cancel?sessionId=" + HttpUtil.encodeQueryParam(sessionId),
                    new byte[0], CANCEL_TIMEOUT_MS, CANCEL_TIMEOUT_MS);
        } catch (IOException ignored) {
            // 尽力而为：取消失败不影响把原始错误抛给 UI。
        }
    }

    private DeviceInfo buildSelf() {
        DeviceInfo info = self;
        if (info == null) {
            info = defaultSelfInfo();
        }
        return info;
    }

    // ==================================================================
    // 静态发现 / 调试方法
    // ==================================================================

    /**
     * 通过 HTTP POST /api/localsend/v2/register 注册到指定 IP，返回对方设备信息。
     * 若对方只支持 v1（返回 404），自动回退到 /api/localsend/v1/register。
     */
    public static DeviceInfo register(DeviceInfo self, String targetIp, int port) throws IOException {
        if (targetIp == null || targetIp.length() == 0) {
            throw new IOException("目标 IP 地址为空");
        }
        int actualPort = port > 0 ? port : DEFAULT_PORT;
        DeviceInfo selfInfo = self == null ? defaultSelfInfo() : self;
        HttpUtil.HttpResult result = HttpUtil.request("POST", targetIp, actualPort,
                PATH_V2 + "/register", HttpUtil.utf8(Json.stringify(selfInfo.toMap())),
                HttpUtil.CONNECT_TIMEOUT_MS, HttpUtil.READ_TIMEOUT_MS);
        if (result.status == 404) {
            return registerV1(selfInfo, targetIp, actualPort);
        }
        if (result.status != 200) {
            throw new IOException("注册失败：HTTP " + result.status);
        }
        return parseDeviceInfo(result.bodyText(), actualPort, "http");
    }

    /** 读取对方的 /api/localsend/v2/info（调试/手动添加设备用；404 时回退 v1 的 /info）。 */
    public static DeviceInfo fetchInfo(DeviceInfo self, String targetIp, int port) throws IOException {
        if (targetIp == null || targetIp.length() == 0) {
            throw new IOException("目标 IP 地址为空");
        }
        int actualPort = port > 0 ? port : DEFAULT_PORT;
        HttpUtil.HttpResult result = HttpUtil.request("GET", targetIp, actualPort,
                PATH_V2 + "/info", null, HttpUtil.CONNECT_TIMEOUT_MS, HttpUtil.READ_TIMEOUT_MS);
        if (result.status == 404) {
            return fetchInfoV1(self, targetIp, actualPort);
        }
        if (result.status != 200) {
            throw new IOException("获取设备信息失败：HTTP " + result.status);
        }
        return parseDeviceInfo(result.bodyText(), actualPort, "http");
    }

    private static DeviceInfo fetchInfoV1(DeviceInfo self, String targetIp, int port) throws IOException {
        StringBuilder path = new StringBuilder(96);
        path.append(PATH_V1).append("/info");
        if (self != null && self.fingerprint != null && self.fingerprint.length() > 0) {
            path.append("?fingerprint=").append(HttpUtil.encodeQueryParam(self.fingerprint));
        }
        HttpUtil.HttpResult result = HttpUtil.request("GET", targetIp, port, path.toString(), null,
                HttpUtil.CONNECT_TIMEOUT_MS, HttpUtil.READ_TIMEOUT_MS);
        if (result.status != 200) {
            throw new IOException("获取设备信息失败（v1 回退）：HTTP " + result.status);
        }
        DeviceInfo info = parseDeviceInfo(result.bodyText(), port, "http");
        info.version = "1.0";
        return info;
    }

    private static DeviceInfo registerV1(DeviceInfo self, String targetIp, int port) throws IOException {
        Map<String, Object> body = Json.newObject();
        body.put("alias", self == null || self.alias == null ? "" : self.alias);
        body.put("deviceModel", self == null || self.deviceModel == null ? "" : self.deviceModel);
        body.put("deviceType", self == null || self.deviceType == null ? "mobile" : self.deviceType);
        body.put("fingerprint", self == null || self.fingerprint == null ? "" : self.fingerprint);
        HttpUtil.HttpResult result = HttpUtil.request("POST", targetIp, port,
                PATH_V1 + "/register", HttpUtil.utf8(Json.stringify(body)),
                HttpUtil.CONNECT_TIMEOUT_MS, HttpUtil.READ_TIMEOUT_MS);
        if (result.status != 200) {
            throw new IOException("注册失败（v1 回退）：HTTP " + result.status);
        }
        DeviceInfo info = parseDeviceInfo(result.bodyText(), port, "http");
        info.version = "1.0";
        return info;
    }

    private static DeviceInfo parseDeviceInfo(String body, int fallbackPort, String fallbackProtocol)
            throws IOException {
        Map<String, Object> map;
        try {
            map = Json.parseObject(body);
        } catch (Json.JsonException e) {
            throw new IOException("无法解析设备信息 JSON: " + e.getMessage());
        }
        DeviceInfo info = DeviceInfo.fromMap(map);
        if (info == null) {
            throw new IOException("对方返回的设备信息为空");
        }
        if (info.port <= 0) {
            info.port = fallbackPort;
        }
        if (info.protocol == null || info.protocol.length() == 0) {
            info.protocol = fallbackProtocol;
        }
        return info;
    }

    private static DeviceInfo defaultSelfInfo() {
        DeviceInfo info = new DeviceInfo("LocalSend", "", randomFingerprint(), DEFAULT_PORT);
        info.protocol = "http";
        return info;
    }

    private static String randomFingerprint() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ==================================================================
    // 错误信息
    // ==================================================================

    private static String prepareErrorMessage(HttpUtil.HttpResult result) {
        switch (result.status) {
            case 400:
                return "对方认为请求内容无效（400 Invalid body）";
            case 401:
                return "对方要求 PIN 码或 PIN 错误（401），请检查 PIN 后重试";
            case 403:
                return "对方拒绝了本次传输（403 Rejected）";
            case 409:
                return "对方正忙，已有另一个传输会话（409 Blocked by another session）";
            case 429:
                return "请求过于频繁，请稍后再试（429 Too many requests）";
            case 500:
                return "对方内部错误（500）";
            default:
                return "对方返回异常状态码 " + result.status;
        }
    }

    private static String uploadErrorMessage(HttpUtil.HttpResult result, FileMeta meta) {
        String name = meta == null ? "文件" : meta.displayName();
        switch (result.status) {
            case 400:
                return "上传 " + name + " 失败：参数错误或传输被取消（400）";
            case 403:
                return "上传 " + name + " 被拒绝：令牌或 IP 校验失败（403）";
            case 409:
                return "上传 " + name + " 失败：对方正忙或该文件正在被上传（409）";
            case 422:
                return "文件 " + name + " 校验失败：SHA-256 不匹配（422）";
            case 500:
                return "上传 " + name + " 失败：对方写入文件出错（500）";
            default:
                return "上传 " + name + " 失败：HTTP " + result.status;
        }
    }

    // ==================================================================
    // 回调（保护：UI 回调抛异常不能打断传输）
    // ==================================================================

    private static void notifySendProgress(DeviceInfo target, FileMeta file, long sent, long total,
                                           TransferListener listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.onSendProgress(target, file, sent, total);
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    private static void notifySendComplete(DeviceInfo target, List<FileMeta> files,
                                           TransferListener listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.onSendComplete(target, files);
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    private static void notifySendFailed(DeviceInfo target, String reason, TransferListener listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.onSendFailed(target, reason);
        } catch (RuntimeException ignored) {
            // ignore
        }
    }
}
