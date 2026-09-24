package com.simple_p2p.mixin;

import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 延长服务端"登录超时"（原版 600 tick ≈ 30 秒 → 6000 tick ≈ 5 分钟）。
 * 组网/中继高延迟 + 整合包登录数据量大时，30 秒不足以完成登录。
 * 注意：作用于服务端类，服务端与客户端都需安装本 mod 才生效。
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public class ServerLoginTimeoutMixin {

    /** 把登录超时常量 600 tick 延长到 6000 tick。 */
    @ModifyConstant(method = "tick", constant = @Constant(intValue = 600), require = 0)
    private int simplep2p$extendLoginTimeout(int original) {
        return 6000;
    }
}
