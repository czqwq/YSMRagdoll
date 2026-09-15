package com.Lilith.ysmragdoll.event;

import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraftforge.event.world.ExplosionEvent;

import com.Lilith.ysmragdoll.YsmRagdollLog;
import com.Lilith.ysmragdoll.network.ExplosionImpulseSnapshot;
import com.Lilith.ysmragdoll.network.RagdollNetwork;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 捕获 Forge 通用爆炸结算事件。TNT、苦力怕以及复用原版 Explosion 流程的模组爆炸
 * 都会经过这里；完全绕过 Forge 爆炸事件的自定义伤害实现无法自动识别。
 *
 * <p>
 * 1.7.10 的 {@link Explosion#explosionSize} 与坐标都是 public 字段，因此不需要
 * 上游 1.20.1 版本里的混淆安全反射，也就不存在读取失败的后备路径。
 * </p>
 */
public final class ServerExplosionEvents {

    @SubscribeEvent
    public void onExplosionDetonate(ExplosionEvent.Detonate event) {
        World world = event.world;
        if (world == null || world.isRemote) {
            return;
        }
        try {
            Explosion explosion = event.explosion;
            if (explosion == null) {
                return;
            }
            float radius = explosion.explosionSize;
            if (Float.isNaN(radius) || Float.isInfinite(radius) || radius <= 0.0F) {
                return;
            }
            RagdollNetwork.sendExplosion(
                world,
                new ExplosionImpulseSnapshot(explosion.explosionX, explosion.explosionY, explosion.explosionZ, radius));
            String source = explosion.exploder == null ? "无实体来源" : explosion.exploder.getCommandSenderName();
            YsmRagdollLog.info(
                "服务端捕获爆炸: 中心=" + explosion.explosionX
                    + ","
                    + explosion.explosionY
                    + ","
                    + explosion.explosionZ
                    + ", 威力="
                    + radius
                    + ", 来源="
                    + source);
        } catch (RuntimeException | LinkageError exception) {
            // 布娃娃是附加视觉功能，任何兼容问题都不能中断原版爆炸和服务端 tick。
            YsmRagdollLog.warn("处理爆炸冲击失败，已跳过本次布娃娃响应: " + exception);
        }
    }
}
