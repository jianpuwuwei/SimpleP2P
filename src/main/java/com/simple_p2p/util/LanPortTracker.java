package com.simple_p2p.util;

/**
 * 单人世界“对局域网开放”时由 Mixin 记录的监听端口，供命令层读取。
 * <p>IntegratedServer.getPort() 在开放局域网之前返回 0，因此不能直接依赖它。
 */
public final class LanPortTracker {

    private static volatile int lanPort = -1;

    private LanPortTracker() {}

    /** @return 已开放的局域网端口；未开放返回 -1 */
    public static int getLanPort() {
        return lanPort;
    }

    public static void set(int port) {
        lanPort = port > 0 ? port : -1;
    }

    public static void clear() {
        lanPort = -1;
    }
}
