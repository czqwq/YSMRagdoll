package com.Lilith.ysmragdoll.client;

import java.util.Locale;

import javax.vecmath.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.Lilith.ysmragdoll.YsmRagdollLog;
import com.Lilith.ysmragdoll.client.physics.PhysicsGrab;
import com.Lilith.ysmragdoll.config.YsmRagdollConfig;

/**
 * 仅在本地生效的输入与光束；从不发送物品交互或物理数据包。
 *
 * <p>
 * 瞄准射线必须与准星指向同一条直线：眼位取插值位置并按
 * {@code posY + (getEyeHeight() - getDefaultEyeHeight())} 修正，方向取
 * {@link EntityLivingBase#getLook} 归一化后的单位向量，触及距离取原版
 * {@code playerController} 的值，最后用原版方块射线裁剪。
 * </p>
 *
 * <p>
 * <b>眼位不能存成 {@code Vec3}。</b>1.7.10 的 {@code World#rayTraceBlocks}
 * 会在内部就地把传入的起点向量沿射线一路推到命中点（{@code World#func_147447_a}
 * 直接对参数 {@code p_147447_1_} 的 xCoord/yCoord/zCoord 做增减），调用方之后读到的
 * 就不再是眼睛位置。历史上整条牵引链路正是因此把“地面命中点”当成了视线起点，
 * 射线长度归零，JBullet 的 {@code rayTest} 退化成一次点查询，准星对着尸体也抓不住。
 * 这里所有坐标都用 double 保存，只在真正调用原版接口的那一刻才新建 {@code Vec3}。
 * </p>
 */
public final class GravityGunController {

    private static final double MIN_DISTANCE = 1.5;
    private static final double MAX_DISTANCE = 12.0;
    /** 按住使用键但没抓到东西时，每 20 次更新重新瞄一次。 */
    private static final int RETRY_INTERVAL_TICKS = 20;
    /** 眼高修正的合法上限；超出说明上游数值异常，退回“posY 已含默认眼高”的原版约定。 */
    private static final double MAX_EYE_OFFSET = 1.62D;
    /**
     * 方块把射线裁剪到小于这个距离时视为“眼睛本身就贴着方块”。照常裁剪会把线段压成
     * 零长度，JBullet 的 {@code rayTest} 随即退化成一次点查询，什么都抓不住，
     * 因此这种情况保留完整触及距离。
     */
    private static final double MIN_BLOCK_CLIP = 0.5D;
    /** 与 {@code ItemRenderer} 一致，在命中点之前留一点余量。 */
    private static final double RAY_BACKOFF = 0.05D;

    private static PhysicsGrab grab;
    private static double distance;
    private static boolean pressHandled;
    private static int retryTicks;
    /** 按住使用键但没抓到东西时，每个按下周期最多打印一次原因。 */
    private static boolean reportedMiss;

    private GravityGunController() {}

