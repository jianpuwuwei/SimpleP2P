package com.simple_p2p.client.config;

import com.simple_p2p.config.ModConfig;
import com.simple_p2p.enums.P2PMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SimpleP2P 设置界面。可从 mod 列表的 Config 按钮或多人游戏界面的“设置”按钮打开。
 * 所有修改在关闭界面时一次性写入配置文件。
 */
public class SimpleP2PConfigScreen extends Screen {

    private static final int ROW_W = 320;
    private static final int ROW_H = 22;
    private static final int LIST_TOP = 34;
    private static final int LIST_BOTTOM_GAP = 34;
    private static final int WIDGET_W = 148;

    private final Screen parent;
    private final ModConfig cfg = ModConfig.getInstance();
    private final List<ConfigOption> options = new ArrayList<>();
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private int scroll;

    public SimpleP2PConfigScreen(Screen parent) {
        super(Component.literal("SimpleP2P 设置"));
        this.parent = parent;
        buildOptions();
    }

    private void buildOptions() {
        options.add(ConfigOption.header("联机模式"));
        options.add(ConfigOption.choice("客户端地址输入",
                new String[]{"自动识别", "房间号", "IP/域名"},
                () -> !cfg.isClientAddressModeManual() ? "自动识别"
                        : ("room".equals(cfg.getClientAddressMode()) ? "房间号" : "IP/域名"),
                v -> {
                    if ("自动识别".equals(v)) {
                        cfg.setClientAddressModeManual(false);
                    } else {
                        cfg.setClientAddressModeManual(true);
                        cfg.setClientAddressMode("房间号".equals(v) ? "room" : "ip");
                    }
                }));
        options.add(ConfigOption.choice("服务端模式",
                new String[]{P2PMode.BOTH.getDisplayName(), P2PMode.EASYTIER_ONLY.getDisplayName(), P2PMode.OPENP2P_ONLY.getDisplayName()},
                () -> cfg.getServerMode().getDisplayName(),
                v -> cfg.setServerMode(P2PMode.fromId(modeId(v)))));
        options.add(ConfigOption.bool("自动检测 MC 端口", cfg::isAutoDetectMcPort, cfg::setAutoDetectMcPortRaw));
        options.add(ConfigOption.integer("MC 端口 (1-65535)", 1, 65535, cfg::getServerLocalPort, cfg::setServerLocalPortRaw));
        options.add(ConfigOption.integer("组网等待上限 ms (5000-120000)", 5000, 120000,
                cfg::getExtConnectTimeoutMs, cfg::setExtConnectTimeoutMs));
        options.add(ConfigOption.bool("自动开房（启动/开放局域网后）", cfg::isAutoOpenRoom, cfg::setAutoOpenRoom));

        options.add(ConfigOption.header("公共节点"));
        options.add(ConfigOption.bool("自动实测选优", cfg::isAutoSelectNode, cfg::setAutoSelectNode));
        options.add(ConfigOption.choice("节点使用方式",
                new String[]{"自动选优", "手动指定"},
                () -> cfg.isNodeSelectManual() ? "手动指定" : "自动选优",
                v -> cfg.setNodeSelectMode("手动指定".equals(v) ? "selected" : "auto")));
        options.add(ConfigOption.integer("单节点测试超时 ms (200-30000)", 200, 30000,
                cfg::getNodePingTimeoutMs, cfg::setNodePingTimeoutMs));
        options.add(ConfigOption.action("管理节点池...", () -> {
            save();
            if (minecraft != null) minecraft.setScreen(new NodePoolScreen(this));
        }));
        options.add(ConfigOption.string("节点列表接口", cfg::getNodeListUrl, cfg::setNodeListUrl));
        options.add(ConfigOption.string("兜底节点", cfg::getEasyTierPublicNode, cfg::setEasyTierPublicNode));

        options.add(ConfigOption.header("EasyTier"));
        options.add(ConfigOption.bool("自动使用最新版本", cfg::isEasyTierAutoLatest, cfg::setEasyTierAutoLatest));
        options.add(ConfigOption.string("版本号", cfg::getEasyTierVersion, cfg::setEasyTierVersion));
        options.add(ConfigOption.string("服务端虚拟 IP", cfg::getEasyTierServerIp, cfg::setEasyTierServerIp));

        options.add(ConfigOption.header("OpenP2P"));
        options.add(ConfigOption.string("Token", cfg::getOpenP2PToken, cfg::setOpenP2PTokenRaw));
        options.add(ConfigOption.bool("自动使用最新版本", cfg::isOpenP2PAutoLatest, cfg::setOpenP2PAutoLatest));
        options.add(ConfigOption.string("版本号", cfg::getOpenP2PVersion, cfg::setOpenP2PVersion));

        options.add(ConfigOption.header("下载"));
        options.add(ConfigOption.bool("自动下载官方客户端", cfg::isAutoDownloadBinaries, cfg::setAutoDownloadBinaries));
        options.add(ConfigOption.bool("下载时忽略 SSL 校验", cfg::isIgnoreSslVerify, cfg::setIgnoreSslVerify));
    }

