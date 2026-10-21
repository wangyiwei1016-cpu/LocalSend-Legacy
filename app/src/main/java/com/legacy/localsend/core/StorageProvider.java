package com.legacy.localsend.core;

import java.io.IOException;

/** 接收文件的落盘策略（Android 实现写公共下载目录；桌面测试写临时目录）。 */
public interface StorageProvider {

    /**
     * 为即将接收的文件分配一个写入目标。实现必须处理重名、路径穿越和非法文件名。
     */
    IncomingFile allocate(FileMeta meta) throws IOException;
}
