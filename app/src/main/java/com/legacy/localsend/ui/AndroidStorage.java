package com.legacy.localsend.ui;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.legacy.localsend.core.FileMeta;
import com.legacy.localsend.core.IncomingFile;
import com.legacy.localsend.core.StorageProvider;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 接收文件落盘：
 *  - API 29+：MediaStore.Downloads（RELATIVE_PATH=Download/LocalSendLegacy，IS_PENDING 控制可见性）
 *  - API 8~28：公共下载目录/Download/LocalSendLegacy，先写 .part 再 rename，重名加 (1)，
 *    不可写时回退到应用私有外部目录
 */
public class AndroidStorage implements StorageProvider {

    /** 已落盘文件的实际位置，供历史记录/打开/删除使用（API 29+ 只有 MediaStore Uri）。 */
    public interface SavedFileInfo {
        /** 文件系统绝对路径；MediaStore 落盘时为 null。 */
        String savedPath();

        /** MediaStore content Uri 字符串；旧系统落盘时为 null。 */
        String savedUri();

        String savedName();
    }

    private static final String FOLDER = "LocalSendLegacy";
    private static final String DEFAULT_MIME = "application/octet-stream";

    private final Context app;

    public AndroidStorage(Context context) {
        this.app = context.getApplicationContext();
    }

    @Override
    public IncomingFile allocate(FileMeta meta) throws IOException {
        FileMeta safeMeta = meta;
        if (safeMeta == null) {
            safeMeta = new FileMeta("0", "file", 0L, DEFAULT_MIME);
        }
        String name = FileMeta.sanitizeFileName(safeMeta.fileName);
        if (Build.VERSION.SDK_INT >= 29) {
            return MediaStoreAllocator.allocate(app, safeMeta, name);
        }
        return allocateLegacy(safeMeta, name);
    }

    /** 给主界面/设置页显示的接收目录。 */
    public String describeDirectory() {
        if (Build.VERSION.SDK_INT >= 29) {
            return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER;
        }
        File publicDir = publicDirectory();
        if (publicDir != null && ensureWritable(publicDir)) {
            return publicDir.getAbsolutePath();
        }
        File privateDir = privateDirectory();
        if (ensureWritable(privateDir)) {
            return privateDir.getAbsolutePath();
        }
        return privateDir.getAbsolutePath();
    }

