package com.simple_p2p.command;

import com.simple_p2p.P2P;
import com.simple_p2p.ext.NetUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
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

/**
 * /p2p 的 Brigadier 语法树构造，以及命令输出的聊天组件渲染。
 *
 * <p>这里只用原版类（{@code net.minecraft.commands} / {@code net.minecraft.network.chat}），
 * 不含任何加载器 API，因此所有加载器、所有 MC 版本共用同一份实现。
 * 具体负责注册的是各加载器模块里的入口类（各自的命令事件）。
 */
public final class P2PCommandTree {

    private P2PCommandTree() {}

    private static final SuggestionProvider<CommandSourceStack> SUBCOMMAND_SUGGESTIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(new String[]{
                "open", "setcode", "close", "mode", "status", "setport", "autoport", "autoopen",
                "settoken", "token", "sslignore", "help"
            }, builder);

    /**
     * 构造 /p2p 的 Brigadier 语法树。
     * 所有子命令参数解析后统一交给 {@link P2PServerCommands#execute} 处理
     * （该函数只看命令字符串，不依赖具体 CommandSourceStack 类型）。
     *
     * @param clientOnly true 表示注册到客户端（单人/局域网）命令分发器，不做权限限制
     */
    public static LiteralArgumentBuilder<CommandSourceStack> build(boolean clientOnly) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("p2p")
                // 无参数 → help
                .executes(ctx -> reply(ctx, current().execute(senderName(ctx), ""), false))
                // 一个子命令字符串 (提示候选)
                .then(Commands.argument("sub", StringArgumentType.word())
                        .suggests(SUBCOMMAND_SUGGESTIONS)
                        .executes(ctx -> {
                            autoDetectPort(ctx);
                            return reply(ctx, current().execute(
                                senderName(ctx), StringArgumentType.getString(ctx, "sub")), false);
                        })
                        // 剩余参数作为字符串
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    autoDetectPort(ctx);
                                    return reply(ctx, current().execute(
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
                                    return reply(ctx, current().execute(
                                        senderName(ctx), "mode " + StringArgumentType.getString(ctx, "mode")),
                                        false);
                                })))
                // /p2p setcode <房间号>
                .then(Commands.literal("setcode")
                        .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument(
                                "code", StringArgumentType.word())
                                .executes(ctx -> {
                                    autoDetectPort(ctx);
                                    return reply(ctx, current().execute(
                                        senderName(ctx),
                                        "setcode " + StringArgumentType.getString(ctx, "code")),
                                        false);
                                })))
                // /p2p setport <int>
                .then(Commands.literal("setport")
                        .then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
                                .executes(ctx -> reply(ctx, current().execute(
                                        senderName(ctx),
                                        "setport " + IntegerArgumentType.getInteger(ctx, "port")),
                                        false))))
                // /p2p autoport <on|off>
                .then(Commands.literal("autoport")
                        .executes(ctx -> reply(ctx, current().execute(
                                senderName(ctx), "autoport"), false))
                        .then(Commands.argument("toggle", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        new String[]{"on", "off"}, b))
                                .executes(ctx -> reply(ctx, current().execute(
                                        senderName(ctx),
                                        "autoport " + StringArgumentType.getString(ctx, "toggle")),
                                        false))))
                // /p2p autoopen <on|off>
                .then(Commands.literal("autoopen")
                        .executes(ctx -> reply(ctx, current().execute(
                                senderName(ctx), "autoopen"), false))
                        .then(Commands.argument("toggle", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        new String[]{"on", "off"}, b))
                                .executes(ctx -> reply(ctx, current().execute(
                                        senderName(ctx),
                                        "autoopen " + StringArgumentType.getString(ctx, "toggle")),
                                        false))))
                // /p2p open [房间号]
                .then(Commands.literal("open")
                        .executes(ctx -> {
                            autoDetectPort(ctx);
                            return reply(ctx, current().execute(
                                senderName(ctx), "open"), false);
                        })
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(ctx -> {
                                    autoDetectPort(ctx);
                                    return reply(ctx, current().execute(
                                        senderName(ctx),
                                        "open " + StringArgumentType.getString(ctx, "code")),
                                        false);
                                })))
                // /p2p settoken <token>
                .then(Commands.literal("settoken")
                        .then(Commands.argument("token", StringArgumentType.greedyString())
                                .executes(ctx -> reply(ctx, current().execute(
                                        senderName(ctx),
                                        "settoken " + StringArgumentType.getString(ctx, "token")),
                                        true))));

        if (clientOnly) {
            // 客户端 (单人/局域网) 不做权限限制，方便用户调试
            root.requires(stack -> true);
        } else {
            // 服务端要求操作员级别 2 或以上 才能 open/setcode/close/setport/mode
            root.requires(stack -> stack.hasPermission(2));
        }
        return root;
    }

    private static P2PServerCommands current() {
        return P2P.commands;
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
     * 专用服务器取 server.getPort()；单人世界取“对局域网开放”后由客户端轮询写入的
     * {@link P2P#lanPort}（IntegratedServer.getPort() 在开放前返回 -1）。
     */
    private static void autoDetectPort(CommandContext<CommandSourceStack> ctx) {
        P2PServerCommands cmds = current();
        if (cmds == null) return;
        int port = -1;
        try {
            MinecraftServer server = ctx.getSource().getServer();
            if (server != null && server.getPort() > 0) port = server.getPort();
        } catch (Throwable ignored) {}
        if (port <= 0) port = P2P.lanPort;

        if (port > 0) {
            cmds.setAutoMcPort(port);
            boolean reachable = NetUtils.tcpReachable("127.0.0.1", port, 400);
            cmds.setPortDetectWarning(reachable ? null
                    : "提示: 检测到端口 " + port + " 但本机无法连接，可用 /p2p setport <实际端口> 指定");
            return;
        }
        cmds.setAutoMcPort(-1);
        cmds.setPortDetectWarning(
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
            // 控制台来源的 sendSuccess 本身就会写日志；玩家来源不会，这里补一行，保证日志能看到进度
            if (ctx.getSource().getEntity() != null) System.out.println("[SimpleP2P] " + msg);
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
        P2PServerCommands cmds = current();
        if (cmds != null) {
            cmds.setAsyncChat(msg -> sendChat(ctx, msg));
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
}