    /**
     * 使用键是否被按住。
     *
     * <p>
     * 优先读原版按键状态：它由鼠标事件写入，能正确反映玩家在控制设置里改成键盘按键的
     * “使用物品/放置方块”。原版状态在一个 tick 内的处理顺序可能晚于本模组的客户端
     * tick，因此仍然保留直接读鼠标右键作为兜底。
     * </p>
     */
    public static boolean isUseKeyDown() {
        Minecraft minecraft = Minecraft.getMinecraft();
        GameSettings settings = minecraft.gameSettings;
        if (settings != null && settings.keyBindUseItem != null && settings.keyBindUseItem.getIsKeyPressed()) {
            return true;
        }
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

    /**
     * 由 {@code PlayerInteractEvent} 与客户端 tick 的按键检测共同调用。
     *
     * <p>
     * 上游 1.20.1 只有 {@code InputEvent.InteractionKeyMappingTriggered} 一条输入路径。
     * 1.7.10 的右键事件会被原版方块交互分支和其他模组影响，因此额外用按键状态做边沿
     * 检测，保证“按住右键牵引”一定能进入本控制器。
     * </p>
     */
    public static void start() {
        if (!isArmed() || pressHandled) {
            return;
        }
        pressHandled = true;
        retryTicks = 0;
        reportedMiss = false;
        attemptGrab();
    }

    /** 按住使用键时按固定间隔重试；抓到之后由 {@link #update} 跟随准星。 */
    private static void attemptGrab() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer player = minecraft.thePlayer;
        if (player == null) {
            return;
        }
        Aim aim = aim(player, 1.0F);
        grab = ClientRagdollManager.grab(aim.eyeVector(), aim.endVector(), aim.clippedReach);
        if (grab != null) {
            Vector3f anchor = grab.anchor();
            distance = clampDistance(distance(aim.eyeX, aim.eyeY, aim.eyeZ, anchor.x, anchor.y, anchor.z));
            YsmRagdollLog.info("牵引模式已抓住肢体: 距离=" + format(distance) + ", 抓取点=" + format(anchor));
            return;
        }
        if (!reportedMiss) {
            reportedMiss = true;
            // 一并打印方向、线段长度、眼高修正与玩家自身状态：射线退化成零长度时
            // 线段长度会直接归零，一眼就能看出是不是眼位/裁剪出了问题。
            YsmRagdollLog.info(
                "牵引模式未命中布娃娃: 视线起点=" + format(aim.eyeX, aim.eyeY, aim.eyeZ)
                    + ", 射线终点="
                    + format(aim.endX, aim.endY, aim.endZ)
                    + ", 线段长度="
                    + format(aim.clippedReach)
                    + ", 触及距离="
                    + format(aim.reach)
                    + ", 视线方向="
                    + format(aim.directionX, aim.directionY, aim.directionZ)
                    + ", 玩家位置="
                    + format(player.posX, player.posY, player.posZ)
                    + ", 上一tick位置="
                    + format(player.prevPosX, player.prevPosY, player.prevPosZ)
                    + ", 眼高修正="
                    + format(eyeOffset(player.getEyeHeight(), player.getDefaultEyeHeight()))
                    + ", 偏航/俯仰="
                    + format(player.rotationYaw)
                    + "/"
                    + format(player.rotationPitch)
                    + ", 已启用的物理布娃娃="
                    + ClientRagdollManager.physicsRagdollCount());
        }
    }

