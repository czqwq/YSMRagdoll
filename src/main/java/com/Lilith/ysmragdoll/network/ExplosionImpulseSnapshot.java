package com.Lilith.ysmragdoll.network;

import com.Lilith.ysmragdoll.YsmRagdollLog;
import com.Lilith.ysmragdoll.ysmragdoll;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** 服务端广播的最小爆炸状态；方块遮挡、距离衰减和刚体冲量由客户端计算。 */
public class ExplosionImpulseSnapshot implements IMessage {

    private double x;
    private double y;
    private double z;
    private float radius;

    /** 网络反序列化需要的无参构造。 */
    public ExplosionImpulseSnapshot() {}

    public ExplosionImpulseSnapshot(double x, double y, double z, float radius) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
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

    public float radius() {
        return radius;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        x = buffer.readDouble();
        y = buffer.readDouble();
        z = buffer.readDouble();
        radius = buffer.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        buffer.writeFloat(radius);
    }

    public static class Handler implements IMessageHandler<ExplosionImpulseSnapshot, IMessage> {

        @Override
        public IMessage onMessage(ExplosionImpulseSnapshot message, MessageContext context) {
            if (Double.isNaN(message.x()) || Double.isNaN(message.y())
                || Double.isNaN(message.z())
                || Double.isInfinite(message.x())
                || Double.isInfinite(message.y())
                || Double.isInfinite(message.z())
                || Float.isNaN(message.radius())
                || Float.isInfinite(message.radius())
                || message.radius() <= 0.0F) {
                YsmRagdollLog.warn(
                    "忽略非法爆炸快照: 中心=" + message.x() + "," + message.y() + "," + message.z() + ", 威力=" + message.radius());
                return null;
            }
            ysmragdoll.proxy.handleExplosion(message);
            return null;
        }
    }
}
