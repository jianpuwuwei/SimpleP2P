package com.simple_p2p.client;

import com.simple_p2p.AutoRoomOpener;
import com.simple_p2p.P2P;
import com.simple_p2p.client.config.ScreenApiCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.EditServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

/**
 * 客户端界面的通用挂载逻辑（只用原版类，不含加载器 API）。
 *
 * <p>各加载器模块的 ClientSetup 只负责把这里的返回值挂到对应的事件上：
 * <ul>
 *   <li>{@link #hookFor(Screen)} 返回该界面需要追加的按钮（多人游戏界面是“设置”按钮，
 *       直接连接/添加服务器界面是地址模式切换按钮），没有则为 null；</li>
 *   <li>{@link #refreshModeButton(Screen)} 每帧渲染后刷新模式按钮文字；</li>
 *   <li>{@link #onClientTick()} 每次客户端 tick 调用一次，用于轮询单人世界的局域网端口。</li>
 * </ul>
 */
public final class ClientUiHooks {

    private ClientUiHooks() {}

    /** 当前挂着地址模式按钮的界面，用于每帧刷新按钮文字。 */
    private static Screen modeScreen;
    private static Button modeButton;

    private static final int INTERVAL_TICKS = 20;
    private static int lastPort = -1;
    private static int ticks;

    /** 由加载器入口在初始化时调用：把自动开房的进度提示接到客户端。 */
    public static void init() {
        AutoRoomOpener.setNotifier(ClientUiHooks::notifyClient);
    }

    /** 自动开房的进度提示：有玩家就发聊天栏，否则用右上角提示；同时记一行日志。 */
    public static void notifyClient(String line) {
        System.out.println("[SimpleP2P] " + line);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("[SimpleP2P] " + line));
            } else {
                ToastHelper.show("SimpleP2P", line);
            }
        });
    }

    /**
     * 按界面类型返回需要追加的按钮；调用方负责把它注册进界面（Forge/NeoForge 用
     * {@code event.addListener}，Fabric 用 {@code Screens.getButtons(screen).add}）。
     */
    public static Button hookFor(Screen screen) {
        if (screen instanceof JoinMultiplayerScreen) {
            modeScreen = null;
            modeButton = null;
            return Button.builder(Component.literal("设置"),
                            b -> Minecraft.getInstance().setScreen(ScreenApiCompat.config(screen)))
                    .bounds(5, 5, 60, 20).build();
        }
        if (screen instanceof DirectJoinServerScreen) {
            return attachModeToggle(screen, "selectServer.select");
        }
        if (screen instanceof EditServerScreen) {
            return attachModeToggle(screen, "addServer.add");
        }
        return null;
    }

    /** 每帧渲染后调用，刷新模式按钮文字（按钮不存在时不做任何事）。 */
    public static void refreshModeButton(Screen screen) {
        if (modeButton == null || screen != modeScreen) return;
        AddressModeToggle.refresh(modeButton, modeScreen);
    }

    /** 在“加入服务器/完成”按钮正上方放一个同尺寸的地址模式按钮。 */
    private static Button attachModeToggle(Screen screen, String targetKey) {
        Button target = findButton(screen, targetKey);
        if (target == null) {
            modeScreen = null;
            modeButton = null;
            return null;
        }
        Button toggle = AddressModeToggle.create(screen, target);
        modeScreen = screen;
        modeButton = toggle;
        return toggle;
    }

    private static Button findButton(Screen screen, String translationKey) {
        String want = Component.translatable(translationKey).getString();
        for (GuiEventListener l : screen.children()) {
            if (l instanceof Button b && b.getMessage().getString().equals(want)) return b;
        }
        return null;
    }

    /**
     * 轮询单人世界的局域网端口，由加载器的客户端 tick 事件每次 tick 调用。
     * <p>{@code IntegratedServer.getPort()} 返回的是 publishedPort：未开放为 -1，开放后才变成实际端口，
     * 所以每秒查一次即可，不必往 IntegratedServer 里注入 Mixin。
     */
    public static void onClientTick() {
        if (++ticks < INTERVAL_TICKS) return;
        ticks = 0;

        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        int port = server == null ? -1 : server.getPort();
        if (port == lastPort) return;

        boolean justOpened = port > 0 && lastPort <= 0;
        lastPort = port;
        P2P.lanPort = port;
        if (justOpened) AutoRoomOpener.trigger("已对局域网开放", port);
    }
}
