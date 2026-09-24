package com.simple_p2p;

import com.simple_p2p.client.ClientConnectionManager;
import com.simple_p2p.client.ClientSetup;
import com.simple_p2p.client.ServerListUIHelper;
import com.simple_p2p.command.P2PServerCommands;
import com.simple_p2p.config.ModConfig;
import com.simple_p2p.ext.ExternalNetManager;
import com.simple_p2p.ext.NetUtils;
import com.simple_p2p.signaling.EmbeddedSignaling;
import com.simple_p2p.util.LanPortTracker;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
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
 * SimpleP2P — 只针对 Forge 1.20.1 的主入口。
 *
 * 使用官方 @Mod + FMLJavaModLoadingContext + RegisterCommandsEvent(Brigadier)。
 * 不再使用任何 //#if 预处理器，避免运行期签名不匹配的 NoSuchMethodError。
 */
@Mod(SimpleP2PMod.MOD_ID)
public class SimpleP2PMod {

    public static final String MOD_ID = "simplep2p";
    public static final String MOD_NAME = "简易联机";
    public static final String MOD_VERSION = "1.0.0";

    public static P2PServerCommands registeredCommandsRef;
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

        // 2. 命令处理器（命令注册由RegisterCommandsEvent事件完成，这里先造好逻辑实例）
        this.serverCommands = new P2PServerCommands();
        registeredCommandsRef = this.serverCommands;

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

    private static final SuggestionProvider<CommandSourceStack> SUBCOMMAND_SUGGESTIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(new String[]{
                "open", "setcode", "close", "mode", "status", "setport", "autoport", "settoken", "token", "sslignore", "help"
            }, builder);

