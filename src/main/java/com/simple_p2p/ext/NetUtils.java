package com.simple_p2p.ext;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** 外部组网所需的网络小工具（TCP 连通探测、端口占用探测）。 */
public final class NetUtils {

    private NetUtils() {}

    /** 尝试 TCP 连接 host:port，成功返回 true。 */
    public static boolean tcpReachable(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 尝试在 host:port 上 bind 一个 ServerSocket。成功返回 socket（调用方负责关闭），
     *  失败返回 null（说明端口已被占用/不可绑定）。 */
    public static ServerSocket tryBind(String host, int port) {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(host, port));
            return ss;
        } catch (Exception e) {
            return null;
        }
    }

    /** 找一个本机空闲 TCP 端口（127.0.0.1）。 */
    public static int findFreePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Windows 下检测当前进程是否为管理员。非 Windows 恒返回 true（Linux/mac 无 UAC，由文件权限决定）。
     * 通过 "net session" 退出码判断（非管理员会因权限不足失败）。
     */
    public static boolean isAdminWindows() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
                Process p = new ProcessBuilder("net", "session").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                boolean ok = p.waitFor() == 0;
                p.destroyForcibly();
                return ok;
            } catch (Exception e) {
                return false;
            }
        }
        return true; // 非 Windows 视为"无 UAC 限制"
    }
}
