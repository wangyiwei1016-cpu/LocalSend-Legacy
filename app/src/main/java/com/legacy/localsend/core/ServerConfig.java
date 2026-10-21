package com.legacy.localsend.core;

/** 服务器配置。UI 在启动服务前填充。 */
public class ServerConfig {

    public int port = 53317;
    public String alias = "LocalSend";
    public String deviceModel = "";
    public String deviceType = "mobile";
    public String fingerprint = "";
    public String protocol = "http";
    public boolean download = false;

    /** 接收 PIN，空字符串表示不需要 PIN。 */
    public String pin = "";

    public StorageProvider storage;
    public TransferListener listener;

    /** 是否同时兼容 LocalSend v1 老协议（HTTP）。 */
    public boolean enableV1 = true;

    /** 单次 HTTP 请求体上限（默认 8 GB），防御异常请求。 */
    public long maxRequestBytes = 8L * 1024L * 1024L * 1024L;

    /**
     * 单个文件声明的最大字节数（默认 8 GB）。prepare-upload 里声明的 size 超过该值直接 413；
     * 上传时也按声明大小严格计数，防止发送端用 chunked 超量写盘把磁盘写满。
     * 小于等于 0 表示使用 {@link #maxRequestBytes} 作为兜底。
     */
    public long maxFileBytes = 8L * 1024L * 1024L * 1024L;

    /** ASK_MODE：等待用户在 UI 上确认；AUTO_ACCEPT：自动接收。 */
    public boolean autoAccept = false;
}
