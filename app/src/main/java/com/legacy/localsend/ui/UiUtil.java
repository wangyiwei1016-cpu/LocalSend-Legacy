package com.legacy.localsend.ui;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.io.Closeable;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 与具体界面无关的小工具：文件大小格式化、本机 IPv4 地址枚举。
 *
 * 兼容性重点：java.net.NetworkInterface 是 API 9 才有的类，Android 2.2(API 8) 上不能直接用。
 * 因此优先用 API 1 就有的 WifiManager.getConnectionInfo().getIpAddress() 取 Wi-Fi 地址，
 * 网卡枚举放在仅在 API 9+ 执行的嵌套类里，并整体 try/catch(Throwable) 兜底。
 */
final class UiUtil {

    private UiUtil() {
    }

    /** 把字节数格式化成人类可读的大小。 */
    static String formatSize(long bytes) {
        if (bytes < 0L) {
            return "0 B";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0d;
        if (kb < 1024.0d) {
            return String.format(Locale.US, "%.1f KB", Double.valueOf(kb));
        }
        double mb = kb / 1024.0d;
        if (mb < 1024.0d) {
            return String.format(Locale.US, "%.1f MB", Double.valueOf(mb));
        }
        double gb = mb / 1024.0d;
        return String.format(Locale.US, "%.2f GB", Double.valueOf(gb));
    }

    /** 0..1000 千分比，用于水平进度条。 */
    static int permille(long done, long total) {
        if (total <= 0L || done <= 0L) {
            return 0;
        }
        if (done >= total) {
            return 1000;
        }
        long value = done * 1000L / total;
        if (value > 1000L) {
            value = 1000L;
        }
        return (int) value;
    }

    /**
     * 本机所有非回环 IPv4 地址：
     *  1) WifiManager 的 Wi-Fi 地址（API 1，Android 2.2 也可用）
     *  2) API 9+ 再枚举网卡（eth0/热点等）
     */
    static List<String> localIPv4Addresses(Context context) {
        List<String> result = new ArrayList<String>();
        String wifiIp = wifiIpAddress(context);
        if (wifiIp != null && wifiIp.length() > 0) {
            result.add(wifiIp);
        }
        if (Build.VERSION.SDK_INT >= 9) {
            List<String> fromInterfaces = interfaceAddresses();
            for (int i = 0; i < fromInterfaces.size(); i++) {
                String address = fromInterfaces.get(i);
                if (address != null && !result.contains(address)) {
                    result.add(address);
                }
            }
        }
        return result;
    }

    static String localIpDisplay(Context context) {
        List<String> addresses = localIPv4Addresses(context);
        if (addresses.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < addresses.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(addresses.get(i));
        }
        return sb.toString();
    }

    /** WifiManager 拿到的 IP（小端序 int 转 a.b.c.d）；拿不到返回 null。 */
    private static String wifiIpAddress(Context context) {
        if (context == null) {
            return null;
        }
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return null;
            }
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null) {
                return null;
            }
            int ip = info.getIpAddress();
            if (ip == 0) {
                return null;
            }
            return formatIpv4(ip);
        } catch (Throwable t) {
            return null;
        }
    }

    /** WifiInfo.getIpAddress() 是小端序，最低字节是第一个网段。 */
    static String formatIpv4(int ip) {
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }

    /** 仅在 API 9+ 调用；API 8 上 NetworkInterface 类不存在。 */
    private static List<String> interfaceAddresses() {
        try {
            return NetworkInterfaceHelper.addresses();
        } catch (Throwable t) {
            // NoClassDefFoundError(API 8)/SocketException 等都按“拿不到”处理
            return new ArrayList<String>();
        }
    }

    static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }

    /** 放在嵌套类里，避免 API 8 在加载 UiUtil 时就解析 NetworkInterface。 */
    @TargetApi(9)
    private static final class NetworkInterfaceHelper {

        private NetworkInterfaceHelper() {
        }

        static List<String> addresses() {
            List<String> result = new ArrayList<String>();
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                if (interfaces == null) {
                    return result;
                }
                while (interfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = interfaces.nextElement();
                    if (networkInterface == null) {
                        continue;
                    }
                    String name = networkInterface.getName();
                    if (name != null && name.startsWith("dummy")) {
                        continue;
                    }
                    Enumeration<InetAddress> hostAddresses = networkInterface.getInetAddresses();
                    while (hostAddresses.hasMoreElements()) {
                        InetAddress hostAddress = hostAddresses.nextElement();
                        if (!(hostAddress instanceof Inet4Address)) {
                            continue;
                        }
                        if (hostAddress.isLoopbackAddress() || hostAddress.isLinkLocalAddress()) {
                            continue;
                        }
                        String host = hostAddress.getHostAddress();
                        if (host != null && host.length() > 0 && !result.contains(host)) {
                            result.add(host);
                        }
                    }
                }
            } catch (Throwable t) {
                // SocketException / SecurityException 等都按“拿不到”处理
            }
            return result;
        }
    }
}
