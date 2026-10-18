package com.simple_p2p.client.config;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 配置界面的 1.20.1 侧适配（1.21.1 侧有一份同名的对应实现）。
 *
 * 共用的 {@link SimpleP2PConfigScreen} / {@link NodePoolScreen} 用到了三处原版签名在两个
 * 版本间不一致、没法用一份源码同时满足的接口，差异全部集中到这里：
 *
 * <pre>
 * 1. Checkbox 构造函数
 *    1.20.1：Checkbox(int x, int y, int width, int height, Component message, boolean selected)
 *    1.21.1：Checkbox(int x, int y, int width, Component message, Font font, boolean selected, OnValueChange)
 * 2. Screen#renderBackground
 *    1.20.1：renderBackground(GuiGraphics)
 *    1.21.1：renderBackground(GuiGraphics, int mouseX, int mouseY, float partialTick)
 * 3. Screen#mouseScrolled
 *    1.20.1：mouseScrolled(double mouseX, double mouseY, double delta)
 *    1.21.1：mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY)
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
     * height 在 1.21.1 上由字体高度推算，传入值只用于 1.20.1。
     */
    public static Checkbox checkbox(Font font, int x, int y, int width, int height,
                                    boolean selected, Consumer<Checkbox> onToggle) {
        return new Checkbox(x, y, width, height, Component.empty(), selected) {
            @Override
            public void onPress() {
                super.onPress();
                onToggle.accept(this);
            }
        };
    }

    private static final class Config extends SimpleP2PConfigScreen {
        Config(Screen parent) {
            super(parent);
        }

        @Override
        protected void renderBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            renderBackground(g);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            scrollBy(delta);
            return true;
        }
    }

    private static final class NodePool extends NodePoolScreen {
        NodePool(Screen parent) {
            super(parent);
        }

        @Override
        protected void renderBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            renderBackground(g);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            scrollBy(delta);
            return true;
        }
    }
}
