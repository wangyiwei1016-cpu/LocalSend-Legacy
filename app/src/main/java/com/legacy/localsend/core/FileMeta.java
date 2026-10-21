package com.legacy.localsend.core;

import java.util.Map;

/**
 * 单个待传输文件的元数据（LocalSend v2 files 对象中的一项）。
 */
public class FileMeta {

    public String id;
    public String fileName;
    public long size;
    public String fileType = "application/octet-stream";
    public String sha256;
    public String preview;
    public Map<String, Object> metadata;

    public FileMeta() {
    }

    public FileMeta(String id, String fileName, long size, String fileType) {
        this.id = id;
        this.fileName = fileName;
        this.size = size;
        this.fileType = fileType == null ? "application/octet-stream" : fileType;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = Json.newObject();
        if (id != null) {
            map.put("id", id);
        }
        map.put("fileName", fileName == null ? "" : fileName);
        map.put("size", Long.valueOf(size));
        map.put("fileType", fileType == null ? "application/octet-stream" : fileType);
        if (sha256 != null) {
            map.put("sha256", sha256);
        }
        if (preview != null) {
            map.put("preview", preview);
        }
        if (metadata != null) {
            map.put("metadata", metadata);
        }
        return map;
    }

    public static FileMeta fromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        FileMeta meta = new FileMeta();
        meta.id = Json.optString(map, "id", null);
        meta.fileName = Json.optString(map, "fileName", "file");
        meta.size = Json.optLong(map, "size", 0L);
        meta.fileType = Json.optString(map, "fileType", "application/octet-stream");
        meta.sha256 = Json.optString(map, "sha256", null);
        meta.preview = Json.optString(map, "preview", null);
        meta.metadata = Json.optObject(map, "metadata");
        return meta;
    }

    /**
     * 把不可信文件名清洗成安全文件名：去掉路径分隔符/控制字符/Windows 保留字符，限制长度。
     */
    public static String sanitizeFileName(String name) {
        if (name == null) {
            return "file";
        }
        String cleaned = name.replace('/', '_').replace('\\', '_');
        StringBuilder sb = new StringBuilder(cleaned.length());
        for (int i = 0; i < cleaned.length() && sb.length() < 120; i++) {
            char c = cleaned.charAt(i);
            if (c < 0x20 || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString().trim();
        while (result.startsWith(".")) {
            result = result.substring(1).trim();
        }
        if (result.length() == 0) {
            return "file";
        }
        return result;
    }

    public String displayName() {
        return fileName == null ? "文件" : fileName;
    }
}
