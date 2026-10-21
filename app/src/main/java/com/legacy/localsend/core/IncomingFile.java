package com.legacy.localsend.core;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 接收端一个文件落盘目标。由 StorageProvider 创建；核心协议层只负责写数据和校验。
 */
public interface IncomingFile {

    FileMeta getMeta();

    /** 打开输出流（只调用一次）。 */
    OutputStream openStream() throws IOException;

    /** 数据完整并且 SHA-256 校验通过后调用，发布到最终位置。 */
    void commit() throws IOException;

    /** 失败/取消时调用，清理半成品。 */
    void abort();

    /** 给用户看的最终位置，例如 /sdcard/Download/LocalSend/a.jpg 或“下载/LocalSend”。 */
    String describe();
}
