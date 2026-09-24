package com.simple_p2p.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * 界面右上角 toast 提示工具：组网/连接在后台线程执行，聊天栏进度看不见，
 * 因此用 toast 在右上角弹出关键进度（内部会切到 MC 主线程）。
 */
public final class ToastHelper {

    /** 正文最大长度，超过则截断以免 toast 撑出屏幕。 */
    private static final int MAX_LEN = 220;

    private ToastHelper() {}

    /** 弹出提示（可安全地在任意线程调用，内部会切到 MC 主线程执行）。 */
    public static void show(String title, String message) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        String msg = message == null ? "" : message.replace('\n', ' ').trim();
        if (msg.isEmpty()) return;
        if (msg.length() > MAX_LEN) msg = msg.substring(0, MAX_LEN) + "...";
        final String finalMsg = msg;
        final String finalTitle = title == null ? "SimpleP2P" : title;
        Runnable task = () -> {
            try {
                SystemToast toast = SystemToast.multiline(mc, SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                        Component.literal(finalTitle), Component.literal(finalMsg));
                mc.getToasts().addToast(toast);
            } catch (Throwable t) {
                System.out.println("[SimpleP2P] " + finalTitle + ": " + finalMsg);
            }
        };
        // 后台线程不能直接操作 UI，必须调度回 MC 主线程
        if (mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
    }

    /** 弹出提示（标题固定为 SimpleP2P）。 */
    public static void show(String message) {
        show("SimpleP2P", message);
    }
}
