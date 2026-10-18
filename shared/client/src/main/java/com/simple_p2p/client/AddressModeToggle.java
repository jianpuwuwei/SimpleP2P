package com.simple_p2p.client;

import com.simple_p2p.config.ModConfig;
import com.simple_p2p.util.AddressRecognizer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 直接连接 / 添加服务器界面上的地址模式按钮：自动识别 → 房间号 → IP/域名 循环。
 * <p>未手动切换过时按输入内容自动识别；手动切换后固定按用户选的模式处理。
 */
public final class AddressModeToggle {

    public static final String MODE_AUTO = "自动识别";
    public static final String MODE_ROOM = "房间号";
    public static final String MODE_IP = "IP/域名";

    private AddressModeToggle() {}

    /**
     * 在 target 按钮正上方创建同尺寸的模式按钮。
     * 优先紧贴（2px 间距）——「添加服务器」界面的完成按钮上方只有 22px 空隙，
     * 间距再大就会被顶到资源包下拉框上面去；实在有冲突才继续上移。
     */
    public static Button create(Screen screen, Button target) {
        int x = target.getX();
        int w = target.getWidth();
        int h = target.getHeight();
        int y = target.getY() - h - 2;
        while (y > 4 && overlaps(screen, target, x, y, w, h)) {
            y -= 4;
        }
        return Button.builder(label(screen), btn -> {
            ModConfig c = ModConfig.getInstance();
            String cur = currentMode();
            if (MODE_AUTO.equals(cur)) {
                // 首次手动切换：从自动识别切到房间号
                c.setClientAddressModeManual(true);
                c.setClientAddressMode("room");
            } else if (MODE_ROOM.equals(cur)) {
                c.setClientAddressMode("ip");
            } else {
                // 从 IP 切回自动识别
                c.setClientAddressModeManual(false);
            }
            c.save();
            btn.setMessage(label(screen));
        }).bounds(x, y, w, h).build();
    }

    /** 当前模式显示名。 */
    public static String currentMode() {
        ModConfig c = ModConfig.getInstance();
        if (!c.isClientAddressModeManual()) return MODE_AUTO;
        return "room".equals(c.getClientAddressMode()) ? MODE_ROOM : MODE_IP;
    }

    /** 按钮文字：自动识别模式下附带识别结果，方便确认当前输入会被当成什么。 */
    public static Component label(Screen screen) {
        String mode = currentMode();
        String suffix = "";
        if (MODE_AUTO.equals(mode)) {
            String input = addressInput(screen);
            if (input != null && !input.isBlank()) {
                suffix = AddressRecognizer.recognize(input).isRoomCode ? "（房间号）" : "（IP）";
            }
        }
        return Component.literal("模式: " + mode + suffix);
    }

    /** 每帧刷新文字，让自动识别结果随输入内容变化。 */
    public static void refresh(Button button, Screen screen) {
        Component want = label(screen);
        if (!want.getString().equals(button.getMessage().getString())) {
            button.setMessage(want);
        }
    }

    /** 取界面上服务器地址输入框的内容。 */
    private static String addressInput(Screen screen) {
        String hint = Component.translatable("addServer.enterIp").getString();
        for (GuiEventListener l : screen.children()) {
            if (l instanceof EditBox box && box.getMessage().getString().equals(hint)) {
                return box.getValue();
            }
        }
        return null;
    }

    /** 矩形是否与界面上已有控件重叠（忽略自身）。 */
    private static boolean overlaps(Screen screen, AbstractWidget self, int x, int y, int w, int h) {
        for (GuiEventListener l : screen.children()) {
            if (!(l instanceof AbstractWidget other) || other == self || !other.visible) continue;
            if (x < other.getX() + other.getWidth() && x + w > other.getX()
                    && y < other.getY() + other.getHeight() && y + h > other.getY()) {
                return true;
            }
        }
        return false;
    }
}
