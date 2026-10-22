package com.legacy.localsend.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只读文件提供者：Android 7.0+ 禁止向外部分享 file:// Uri，
 * 用 content:// 把接收目录里的文件交给文件管理器/相册/播放器打开。
 */
public class ReceivedFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.legacy.localsend.files";
    private static final String PATH_OPEN = "open";

    public static Uri uriFor(String absolutePath, String mime) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(PATH_OPEN)
                .appendQueryParameter("path", absolutePath)
                .appendQueryParameter("mime", mime == null ? "application/octet-stream" : mime)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        if (uri == null) {
            return null;
        }
        String mime = uri.getQueryParameter("mime");
        if (mime != null && mime.length() > 0) {
            return mime;
        }
        String path = uri.getQueryParameter("path");
        if (path != null) {
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < path.length()) {
                String extension = path.substring(dot + 1).toLowerCase();
                String found = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (found != null) {
                    return found;
                }
            }
        }
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !"r".equals(mode)) {
            throw new FileNotFoundException("只读");
        }
        File file = resolve(uri);
        if (file == null) {
            throw new FileNotFoundException("无效路径");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        if (file == null) {
            return null;
        }
        String[] columns = projection == null
                ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = Long.valueOf(file.length());
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    /** 只允许应用自己的目录和公共存储里的文件，防止通过构造 Uri 读取任意路径。 */
    private File resolve(Uri uri) {
        if (uri == null) {
            return null;
        }
        String path = uri.getQueryParameter("path");
        if (path == null || path.length() == 0) {
            return null;
        }
        try {
            File file = new File(path);
            if (!file.isFile()) {
                return null;
            }
            String canonical = file.getCanonicalPath();
            if (!underRoot(canonical, Environment.getExternalStorageDirectory())
                    && !underRoot(canonical, getContext().getFilesDir())
                    && !underRoot(canonical, getContext().getExternalFilesDir(null))) {
                return null;
            }
            return file;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean underRoot(String canonical, File root) {
        if (root == null) {
            return false;
        }
        try {
            String rootPath = root.getCanonicalPath();
            return canonical.equals(rootPath)
                    || canonical.startsWith(rootPath + File.separator);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 给历史页使用：把文件交给系统打开。 */
    static Uri contentUriFor(File file, String mime) {
        return uriFor(file.getAbsolutePath(), mime);
    }
}
