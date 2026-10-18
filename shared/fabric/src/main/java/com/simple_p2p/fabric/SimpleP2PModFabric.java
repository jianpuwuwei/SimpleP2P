package com.simple_p2p.fabric;

import com.simple_p2p.AutoRoomOpener;
import com.simple_p2p.P2P;
import com.simple_p2p.command.P2PCommandTree;
import com.simple_p2p.command.P2PServerCommands;
import com.simple_p2p.config.ModConfig;
import com.simple_p2p.ext.ExternalNetManager;
import com.simple_p2p.signaling.EmbeddedSignaling;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/**
 * SimpleP2P — Fabric 主入口（通用端）。
 *
 * <p>只做加载器接线：命令语法树在 {@link P2PCommandTree}，客户端界面挂载在
 * {@link com.simple_p2p.client.ClientUiHooks}，都是加载器无关的。
 * 这里的代码在两个 Fabric 模块（1.20.1 / 1.21.1）之间共用。
 */
public class SimpleP2PModFabric implements ModInitializer {

    public static final String MOD_ID = "simplep2p";
    public static final String MOD_NAME = "简易联机";
    public static final String MOD_VERSION = "1.1.0";

    @Override
    public void onInitialize() {
        // 1. 加载配置
        ModConfig.getInstance();
        System.out.println("[SimpleP2P] 加载配置完成");

        // 2. 命令逻辑实例（命令注册由 CommandRegistrationCallback 完成）
        P2P.commands = new P2PServerCommands();

        // 3. 外部组网进程清理：关服/JVM 退出时终止 easytier-core / openp2p 子进程
        ExternalNetManager.INSTANCE.registerShutdownHook();

        // 4. 服务端命令（专用服务器 / 单人世界的集成服务器都走这条回调）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(P2PCommandTree.build(false)));

        // 5. 生命周期：专用服务器加载完成后按配置自动开房；关服时清理外部进程
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        // 启动内嵌信令，用于本机/局域网 UDP 探测播报
        try { EmbeddedSignaling.instance().ensureStarted(); } catch (Throwable ignored) {}

        System.out.println("[SimpleP2P] v" + MOD_VERSION + " 初始化完成");
    }

    /** 专用服务器加载完成后按配置自动开房；单人世界由“对局域网开放”触发。 */
    private void onServerStarted(MinecraftServer server) {
        if (server == null || !server.isDedicatedServer()) return;
        AutoRoomOpener.trigger("服务器加载完成", server.getPort());
    }

    /** 服务端关闭时终止所有外部组网进程。 */
    private void onServerStopping(MinecraftServer server) {
        ExternalNetManager.INSTANCE.stopAll();
        System.out.println("[SimpleP2P] 服务端关闭，已清理外部组网进程");
    }
}
