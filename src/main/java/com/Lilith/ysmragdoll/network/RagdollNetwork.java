package com.Lilith.ysmragdoll.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import com.Lilith.ysmragdoll.ysmragdoll;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * 网络通道允许远端缺失：服务端即使给未安装本模组的客户端发送自定义负载，
 * 客户端也只会忽略未知通道，不会断开连接。
 *
 * <p>
 * 包编号一经发布不可重排，新包只能追加编号。
 * </p>
 */
public final class RagdollNetwork {

    /** 死亡快照的广播半径（格）。与玩家的客户端追踪范围保持同一量级。 */
    private static final double DEATH_BROADCAST_RANGE = 512.0D;

    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(ysmragdoll.MODID);

    private RagdollNetwork() {}

    public static void register() {
        // 包编号 0/1 属于协议版本 2，不要重排。
        CHANNEL.registerMessage(PlayerDeathSnapshot.Handler.class, PlayerDeathSnapshot.class, 0, Side.CLIENT);
        CHANNEL.registerMessage(ExplosionImpulseSnapshot.Handler.class, ExplosionImpulseSnapshot.class, 1, Side.CLIENT);
    }

    public static void sendDeathSnapshot(EntityPlayerMP player, PlayerDeathSnapshot snapshot) {
        CHANNEL.sendToAllAround(
            snapshot,
            new NetworkRegistry.TargetPoint(
                player.dimension,
                player.posX,
                player.posY,
                player.posZ,
                DEATH_BROADCAST_RANGE));
    }

    /**
     * 向爆炸所在维度广播一次冲击。客户端只计算自己持有的布娃娃，包体只有中心和威力。
     * 广播整个维度可保证暂时远离尸体的观察者回来后仍保留正确的物理结果。
     */
    public static void sendExplosion(World world, ExplosionImpulseSnapshot snapshot) {
        CHANNEL.sendToDimension(snapshot, world.provider.dimensionId);
    }
}
