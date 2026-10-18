package com.simple_p2p;

import com.simple_p2p.client.ClientConnectionManager;
import com.simple_p2p.client.ClientSetup;
import com.simple_p2p.client.ServerListUIHelper;
import com.simple_p2p.command.P2PCommandTree;
import com.simple_p2p.command.P2PServerCommands;
import com.simple_p2p.config.ModConfig;
import com.simple_p2p.ext.ExternalNetManager;
import com.simple_p2p.signaling.EmbeddedSignaling;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * SimpleP2P — Forge 1.20.1 入口。
 *
 * <p>本类只做「加载器接线」：拿 mod 总线、订阅生命周期与命令事件、按运行端注册配置界面。
 * 命令语法树在 {@link P2PCommandTree}，客户端界面挂载在
 * {@link com.simple_p2p.client.ClientUiHooks}，都是加载器无关的。
 */
@Mod(SimpleP2PMod.MOD_ID)
public class SimpleP2PMod {

    public static final String MOD_ID = "simplep2p";
    public static final String MOD_NAME = "简易联机";
    public static final String MOD_VERSION = "1.1.0";

    public static ServerListUIHelper serverListUIHelperRef;
    public static ClientConnectionManager clientConnectionManagerRef;

    private static SimpleP2PMod INSTANCE;

    private final ModConfig config;
    private final P2PServerCommands serverCommands;
    private final ServerListUIHelper serverListUIHelper;

    public SimpleP2PMod() {
        INSTANCE = this;

        // 1. 加载配置
        this.config = ModConfig.getInstance();
        System.out.println("[SimpleP2P] 加载配置完成");

        // 2. 命令处理器（命令注册由 RegisterCommandsEvent 事件完成，这里先造好逻辑实例）
        this.serverCommands = new P2PServerCommands();
        P2P.commands = this.serverCommands;

        // 3. 客户端 UI 辅助类（纯逻辑，无副作用）
        this.serverListUIHelper = new ServerListUIHelper();
        serverListUIHelperRef = this.serverListUIHelper;
        clientConnectionManagerRef = this.serverListUIHelper.getConnectionManager();

        // 4. 订阅 mod 总线 (生命周期事件) 与 Forge 通用总线 (游戏/命令事件)
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        // Forge 1.20.1 中 get() 方法在文档中标记为 @Deprecated(forRemoval = true) 但仍可用；
        // 为避免 -Xlint:removal 的噪声，这里不做进一步替换（主构造函数是获取 modBus 的唯一稳定入口）。
        modBus.addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(this);

        // 5. 外部组网进程清理：关服/JVM 退出时终止 easytier-core / openp2p 子进程
        ExternalNetManager.INSTANCE.registerShutdownHook();

        // 6. 客户端：注册 mod 列表的 Config 按钮入口
        if (FMLEnvironment.dist.isClient()) {
            ClientSetup.registerModConfigScreen();
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        // 1.20.1 Forge enqueueWork 返回 CompletableFuture<Void>，不关心结果时忽略即可
        CompletableFuture<Void> unused = event.enqueueWork(this::initCommon);
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

    // ========== 命令注册 (Forge 1.19+ 的标准事件) ==========

    @SubscribeEvent
    public void onRegisterServerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(P2PCommandTree.build(false));
    }

    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        // 客户端也注册一份，允许单人游戏/局域网作为“服务端”时使用。
        // 若已在服务端注册过，客户端 Dispatcher 再注册同名也无副作用。
        event.getDispatcher().register(P2PCommandTree.build(true));
    }

    public static SimpleP2PMod getInstance() { return INSTANCE; }
    public ModConfig getConfig() { return config; }
    public P2PServerCommands getServerCommands() { return serverCommands; }
    public ServerListUIHelper getServerListUIHelper() { return serverListUIHelper; }
}
