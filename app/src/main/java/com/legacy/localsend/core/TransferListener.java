package com.legacy.localsend.core;

import java.util.List;

/**
 * 核心协议层 -> UI 的事件回调。回调发生在后台线程，Android 实现负责切回主线程。
 */
public interface TransferListener {

    /**
     * 收到别人的发送请求（仅元数据）。返回 true 表示接收。
     * 注意：运行在 HTTP 工作线程上，UI 实现可以阻塞等待用户点击，但必须自己设置超时。
     */
    boolean onReceiveRequest(ReceiveRequest request);

    void onReceiveProgress(String sessionId, FileMeta file, long bytesReceived, long totalBytes);

    void onReceiveComplete(String sessionId, List<IncomingFile> files);

    void onReceiveFailed(String sessionId, String reason);

    void onSendProgress(DeviceInfo target, FileMeta file, long bytesSent, long totalBytes);

    void onSendComplete(DeviceInfo target, List<FileMeta> files);

    void onSendFailed(DeviceInfo target, String reason);

    /** 有设备通过 HTTP /register 主动登记到自己（用于更新设备列表）。 */
    void onPeerDiscovered(DeviceInfo info, String ip);

    /** 供调试面板/日志使用。 */
    void onLog(String message);
}
