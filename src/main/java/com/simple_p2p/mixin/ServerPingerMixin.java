package com.simple_p2p.mixin;

import com.simple_p2p.client.ClientP2PEntry;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截 ServerStatusPinger.pingServer：
 * <p>
 * 当服务器地址是房间码时，不做 DNS 解析和 TCP ping，
 * 而是做 UDP PROBE 获取延迟和模式信息，直接设置到 ServerData 上。
 * 这样服务器列表不会显示"未知主机"，而是显示 P2P 房间信息和延迟。
 *
 * <p>注：1.20.1 中类名是 ServerStatusPinger（不是旧版的 ServerPinger），
 * pingServer 签名为 {@code pingServer(ServerData, Runnable)}。
 * require = 0 让注入在目标方法签名变化时降级为不注入，避免运行期崩溃。
 */
@Mixin(ServerStatusPinger.class)
public abstract class ServerPingerMixin {

    @Inject(method = "pingServer", at = @At("HEAD"), cancellable = true, require = 0)
    private void onP2PPing(ServerData data, Runnable onComplete, CallbackInfo ci) {
        String ip = data.ip;
        if (ip == null || ip.isBlank()) return;

        ClientP2PEntry.PingResult result = ClientP2PEntry.ping(ip);
        if (result == null || !result.handled) {
            // 不是房间码，交给 MC 原版 ping
            return;
        }

        // 是房间码：取消原版 DNS/TCP ping，用 UDP 探测结果代替
        ci.cancel();

        data.ping = result.ping;
        data.motd = Component.literal(result.motd);

        // 执行回调，让上层知道 ping 已完成
        try {
            onComplete.run();
        } catch (Throwable ignored) {}

        System.out.println("[SimpleP2P] ServerPinger: 房间码 " + ip
                + " UDP探测完成, ping=" + result.ping + ", motd=" + result.motd);
    }
}
