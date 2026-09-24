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
 * 拦截 ServerStatusPinger.pingServer：地址为房间码时不做 DNS/TCP ping，
 * 改做 UDP PROBE 取延迟和模式，避免服务器列表显示"未知主机"。
 * 注：1.20.1 中类名为 ServerStatusPinger，pingServer 签名为 (ServerData, Runnable)；require=0 使其在签名变化时降级不注入。
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

        // 回调上层告知 ping 完成
        try {
            onComplete.run();
        } catch (Throwable ignored) {}

        System.out.println("[SimpleP2P] ServerPinger: 房间码 " + ip
                + " UDP探测完成, ping=" + result.ping + ", motd=" + result.motd);
    }
}