    static File publicDirectory() {
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            return new File(downloads, FOLDER);
        } catch (Throwable t) {
            return null;
        }
    }

    File privateDirectory() {
        File base = null;
        try {
            base = app.getExternalFilesDir(null);
        } catch (Throwable ignored) {
            // 部分老机器外部目录不可用
        }
        if (base == null) {
            base = app.getFilesDir();
        }
        return new File(base, FOLDER);
    }

    private IncomingFile allocateLegacy(FileMeta meta, String name) throws IOException {
        File dir = publicDirectory();
        if (dir == null || !ensureWritable(dir)) {
            dir = privateDirectory();
        }
        if (!ensureWritable(dir)) {
            throw new IOException("无法创建接收目录: " + (dir == null ? "?" : dir.getAbsolutePath()));
        }
        File target = uniqueFile(dir, name);
        File part = new File(dir, target.getName() + ".part");
        if (part.exists() && !part.delete()) {
            throw new IOException("无法清理旧的临时文件: " + part.getAbsolutePath());
        }
        return new LegacyIncomingFile(meta, part, target);
    }

    /** 确保目录存在且真的能写入（mkdirs 成功不代表有写权限）。 */
    static boolean ensureWritable(File dir) {
        if (dir == null) {
            return false;
        }
        try {
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                return false;
            }
            if (!dir.canWrite()) {
                return false;
            }
            File probe = new File(dir, ".ls_write_" + System.currentTimeMillis());
            if (!probe.createNewFile()) {
                return false;
            }
            probe.delete();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 目录内重名时追加 (1)、(2)… */
    static File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            candidate = new File(dir, base + " (" + i + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, base + " (" + System.currentTimeMillis() + ")" + extension);
    }

    /** API 8~28 的落盘实现：先写 .part，校验完成后 rename 成正式文件。 */
    private static final class LegacyIncomingFile implements IncomingFile, SavedFileInfo {

        private final FileMeta meta;
        private final File partFile;
        private final File targetFile;
        private FileOutputStream rawStream;
        private boolean committed;

        LegacyIncomingFile(FileMeta meta, File partFile, File targetFile) {
            this.meta = meta;
            this.partFile = partFile;
            this.targetFile = targetFile;
        }

        @Override
        public FileMeta getMeta() {
            return meta;
        }

        @Override
        public OutputStream openStream() throws IOException {
            rawStream = new FileOutputStream(partFile);
            return new BufferedOutputStream(rawStream, 64 * 1024);
        }

        @Override
        public void commit() throws IOException {
            if (rawStream != null) {
                try {
                    rawStream.getFD().sync();
                } catch (Throwable ignored) {
                    // 部分老设备不支持 fsync
                }
                try {
                    rawStream.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
            if (partFile.exists() && !partFile.renameTo(targetFile)) {
                throw new IOException("无法保存文件: " + targetFile.getAbsolutePath());
            }
            committed = true;
        }

        @Override
        public void abort() {
            if (rawStream != null) {
                try {
                    rawStream.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
            if (partFile.exists()) {
                partFile.delete();
            }
        }

        @Override
        public String describe() {
            return committed ? targetFile.getAbsolutePath() : partFile.getAbsolutePath();
        }

        @Override
        public String savedPath() {
            return targetFile.getAbsolutePath();
        }

        @Override
        public String savedUri() {
            return null;
        }

        @Override
        public String savedName() {
            return targetFile.getName();
        }
    }

    /** API 29+ 的 MediaStore 落盘实现。 */
    @TargetApi(29)
    private static final class MediaStoreAllocator {

        private MediaStoreAllocator() {
        }

        static IncomingFile allocate(Context app, FileMeta meta, String name) throws IOException {
            ContentResolver resolver = app.getContentResolver();
            String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/";
            String displayName = uniqueName(resolver, relativePath, name);
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, meta.fileType == null ? DEFAULT_MIME : meta.fileType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            values.put(MediaStore.MediaColumns.IS_PENDING, Integer.valueOf(1));
            Uri uri;
            try {
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            } catch (Throwable t) {
                throw new IOException("无法写入下载目录: " + t.getMessage());
            }
            if (uri == null) {
                throw new IOException("无法在下载目录中创建文件");
            }
            return new MediaStoreIncomingFile(resolver, uri, displayName, meta);
        }

        private static String uniqueName(ContentResolver resolver, String relativePath, String name) {
            if (!exists(resolver, relativePath, name)) {
                return name;
            }
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String extension = dot > 0 ? name.substring(dot) : "";
            for (int i = 1; i < 1000; i++) {
                String candidate = base + " (" + i + ")" + extension;
                if (!exists(resolver, relativePath, candidate)) {
                    return candidate;
                }
            }
            return base + " (" + System.currentTimeMillis() + ")" + extension;
        }

        private static boolean exists(ContentResolver resolver, String relativePath, String displayName) {
            Cursor cursor = null;
            try {
                cursor = resolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[] { MediaStore.MediaColumns._ID },
                        MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                        new String[] { relativePath, displayName },
                        null);
                return cursor != null && cursor.getCount() > 0;
            } catch (Throwable t) {
                return false;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
    }

    @TargetApi(29)
    private static final class MediaStoreIncomingFile implements IncomingFile, SavedFileInfo {

        private final ContentResolver resolver;
        private final Uri uri;
        private final String displayName;
        private final FileMeta meta;
        private OutputStream stream;
        private boolean committed;

        MediaStoreIncomingFile(ContentResolver resolver, Uri uri, String displayName, FileMeta meta) {
            this.resolver = resolver;
            this.uri = uri;
            this.displayName = displayName;
            this.meta = meta;
        }

        @Override
        public FileMeta getMeta() {
            return meta;
        }

        @Override
        public OutputStream openStream() throws IOException {
            stream = resolver.openOutputStream(uri);
            if (stream == null) {
                throw new IOException("无法打开下载目录文件流");
            }
            return new BufferedOutputStream(stream, 64 * 1024);
        }

        @Override
        public void commit() throws IOException {
            if (stream != null) {
                try {
                    stream.flush();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, Integer.valueOf(0));
            try {
                int updated = resolver.update(uri, values, null, null);
                if (updated <= 0) {
                    throw new IOException("无法发布接收文件");
                }
            } catch (IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new IOException("无法发布接收文件: " + t.getMessage());
            }
            committed = true;
        }

        @Override
        public void abort() {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
            try {
                resolver.delete(uri, null, null);
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        @Override
        public String describe() {
            return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/" + displayName;
        }

        @Override
        public String savedPath() {
            return null;
        }

        @Override
        public String savedUri() {
            return uri.toString();
        }

        @Override
        public String savedName() {
            return displayName;
        }
    }
}
