package com.legacy.localsend.ui;

import android.content.ContentResolver;
import android.net.Uri;

import com.legacy.localsend.core.FileSource;

import java.io.IOException;
import java.io.InputStream;

/** 用 ContentResolver 读取 ACTION_GET_CONTENT 选中的 Uri。 */
final class UriFileSource implements FileSource {

    private final ContentResolver resolver;
    private final Uri uri;
    private final long length;

    UriFileSource(ContentResolver resolver, Uri uri, long length) {
        this.resolver = resolver;
        this.uri = uri;
        this.length = length;
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public InputStream openStream() throws IOException {
        InputStream input = resolver.openInputStream(uri);
        if (input == null) {
            throw new IOException("无法打开文件: " + uri);
        }
        return input;
    }
}
