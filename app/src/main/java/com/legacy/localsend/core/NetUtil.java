package com.legacy.localsend.core;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 网卡 / 本机 IPv4 枚举辅助类。
 *
 * 注意：java.net.NetworkInterface 在 Android 上的 API Level 是 9（Gingerbread），
 * 而本项目 minSdk = 8。因此 Discovery 调用本类时用 try/catch(Throwable) 兜底：
 * 在 API 8 上本类加载失败只会得到空列表，不会让整个应用崩溃。
 * 本类单独成文件，避免把 NetworkInterface 的引用放进 Discovery 的常量池，
 * 尽量降低老 Dalvik 校验器加载 Discovery 失败的风险。
 */
final class NetUtil {

    private NetUtil() {
    }

    static List<String> enumerateIpv4() {
        List<String> result = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                try {
                    if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                        continue;
                    }
                } catch (Exception ignored) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses != null && addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)) {
                        continue;
                    }
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                        continue;
                    }
                    String hostAddress = address.getHostAddress();
                    if (hostAddress != null && hostAddress.length() > 0 && !result.contains(hostAddress)) {
                        result.add(hostAddress);
                    }
                }
            }
        } catch (Throwable ignored) {
            // API 8 上没有 NetworkInterface，或枚举网卡被系统拒绝：返回已收集到的部分。
        }
        return result;
    }
}
