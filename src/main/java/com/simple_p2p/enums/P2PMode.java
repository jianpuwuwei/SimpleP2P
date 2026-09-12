package com.simple_p2p.enums;

/**
 * P2P连接模式枚举
 * 服务端可切换：单EasyTier / 单OpenP2P / 双开(默认)
 */
public enum P2PMode {
    /**
     * 仅EasyTier模式：无需token，直接尝试P2P联机
     */
    EASYTIER_ONLY("easytier", "EasyTier"),

    /**
     * 仅OpenP2P模式：需要token认证
     */
    OPENP2P_ONLY("openp2p", "OpenP2P"),

    /**
     * 双开模式(默认)：同时开启两种模式，客户端选择其一
     */
    BOTH("both", "EasyTier + OpenP2P");

    private final String id;
    private final String displayName;

    P2PMode(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static P2PMode fromId(String id) {
        if (id == null) return BOTH;
        for (P2PMode mode : values()) {
            if (mode.id.equalsIgnoreCase(id)) {
                return mode;
            }
        }
        return BOTH; // 默认双开
    }

    /**
     * 是否支持EasyTier
     */
    public boolean supportsEasyTier() {
        return this == EASYTIER_ONLY || this == BOTH;
    }

    /**
     * 是否支持OpenP2P
     */
    public boolean supportsOpenP2P() {
        return this == OPENP2P_ONLY || this == BOTH;
    }
}
