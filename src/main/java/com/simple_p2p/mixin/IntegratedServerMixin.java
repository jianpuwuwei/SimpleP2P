package com.simple_p2p.mixin;

import com.simple_p2p.AutoRoomOpener;
import com.simple_p2p.util.LanPortTracker;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 记录单人世界“对局域网开放”使用的端口。
 * <p>IntegratedServer.publishServer 内部才会把端口写入 publishedPort，开放之前 getPort() 为 0，
 * 因此在这里抓取；退出世界时清除。
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {

    @Inject(method = "publishServer", at = @At("RETURN"))
    private void sp2p$recordLanPort(GameType gameType, boolean allowCommands, int port,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            LanPortTracker.set(port);
            AutoRoomOpener.trigger("已对局域网开放", port);
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void sp2p$clearLanPort(CallbackInfo ci) {
        LanPortTracker.clear();
    }
}