    private static String modeId(String display) {
        for (P2PMode m : P2PMode.values()) {
            if (m.getDisplayName().equals(display)) return m.getId();
        }
        return P2PMode.BOTH.getId();
    }

    private void save() {
        cfg.save();
    }

    @Override
    protected void init() {
        clearWidgets();
        rowWidgets.clear();
        rebuildRows();
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose())
                .bounds(width / 2 - 60, height - 26, 120, 20).build());
    }

    private int rowLeft() {
        return width / 2 - ROW_W / 2;
    }

    private int listHeight() {
        return Math.max(0, height - LIST_TOP - LIST_BOTTOM_GAP);
    }

    private int maxScroll() {
        return Math.max(0, options.size() * ROW_H - listHeight());
    }

    private int firstVisible() {
        return Math.max(0, scroll / ROW_H);
    }

    private int lastVisible() {
        return Math.min(options.size(), firstVisible() + listHeight() / ROW_H + 1);
    }

    /** 按当前滚动位置重建可见行的控件（不可见的行不创建控件，避免越界绘制）。 */
    private void rebuildRows() {
        for (AbstractWidget w : rowWidgets) removeWidget(w);
        rowWidgets.clear();
        for (int i = firstVisible(); i < lastVisible(); i++) {
            int y = LIST_TOP + i * ROW_H - scroll;
            if (y < LIST_TOP || y + ROW_H > height - LIST_BOTTOM_GAP + 4) continue;
            AbstractWidget w = createWidget(options.get(i), rowLeft() + ROW_W - WIDGET_W, y + 1);
            if (w != null) {
                rowWidgets.add(w);
                addRenderableWidget(w);
            }
        }
    }

    private AbstractWidget createWidget(ConfigOption o, int x, int y) {
        switch (o.type) {
            case BOOL:
                return new Checkbox(x, y, 20, 18, Component.empty(), "true".equals(o.value())) {
                    @Override
                    public void onPress() {
                        super.onPress();
                        o.set(String.valueOf(this.selected()));
                    }
                };
            case INT: {
                EditBox box = new EditBox(font, x, y, WIDGET_W, 18, Component.literal(o.label));
                box.setValue(o.value());
                box.setResponder(v -> {
                    if (v.matches("\\d{1,6}")) o.set(v);
                });
                return box;
            }
            case STRING: {
                EditBox box = new EditBox(font, x, y, WIDGET_W, 18, Component.literal(o.label));
                // EditBox 默认 maxLength 只有 32，而 setValue 会直接截断并回调 responder，
                // 不放开的话像节点列表地址这种长字符串一打开界面就被截短写回配置
                box.setMaxLength(256);
                box.setValue(o.value());
                box.setResponder(o::set);
                return box;
            }
            case ENUM:
                return Button.builder(Component.literal(o.value()), b -> {
                    String next = nextChoice(o);
                    o.set(next);
                    b.setMessage(Component.literal(next));
                }).bounds(x, y, WIDGET_W, 18).build();
            case ACTION:
                return Button.builder(Component.literal(o.label), b -> o.run())
                        .bounds(x, y, WIDGET_W, 18).build();
            default:
                return null;
        }
    }

    private static String nextChoice(ConfigOption o) {
        String cur = o.value();
        for (int i = 0; i < o.choices.length; i++) {
            if (o.choices[i].equals(cur)) return o.choices[(i + 1) % o.choices.length];
        }
        return o.choices[0];
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);

        int left = rowLeft();
        for (int i = firstVisible(); i < lastVisible(); i++) {
            int y = LIST_TOP + i * ROW_H - scroll;
            if (y < LIST_TOP || y + ROW_H > height - LIST_BOTTOM_GAP + 4) continue;
            ConfigOption o = options.get(i);
            if (o.type == ConfigOption.Type.HEADER) {
                g.drawString(font, o.label, left, y + 6, 0xFFAA00, false);
            } else if (o.type != ConfigOption.Type.ACTION) {
                g.drawString(font, o.label, left, y + 6, 0xFFFFFF, false);
            }
        }
        super.render(g, mouseX, mouseY, partialTick);

        int m = maxScroll();
        if (m > 0) {
            int barH = Math.max(16, listHeight() * listHeight() / (options.size() * ROW_H));
            int barY = LIST_TOP + (listHeight() - barH) * scroll / m;
            g.fill(width / 2 + ROW_W / 2 + 4, LIST_TOP, width / 2 + ROW_W / 2 + 8, LIST_TOP + listHeight(), 0x40000000);
            g.fill(width / 2 + ROW_W / 2 + 4, barY, width / 2 + ROW_W / 2 + 8, barY + barH, 0xFFA0A0A0);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (delta * ROW_H)));
        rebuildRows();
        return true;
    }

    @Override
    public void onClose() {
        save();
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
