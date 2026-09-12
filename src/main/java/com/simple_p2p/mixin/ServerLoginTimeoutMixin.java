package com.simple_p2p.mixin;

import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 延长服务端"登录超时"（原版 600 tick ≈ 30 秒）。
 *
 * <p>背景：通过 EasyTier 组网连接时（尤其是走 relay 中继、延迟数百毫秒），
 * 而整合包模组数量多、登录阶段要同步的注册表与模组数据量很大时，
 * 30 秒内传不完就会被原版以 "Took too long to log in / 登录超时" 断开，
 * 现象是"已经连上了，但约 30 秒后提示连接中断"。
 *
 * <p>注意：此 Mixin 作用于服务端类，服务端（含单人世界开局域网）与客户端都需安装本 mod
 * 才能生效于对应一侧。
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public class ServerLoginTimeoutMixin {

    /**
     * 把登录超时从 600 tick（30 秒）延长到 6000 tick（5 分钟）。
     *
     * @param original 原常量值（600）
     * @return 延长后的 tick 数
     */
    @ModifyConstant(method = "tick", constant = @Constant(intValue = 600), require = 0)
    private int simplep2p$extendLoginTimeout(int original) {
        return 6000;
    }
}
