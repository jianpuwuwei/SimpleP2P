package com.simple_p2p;

import com.simple_p2p.command.P2PServerCommands;

/**
 * 加载器无关的运行时持有类：存放各加载器入口在初始化阶段写入、需要被纯逻辑类跨类访问的引用。
 *
 * <p>放在这里是为了让 {@link AutoRoomOpener} 之类的纯逻辑类不必依赖任何具体的加载器入口类
 * （Forge / NeoForge / Fabric 的入口类名不同）。
 */
public final class P2P {

    /** 命令逻辑实例，由加载器入口在构造期赋值。 */
    public static volatile P2PServerCommands commands;

    /** 单人世界已开放的局域网端口，&lt;=0 表示未开放；由客户端 tick 轮询写入。 */
    public static volatile int lanPort = -1;

    private P2P() {}
}
