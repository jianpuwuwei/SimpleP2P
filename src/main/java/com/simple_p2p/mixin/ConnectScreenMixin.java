package com.simple_p2p.mixin;

import com.simple_p2p.client.ClientP2PEntry;
import com.simple_p2p.client.ToastHelper;
import com.simple_p2p.config.ModConfig;
import com.simple_p2p.util.AddressRecognizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截 ConnectScreen.startConnecting：地址是房间号时先做 P2P 组网，
 * 就绪后在主线程用组网地址重新调用 startConnecting；普通地址不干预。
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {

    /** 标记当前线程正在进行 P2P 重定向，防止重新调用时递归。 */
    private static final ThreadLocal<Boolean> P2P_REDIRECTING = ThreadLocal.withInitial(() -> false);

    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true, require = 0)
    private static void onP2PStartConnecting(Screen parent, Minecraft mc,
                                             ServerAddress hostAndPort, ServerData data,
                                             boolean quickPlay, CallbackInfo ci) {
        if (P2P_REDIRECTING.get()) {
            P2P_REDIRECTING.set(false);
            return;
        }

        // 识别阶段出错就按原版地址走，不能把游戏带崩
        final String roomCode;
        try {
            String ip = data.ip;
            if (ip == null || ip.isBlank()) return;
            // 地址模式为 IP 时不做房间号识别
            if (!ModConfig.roomCodeMode()) return;

            AddressRecognizer.RecognizeResult r = AddressRecognizer.recognize(ip);
            if (!r.isRoomCode) return;
            roomCode = r.address;
        } catch (Throwable t) {
            System.err.println("[SimpleP2P] 房间号识别失败，按原版地址处理: " + t);
            return;
        }

        // 立即取消，避免 MC 拿房间号去做 DNS 解析
        ci.cancel();
        ToastHelper.show("SimpleP2P", "正在组网...");

        // 组网含下载/进程启动/就绪轮询，可能耗时较久，放到后台线程
        Thread worker = new Thread(() -> {
            ClientP2PEntry.ConnectResult result;
            try {
                result = ClientP2PEntry.connect(roomCode);
            } catch (Throwable t) {
                result = new ClientP2PEntry.ConnectResult(false, null, 0, "组网异常: " + t.getMessage());
            }
            final ClientP2PEntry.ConnectResult res = result;
            mc.execute(() -> {
                if (res != null && res.success) {
                    String host = (res.host != null && !res.host.isBlank()) ? res.host : "127.0.0.1";
                    int port = res.port;
                    data.ip = host + ":" + port;
                    P2P_REDIRECTING.set(true);
                    System.out.println("[SimpleP2P] 组网完成，重定向到 " + host + ":" + port);
                    ConnectScreen.startConnecting(parent, mc, new ServerAddress(host, port), data, quickPlay);
                } else {
                    String err = (res != null && res.error != null) ? res.error : "未知错误";
                    System.err.println("[SimpleP2P] P2P连接失败: " + err);
                    mc.setScreen(parent);
                    ToastHelper.show("SimpleP2P 连接失败", err);
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(Component.literal("\u00a7c[SimpleP2P] \u00a7f" + err));
                    }
                }
            });
        }, "SimpleP2P-Connect");
        worker.setDaemon(true);
        worker.start();
    }
}
