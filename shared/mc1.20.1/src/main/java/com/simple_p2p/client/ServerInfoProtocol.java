package com.simple_p2p.client;

/** 当前游戏版本协议号（服务器状态查询握手用）。1.20.1 = 763。 */
public final class ServerInfoProtocol {
    private ServerInfoProtocol() {}
    public static int protocolVersion() {
        return 763;
    }
}
