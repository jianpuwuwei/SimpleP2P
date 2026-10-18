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

    /** 尝试 bind host:port；成功返回 ServerSocket（调用方负责关闭），失败返回 null。 */
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

    /** Windows 下通过 "net session" 退出码判断当前进程是否管理员；非 Windows 恒返回 true。 */
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
