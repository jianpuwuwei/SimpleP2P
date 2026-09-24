package com.simple_p2p;

import com.simple_p2p.command.P2PServerCommands;
import com.simple_p2p.config.ModConfig;
import com.simple_p2p.ext.ExternalNetManager;

import java.util.function.Consumer;

/**
 * 自动开房：服务器加载完成（专用服）或本地“对局域网开放”后，按配置自动执行开房流程。
 */
public final class AutoRoomOpener {

    /** 结果输出通道，客户端会替换成聊天栏提示。 */
    private static volatile Consumer<String> notifier =
            line -> System.out.println("[SimpleP2P] " + line);

    private AutoRoomOpener() {}

    public static void setNotifier(Consumer<String> n) {
        if (n != null) notifier = n;
    }

    /** mcPort &lt;= 0 表示未知，交由配置端口兜底。 */
    public static void trigger(String reason, int mcPort) {
        if (!ModConfig.getInstance().isAutoOpenRoom()) return;
        P2PServerCommands cmds = SimpleP2PMod.registeredCommandsRef;
        if (cmds == null) return;
        if (ExternalNetManager.INSTANCE.isServerRunning()) return;

        notify(reason + "，自动开启 P2P 房间");
        cmds.setAutoMcPort(mcPort > 0 ? mcPort : -1);
        cmds.setPortDetectWarning(null);
        cmds.setAsyncChat(AutoRoomOpener::notify);
        P2PServerCommands.CommandResult r = cmds.execute("auto", "open");
        if (!r.success) {
            for (String line : r.lines) notify(line);
        }
    }

    private static void notify(String line) {
        Consumer<String> n = notifier;
        try {
            n.accept(line);
        } catch (Throwable ignored) {
        }
    }
}
