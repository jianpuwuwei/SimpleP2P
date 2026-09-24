package com.simple_p2p.enums;

/**
 * 连接方式枚举：P2P直连 或 中继
 */
public enum ConnectionType {
    /** P2P 直连：打洞成功，走点对点 UDP。 */
    P2P_DIRECT("P2P直连"),

    /** 中继模式：打洞失败，经公网中继服务器转发。 */
    RELAY("中继模式"),

    NONE("未连接");

    private final String displayName;

    ConnectionType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
