package com.simple_p2p.mixin;

import com.simple_p2p.client.ClientP2PEntry;
import com.simple_p2p.client.ToastHelper;
import com.simple_p2p.util.AddressRecognizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
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
 * 拦截 ConnectScreen.startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean)：
 * <ol>
 *   <li>检测 ServerData.ip 是否为房间号（通过 AddressRecognizer）</li>
 *   <li>若是房间号：取消原调用，在后台线程做 P2P 组网（避免阻塞主线程导致游戏卡死）</li>
 *   <li>组网就绪后在主线程用组网地址重新调用 startConnecting</li>
 *   <li>不是房间号 → 不干预，MC 原版流程继续</li>
 * </ol>
 *
 * <p>注意：1.20.1 中 startConnecting 有 5 个参数且 ServerAddress 不可变，必须取消后重新调用。
 * 使用 ThreadLocal 标记防止重新调用时再次进入 P2P 逻辑。
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {

    /** 标记"当前线程正在进行 P2P 重定向"，防止重新调用 startConnecting 时递归。 */
    private static final ThreadLocal<Boolean> P2P_REDIRECTING = ThreadLocal.withInitial(() -> false);

    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true, require = 0)
    private static void onP2PStartConnecting(Screen parent, Minecraft mc,
                                             ServerAddress hostAndPort, ServerData data,
                                             boolean quickPlay, CallbackInfo ci) {
        if (P2P_REDIRECTING.get()) {
            // 我们自己重新调用 startConnecting 时的重入，放行
            P2P_REDIRECTING.set(false);
            return;
        }

        String ip = data.ip;
        if (ip == null || ip.isBlank()) return;

        AddressRecognizer.RecognizeResult r = AddressRecognizer.recognize(ip);
        if (!r.isRoomCode) {
            // 普通地址，交给 MC 原版流程
            return;
        }

        // 是房间号：立即取消原调用，避免 MC 拿房间号去做 DNS 解析
        ci.cancel();
        final String roomCode = r.address;
        toast(mc, "SimpleP2P", "正在通过房间号组网，请稍候（约需数秒到数十秒）...");

        // 后台线程组网，避免阻塞主线程（组网含下载/进程启动/就绪轮询，可能耗时数十秒）
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
                    System.out.println("[SimpleP2P] 房间码已识别并组网成功，重定向到 " + host + ":" + port);
                    ConnectScreen.startConnecting(parent, mc, new ServerAddress(host, port), data, quickPlay);
                } else {
                    String err = (res != null && res.error != null) ? res.error : "未知错误";
                    System.err.println("[SimpleP2P] P2P连接失败: " + err);
                    mc.setScreen(parent);
                    toast(mc, "SimpleP2P 连接失败", err);
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(Component.literal("\u00a7c[SimpleP2P] \u00a7fP2P\u8fde\u63a5\u5931\u8d25: " + err));
                    }
                }
            });
        }, "SimpleP2P-Connect");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 在界面右上角弹出提示（玩家在服务器列表界面看不到聊天栏，用 toast 更直观）。
     * 使用 SystemToast.multiline 以支持自动换行；仅对极端超长文本做保护性截断，
     * 避免 toast 高度溢出屏幕（完整信息始终输出到日志）。
     */
    private static void toast(Minecraft mc, String title, String message) {
        // 统一走 ToastHelper（内部负责切 MC 主线程 + 自动换行 + 超长截断）
        ToastHelper.show(title, message);
    }
}
