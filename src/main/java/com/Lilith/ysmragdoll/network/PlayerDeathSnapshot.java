package com.Lilith.ysmragdoll.network;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.Lilith.ysmragdoll.YsmRagdollLog;
import com.Lilith.ysmragdoll.ysmragdoll;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端广播的最小死亡状态；模型本身仍由各客户端的 YSM 缓存提供。
 *
 * <p>
 * 包体只有实体编号、玩家 UUID、位置、线速度和身体朝向，不包含模型、纹理或
 * 骨骼数组。未安装本模组的客户端只会收到一个未知通道的自定义负载并忽略它。
 * </p>
 */
public class PlayerDeathSnapshot implements IMessage {

    private int entityId;
    private long playerIdMost;
    private long playerIdLeast;
    private double x;
    private double y;
    private double z;
    private double velocityX;
    private double velocityY;
    private double velocityZ;
    private float bodyYaw;

    /** 网络反序列化需要的无参构造。 */
    public PlayerDeathSnapshot() {}

    public PlayerDeathSnapshot(int entityId, UUID playerId, double x, double y, double z, double velocityX,
        double velocityY, double velocityZ, float bodyYaw) {
        this.entityId = entityId;
        this.playerIdMost = playerId.getMostSignificantBits();
        this.playerIdLeast = playerId.getLeastSignificantBits();
        this.x = x;
        this.y = y;
        this.z = z;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.velocityZ = velocityZ;
        this.bodyYaw = bodyYaw;
    }

    public static PlayerDeathSnapshot from(EntityPlayerMP player) {
        return new PlayerDeathSnapshot(
            player.getEntityId(),
            player.getUniqueID(),
            player.posX,
            player.posY,
            player.posZ,
            player.motionX,
            player.motionY,
            player.motionZ,
            player.renderYawOffset);
    }

    public int entityId() {
        return entityId;
    }

    public UUID playerId() {
        return new UUID(playerIdMost, playerIdLeast);
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public double velocityX() {
        return velocityX;
    }

    public double velocityY() {
        return velocityY;
    }

    public double velocityZ() {
        return velocityZ;
    }

    public float bodyYaw() {
        return bodyYaw;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        entityId = buffer.readInt();
        playerIdMost = buffer.readLong();
        playerIdLeast = buffer.readLong();
        x = buffer.readDouble();
        y = buffer.readDouble();
        z = buffer.readDouble();
        velocityX = buffer.readDouble();
        velocityY = buffer.readDouble();
        velocityZ = buffer.readDouble();
        bodyYaw = buffer.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeLong(playerIdMost);
        buffer.writeLong(playerIdLeast);
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        buffer.writeDouble(velocityX);
        buffer.writeDouble(velocityY);
        buffer.writeDouble(velocityZ);
        buffer.writeFloat(bodyYaw);
    }

    public static class Handler implements IMessageHandler<PlayerDeathSnapshot, IMessage> {

        @Override
        public IMessage onMessage(PlayerDeathSnapshot message, MessageContext context) {
            YsmRagdollLog.info("客户端收到死亡快照: " + message.playerId() + "，实体编号=" + message.entityId());
            // 通过 SidedProxy 转发，保证专用服务端不会加载任何客户端类。
            ysmragdoll.proxy.handleDeathSnapshot(message);
            return null;
        }
    }
}
