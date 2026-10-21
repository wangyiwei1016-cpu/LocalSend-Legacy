package com.legacy.localsend.core;

import java.util.List;

/** 一次待确认的接收请求。 */
public class ReceiveRequest {

    public final DeviceInfo sender;
    public final String senderIp;
    public final List<FileMeta> files;
    public final String pin;

    public ReceiveRequest(DeviceInfo sender, String senderIp, List<FileMeta> files, String pin) {
        this.sender = sender;
        this.senderIp = senderIp;
        this.files = files;
        this.pin = pin;
    }

    public String displayName() {
        return sender == null ? "未知设备" : sender.displayName();
    }

    public long totalSize() {
        long total = 0;
        if (files != null) {
            for (int i = 0; i < files.size(); i++) {
                total += files.get(i).size;
            }
        }
        return total;
    }
}
