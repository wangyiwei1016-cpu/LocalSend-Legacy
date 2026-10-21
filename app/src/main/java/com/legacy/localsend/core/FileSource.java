package com.legacy.localsend.core;

import java.io.IOException;
import java.io.InputStream;

/**
 * 发送端文件数据源抽象，避免把 Android content:// URI 强制落盘成临时文件。
 */
public interface FileSource {

    long length();

    InputStream openStream() throws IOException;
}
