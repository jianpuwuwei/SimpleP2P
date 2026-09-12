package com.simple_p2p.command;

import com.simple_p2p.config.ModConfig;
import com.simple_p2p.enums.P2PMode;
import com.simple_p2p.ext.ExternalNetManager;

import java.util.function.Consumer;

/**
 * P2P服务端命令处理器（外部官方客户端版本）。
 *
 * 命令（由Minecraft的Command系统调用执行，这里提供纯逻辑层）：
 *
 *  /p2p open [房间号]            开启房间（异步组网：EasyTier / OpenP2P 官方客户端）
 *  /p2p setcode <新房间号>       修改当前房间号（房间已开启时自动重启）
 *  /p2p close                   关闭房间
 *  /p2p mode <easytier|openp2p|both>  切换服务端模式（默认双开）
 *  /p2p status                  查看当前房间/组网状态
 *  /p2p setport <端口>          设置MC本地服务端口（默认25565）
 *  /p2p settoken <token>        设置OpenP2P Token
 *  /p2p token                   查看当前Token状态
 *
 * <p>open/setCode/mode/close 为异步流程：命令立即返回"正在启动…"，最终结果经
 * {@link #setAsyncChat(Consumer)} 回调抛回聊天。不阻塞 MC 主线程。
 */
public class P2PServerCommands {

    private final ModConfig config;
    private P2PMode currentMode;
    private String currentRoomCode;
    private int autoMcPort = -1; // >0 表示自动检测到的 MC 端口
    private Consumer<String> asyncChat;
    /** 端口自动检测的警告（未检测到/不可达时由命令注册方设置，开房时提示玩家）。 */
    private String portDetectWarning;

    public P2PServerCommands() {
        this.config = ModConfig.getInstance();
        this.currentMode = config.getServerMode();
        this.currentRoomCode = config.getServerRoomCode();
    }

    /** 设置端口检测警告（为空表示无警告）。 */
    public void setPortDetectWarning(String warning) {
        this.portDetectWarning = warning;
    }

    /** 设置异步回聊通道（由命令注册方注入，用于把组网进度/结果发回聊天）。 */
    public void setAsyncChat(Consumer<String> chat) {
        this.asyncChat = chat;
    }

    /** 由上层（SimpleP2PMod 命令执行前）设置自动检测到的 MC 端口。 */
    public void setAutoMcPort(int port) {
        this.autoMcPort = port;
    }

    /** 返回当前应使用的 MC 端口：优先自动检测，否则用配置值。 */
    private int getEffectivePort() {
        return (config.isAutoDetectMcPort() && autoMcPort > 0) ? autoMcPort : config.getServerLocalPort();
    }

    private void chat(String line) {
        if (asyncChat != null) {
            try { asyncChat.accept(line); } catch (Exception ignored) {}
        }
        System.out.println("[SimpleP2P] " + line);
    }

