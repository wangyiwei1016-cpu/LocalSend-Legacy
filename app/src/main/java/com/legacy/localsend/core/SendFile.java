package com.legacy.localsend.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** 待发送文件 = 元数据 + 数据源。 */
public class SendFile {

    public final FileMeta meta;
    public final FileSource source;

    public SendFile(FileMeta meta, FileSource source) {
        this.meta = meta;
        this.source = source;
    }

    public static SendFile fromFile(File file) {
        return fromFile(file, guessMimeType(file.getName()));
    }

    public static SendFile fromFile(File file, String mimeType) {
        FileMeta meta = new FileMeta(
                UUID.randomUUID().toString(),
                file.getName(),
                file.length(),
                mimeType);
        meta.sha256 = null;
        return new SendFile(meta, new FileSource() {
            @Override
            public long length() {
                return file.length();
            }

            @Override
            public InputStream openStream() throws IOException {
                return new FileInputStream(file);
            }
        });
    }

    public static String guessMimeType(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log")) return "text/plain";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }
}
