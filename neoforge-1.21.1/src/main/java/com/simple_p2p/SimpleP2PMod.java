package com.simple_p2p;

import com.simple_p2p.client.ClientSetup;
import com.simple_p2p.command.P2PCommandTree;
import com.simple_p2p.command.P2PServerCommands;
import com.simple_p2p.config.ModConfig;
import com.simple_p2p.ext.ExternalNetManager;
import com.simple_p2p.signaling.EmbeddedSignaling;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * SimpleP2P — NeoForge 1.21.1 入口。
 *
 * <p>与 Forge 1.20.1 的差别集中在加载器模型上：1.21 已移除
 * {@code FMLJavaModLoadingContext}，mod 总线改为构造参数注入；
 * 事件总线用 {@code NeoForge.EVENT_BUS}，包名从 {@code net.minecraftforge.*}
 * 换成 {@code net.neoforged.neoforge.*}。命令语法树（{@link P2PCommandTree}）与
 * 客户端界面挂载（{@link com.simple_p2p.client.ClientUiHooks}）都不变。
 */
@Mod(SimpleP2PMod.MOD_ID)
public class SimpleP2PMod {

    public static final String MOD_ID = "simplep2p";
    public static final String MOD_NAME = "简易联机";
    public static final String MOD_VERSION = "1.1.0";

    private static SimpleP2PMod INSTANCE;

    private final ModConfig config;
    private final P2PServerCommands serverCommands;

    public SimpleP2PMod(IEventBus modEventBus, ModContainer modContainer) {
        INSTANCE = this;

        // 1. 加载配置
        this.config = ModConfig.getInstance();
        System.out.println("[SimpleP2P] 加载配置完成");

        // 2. 命令处理器（命令注册由 RegisterCommandsEvent 事件完成，这里先造好逻辑实例）
        this.serverCommands = new P2PServerCommands();
        P2P.commands = this.serverCommands;

        // 3. 订阅 mod 总线 (生命周期事件) 与 NeoForge 通用总线 (游戏/命令事件)
        modEventBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.register(this);

        // 4. 外部组网进程清理：关服/JVM 退出时终止 easytier-core / openp2p 子进程
        ExternalNetManager.INSTANCE.registerShutdownHook();

        // 5. 客户端：注册 mod 列表的 Config 按钮入口（NeoForge 用 IConfigScreenFactory 扩展点）
        if (FMLEnvironment.dist.isClient()) {
            ClientSetup.registerModConfigScreen(modContainer);
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(this::initCommon);
    }

    private void initCommon() {
        if (FMLEnvironment.dist.isClient()) {
            ClientSetup.init();
        }
        // 启动内嵌信令，用于本机/局域网 UDP 探测播报
        try { EmbeddedSignaling.instance().ensureStarted(); } catch (Throwable ignored) {}
        System.out.println("[SimpleP2P] v" + MOD_VERSION + " 初始化完成");
    }

    /** 服务端关闭时终止所有外部组网进程。 */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ExternalNetManager.INSTANCE.stopAll();
        System.out.println("[SimpleP2P] 服务端关闭，已清理外部组网进程");
    }

    /** 专用服务器加载完成后按配置自动开房；单人世界由“对局域网开放”触发。 */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (server == null || !server.isDedicatedServer()) return;
        AutoRoomOpener.trigger("服务器加载完成", server.getPort());
    }

    // ========== 命令注册 ==========

    @SubscribeEvent
    public void onRegisterServerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(P2PCommandTree.build(false));
    }

    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        // 客户端也注册一份，允许单人游戏/局域网作为“服务端”时使用
        event.getDispatcher().register(P2PCommandTree.build(true));
    }

    public static SimpleP2PMod getInstance() { return INSTANCE; }
    public ModConfig getConfig() { return config; }
    public P2PServerCommands getServerCommands() { return serverCommands; }
}
