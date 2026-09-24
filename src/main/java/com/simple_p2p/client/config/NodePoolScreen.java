package com.simple_p2p.client.config;

import com.simple_p2p.config.ModConfig;
import com.simple_p2p.ext.PublicNodeSelector;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 节点池：展示节点及实测延迟，勾选要连接的节点，并可手动添加/删除节点。
 * 勾选为空时等同于自动选优。
 */
public class NodePoolScreen extends Screen {

    private static final int ROW_H = 20;
    private static final int LIST_TOP = 34;
    private static final int LIST_BOTTOM_GAP = 62;
    private static final int LIST_W = 320;

    private final Screen parent;
    private final ModConfig cfg = ModConfig.getInstance();
    private final List<String> nodes = new ArrayList<>();
    private final Set<String> checked = new LinkedHashSet<>();
    private final Map<String, Long> latency = new HashMap<>();
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private EditBox input;
    private int scroll;
    private String status;
    private boolean loading;
    private boolean loadStarted;

    public NodePoolScreen(Screen parent) {
        super(Component.literal("节点池"));
        this.parent = parent;
        this.nodes.addAll(cfg.getNodePool());
        this.checked.addAll(cfg.getSelectedNodes());
        this.status = nodes.isEmpty() ? "暂无节点，点击“刷新”获取" : "共 " + nodes.size() + " 个节点";
    }

    @Override
    protected void init() {
        clearWidgets();
        rowWidgets.clear();
        rebuildRows();

        int cx = width / 2;
        input = new EditBox(font, cx - 200, height - 52, 220, 18, Component.literal("节点地址"));
        input.setHint(Component.literal("tcp://host:11010"));
        input.setMaxLength(128);
        addRenderableWidget(input);
        addRenderableWidget(Button.builder(Component.literal("添加"), b -> addFromInput())
                .bounds(cx + 26, height - 52, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> runLoad(true))
                .bounds(cx + 92, height - 52, 60, 20).build());

        addRenderableWidget(Button.builder(Component.literal("全选"), b -> {
            checked.addAll(nodes);
            rebuildRows();
        }).bounds(cx - 190, height - 26, 88, 20).build());
        addRenderableWidget(Button.builder(Component.literal("全不选"), b -> {
            checked.clear();
            rebuildRows();
        }).bounds(cx - 96, height - 26, 88, 20).build());
        addRenderableWidget(Button.builder(Component.literal("重置排除"), b -> {
            cfg.clearExcludedNodes();
            cfg.save();
            runLoad(false);
        }).bounds(cx - 2, height - 26, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose())
                .bounds(cx + 100, height - 26, 88, 20).build());

        if (!loadStarted) {
            loadStarted = true;
            runLoad(false);
        }
    }

    /** force=true 强制联网重拉；false 走缓存，只重算池子并测速。 */
    private void runLoad(boolean force) {
        if (loading) return;
        loading = true;
        status = "正在获取节点并测试连通性...";
        new Thread(() -> {
            List<String> pool;
            try {
                pool = force ? PublicNodeSelector.refreshPool(msg -> {})
                             : PublicNodeSelector.fetchCandidates(msg -> {});
            } catch (Throwable t) {
                pool = new ArrayList<>();
            }
            List<PublicNodeSelector.NodeLatency> probed = PublicNodeSelector.probeAll(pool, msg -> {});
            final List<String> fetched = pool;
            if (minecraft == null) {
                loading = false;
                return;
            }
            minecraft.execute(() -> {
                nodes.clear();
                nodes.addAll(fetched);
                latency.clear();
                for (PublicNodeSelector.NodeLatency nl : probed) {
                    latency.put(nl.node, nl.latencyMs);
                }
                long ok = probed.stream().filter(PublicNodeSelector.NodeLatency::reachable).count();
                status = nodes.isEmpty() ? "没有可用节点，可手动添加或点“刷新”"
                        : "共 " + nodes.size() + " 个节点，可用 " + ok;
                loading = false;
                rebuildRows();
            });
        }, "SimpleP2P-NodePool").start();
    }