    public static void update(float partialTick) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!isArmed()) {
            release();
            pressHandled = false;
            retryTicks = 0;
            reportedMiss = false;
            return;
        }
        if (!isUseKeyDown()) {
            if (grab != null) {
                grab.releaseWithInertia();
            }
            release();
            pressHandled = false;
            retryTicks = 0;
            reportedMiss = false;
            return;
        }
        if (!pressHandled) {
            pressHandled = true;
            retryTicks = 0;
        }
        if (grab == null) {
            // 每 tick 会调用两次（客户端 tick + 渲染帧），这里只负责计时。
            if (++retryTicks % RETRY_INTERVAL_TICKS == 0) {
                attemptGrab();
            }
            return;
        }
        if (!grab.isActive()) {
            release();
            return;
        }
        EntityPlayer player = minecraft.thePlayer;
        if (player == null) {
            release();
            return;
        }
        Aim aim = aim(player, partialTick);
        double reach = clampDistance(distance);
        grab.moveTo(
            new Vector3f(
                (float) (aim.eyeX + aim.directionX * reach),
                (float) (aim.eyeY + aim.directionY * reach),
                (float) (aim.eyeZ + aim.directionZ * reach)));
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

    /** 与准星一致的眼位、方向与方块裁剪后的射线终点。 */
    private static Aim aim(EntityPlayer player, float partialTick) {
        double[] eye = interpolatedEye(player, partialTick);
        double[] direction = lookVector(player, partialTick);
        return computeAim(eye, direction, blockReach(), (from, to) -> {
            MovingObjectPosition hit = player.worldObj.rayTraceBlocks(
                Vec3.createVectorHelper(from[0], from[1], from[2]),
                Vec3.createVectorHelper(to[0], to[1], to[2]),
                false);
            if (hit == null || hit.hitVec == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                return null;
            }
            // hit.hitVec 也可能被后续调用改写，读出来立刻转成 double。
            return new double[] { hit.hitVec.xCoord, hit.hitVec.yCoord, hit.hitVec.zCoord };
        });
    }

    /**
     * 由眼位、方向、触及距离和一次方块射线合成牵引射线。
     *
     * <p>
     * 独立成纯几何方法是为了能直接测试：{@code tracer} 允许像原版一样就地改写它收到的
     * {@code from}/{@code to} 数组，本方法必须依然返回正确的眼位、终点和有效距离。
     * </p>
     */
    static Aim computeAim(double[] eye, double[] direction, double reach, BlockTrace tracer) {
        Aim aim = new Aim();
        aim.eyeX = eye[0];
        aim.eyeY = eye[1];
        aim.eyeZ = eye[2];
        double[] unit = unitVector(direction[0], direction[1], direction[2]);
        aim.directionX = unit[0];
        aim.directionY = unit[1];
        aim.directionZ = unit[2];
        aim.reach = reach > 0.0D && isFinite(reach) ? reach : MAX_DISTANCE;
        aim.clippedReach = aim.reach;
        aim.setEnd(aim.reach);
        double[] hit = tracer
            .trace(new double[] { aim.eyeX, aim.eyeY, aim.eyeZ }, new double[] { aim.endX, aim.endY, aim.endZ });
        if (hit != null) {
            double travelled = distance(aim.eyeX, aim.eyeY, aim.eyeZ, hit[0], hit[1], hit[2]) - RAY_BACKOFF;
            aim.clippedReach = clippedRayLength(travelled, aim.reach);
            aim.setEnd(aim.clippedReach);
        }
        return aim;
    }

    /**
     * 与准星一致的插值眼位，返回 {@code {x, y, z}}。
     *
     * <p>
     * 1.7.10 里 {@code EntityPlayer} 的 {@code posY} 已经把 {@code yOffset}（站立时 1.62）
     * 算进去了，所以原版 {@code EntityPlayer#getPosition} 用的是
     * {@code posY + (getEyeHeight() - getDefaultEyeHeight())}。直接再加一次
     * {@code getEyeHeight()} 会让视线比准星高一截，近距离瞄准时命中点会偏出模型。
     * </p>
     */
    static double[] interpolatedEye(EntityPlayer player, float partialTick) {
        double x = interpolate(player.prevPosX, player.posX, partialTick);
        double y = interpolate(player.prevPosY, player.posY, partialTick);
        double z = interpolate(player.prevPosZ, player.posZ, partialTick);
        return new double[] { x, y + eyeOffset(player.getEyeHeight(), player.getDefaultEyeHeight()), z };
    }

    /** 眼高修正 {@code getEyeHeight() - getDefaultEyeHeight()}；数值异常时返回 0。 */
    static double eyeOffset(float eyeHeight, float defaultEyeHeight) {
        if (!isFinite(eyeHeight) || !isFinite(defaultEyeHeight)) {
            return 0.0D;
        }
        double offset = (double) eyeHeight - (double) defaultEyeHeight;
        if (!isFinite(offset) || Math.abs(offset) > MAX_EYE_OFFSET) {
            return 0.0D;
        }
        return offset;
    }

    /**
     * 单位视线方向，返回 {@code {x, y, z}}。
     *
     * <p>
     * 优先使用 {@code EntityLivingBase#getLook}：它就是原版准星和
     * {@code getMouseOver} 使用的同一个方法，能自动跟随玩家当前的插值偏航/俯仰。
     * 只有在该方法返回空或非法向量时才退回手工按插值旋转构造 -Z 方向。
     * 无论走哪条路径都做一次归一化：后续的射线长度与“沿视线固定距离”的换算
     * 都假定方向是单位向量，而且长度为零的方向会让 JBullet 的
     * {@code rayTest} 直接退化成一次点查询，永远打不中任何刚体。
     * </p>
     */
    static double[] lookVector(Entity entity, float partialTick) {
        if (entity instanceof EntityLivingBase) {
            Vec3 look = ((EntityLivingBase) entity).getLook(partialTick);
            if (look != null && isFinite(look)) {
                return unitVector(look.xCoord, look.yCoord, look.zCoord);
            }
        }
        float yaw = interpolate(entity.prevRotationYaw, entity.rotationYaw, partialTick);
        float pitch = interpolate(entity.prevRotationPitch, entity.rotationPitch, partialTick);
        if (!isFinite(yaw)) {
            yaw = 0.0F;
        }
        if (!isFinite(pitch)) {
            pitch = 0.0F;
        }
        double yawRadians = Math.toRadians(-yaw);
        double pitchRadians = Math.toRadians(-pitch);
        double horizontal = Math.cos(pitchRadians);
        return unitVector(Math.sin(yawRadians) * horizontal, Math.sin(pitchRadians), Math.cos(yawRadians) * horizontal);
    }

    /** 归一化；零长度或非法输入回退到 +Z，保证映射到 JBullet 的线段一定有实际长度。 */
    static double[] unitVector(double x, double y, double z) {
        double squared = x * x + y * y + z * z;
        if (!isFinite(x) || !isFinite(y) || !isFinite(z) || !(squared > 1.0E-12D)) {
            return new double[] { 0.0D, 0.0D, 1.0D };
        }
        double scale = 1.0D / Math.sqrt(squared);
        return new double[] { x * scale, y * scale, z * scale };
    }

    /**
     * 方块裁剪后的有效距离。
     *
     * <p>
     * 命中点离眼睛太近（眼睛本身就贴着方块）时保留完整触及距离，否则裁剪出来的线段
     * 会退化成点查询；非法值同样保留完整距离。
     * </p>
     */
    static double clippedRayLength(double travelled, double reach) {
        if (!isFinite(travelled) || travelled < MIN_BLOCK_CLIP) {
            return reach;
        }
        return Math.min(travelled, reach);
    }

    private static double blockReach() {
        Minecraft minecraft = Minecraft.getMinecraft();
        double reach = MAX_DISTANCE;
        if (minecraft.playerController != null) {
            double serverReach = minecraft.playerController.getBlockReachDistance();
            if (serverReach > 0.0D && !Double.isNaN(serverReach) && !Double.isInfinite(serverReach)) {
                reach = Math.max(serverReach, MAX_DISTANCE);
            }
        }
        return reach;
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
        double[] eye = interpolatedEye(player, partialTick);
        double[] direction = lookVector(player, partialTick);
        // 1.7.10 没有双持，光束统一从右手侧出发。
        double rightX = direction[2];
        double rightZ = -direction[0];
        double rightLength = Math.sqrt(rightX * rightX + rightZ * rightZ);
        if (rightLength > 1.0E-6D) {
            rightX /= rightLength;
            rightZ /= rightLength;
        } else {
            rightX = 0.0D;
            rightZ = 0.0D;
        }
        // 调用方已经把 GL 矩阵平移了 -RenderManager.renderPos*，因此这里直接使用世界坐标。
        double fromX = eye[0] + direction[0] * 0.35D + rightX * 0.22D;
        double fromY = eye[1] + direction[1] * 0.35D - 0.20D;
        double fromZ = eye[2] + direction[2] * 0.35D + rightZ * 0.22D;
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

    private static double interpolate(double previous, double current, float partialTick) {
        return previous + (current - previous) * partialTick;
    }

    private static float interpolate(float previous, float current, float partialTick) {
        return previous + (current - previous) * partialTick;
    }

    private static double distance(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean isFinite(Vec3 vector) {
        return isFinite(vector.xCoord) && isFinite(vector.yCoord) && isFinite(vector.zCoord);
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String format(Vector3f point) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", point.x, point.y, point.z);
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String format(double x, double y, double z) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", x, y, z);
    }

    /**
     * 一次瞄准计算的结果。
     *
     * <p>
     * 坐标全部以 double 保存：原版方块射线会就地改写传给它的起点向量，把坐标留在
     * {@code Vec3} 里等于把“眼位”交给别人随便改。需要向量时用
     * {@link #eyeVector()} / {@link #endVector()} 现取现建。
     * </p>
     */
    static final class Aim {

        double eyeX;
        double eyeY;
        double eyeZ;
        double directionX;
        double directionY;
        double directionZ;
        double endX;
        double endY;
        double endZ;
        /** 未裁剪的触及距离。 */
        double reach;
        /** 方块射线裁剪后的有效距离；兜底选取按它限制范围，避免抓到墙后的尸体。 */
        double clippedReach;

        /** 每次调用都返回全新向量：原版射线会改写传给它的实例。 */
        Vec3 eyeVector() {
            return Vec3.createVectorHelper(eyeX, eyeY, eyeZ);
        }

        Vec3 endVector() {
            return Vec3.createVectorHelper(endX, endY, endZ);
        }

        private void setEnd(double length) {
            endX = eyeX + directionX * length;
            endY = eyeY + directionY * length;
            endZ = eyeZ + directionZ * length;
        }
    }

    /**
     * 一次方块射线查询；返回值是命中点 {@code {x, y, z}}，未命中返回 {@code null}。
     *
     * <p>
     * 实现允许像原版一样就地改写传入的 {@code from}/{@code to} 数组。
     * </p>
     */
    interface BlockTrace {

        double[] trace(double[] from, double[] to);
    }
}