    /**
     * 执行命令，返回命令输出的多行文本
     *
     * @param senderName 发送者名(用于日志)
     * @param command    命令行，不含前缀，例如 "open" "setcode Abc123xyz"
     * @return 执行结果文本
     */
    public CommandResult execute(String senderName, String command) {
        if (command == null) command = "";
        String[] parts = command.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return help();
        }
        String sub = parts[0].toLowerCase();
        switch (sub) {
            case "open":
                return cmdOpen(parts);
            case "setcode":
                return cmdSetCode(parts);
            case "close":
                return cmdClose();
            case "mode":
                return cmdMode(parts);
            case "status":
                return cmdStatus();
            case "setport":
                return cmdSetPort(parts);
            case "autoport":
                return cmdAutoPort(parts);
            case "settoken":
                return cmdSetToken(parts);
            case "token":
                return cmdTokenStatus();
            case "sslignore":
                return cmdSslIgnore(parts);
            case "help":
            case "?":
                return help();
            default:
                return CommandResult.fail("未知子命令: " + sub + "，输入 /p2p help 查看用法");
        }
    }

    // ================== 各子命令实现 ==================

    private CommandResult cmdOpen(String[] parts) {
        if (ExternalNetManager.INSTANCE.isServerRunning()) {
            return CommandResult.fail("房间已开启，当前房间号: " + ExternalNetManager.INSTANCE.getActiveRoomCode()
                    + "，如需更换请先 /p2p close");
        }
        final String roomCode;
        if (parts.length >= 2) {
            String code = parts[1];
            if (!config.setRoomCode(code)) {
                return CommandResult.fail("房间号格式错误：需至少6位且只含字母数字");
            }
            roomCode = code;
        } else {
            if (currentRoomCode == null || currentRoomCode.isEmpty()) {
                roomCode = config.openRoom();
            } else {
                config.setRoomCode(currentRoomCode);
                roomCode = currentRoomCode;
            }
        }
        currentRoomCode = roomCode;
        final int port = getEffectivePort();
        final P2PMode mode = currentMode;

        chat("正在启动 P2P 房间 " + roomCode + "（模式: " + mode.getDisplayName() + "）...");
        if (portDetectWarning != null && !portDetectWarning.isBlank()) {
            chat(portDetectWarning);
        }

        ExternalNetManager.INSTANCE.startServerAsync(roomCode, mode, port,
                result -> {
                    if (result.ok) {
                        currentMode = result.effectiveMode;
                        StringBuilder sb = new StringBuilder();
                        sb.append("============================================\n");
                        sb.append("P2P房间启动成功！\n");
                        sb.append(" 房间号: ").append(roomCode).append("\n");
                        sb.append(" 模式: ").append(result.effectiveMode.getDisplayName()).append("\n");
                        sb.append(" 本地MC端口: ").append(port).append("\n");
                        if (result.address != null) {
                            sb.append(" 组网地址(映射IP): ").append(result.address).append("\n");
                        }
                        if (result.warnings.length > 0) {
                            sb.append(" ").append("\n");
                            for (String w : result.warnings) sb.append(w).append("\n");
                        }
                        sb.append(" \n");
                        sb.append("玩家(客户端)操作方式:\n");
                        sb.append("  1) 在多人游戏->添加服务器，服务器地址处直接输入房间号\n");
                        sb.append("  2) 或在'直接连接'里输入房间号即可进入\n");
                        sb.append("============================================");
                        for (String l : sb.toString().split("\n")) chat(l);
                    } else {
                        chat("房间启动失败: " + (result.failReason != null ? result.failReason : "未知原因"));
                    }
                },
                this::chat);

        return CommandResult.success("正在启动房间...请稍候，结果将在此处显示。");
    }

    private CommandResult cmdSetCode(String[] parts) {
        if (parts.length < 2) {
            return CommandResult.fail("用法: /p2p setcode <新房间号> （至少6位字母数字）");
        }
        String newCode = parts[1];
        boolean wasRunning = ExternalNetManager.INSTANCE.isServerRunning();
        String oldCode = ExternalNetManager.INSTANCE.getActiveRoomCode();
        if (wasRunning) {
            ExternalNetManager.INSTANCE.stopServerAsync(oldCode, this::chat);
        }
        if (!config.setRoomCode(newCode)) {
            return CommandResult.fail("房间号格式错误：需至少6位且只含字母数字");
        }
        currentRoomCode = newCode;
        if (wasRunning) {
            chat("房间号已更改为: " + newCode + "，正在重启房间...");
            final int port = getEffectivePort();
            ExternalNetManager.INSTANCE.startServerAsync(newCode, currentMode, port,
                    result -> {
                        if (result.ok) {
                            currentMode = result.effectiveMode;
                            chat("房间已重新启动，模式: " + result.effectiveMode.getDisplayName());
                            if (result.warnings.length > 0) {
                                for (String w : result.warnings) chat(w);
                            }
                        } else {
                            chat("房间号已更新，但重启失败: " + (result.failReason != null ? result.failReason : "未知原因"));
                        }
                    },
                    this::chat);
            return CommandResult.success("房间号已更改为: " + newCode + "（房间重新启动中...）");
        }
        return CommandResult.success("房间号已更改为: " + newCode);
    }

    private CommandResult cmdClose() {
        String code = ExternalNetManager.INSTANCE.getActiveRoomCode();
        if (!ExternalNetManager.INSTANCE.isServerRunning()) {
            return CommandResult.fail("房间未开启");
        }
        ExternalNetManager.INSTANCE.stopServerAsync(code, this::chat);
        config.closeRoom();
        return CommandResult.success("正在关闭房间...");
    }

    private CommandResult cmdMode(String[] parts) {
        if (parts.length < 2) {
            return CommandResult.fail(
                    "用法: /p2p mode <easytier|openp2p|both>",
                    "当前模式: " + currentMode.getDisplayName()
            );
        }
        P2PMode newMode = P2PMode.fromId(parts[1]);
        currentMode = newMode;
        config.setServerMode(newMode);

        boolean wasRunning = ExternalNetManager.INSTANCE.isServerRunning();
        String oldCode = ExternalNetManager.INSTANCE.getActiveRoomCode();
        if (wasRunning) {
            ExternalNetManager.INSTANCE.stopServerAsync(oldCode, this::chat);
            chat("服务端模式已切换为: " + newMode.getDisplayName() + "，正在重启房间...");
            final int port = getEffectivePort();
            ExternalNetManager.INSTANCE.startServerAsync(oldCode, newMode, port,
                    result -> {
                        if (result.ok) {
                            currentMode = result.effectiveMode;
                            chat("房间已重新启动，实际模式: " + result.effectiveMode.getDisplayName());
                            if (result.warnings.length > 0) {
                                for (String w : result.warnings) chat(w);
                            }
                        } else {
                            chat("模式已切换，但重启失败: " + (result.failReason != null ? result.failReason : "未知原因"));
                        }
                    },
                    this::chat);
            return CommandResult.success("正在切换模式并重启房间...");
        }
        return CommandResult.success("服务端模式已切换为: " + newMode.getDisplayName()
                + "（下次 /p2p open 生效）");
    }

    private CommandResult cmdStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("==== SimpleP2P 状态 ====\n");
        if (ExternalNetManager.INSTANCE.isServerRunning()) {
            sb.append("房间状态: 已开启\n");
            sb.append("房间号: ").append(ExternalNetManager.INSTANCE.getActiveRoomCode()).append("\n");
        } else {
            sb.append("房间状态: 未开启\n");
            if (currentRoomCode != null) sb.append("最近使用的房间号: ").append(currentRoomCode).append("\n");
        }
        sb.append("服务端当前模式配置: ").append(currentMode.getDisplayName()).append("\n");
        sb.append("OpenP2P Token: ").append(config.hasOpenP2PToken() ? "已设置" : "未设置").append("\n");
        sb.append("自动检测MC端口: ").append(config.isAutoDetectMcPort() ? "开启" : "关闭").append("\n");
        sb.append("配置MC端口: ").append(config.getServerLocalPort()).append("\n");
        sb.append("实际使用端口: ").append(getEffectivePort()).append(autoMcPort > 0 ? " (自动检测)" : "").append("\n");
        sb.append(ExternalNetManager.INSTANCE.statusText()).append("\n");
        sb.append("===========================");
        return CommandResult.success(sb.toString().split("\n"));
    }

    private CommandResult cmdSetPort(String[] parts) {
        if (parts.length < 2) return CommandResult.fail("用法: /p2p setport <端口>");
        int port;
        try { port = Integer.parseInt(parts[1]); } catch (NumberFormatException e) {
            return CommandResult.fail("端口格式错误");
        }
        if (port <= 0 || port > 65535) return CommandResult.fail("端口范围1-65535");
        config.setServerLocalPort(port);
        return CommandResult.success("MC本地服务端口已设置为 " + port + "（需下次开房间生效）");
    }

    private CommandResult cmdAutoPort(String[] parts) {
        if (parts.length < 2) {
            return CommandResult.success(
                    "自动检测MC端口: " + (config.isAutoDetectMcPort() ? "开启" : "关闭"),
                    "用法: /p2p autoport <on|off>",
                    "开启时自动从运行中的MC服务端获取端口，",
                    "关闭时使用配置的端口（当前: " + config.getServerLocalPort() + "）"
            );
        }
        boolean on = "on".equalsIgnoreCase(parts[1]) || "true".equalsIgnoreCase(parts[1]);
        config.setAutoDetectMcPort(on);
        return CommandResult.success("自动检测MC端口已" + (on ? "开启" : "关闭")
                + "，下次 /p2p open 时生效");
    }

    private CommandResult cmdSetToken(String[] parts) {
        if (parts.length < 2) return CommandResult.fail("用法: /p2p settoken <你的OpenP2P Token>");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(parts[i]);
        }
        config.setOpenP2PToken(sb.toString());
        return CommandResult.success("OpenP2P Token 已保存。获取Token请访问: " + config.getOpenP2PRegisterUrl());
    }

    private CommandResult cmdTokenStatus() {
        if (config.hasOpenP2PToken()) {
            return CommandResult.success(
                    "OpenP2P Token 已设置",
                    "注册地址: " + config.getOpenP2PRegisterUrl()
            );
        } else {
            return CommandResult.fail(
                    "OpenP2P Token 尚未设置",
                    "请先到 " + config.getOpenP2PRegisterUrl() + " 注册获取Token",
                    "然后执行: /p2p settoken <你的Token>"
            );
        }
    }

    /**
     * /p2p sslignore <on|off>：下载官方客户端时是否忽略 SSL 证书校验。
     * <p>用于部分网络环境下访问 GitHub 官方直链出现证书链校验失败（PKIX path building failed）的场景。
     */
    private CommandResult cmdSslIgnore(String[] parts) {
        if (parts.length < 2) {
            return CommandResult.success(
                    "忽略 SSL 证书校验: " + (config.isIgnoreSslVerify() ? "已开启" : "已关闭"),
                    "用法: /p2p sslignore <on|off>",
                    "开启后，下载官方客户端时会跳过证书与主机名校验。",
                    "适用于部分网络下 GitHub 直链报 PKIX/证书错误的场景。",
                    "注意: 跳过校验存在中间人风险，请确认网络环境可信。"
            );
        }
        boolean on = "on".equalsIgnoreCase(parts[1]) || "true".equalsIgnoreCase(parts[1]);
        config.setIgnoreSslVerify(on);
        if (on) {
            return CommandResult.success(
                    "忽略 SSL 证书校验已开启（下载时会跳过证书校验，存在安全风险）",
                    "请再次执行 /p2p open 触发重新下载；",
                    "若核心已安装过，可先删除 mods/simplep2p 下对应工具目录再重新 open 以强制重下。"
            );
        }
        return CommandResult.success("忽略 SSL 证书校验已关闭（恢复正常证书校验）");
    }

    private CommandResult help() {
        return CommandResult.success(
                "===== SimpleP2P 命令帮助 =====",
                "/p2p open [房间号]       开启房间（可指定房间号，否则自动生成或使用上次的）",
                "/p2p setcode <新号>      修改房间号（至少6位字母数字）",
                "/p2p close               关闭房间",
                "/p2p mode <模式>         切换模式：easytier / openp2p / both(默认)",
                "/p2p status              查看P2P状态",
                "/p2p setport <端口>      设置MC本地服务端口（默认25565）",
                "/p2p autoport <on|off>  开关自动检测MC端口（默认开启）",
                "/p2p settoken <Token>    设置OpenP2P Token",
                "/p2p token               查看Token状态和注册地址",
                "/p2p sslignore <on|off>  下载时是否忽略SSL证书校验（默认关闭）",
                "/p2p help                显示此帮助"
        );
    }

    // ================== 命令结果封装 ==================

    public static class CommandResult {
        public final boolean success;
        public final String[] lines;

        private CommandResult(boolean success, String[] lines) {
            this.success = success;
            this.lines = lines;
        }

        public static CommandResult success(String... lines) {
            return new CommandResult(true, lines);
        }

        public static CommandResult fail(String... lines) {
            return new CommandResult(false, lines);
        }

        public String asSingleLine() {
            return String.join("\n", lines);
        }
    }

    // ========= 提供给GUI/外层调用的辅助方法 =========

    public boolean isRoomOpen() {
        return ExternalNetManager.INSTANCE.isServerRunning();
    }

    public String getCurrentRoomCode() {
        return ExternalNetManager.INSTANCE.getActiveRoomCode() != null
                ? ExternalNetManager.INSTANCE.getActiveRoomCode() : currentRoomCode;
    }

    public P2PMode getCurrentMode() {
        return currentMode;
    }
}
