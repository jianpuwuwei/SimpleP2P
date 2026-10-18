package com.simple_p2p.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.simple_p2p.client.ClientUiHooks;
import com.simple_p2p.command.P2PCommandTree;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.commands.CommandSourceStack;

/**
 * SimpleP2P — Fabric 客户端入口。
 *
 * <p>把 Fabric API 的客户端事件接到加载器无关的 {@link ClientUiHooks}。
 * 这里的代码在两个 Fabric 模块（1.20.1 / 1.21.1）之间共用。
 */
public class SimpleP2PModFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientUiHooks.init();

        // 客户端也注册一份命令，允许单人游戏/局域网作为“服务端”时使用
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                asSourceStackDispatcher(dispatcher).register(P2PCommandTree.build(true)));

        // 每分钟（20 tick）轮询一次单人世界的局域网端口
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientUiHooks.onClientTick());

        // 界面上追加按钮：多人游戏界面是“设置”，直接连接/添加服务器界面是地址模式切换
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            Button hook = ClientUiHooks.hookFor(screen);
            if (hook == null) return;
            Screens.getButtons(screen).add(hook);
            // Fabric 的渲染事件是按界面注册的实例事件（没有全局 AFTER_RENDER），
            // 所以在这里顺带把每帧刷新接上
            ScreenEvents.afterRender(screen).register((s, graphics, mouseX, mouseY, tickDelta) ->
                    ClientUiHooks.refreshModeButton(s));
        });
    }

    /**
     * Fabric 客户端命令分发器的泛型参数是 {@link FabricClientCommandSource}
     * （它是 {@link CommandSourceStack} 的子接口），而共用的语法树是按
     * {@code CommandSourceStack} 构造的，泛型不协变所以不能直接注册。
     * 运行时两者是同一个对象（Fabric 传入的 source 本来就是 CommandSourceStack 的子类），
     * 这里做一次泛型桥接，避免为客户端单独维护一份语法树。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CommandDispatcher<CommandSourceStack> asSourceStackDispatcher(
            CommandDispatcher<FabricClientCommandSource> dispatcher) {
        return (CommandDispatcher<CommandSourceStack>) (CommandDispatcher) dispatcher;
    }
}
