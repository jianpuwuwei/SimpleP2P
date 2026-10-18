package com.simple_p2p.client.config;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 配置界面的 1.21.1 侧适配（1.20.1 侧有一份同名的对应实现）。
 *
 * 与 1.20.1 版的差别（详见 1.20.1 那份的注释）：
 * <pre>
 * 1. Checkbox：带 Font/OnValueChange 的构造函数是包内可见，外部无法继承，
 *    只能走 Checkbox.builder(Component, Font) 构造（1.20.1 仍是 public 构造函数，可直接子类化）
 * 2. Screen#renderBackground：改为 (GuiGraphics, int mouseX, int mouseY, float partialTick)
 * 3. Screen#mouseScrolled：改为 (double mouseX, double mouseY, double scrollX, double scrollY)，纵向是第 4 个参数
 * </pre>
 */
public final class ScreenApiCompat {

    private ScreenApiCompat() {
    }

    /** 设置界面；parent 是关闭后返回的界面。 */
    public static Screen config(Screen parent) {
        return new Config(parent);
    }

    /** 节点池界面。 */
    public static Screen nodePool(Screen parent) {
        return new NodePool(parent);
    }

    /**
     * 勾选框。状态切换后回调 onToggle，参数是勾选框自身，用于读取切换后的状态。
     * height 在 1.21.1 上由字体高度推算，这里的参数只为与 1.20.1 侧保持同一签名。
     */
    public static Checkbox checkbox(Font font, int x, int y, int width, int height,
                                    boolean selected, Consumer<Checkbox> onToggle) {
        // 1.21.1 的 Checkbox 构造函数是包内可见的，无法匿名子类化，只能用公开的 builder
        return Checkbox.builder(Component.empty(), font)
                .pos(x, y)
                .maxWidth(width)
                .selected(selected)
                .onValueChange((box, value) -> onToggle.accept(box))
                .build();
    }

    private static final class Config extends SimpleP2PConfigScreen {
        Config(Screen parent) {
            super(parent);
        }

        @Override
        protected void renderBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            renderBackground(g, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            scrollBy(scrollY);
            return true;
        }
    }

    private static final class NodePool extends NodePoolScreen {
        NodePool(Screen parent) {
            super(parent);
        }

        @Override
        protected void renderBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            renderBackground(g, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            scrollBy(scrollY);
            return true;
        }
    }
}
