package com.legacy.localsend.ui;

import com.legacy.localsend.ui.kit.IconView;

/** 接收文件的历史/完成弹窗按类型显示不同图标。 */
final class FileTypes {

    private FileTypes() {
    }

    static String iconFor(String name, String mime) {
        String type = mime == null ? "" : mime.toLowerCase();
        String lower = name == null ? "" : name.toLowerCase();
        if (type.startsWith("image/")) {
            return IconView.IMAGE;
        }
        if (type.startsWith("video/")) {
            return IconView.VIDEO;
        }
        if (type.startsWith("audio/")) {
            return IconView.AUDIO;
        }
        if (type.equals("application/vnd.android.package-archive") || lower.endsWith(".apk")) {
            return IconView.APK;
        }
        if (type.equals("application/pdf") || lower.endsWith(".pdf")) {
            return IconView.PDF;
        }
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")
                || lower.endsWith(".tar") || lower.endsWith(".gz")) {
            return IconView.ARCHIVE;
        }
        if (type.startsWith("text/") || lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".rtf")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx")
                || lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return IconView.DOC;
        }
        return IconView.FILE;
    }

    /** 低饱和的类型色，深浅色模式都能看清。 */
    static int colorFor(String icon) {
        if (IconView.IMAGE.equals(icon)) {
            return 0xFF6F8F6A;
        }
        if (IconView.VIDEO.equals(icon)) {
            return 0xFF8A6F8F;
        }
        if (IconView.AUDIO.equals(icon)) {
            return 0xFFB08A5E;
        }
        if (IconView.APK.equals(icon)) {
            return 0xFF5F8C8A;
        }
        if (IconView.PDF.equals(icon)) {
            return 0xFFA86B6B;
        }
        if (IconView.DOC.equals(icon)) {
            return 0xFF6B7F99;
        }
        if (IconView.ARCHIVE.equals(icon)) {
            return 0xFF8F7A5F;
        }
        return Theme.primary;
    }
}
