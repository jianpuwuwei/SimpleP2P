package com.simple_p2p.client;

import com.simple_p2p.AutoRoomOpener;
import com.simple_p2p.SimpleP2PMod;
import com.simple_p2p.client.config.SimpleP2PConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.EditServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * 客户端入口：注册 mod 配置界面，并在多人游戏/连接界面挂上入口按钮。
 */
public final class ClientSetup {

    private ClientSetup() {}

    /** 注册 Forge 事件监听（仅客户端调用）。 */
    public static void init() {
        MinecraftForge.EVENT_BUS.register(new ScreenHooks());
        MinecraftForge.EVENT_BUS.register(new LanPortWatcher());
        AutoRoomOpener.setNotifier(ClientSetup::notifyClient);
    }

    /** 自动开房的进度提示：有玩家就发聊天栏，否则用右上角提示。 */
    private static void notifyClient(String line) {
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

    /** 注册 mod 列表里的 Config 按钮入口，必须在 mod 构造期调用。 */
    public static void registerModConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (Minecraft mc, Screen parent) -> new SimpleP2PConfigScreen(parent)));
    }

    public static final class ScreenHooks {

        /** 当前挂着地址模式按钮的界面，用于每帧刷新按钮文字。 */
        private Screen modeScreen;
        private Button modeButton;

        @SubscribeEvent
        public void onScreenInit(ScreenEvent.Init.Post event) {
            Screen screen = event.getScreen();
            if (screen instanceof JoinMultiplayerScreen) {
                event.addListener(Button.builder(Component.literal("设置"),
                                b -> Minecraft.getInstance().setScreen(new SimpleP2PConfigScreen(screen)))
                        .bounds(5, 5, 60, 20).build());
                modeScreen = null;
                modeButton = null;
                return;
            }
            if (screen instanceof DirectJoinServerScreen) {
                attachModeToggle(event, screen, "selectServer.select");
            } else if (screen instanceof EditServerScreen) {
                attachModeToggle(event, screen, "addServer.add");
            }
        }

        @SubscribeEvent
        public void onScreenRender(ScreenEvent.Render.Post event) {
            if (modeButton == null || event.getScreen() != modeScreen) return;
            AddressModeToggle.refresh(modeButton, modeScreen);
        }

        /** 在“加入服务器/完成”按钮正上方放一个同尺寸的地址模式按钮。 */
        private void attachModeToggle(ScreenEvent.Init.Post event, Screen screen, String targetKey) {
            Button target = findButton(screen, targetKey);
            if (target == null) {
                modeScreen = null;
                modeButton = null;
                return;
            }
            Button toggle = AddressModeToggle.create(screen, target);
            event.addListener(toggle);
            modeScreen = screen;
            modeButton = toggle;
        }

        private static Button findButton(Screen screen, String translationKey) {
            String want = Component.translatable(translationKey).getString();
            for (GuiEventListener l : screen.children()) {
                if (l instanceof Button b && b.getMessage().getString().equals(want)) return b;
            }
            return null;
        }
    }

    /**
     * 轮询单人世界的局域网端口。
     * <p>{@code IntegratedServer.getPort()} 返回的是 publishedPort：未开放为 -1，开放后才变成实际端口，
     * 所以每秒查一次即可，不必往 IntegratedServer 里注入 Mixin。
     */
    private static final class LanPortWatcher {

        private static final int INTERVAL_TICKS = 20;

        private int lastPort = -1;
        private int ticks;

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            if (++ticks < INTERVAL_TICKS) return;
            ticks = 0;

            Minecraft mc = Minecraft.getInstance();
            IntegratedServer server = mc.getSingleplayerServer();
            int port = server == null ? -1 : server.getPort();
            if (port == lastPort) return;

            boolean justOpened = port > 0 && lastPort <= 0;
            lastPort = port;
            SimpleP2PMod.lanPort = port;
            if (justOpened) AutoRoomOpener.trigger("已对局域网开放", port);
        }
    }
}
