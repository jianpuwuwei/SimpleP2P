package com.simple_p2p.client;

import com.simple_p2p.client.config.ScreenApiCompat;
import net.minecraft.client.gui.components.Button;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge 1.21.1 客户端入口：把加载器事件接到 {@link ClientUiHooks}，并注册 mod 列表的配置界面入口。
 *
 * <p>与 Forge 1.20.1 的差别：客户端 tick 事件在 1.20.5 被拆成 Pre/Post 两个事件，这里用 Post；
 * 配置界面入口从 ConfigScreenHandler 换成 IConfigScreenFactory（在 mod 构造期注册到 ModContainer）。
 */
public final class ClientSetup {

    private ClientSetup() {}

    /** 注册事件监听（仅客户端调用）。 */
    public static void init() {
        NeoForge.EVENT_BUS.register(new ScreenHooks());
        NeoForge.EVENT_BUS.register(new ClientTickHandler());
        ClientUiHooks.init();
    }

    /** 注册 mod 列表里的 Config 按钮入口（NeoForge 的 IConfigScreenFactory 扩展点）。 */
    public static void registerModConfigScreen(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (container, screen) -> ScreenApiCompat.config(screen));
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
    private static final class ClientTickHandler {

        @SubscribeEvent
        public void onClientTick(ClientTickEvent.Post event) {
            ClientUiHooks.onClientTick();
        }
    }
}
