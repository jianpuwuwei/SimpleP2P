package com.simple_p2p.enums;

/**
 * 连接方式枚举：P2P直连 或 中继
 */
public enum ConnectionType {
    /**
     * P2P直连：打洞成功，使用点对点UDP通信
     */
    P2P_DIRECT("P2P直连"),

    /**
     * 中继模式：打洞失败，通过公网中继服务器转发
     */
    RELAY("中继模式"),

    /**
     * 未连接
     */
    NONE("未连接");

    private final String displayName;

    ConnectionType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
