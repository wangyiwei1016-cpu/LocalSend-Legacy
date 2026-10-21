package com.legacy.localsend.core;

import java.util.Map;

/**
 * LocalSend v2 设备信息（协议中的 DeviceInfo）。
 * 纯 Java 模型：Android UI、协议服务器、桌面测试共用。
 */
public class DeviceInfo {

    public String alias = "";
    public String version = "2.0";
    public String deviceModel = "";
    public String deviceType = "mobile";
    public String fingerprint = "";
    public int port = 53317;
    public String protocol = "http";
    public boolean download = false;

    public DeviceInfo() {
    }

    public DeviceInfo(String alias, String deviceModel, String fingerprint, int port) {
        this.alias = alias == null ? "" : alias;
        this.deviceModel = deviceModel == null ? "" : deviceModel;
        this.fingerprint = fingerprint == null ? "" : fingerprint;
        this.port = port;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = Json.newObject();
        map.put("alias", alias == null ? "" : alias);
        map.put("version", version == null ? "2.0" : version);
        if (deviceModel != null && deviceModel.length() > 0) {
            map.put("deviceModel", deviceModel);
        }
        map.put("deviceType", deviceType == null ? "mobile" : deviceType);
        map.put("fingerprint", fingerprint == null ? "" : fingerprint);
        map.put("port", Long.valueOf(port));
        map.put("protocol", protocol == null ? "http" : protocol);
        map.put("download", Boolean.valueOf(download));
        return map;
    }

    public static DeviceInfo fromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        DeviceInfo info = new DeviceInfo();
        info.alias = Json.optString(map, "alias", "");
        info.version = Json.optString(map, "version", "2.0");
        info.deviceModel = Json.optString(map, "deviceModel", "");
        info.deviceType = Json.optString(map, "deviceType", "mobile");
        info.fingerprint = Json.optString(map, "fingerprint", "");
        info.port = Json.optInt(map, "port", 53317);
        info.protocol = Json.optString(map, "protocol", "http");
        info.download = Json.optBoolean(map, "download", false);
        return info;
    }

    public DeviceInfo copy() {
        return fromMap(toMap());
    }

    public boolean isHttps() {
        return "https".equalsIgnoreCase(protocol);
    }

    public String displayName() {
        if (alias != null && alias.length() > 0) {
            return alias;
        }
        if (deviceModel != null && deviceModel.length() > 0) {
            return deviceModel;
        }
        return "未命名设备";
    }

    @Override
    public String toString() {
        return "DeviceInfo{" + alias + ", " + protocol + ", " + port + ", fp=" + fingerprint + "}";
    }
}
