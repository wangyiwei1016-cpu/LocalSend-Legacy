package com.legacy.localsend.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.legacy.localsend.core.FileMeta;
import com.legacy.localsend.core.IncomingFile;
import com.legacy.localsend.core.SendFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 接收历史：以 JSON 存在应用私有 SharedPreferences 里。
 * 每条记录保存文件名、大小、MIME、落盘路径或 MediaStore Uri，供历史页打开/删除。
 */
final class HistoryStore {

    private static final String PREF_FILE = "received_history";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ENTRIES = 300;

    static final class Entry {
        String id;
        String name;
        String mime;
        String path;
        String uri;
        long size;
        long time;
    }

    private HistoryStore() {
    }

    static synchronized List<Entry> load(Context context) {
        return loadLocked(context);
    }

    static synchronized List<Entry> addReceived(Context context, List<IncomingFile> files) {
        List<Entry> added = new ArrayList<Entry>();
        if (files == null) {
            return added;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < files.size(); i++) {
            IncomingFile incoming = files.get(i);
            if (incoming == null) {
                continue;
            }
            FileMeta meta = incoming.getMeta();
            Entry entry = new Entry();
            entry.id = UUID.randomUUID().toString();
            entry.time = now + i;
            entry.name = meta == null ? null : meta.fileName;
            entry.size = meta == null ? 0L : meta.size;
            entry.mime = meta == null ? null : meta.fileType;
            if (incoming instanceof AndroidStorage.SavedFileInfo) {
                AndroidStorage.SavedFileInfo saved = (AndroidStorage.SavedFileInfo) incoming;
                entry.path = saved.savedPath();
                entry.uri = saved.savedUri();
                if (saved.savedName() != null && saved.savedName().length() > 0) {
                    entry.name = saved.savedName();
                }
            }
            if (entry.path == null && entry.uri == null) {
                String described = incoming.describe();
                if (described != null && described.startsWith("/")) {
                    entry.path = described;
                    entry.name = new File(described).getName();
                }
            }
            if (entry.name == null || entry.name.length() == 0) {
                entry.name = "file";
            }
            if (entry.mime == null || entry.mime.length() == 0) {
                entry.mime = SendFile.guessMimeType(entry.name);
            }
            added.add(entry);
        }
        if (added.isEmpty()) {
            return added;
        }
        List<Entry> all = loadLocked(context);
        all.addAll(0, added);
        while (all.size() > MAX_ENTRIES) {
            all.remove(all.size() - 1);
        }
        saveLocked(context, all);
        return added;
    }

    static synchronized void remove(Context context, String id) {
        if (id == null) {
            return;
        }
        List<Entry> all = loadLocked(context);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (id.equals(all.get(i).id)) {
                all.remove(i);
            }
        }
        saveLocked(context, all);
    }

    /** 删除记录对应的源文件；成功或文件本来就不存在都算成功。 */
    static boolean deleteFile(Context context, Entry entry) {
        if (entry == null) {
            return false;
        }
        try {
            if (entry.uri != null && entry.uri.length() > 0) {
                int deleted = context.getContentResolver().delete(Uri.parse(entry.uri), null, null);
                return deleted > 0;
            }
            if (entry.path != null && entry.path.length() > 0) {
                File file = new File(entry.path);
                return !file.exists() || file.delete();
            }
        } catch (Throwable ignored) {
            // 文件被外部删除/无权限时按失败处理
        }
        return false;
    }

    // ------------------------------------------------------------------

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    private static List<Entry> loadLocked(Context context) {
        List<Entry> result = new ArrayList<Entry>();
        String raw;
        try {
            raw = prefs(context).getString(KEY_ITEMS, "");
        } catch (Throwable t) {
            return result;
        }
        if (raw == null || raw.length() == 0) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Entry entry = new Entry();
                entry.id = item.optString("id", "");
                entry.name = item.optString("name", "file");
                entry.mime = item.optString("mime", "application/octet-stream");
                entry.path = item.optString("path", "");
                entry.uri = item.optString("uri", "");
                entry.size = item.optLong("size", 0L);
                entry.time = item.optLong("time", 0L);
                if (entry.id == null || entry.id.length() == 0) {
                    entry.id = UUID.randomUUID().toString();
                }
                if ((entry.path == null || entry.path.length() == 0)
                        && (entry.uri == null || entry.uri.length() == 0)) {
                    continue;
                }
                result.add(entry);
            }
        } catch (Throwable ignored) {
            // JSON 损坏时视为空历史
        }
        return result;
    }

    private static void saveLocked(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            try {
                JSONObject item = new JSONObject();
                item.put("id", entry.id);
                item.put("name", entry.name);
                item.put("mime", entry.mime);
                item.put("path", entry.path == null ? "" : entry.path);
                item.put("uri", entry.uri == null ? "" : entry.uri);
                item.put("size", entry.size);
                item.put("time", entry.time);
                array.put(item);
            } catch (Throwable ignored) {
                // 单条写入失败不影响其他记录
            }
        }
        prefs(context).edit().putString(KEY_ITEMS, array.toString()).commit();
    }
}
