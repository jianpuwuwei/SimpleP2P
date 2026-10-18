package com.simple_p2p.client;

import com.simple_p2p.client.config.ScreenApiCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * Forge 1.20.1 客户端入口：把加载器事件接到 {@link ClientUiHooks}，并注册 mod 列表的配置界面入口。
 */
public final class ClientSetup {

    private ClientSetup() {}

    /** 注册 Forge 事件监听（仅客户端调用）。 */
    public static void init() {
        MinecraftForge.EVENT_BUS.register(new ScreenHooks());
        MinecraftForge.EVENT_BUS.register(new LanPortWatcher());
        ClientUiHooks.init();
    }

    /** 注册 mod 列表里的 Config 按钮入口，必须在 mod 构造期调用。 */
    public static void registerModConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (Minecraft mc, Screen parent) -> ScreenApiCompat.config(parent)));
    }

    public static final class ScreenHooks {

        @SubscribeEvent
        public void onScreenInit(ScreenEvent.Init.Post event) {
            Button hook = ClientUiHooks.hookFor(event.getScreen());
            if (hook != null) event.addListener(hook);
        }

        @SubscribeEvent
        public void onScreenRender(ScreenEvent.Render.Post event) {
            ClientUiHooks.refreshModeButton(event.getScreen());
        }
    }

    /** 客户端 tick 末尾轮询单人世界的局域网端口。 */
    private static final class LanPortWatcher {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            ClientUiHooks.onClientTick();
        }
    }
}