    @SubscribeEvent
    public void onRegisterServerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(buildP2PCommand(false));
    }

    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        // 客户端也注册一份，允许单人游戏/局域网作为“服务端”时使用
        // 但如果已经在服务端注册过，客户端Dispatcher再注册同名也无副作用
        event.getDispatcher().register(buildP2PCommand(true));
    }

    /**
     * 构造 /p2p 的 Brigadier 语法树。
     * 为了简化实现，所有子命令参数解析后统一交给 P2PServerCommands.execute()
     * 处理（该函数只看命令字符串，不依赖具体 CommandSourceStack 类型）。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> buildP2PCommand(boolean clientOnly) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("p2p")
                // 无参数 → help
                .executes(ctx -> reply(ctx, registeredCommandsRef.execute(senderName(ctx), ""), false))
                // 一个子命令字符串 (提示候选)
                .then(Commands.argument("sub", StringArgumentType.word())
                        .suggests(SUBCOMMAND_SUGGESTIONS)
                        .executes(ctx -> {
                            autoDetectPort(ctx);
                            return reply(ctx, registeredCommandsRef.execute(
                                senderName(ctx), StringArgumentType.getString(ctx, "sub")), false);
                        })
                        // 剩余参数作为字符串
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    autoDetectPort(ctx);
                                    return reply(ctx, registeredCommandsRef.execute(
                                        senderName(ctx),
                                        StringArgumentType.getString(ctx, "sub")
                                                + " " + StringArgumentType.getString(ctx, "args")),
                                        false);
                                })))
                // /p2p mode <easytier|openp2p|both> 作为快捷补全
                .then(Commands.literal("mode")
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        new String[]{"easytier", "openp2p", "both"}, b))
                                .executes(ctx -> {
                                    autoDetectPort(ctx);
                                    return reply(ctx, registeredCommandsRef.execute(
                                        senderName(ctx), "mode " + StringArgumentType.getString(ctx, "mode")),
                                        false);
                                })))
                // /p2p setcode <房间号>
                .then(Commands.literal("setcode")
                        .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument(
                                "code", StringArgumentType.word())
                                .executes(ctx -> {
                                    autoDetectPort(ctx);
                                    return reply(ctx, registeredCommandsRef.execute(
                                        senderName(ctx),
                                        "setcode " + StringArgumentType.getString(ctx, "code")),
                                        false);
                                })))
                // /p2p setport <int>
                .then(Commands.literal("setport")
                        .then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
                                .executes(ctx -> reply(ctx, registeredCommandsRef.execute(
                                        senderName(ctx),
                                        "setport " + IntegerArgumentType.getInteger(ctx, "port")),
                                        false))))
                // /p2p autoport <on|off>
                .then(Commands.literal("autoport")
                        .executes(ctx -> reply(ctx, registeredCommandsRef.execute(
                                senderName(ctx), "autoport"), false))
                        .then(Commands.argument("toggle", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        new String[]{"on", "off"}, b))
                                .executes(ctx -> reply(ctx, registeredCommandsRef.execute(
                                        senderName(ctx),
                                        "autoport " + StringArgumentType.getString(ctx, "toggle")),
                                        false))))
                // /p2p open [房间号]
                .then(Commands.literal("open")
                        .executes(ctx -> {
                            autoDetectPort(ctx);
                            return reply(ctx, registeredCommandsRef.execute(
                                senderName(ctx), "open"), false);
                        })
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(ctx -> {
                                    autoDetectPort(ctx);
                                    return reply(ctx, registeredCommandsRef.execute(
                                        senderName(ctx),
                                        "open " + StringArgumentType.getString(ctx, "code")),
                                        false);
                                })))
                // /p2p settoken <token>
                .then(Commands.literal("settoken")
                        .then(Commands.argument("token", StringArgumentType.greedyString())
                                .executes(ctx -> reply(ctx, registeredCommandsRef.execute(
                                        senderName(ctx),
                                        "settoken " + StringArgumentType.getString(ctx, "token")),
                                        true))));

        if (!clientOnly) {
            // 服务端要求操作员级别 2 或以上 才能 open/setcode/close/setport/mode
            root.requires(stack -> stack.hasPermission(2));
        } else {
            // 客户端 (单人/局域网) 不做权限限制，方便用户调试
            root.requires(stack -> true);
        }
        return root;
    }

    private static String senderName(CommandContext<CommandSourceStack> ctx) {
        try {
            return ctx.getSource().getTextName();
        } catch (Throwable t) {
            return "Unknown";
        }
    }

    /**
     * 执行命令前检测当前 MC 端口。
     * 专用服务器取 server.getPort()；单人世界取“对局域网开放”时由
     * {@link com.simple_p2p.mixin.IntegratedServerMixin} 记录的端口（未开放前 getPort() 为 0）。
     */
    private static void autoDetectPort(CommandContext<CommandSourceStack> ctx) {
        if (registeredCommandsRef == null) return;
        int port = -1;
        try {
            MinecraftServer server = ctx.getSource().getServer();
            if (server != null && server.getPort() > 0) port = server.getPort();
        } catch (Throwable ignored) {}
        if (port <= 0) port = LanPortTracker.getLanPort();

        if (port > 0) {
            registeredCommandsRef.setAutoMcPort(port);
            boolean reachable = NetUtils.tcpReachable("127.0.0.1", port, 400);
            registeredCommandsRef.setPortDetectWarning(reachable ? null
                    : "提示: 检测到端口 " + port + " 但本机无法连接，可用 /p2p setport <实际端口> 指定");
            return;
        }
        registeredCommandsRef.setAutoMcPort(-1);
        registeredCommandsRef.setPortDetectWarning(
                "提示: 未检测到 MC 端口，单人世界请先在游戏内“对局域网开放”，或用 /p2p setport 指定");
    }

    // ========== 房间号点击复制正则 ==========
    // 匹配 "房间号: XXXXXXXX" 或 "号: XXXXXXXX" 后面的房间码（6位+字母数字）
    private static final Pattern ROOM_CODE_PATTERN =
            Pattern.compile("(.*[号][:：\\s]+)([A-Za-z0-9]{6,})(.*)");

    /**
     * 将一行文本异步发送给命令发送者。用于组网进度/结果的回聊。
     * 若含"房间号: xxx"则同样加点击复制（与同步回复一致）。
     */
    private static void sendChat(CommandContext<CommandSourceStack> ctx, String msg) {
        if (msg == null || msg.isEmpty()) return;
        try {
            Component c = buildChatComponent(msg, true);
            ctx.getSource().sendSuccess(() -> c, false);
        } catch (Throwable t) {
            System.out.println("[SimpleP2P] " + msg);
        }
    }

    /**
     * 将命令执行结果输出给发送者，并返回 int（Brigadier 约定：成功=1，失败=0）。
     * 如果行中包含"房间号: xxx"，则将 xxx 部分设为可点击复制到剪贴板。
     */
    private static int reply(CommandContext<CommandSourceStack> ctx,
                             P2PServerCommands.CommandResult result,
                             boolean suppressTokenPrint) {
        // 注入异步回聊通道：组网进度/结果通过 sendSuccess 发回发送者
        if (registeredCommandsRef != null) {
            registeredCommandsRef.setAsyncChat(msg -> sendChat(ctx, msg));
        }
        boolean ok = result.success;
        for (String line : result.lines) {
            String msg = (suppressTokenPrint && ok && line.toLowerCase().contains("token"))
                    ? "Token 已保存 (具体内容已隐藏, 请查看 config.json)"
                    : line;
            Component c = buildChatComponent(msg, ok);
            try {
                ctx.getSource().sendSuccess(() -> c, false);
            } catch (Throwable ignoreClientNoOutput) {
                // 兼容某些纯客户端上下文没有 sendSuccess (fallback to stdout)
                System.out.println("[SimpleP2P] " + msg);
            }
        }
        return ok ? 1 : 0;
    }

    /**
     * 构建聊天组件：如果行中包含房间号，则将房间号部分设为可点击复制。
     */
    private static Component buildChatComponent(String msg, boolean ok) {
        Matcher m = ROOM_CODE_PATTERN.matcher(msg);
        if (m.matches()) {
            String prefix = m.group(1);
            String code = m.group(2);
            String suffix = m.group(3);

            ChatFormatting baseColor = ok ? ChatFormatting.GREEN : ChatFormatting.RED;

            MutableComponent comp = Component.literal(prefix).withStyle(baseColor);

            // 房间码部分：可点击复制 + 悬停提示
            Style codeStyle = Style.EMPTY
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.literal("点击复制房间号").withStyle(ChatFormatting.GRAY)))
                    .withColor(ChatFormatting.AQUA);
            Component codePart = Component.literal(code).withStyle(codeStyle);
            comp.append(codePart);

            if (!suffix.isEmpty()) {
                comp.append(Component.literal(suffix).withStyle(baseColor));
            }
            return comp;
        }
        return Component.literal(msg).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    public static SimpleP2PMod getInstance() { return INSTANCE; }
    public ModConfig getConfig() { return config; }
    public P2PServerCommands getServerCommands() { return serverCommands; }
    public ServerListUIHelper getServerListUIHelper() { return serverListUIHelper; }
}
