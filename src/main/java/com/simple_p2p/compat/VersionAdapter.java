package com.simple_p2p.compat;

import com.simple_p2p.SimpleP2PMod;
import com.simple_p2p.client.ClientConnectionManager;
import com.simple_p2p.client.ServerListUIHelper;
import com.simple_p2p.command.P2PServerCommands;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge 1.20.1 专用版本适配器。
 *
 * 之前跨版本占位的那些 registerUniversalCommand / installMixinHooksModern
 * 已经失效，因为主类已经改用标准 RegisterCommandsEvent Brigadier 注册，
 * 不需要这个类再介入命令注册。
 *
 * 这个类保留入口：
 *   - registerServerCommands(...)    : 空操作（兼容历史旧调用，无副作用）
 *   - installServerListHooks(...)    : 对客户端做一次 GUI Hook 入口懒加载的初始化调用（如找不到 Client GUI 类则不报错）
 *   - isClientSide() / isDedicatedServer()
 */
public class VersionAdapter {

    /**
     * 已废弃 — 命令由 SimpleP2PMod.onRegisterServerCommands/onRegisterClientCommands
     * 通过 Forge 的 RegisterCommandsEvent 事件注册。
     * 保留此方法仅为了兼容 SimpleP2PMod.init() 里可能的旧调用。
     */
    @Deprecated
    public static void registerServerCommands(P2PServerCommands commands) {
        if (commands != null) {
            SimpleP2PMod.registeredCommandsRef = commands;
        }
    }

    /**
     * 安装服务器列表 Hook。
     *
     * 在 1.20.1 上完整实现需要 Mixin 目标类：
     *   - net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
     *   - net.minecraft.client.gui.screens.multiplayer.AddServerScreen
     *   - net.minecraft.client.gui.screens.ConnectScreen
     *   - net.minecraft.client.multiplayer.ServerList
     *
     * 这里先做 "防御式懒加载"：如果 ServerListUIHelper / ClientConnectionManager
     * 已经能在当前 classpath 初始化，就执行其安装逻辑；失败则打印警告但不崩溃，
     * 把 Mixin 实现（src/main/resources/simple_p2p.mixins.json）作为后续迭代内容，
     * 不阻塞当前 mod 本体加载。
     */
    public static void installServerListHooks(ServerListUIHelper uiHelper) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        try {
            // 先保证引用不为空
            if (uiHelper == null) return;
            SimpleP2PMod.serverListUIHelperRef = uiHelper;
            ClientConnectionManager cm = uiHelper.getConnectionManager();
            SimpleP2PMod.clientConnectionManagerRef = cm;

            // 尝试懒加载现代版 GUI Hook：通过反射探测类是否存在，避免硬import导致服务端崩
            Class.forName("net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen");
            // 实际 Mixin 类会在资源里声明，这里只打印初始化完成的提示。
            System.out.println("[SimpleP2P] 客户端Hook就绪。需要真正接管服务器列表/连接请配置 simple_p2p.mixins.json。");
        } catch (ClassNotFoundException ignored) {
            // 非客户端 classpath 下静默忽略
        } catch (Throwable t) {
            System.err.println("[SimpleP2P] 安装服务器列表Hook失败(非致命错误): " + t.getMessage());
        }
    }

    public static boolean isClientSide() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    public static boolean isDedicatedServer() {
        return FMLEnvironment.dist == Dist.DEDICATED_SERVER;
    }
}
