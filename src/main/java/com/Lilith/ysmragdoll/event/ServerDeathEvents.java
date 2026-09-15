package com.Lilith.ysmragdoll.event;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import com.Lilith.ysmragdoll.YsmRagdollLog;
import com.Lilith.ysmragdoll.network.PlayerDeathSnapshot;
import com.Lilith.ysmragdoll.network.RagdollNetwork;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 只在服务端创建权威死亡快照，客户端物理由接收方自行计算。 */
public final class ServerDeathEvents {

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        // LivingDeathEvent 在双端都会触发；只有 EntityPlayerMP 才代表真实的服务器玩家。
        if (!(event.entityLiving instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.entityLiving;
        try {
            YsmRagdollLog.info("服务端捕获玩家死亡: " + player.getCommandSenderName() + " / " + player.getUniqueID());
            RagdollNetwork.sendDeathSnapshot(player, PlayerDeathSnapshot.from(player));
        } catch (RuntimeException | LinkageError exception) {
            // 布娃娃是附加视觉功能，任何兼容问题都不能中断原版死亡流程和服务端 tick。
            YsmRagdollLog.warn("发送死亡快照失败，已跳过本次布娃娃响应: " + exception);
        }
    }
}