    private void addFromInput() {
        if (input == null) return;
        String normalized = PublicNodeSelector.normalizeNode(input.getValue());
        if (normalized == null) {
            status = "地址无效，格式如 tcp://host:11010";
            return;
        }
        if (nodes.contains(normalized)) {
            status = "该节点已在池中";
            return;
        }
        cfg.addCustomNode(normalized);
        cfg.save();
        input.setValue("");
        nodes.add(0, normalized);
        checked.add(normalized);
        rebuildRows();
        status = "已添加 " + normalized;
    }

    /** 删除节点：手动添加的直接移除，拉取到的记入排除名单。 */
    private void deleteNode(String node) {
        if (cfg.getCustomNodes().contains(node)) {
            cfg.removeCustomNode(node);
        } else {
            cfg.excludeNode(node);
        }
        checked.remove(node);
        cfg.save();
        nodes.remove(node);
        rebuildRows();
        status = "已移除 " + node;
    }

    private int rowLeft() {
        return width / 2 - LIST_W / 2;
    }

    private int listHeight() {
        return Math.max(0, height - LIST_TOP - LIST_BOTTOM_GAP);
    }

    private int maxScroll() {
        return Math.max(0, nodes.size() * ROW_H - listHeight());
    }

    private int firstVisible() {
        return Math.max(0, scroll / ROW_H);
    }

    private int lastVisible() {
        return Math.min(nodes.size(), firstVisible() + listHeight() / ROW_H + 1);
    }

    private void rebuildRows() {
        for (AbstractWidget w : rowWidgets) removeWidget(w);
        rowWidgets.clear();
        int left = rowLeft();
        for (int i = firstVisible(); i < lastVisible(); i++) {
            int y = LIST_TOP + i * ROW_H - scroll;
            if (y < LIST_TOP || y + ROW_H > height - LIST_BOTTOM_GAP) continue;
            final String node = nodes.get(i);
            Checkbox cb = new Checkbox(left, y, 18, 16, Component.empty(), checked.contains(node)) {
                @Override
                public void onPress() {
                    super.onPress();
                    if (this.selected()) {
                        checked.add(node);
                    } else {
                        checked.remove(node);
                    }
                }
            };
            rowWidgets.add(cb);
            addRenderableWidget(cb);
            Button del = Button.builder(Component.literal("x"), b -> deleteNode(node))
                    .bounds(left + LIST_W - 18, y, 16, 16).build();
            rowWidgets.add(del);
            addRenderableWidget(del);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        g.drawCenteredString(font, status, width / 2, 22, 0xAAAAAA);

        int left = rowLeft();
        for (int i = firstVisible(); i < lastVisible(); i++) {
            int y = LIST_TOP + i * ROW_H - scroll;
            if (y < LIST_TOP || y + ROW_H > height - LIST_BOTTOM_GAP) continue;
            String node = nodes.get(i);
            Long ms = latency.get(node);
            String lat = ms == null ? "" : (ms < 0 ? "\u00a7c不可达" : "\u00a7a" + ms + "ms");
            g.drawString(font, node, left + 22, y + 5, 0xFFFFFF, false);
            g.drawString(font, lat, left + LIST_W - 110, y + 5, 0xAAAAAA, false);
        }
        super.render(g, mouseX, mouseY, partialTick);

        int m = maxScroll();
        if (m > 0) {
            int barH = Math.max(16, listHeight() * listHeight() / (nodes.size() * ROW_H));
            int barY = LIST_TOP + (listHeight() - barH) * scroll / m;
            g.fill(width / 2 + LIST_W / 2 + 4, LIST_TOP, width / 2 + LIST_W / 2 + 8, LIST_TOP + listHeight(), 0x40000000);
            g.fill(width / 2 + LIST_W / 2 + 4, barY, width / 2 + LIST_W / 2 + 8, barY + barH, 0xFFA0A0A0);
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
        List<String> selected = new ArrayList<>();
        for (String n : nodes) {
            if (checked.contains(n)) selected.add(n);
        }
        cfg.setSelectedNodes(selected);
        cfg.save();
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
