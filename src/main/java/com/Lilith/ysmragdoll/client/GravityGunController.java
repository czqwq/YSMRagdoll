package com.Lilith.ysmragdoll.client;

import javax.vecmath.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.Lilith.ysmragdoll.client.physics.PhysicsGrab;
import com.Lilith.ysmragdoll.config.YsmRagdollConfig;

/** 仅在本地生效的输入与光束；从不发送物品交互或物理数据包。 */
public final class GravityGunController {

    private static final double MIN_DISTANCE = 1.5;
    private static final double MAX_DISTANCE = 12.0;

    private static PhysicsGrab grab;
    private static double distance;
    private static boolean pressHandled;

    private GravityGunController() {}

    private static boolean isUseKeyDown() {
        return Mouse.isButtonDown(1);
    }

    public static boolean isArmed() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer player = minecraft.thePlayer;
        if (minecraft.theWorld == null || player == null
            || minecraft.currentScreen != null
            || minecraft.isGamePaused()
            || !player.isEntityAlive()) {
            return false;
        }
        if (!YsmRagdollConfig.gravityGunMode()) {
            return false;
        }
        ItemStack held = player.getCurrentEquippedItem();
        return held != null && held.getItem() == Items.stick;
    }

    /** 每次按下只尝试一次，即使原版在按住期间重复触发使用动作。 */
    public static void start() {
        if (!isArmed() || pressHandled) {
            return;
        }
        pressHandled = true;
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        Vec3 eye = eyePosition(player, 1.0F);
        Vec3 end = clippedTarget(player, eye, player.getLook(1.0F), MAX_DISTANCE);
        grab = ClientRagdollManager.grab(eye, end);
        if (grab != null) {
            Vector3f anchor = grab.anchor();
            distance = clampDistance(eye.distanceTo(Vec3.createVectorHelper(anchor.x, anchor.y, anchor.z)));
        }
    }

    public static void update(float partialTick) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!isArmed()) {
            release();
            pressHandled = false;
            return;
        }
        if (!isUseKeyDown()) {
            if (grab != null) {
                grab.releaseWithInertia();
            }
            release();
            pressHandled = false;
            return;
        }
        if (grab == null) {
            return;
        }
        if (!grab.isActive()) {
            release();
            return;
        }
        EntityPlayer player = minecraft.thePlayer;
        Vec3 eye = eyePosition(player, partialTick);
        Vec3 anchor = clippedTarget(player, eye, player.getLook(partialTick), distance);
        grab.moveTo(new Vector3f((float) anchor.xCoord, (float) anchor.yCoord, (float) anchor.zCoord));
    }

    /** 滚轮调距；返回 true 表示本次滚动已经被牵引模式消费。 */
    public static boolean scroll(double delta) {
        update(1.0F);
        if (grab == null || !grab.isActive()) {
            return false;
        }
        if (!Double.isNaN(delta) && !Double.isInfinite(delta)) {
            distance = clampDistance(distance + delta * 0.5);
        }
        update(1.0F);
        return true;
    }

    static double clampDistance(double value) {
        return Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, value));
    }

    private static Vec3 eyePosition(EntityPlayer player, float partialTick) {
        double x = player.prevPosX + (player.posX - player.prevPosX) * partialTick;
        double y = player.prevPosY + (player.posY - player.prevPosY) * partialTick;
        double z = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTick;
        return Vec3.createVectorHelper(x, y + player.getEyeHeight(), z);
    }

    private static Vec3 clippedTarget(EntityPlayer player, Vec3 eye, Vec3 direction, double reach) {
        Vec3 end = eye.addVector(direction.xCoord * reach, direction.yCoord * reach, direction.zCoord * reach);
        MovingObjectPosition hit = player.worldObj.rayTraceBlocks(eye, end, false);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return end;
        }
        double travelled = Math.max(0.0D, eye.distanceTo(hit.hitVec) - 0.05D);
        return eye.addVector(direction.xCoord * travelled, direction.yCoord * travelled, direction.zCoord * travelled);
    }

    public static void release() {
        if (grab != null) {
            grab.close();
            grab = null;
        }
    }

    /** 细蓝色光束从手部指向当前抓取点；锚点已经包含物理世界偏移。 */
    static void render(float partialTick) {
        if (grab == null || !grab.isActive()) {
            return;
        }
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return;
        }
        Vec3 direction = player.getLook(partialTick);
        // 1.7.10 没有双持，光束统一从右手侧出发。
        double rightX = direction.zCoord;
        double rightZ = -direction.xCoord;
        double rightLength = Math.sqrt(rightX * rightX + rightZ * rightZ);
        if (rightLength > 1.0E-6D) {
            rightX /= rightLength;
            rightZ /= rightLength;
        } else {
            rightX = 0.0D;
            rightZ = 0.0D;
        }
        // 调用方已经把 GL 矩阵平移了 -RenderManager.renderPos*，因此这里直接使用世界坐标。
        Vec3 eye = eyePosition(player, partialTick);
        double fromX = eye.xCoord + direction.xCoord * 0.35D + rightX * 0.22D;
        double fromY = eye.yCoord + direction.yCoord * 0.35D - 0.20D;
        double fromZ = eye.zCoord + direction.zCoord * 0.35D + rightZ * 0.22D;
        Vector3f anchor = grab.anchor();
        double toX = anchor.x + YsmRagdollConfig.renderOffsetX();
        double toY = anchor.y + YsmRagdollConfig.renderOffsetY();
        double toZ = anchor.z + YsmRagdollConfig.renderOffsetZ();

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_LINE_BIT);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2.0F);
        try {
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawing(GL11.GL_LINES);
            tessellator.setBrightness(0xF000F0);
            tessellator.setColorRGBA_F(0.2F, 0.65F, 1.0F, 0.95F);
            tessellator.addVertex(fromX, fromY, fromZ);
            tessellator.setColorRGBA_F(0.4F, 0.85F, 1.0F, 0.95F);
            tessellator.addVertex(toX, toY, toZ);
            tessellator.draw();
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }
}
