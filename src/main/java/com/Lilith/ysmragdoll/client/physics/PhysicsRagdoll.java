package com.Lilith.ysmragdoll.client.physics;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.vecmath.Matrix3f;
import javax.vecmath.Vector3f;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import com.Lilith.ysmragdoll.YsmRagdollLog;
import com.Lilith.ysmragdoll.client.CapturedModel;
import com.Lilith.ysmragdoll.config.YsmRagdollConfig;
import com.Lilith.ysmragdoll.network.PlayerDeathSnapshot;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.RigidBodyConstructionInfo;
import com.bulletphysics.dynamics.constraintsolver.Generic6DofConstraint;
import com.bulletphysics.dynamics.constraintsolver.TypedConstraint;
import com.bulletphysics.linearmath.DefaultMotionState;
import com.bulletphysics.linearmath.Transform;

/**
 * 一具由 YSM 真实骨骼和几何尺寸驱动的 JBullet 布娃娃。
 *
 * <p>
 * 主要骨骼各自对应一个旋转盒刚体，父子部位通过六自由度关节锁定平移并限制角度。
 * 玩家推动首先命中真实刚体，再把响应拆分为整套刚体的共同速度变化与接触肢体的局部
 * 冲量。每个物理子步完成后，刚体的相对旋转被回写到这具尸体独占的骨骼参数数组；
 * 每个物理部位的中心、旋转和原始缩放都会绑定回对应骨骼。
 * </p>
 */
public final class PhysicsRagdoll {

    private static final float DEGREES = (float) (Math.PI / 180.0);
    /** 玩家推动检测只允许略微超出玩家自身碰撞箱，不改变布娃娃刚体大小。 */
    private static final double PUSH_CONTACT_MARGIN = 0.025;
    private static final double PUSH_SPEED_MARGIN = 0.10;
    private static final double MAX_PLAYER_SWEEP_DISTANCE = 1.25;
    private static final float MAX_PUSH_TARGET_SPEED = 8.0F;
    private static final float MIN_PUSH_SPEED_CHANGE = 0.35F;
    private static final float PUSH_SPEED_CHANGE_PER_EASE = 0.18F;
    private static final float MAX_CONTACT_SPEED_CHANGE = 3.0F;
    private static final float SHARED_PUSH_RESPONSE = 0.55F;
    private static final float CONTACT_PUSH_RESPONSE = 0.45F;
    private static final int PUSH_LIFT_SUPPRESSION_STEPS = 18;
    private static final float MAX_PUSH_UPWARD_SPEED = 0.0F;
    private static final float HEAD_OVER_HEELS_RETENTION_PER_SECOND = 0.45F;
    private static final float BODY_TWIST_RETENTION_PER_SECOND = 0.50F;
    private static final float SIDE_ROLL_RETENTION_PER_SECOND = 0.78F;

    private final ClientPhysicsWorld world;
    private final RagdollDefinition definition;
    private final CapturedModel model;
    private final EnumMap<RagdollDefinition.Role, BodyPart> bodies = new EnumMap<>(RagdollDefinition.Role.class);
    /** 捕获瞬间的骨骼参数；缩放和隐藏标志始终保持快照原值。 */
    private final float[] initialBoneTransforms;
    /** 渲染根使用的相对矩阵，用于把物理世界矩阵换回 YSM 模型坐标。 */
    private final Matrix4f basePose;
    /** 捕获瞬间躯干骨骼枢轴的世界位置，作为渲染根的锚点。 */
    private final Vector3f initialBodyPivot = new Vector3f();
    /** 躯干碰撞箱中心相对其骨骼枢轴的局部偏移。 */
    private final Vector3f bodyCenterOffsetLocal = new Vector3f();
    /** 尸体所在区块是否仍由客户端加载；未加载时暂停整个刚体组。 */
    private boolean chunkLoaded = true;
    private final List<TypedConstraint> constraints = new ArrayList<>();
    private int appliedFriction = -1;
    private int pushLiftSuppressionSteps;
    private final PlayerCollisionResponse playerCollision;
    private boolean disposed;
    private PhysicsGrab grab;
    private boolean inFluid;

    PhysicsGrab grab(RigidBody body, Vector3f hit) {
        if (disposed || !chunkLoaded) {
            return null;
        }
        if (grab != null) {
            grab.close();
        }
        pushLiftSuppressionSteps = 0;
        grab = new PhysicsGrab(
            body,
            hit,
            world::addConstraint,
            world::removeConstraint,
            () -> !disposed && chunkLoaded,
            () -> world.currentWorldOffset(new Vector3f()),
            world.grabJointGuard(constraints));
        return grab;
    }

    private boolean isGrabbed() {
        return grab != null && grab.isActive();
    }

    void beforeGrabStep() {
        if (grab != null) {
            grab.beforeStep();
        }
    }

    void afterGrabStep() {
        if (grab != null) {
            grab.afterStep();
        }
    }

    void applyFluidForces(FluidBuoyancy fluids, float seconds) {
        inFluid = false;
        if (disposed || !chunkLoaded) {
            return;
        }
        boolean grabbed = isGrabbed();
        for (BodyPart part : bodies.values()) {
            inFluid |= fluids.apply(part.body, seconds, grabbed);
        }
        if (inFluid) {
            pushLiftSuppressionSteps = 0;
        }
    }

    public static PhysicsRagdoll create(ClientPhysicsWorld world, PlayerDeathSnapshot snapshot, CapturedModel model) {
        RagdollDefinition definition = YsmSkeletonExtractor.extract(model);
        if (!definition.parts()
            .containsKey(RagdollDefinition.Role.BODY)) {
            YsmRagdollLog.warn("YSM 模型没有可识别的躯干骨骼，当前尸体继续使用静态快照");
            return null;
        }
        try {
            return new PhysicsRagdoll(world, snapshot, model, definition);
        } catch (RuntimeException exception) {
            YsmRagdollLog.warn("创建 JBullet 布娃娃失败，当前尸体继续使用静态快照: " + exception);
            return null;
        }
    }

    private PhysicsRagdoll(ClientPhysicsWorld world, PlayerDeathSnapshot snapshot, CapturedModel model,
        RagdollDefinition definition) {
        this.world = world;
        this.model = model;
        this.definition = definition;
        this.initialBoneTransforms = model.boneTransforms()
            .clone();

        Vector3f collisionOffset = world.currentWorldOffset(new Vector3f());
        Matrix4f base = new Matrix4f()
            .translation(
                (float) snapshot.x() + collisionOffset.x,
                (float) snapshot.y() + collisionOffset.y,
                (float) snapshot.z() + collisionOffset.z)
            .mul(model.relativePose());
        // 必须与 ClientRagdollManager.render() 实际使用的渲染根一致。
        this.basePose = new Matrix4f().translation((float) snapshot.x(), (float) snapshot.y(), (float) snapshot.z())
            .mul(model.relativePose());
        // 1.20.1 的 getDeltaMovement() 单位是方块/tick，这里同样按 tick 速度换算成秒。
        Vector3f initialVelocity = new Vector3f(
            (float) snapshot.velocityX() * 20.0F,
            (float) snapshot.velocityY() * 20.0F,
            (float) snapshot.velocityZ() * 20.0F);

        for (RagdollDefinition.Part part : definition.parts()
            .values()) {
            RagdollDefinition.Bone bone = definition.bones()
                .get(part.boneIndex());
            Matrix4f worldMatrix = new Matrix4f(base).mul(bone.initialGlobal());
            org.joml.Vector3f center = worldMatrix.transformPosition(new org.joml.Vector3f(part.center()));
            Transform transform = transform(worldMatrix, center);
            Matrix4f boneToBody = BonePoseMath.bind(transform, worldMatrix);
            org.joml.Vector3f half = BonePoseMath.halfExtents(boneToBody, part.halfExtents());
            BoxShape shape = new BoxShape(new Vector3f(half.x, half.y, half.z));
            shape.setMargin(0.01F);
            Vector3f inertia = new Vector3f();
            shape.calculateLocalInertia(part.mass(), inertia);
            RigidBody body = new RigidBody(
                new RigidBodyConstructionInfo(part.mass(), new DefaultMotionState(transform), shape, inertia));
            body.setLinearVelocity(initialVelocity);
            // 死亡快照没有可靠的角速度；给各肢体随机自旋只会让约束从第一帧开始互相对抗。
            body.setAngularVelocity(new Vector3f());
            // 尸体在肢体落地方块上时不应该获得能量。
            body.setRestitution(0.0F);
            body.setSleepingThresholds(0.12F, 0.15F);
            float smallestHalfExtent = Math.min(half.x, Math.min(half.y, half.z));
            body.setCcdMotionThreshold(Math.max(0.025F, smallestHalfExtent * 0.5F));
            body.setCcdSweptSphereRadius(Math.max(0.02F, smallestHalfExtent * 0.8F));
            // 只参与方块碰撞；布娃娃各刚体之间由关节控制，不再额外互相挤压。
            world.addRagdollBody(body, this);
            // 碰撞盒中心相对刚体原点的局部偏移：平移取世界差值，再乘初始姿态的
            // 逆旋转，使刚体随后旋转时仍能得到正确的世界中心。
            Vector3f centerOffsetLocal = new Vector3f(
                (float) (center.x - transform.origin.x),
                (float) (center.y - transform.origin.y),
                (float) (center.z - transform.origin.z));
            Matrix3f inverseInitialBasis = new Matrix3f(transform.basis);
            inverseInitialBasis.invert();
            inverseInitialBasis.transform(centerOffsetLocal);
            BodyPart bodyPart = new BodyPart(part, body, copy(transform), boneToBody, half, centerOffsetLocal);
            bodies.put(part.role(), bodyPart);
        }
        List<RigidBody> rigidBodies = new ArrayList<>(bodies.size());
        for (BodyPart part : bodies.values()) {
            rigidBodies.add(part.body);
        }
        playerCollision = new PlayerCollisionResponse(rigidBodies, world::updateBodyAabb);
        BodyPart bodyPart = bodies.get(RagdollDefinition.Role.BODY);
        RagdollDefinition.Bone bodyBone = definition.bones()
            .get(bodyPart.part.boneIndex());
        org.joml.Vector3f bodyPivot = new Matrix4f(base).mul(bodyBone.initialGlobal())
            .transformPosition(new org.joml.Vector3f(bodyBone.pivot()).mul(1.0F / 16.0F));
        initialBodyPivot.set(bodyPivot.x, bodyPivot.y, bodyPivot.z);
        float floorCorrection = alignToSpawnFloor(snapshot.y() + collisionOffset.y);
        org.joml.Vector3f correctedBodyPivot = new org.joml.Vector3f(bodyPivot);
        correctedBodyPivot.y += floorCorrection;
        bodyCenterOffsetLocal.set(bodyPart.initialTransform.origin);
        bodyCenterOffsetLocal.sub(new Vector3f(correctedBodyPivot.x, correctedBodyPivot.y, correctedBodyPivot.z));
        Matrix3f inverseBodyBasis = new Matrix3f(bodyPart.initialTransform.basis);
        inverseBodyBasis.invert();
        inverseBodyBasis.transform(bodyCenterOffsetLocal);
        createConstraints(base, floorCorrection);
        updateMaterial();
        YsmRagdollLog.info(
            "已创建 JBullet 布娃娃: 玩家=" + snapshot
                .playerId() + ", 刚体=" + bodies.size() + ", 关节=" + constraints.size() + ", 绑定模式=各部位完整姿态");
    }

    /**
     * YSM 的渲染根矩阵包含模型坐标翻转，不能直接假定其最低点等于玩家脚底。
     * 这里统一移动整套刚体，保持所有关节的相对位置不变，并避免第一步求解时从方块内部开始。
     */
    private float alignToSpawnFloor(double playerFeetY) {
        float minimumY = Float.POSITIVE_INFINITY;
        for (BodyPart part : bodies.values()) {
            Transform transform = part.body.getWorldTransform(new Transform());
            org.joml.Vector3f half = part.halfExtents;
            float projectedHalfHeight = Math.abs(transform.basis.m10) * half.x + Math.abs(transform.basis.m11) * half.y
                + Math.abs(transform.basis.m12) * half.z;
            minimumY = Math.min(minimumY, transform.origin.y - projectedHalfHeight);
        }
        float targetMinimumY = (float) playerFeetY + 0.04F;
        float correction = targetMinimumY - minimumY;
        if (Float.isNaN(correction) || Float.isInfinite(correction) || Math.abs(correction) < 1.0E-4F) {
            return 0.0F;
        }
        for (BodyPart part : bodies.values()) {
            Transform transform = part.body.getWorldTransform(new Transform());
            transform.origin.y += correction;
            part.body.setWorldTransform(transform);
            if (part.body.getMotionState() != null) {
                part.body.getMotionState()
                    .setWorldTransform(transform);
            }
            part.initialTransform.origin.y += correction;
        }
        YsmRagdollLog.info("布娃娃初始地面校准: 原最低点=" + minimumY + ", 目标最低点=" + targetMinimumY + ", 垂直修正=" + correction);
        return correction;
    }

    public Vec3 center() {
        BodyPart body = bodies.get(RagdollDefinition.Role.BODY);
        Transform transform = body.body.getWorldTransform(new Transform());
        return Vec3.createVectorHelper(transform.origin.x, transform.origin.y, transform.origin.z);
    }

    /** 与可配置的碰撞/渲染偏移无关的世界高度。 */
    public double worldCenterY() {
        return center().yCoord - world.currentWorldOffset(new Vector3f()).y;
    }

    /**
     * 根据主要刚体所在区块更新加载状态。区块卸载时不删除刚体，只暂停模拟，
     * 这样重新靠近后可以从原位置和原姿态继续运行。
     */
    public boolean updateChunkLoaded(World level) {
        if (disposed || level == null) {
            return false;
        }
        Vector3f offset = world.currentWorldOffset(new Vector3f());
        boolean loaded = true;
        for (BodyPart part : bodies.values()) {
            Transform transform = part.body.getWorldTransform(new Transform());
            int blockX = MathHelper.floor_double(transform.origin.x - offset.x);
            int blockZ = MathHelper.floor_double(transform.origin.z - offset.z);
            if (!ClientPhysicsWorld.isChunkLoaded(level, blockX, blockZ)) {
                loaded = false;
                break;
            }
        }
        if (loaded == chunkLoaded) {
            return loaded;
        }
        chunkLoaded = loaded;
        if (loaded) {
            for (BodyPart part : bodies.values()) {
                part.body.forceActivationState(CollisionObject.ACTIVE_TAG);
                part.body.activate();
            }
            YsmRagdollLog.info("布娃娃所在区块重新加载，恢复物理模拟");
        } else {
            for (BodyPart part : bodies.values()) {
                part.body.forceActivationState(CollisionObject.DISABLE_SIMULATION);
            }
            YsmRagdollLog.info("布娃娃所在区块未加载，暂停物理并隐藏尸体");
        }
        return loaded;
    }

    public boolean isChunkLoaded() {
        return chunkLoaded;
    }

    public Vec3 renderDisplacement() {
        BodyPart body = bodies.get(RagdollDefinition.Role.BODY);
        if (body == null) {
            return Vec3.createVectorHelper(0, 0, 0);
        }
        // 渲染根仍以躯干枢轴为锚点；每个物理部位的实际中心和旋转由 writePose
        // 回写到骨骼参数，不能把各肢体的位移再次叠加到这里。
        Transform transform = body.body.getWorldTransform(new Transform());
        Vector3f pivotOffset = new Vector3f(bodyCenterOffsetLocal);
        transform.basis.transform(pivotOffset);
        Vector3f currentPivot = new Vector3f(transform.origin);
        currentPivot.sub(pivotOffset);
        currentPivot.sub(initialBodyPivot);
        return Vec3.createVectorHelper(currentPivot.x, currentPivot.y, currentPivot.z);
    }

    public double selectionRadius() {
        BodyPart body = bodies.get(RagdollDefinition.Role.BODY);
        org.joml.Vector3f half = body.halfExtents;
        return Math.max(0.45, Math.sqrt(half.x * half.x + half.y * half.y + half.z * half.z));
    }

    /**
     * 每个肢体碰撞盒在世界坐标系中的中心，以及它相对刚体原点的局部偏移。
     *
     * <p>
     * 供牵引模式做"射线附近最近肢体"的兜底选取；偏移量用于把命中点换算回刚体
     * 局部的抓取锚点，避免再次查询 JBullet 的射线结果。
     * </p>
     */
    public List<PartCenter> partCenters() {
        List<PartCenter> result = new ArrayList<>(bodies.size());
        for (BodyPart part : bodies.values()) {
            Transform transform = part.body.getWorldTransform(new Transform());
            Vector3f offset = new Vector3f(part.centerOffsetLocal);
            Matrix3f basis = new Matrix3f(transform.basis);
            basis.transform(offset);
            result.add(
                new PartCenter(
                    part.body,
                    part.role(),
                    transform.origin.x + offset.x,
                    transform.origin.y + offset.y,
                    transform.origin.z + offset.z,
                    offset.x,
                    offset.y,
                    offset.z,
                    part.halfExtents.x,
                    part.halfExtents.y,
                    part.halfExtents.z));
        }
        return result;
    }

    /** 一个肢体碰撞盒的中心与局部偏移；局部偏移用于反推抓取锚点。 */
    public static final class PartCenter {

        private final RigidBody body;
        private final RagdollDefinition.Role role;
        private final double x;
        private final double y;
        private final double z;
        private final float offsetX;
        private final float offsetY;
        private final float offsetZ;
        private final float halfX;
        private final float halfY;
        private final float halfZ;

        PartCenter(RigidBody body, RagdollDefinition.Role role, double x, double y, double z, float offsetX,
            float offsetY, float offsetZ, float halfX, float halfY, float halfZ) {
            this.body = body;
            this.role = role;
            this.x = x;
            this.y = y;
            this.z = z;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.halfX = halfX;
            this.halfY = halfY;
            this.halfZ = halfZ;
        }

        public RigidBody body() {
            return body;
        }

        public RagdollDefinition.Role role() {
            return role;
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

        public float offsetX() {
            return offsetX;
        }

        public float offsetY() {
            return offsetY;
        }

        public float offsetZ() {
            return offsetZ;
        }

        /** 计算该碰撞盒在世界空间中的最大半径，用于把抓取锚点限制在盒内。 */
        public double radius() {
            return Math.max(0.05D, Math.sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ));
        }
    }

    /**
     * 方块被填入布娃娃内部时整体向上移出，避免逐肢体分离破坏关节并产生持续抖动。
     */
    void ejectAboveNewBlocks(List<AxisAlignedBB> blockBoxes) {
        float requiredLift = 0.0F;
        for (BodyPart part : bodies.values()) {
            Transform transform = part.body.getWorldTransform(new Transform());
            org.joml.Vector3f half = part.halfExtents;
            float halfX = Math.abs(transform.basis.m00) * half.x + Math.abs(transform.basis.m01) * half.y
                + Math.abs(transform.basis.m02) * half.z;
            float halfY = Math.abs(transform.basis.m10) * half.x + Math.abs(transform.basis.m11) * half.y
                + Math.abs(transform.basis.m12) * half.z;
            float halfZ = Math.abs(transform.basis.m20) * half.x + Math.abs(transform.basis.m21) * half.y
                + Math.abs(transform.basis.m22) * half.z;
            double bodyMinX = transform.origin.x - halfX;
            double bodyMinY = transform.origin.y - halfY;
            double bodyMinZ = transform.origin.z - halfZ;
            double bodyMaxX = transform.origin.x + halfX;
            double bodyMaxY = transform.origin.y + halfY;
            double bodyMaxZ = transform.origin.z + halfZ;
            for (AxisAlignedBB blockBox : blockBoxes) {
                if (bodyMinX < blockBox.maxX && bodyMaxX > blockBox.minX
                    && bodyMinY < blockBox.maxY
                    && bodyMaxY > blockBox.minY
                    && bodyMinZ < blockBox.maxZ
                    && bodyMaxZ > blockBox.minZ) {
                    requiredLift = Math.max(requiredLift, (float) (blockBox.maxY - bodyMinY + 0.01));
                }
            }
        }
        if (requiredLift <= 0.0F) {
            return;
        }
        translateBodiesOnly(new Vector3f(0.0F, requiredLift, 0.0F));
        for (BodyPart part : bodies.values()) {
            Vector3f velocity = part.body.getLinearVelocity(new Vector3f());
            velocity.y = Math.max(0.0F, velocity.y);
            part.body.setLinearVelocity(velocity);
            part.body.activate(true);
        }
        YsmRagdollLog.info("检测到方块填入布娃娃内部，整套刚体向上移出: " + requiredLift);
    }

    /**
     * 地面穿透纠正必须整体平移，不能单独移动一个肢体，否则关节会在下一步释放弹跳能量。
     */
    void correctGroundPenetration(float lift) {
        if (lift <= 0.0F) {
            return;
        }
        translateBodiesOnly(new Vector3f(0.0F, lift, 0.0F));
        if (isGrabbed()) {
            return;
        }
        for (BodyPart part : bodies.values()) {
            Vector3f velocity = part.body.getLinearVelocity(new Vector3f());
            if (velocity.y > 0.0F) {
                velocity.y = 0.0F;
                part.body.setLinearVelocity(velocity);
            }
        }
    }

    /**
     * 在躯干自身坐标中按不同保留率衰减三轴角速度，使侧翻比头脚翻滚更容易延续。
     * 这里只调整根刚体角速度，不主动施加倾倒力；四肢仍完全交给关节和碰撞求解。
     */
    void biasTowardSideRoll(float seconds) {
        if (isGrabbed() || inFluid) {
            return;
        }
        BodyPart body = bodies.get(RagdollDefinition.Role.BODY);
        if (body == null) {
            return;
        }
        Transform transform = body.body.getWorldTransform(new Transform());
        Vector3f localAngularVelocity = body.body.getAngularVelocity(new Vector3f());
        Matrix3f inverseBasis = new Matrix3f(transform.basis);
        inverseBasis.invert();
        inverseBasis.transform(localAngularVelocity);

        localAngularVelocity.x = clampAngular(
            localAngularVelocity.x * retention(HEAD_OVER_HEELS_RETENTION_PER_SECOND, seconds),
            2.5F);
        localAngularVelocity.y = clampAngular(
            localAngularVelocity.y * retention(BODY_TWIST_RETENTION_PER_SECOND, seconds),
            4.0F);
        localAngularVelocity.z = clampAngular(
            localAngularVelocity.z * retention(SIDE_ROLL_RETENTION_PER_SECOND, seconds),
            6.0F);
        transform.basis.transform(localAngularVelocity);
        body.body.setAngularVelocity(localAngularVelocity);
    }

    public void applyPlayerPush(EntityPlayer player) {
        if (!chunkLoaded || isGrabbed()) {
            return;
        }
        updateMaterial();
        if (player == null || !player.isEntityAlive()) {
            return;
        }
        int easyPushIndex = YsmRagdollConfig.easyPushIndex();
        if (easyPushIndex == 100) {
            pushLiftSuppressionSteps = 0;
            return;
        }
        float scaledEasyPush = easyPushIndex / 5.0F;
        double velocityX = player.motionX;
        double velocityY = player.motionY;
        double velocityZ = player.motionZ;
        double horizontalSpeed = Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
        double inputForward = player.moveForward;
        double inputStrafe = player.moveStrafing;
        double inputLength = Math.sqrt(inputForward * inputForward + inputStrafe * inputStrafe);
        if (horizontalSpeed < 0.015 && Math.abs(velocityY) < 0.02 && inputLength < 0.01) {
            return;
        }
        // 玩家撞住布娃娃后速度可能已经被原版碰撞压到很小，用输入方向保留推动意图。
        double pushX = velocityX;
        double pushZ = velocityZ;
        if (horizontalSpeed < 0.015 && inputLength >= 0.01) {
            double yaw = Math.toRadians(player.rotationYaw);
            double forwardX = -Math.sin(yaw);
            double forwardZ = Math.cos(yaw);
            double strafeX = -Math.cos(yaw);
            double strafeZ = -Math.sin(yaw);
            pushX = (forwardX * inputForward + strafeX * inputStrafe) * 0.08;
            pushZ = (forwardZ * inputForward + strafeZ * inputStrafe) * 0.08;
        }
        double traveledX = player.posX - player.prevPosX;
        double traveledZ = player.posZ - player.prevPosZ;
        double traveledDistance = Math.sqrt(traveledX * traveledX + traveledZ * traveledZ);
        if (traveledDistance > MAX_PLAYER_SWEEP_DISTANCE) {
            traveledX = velocityX;
            traveledZ = velocityZ;
            traveledDistance = horizontalSpeed;
        }
        // 当前碰撞箱保持很小的接触余量，再向上一 tick 的位置反向展开以防高速穿过。
        AxisAlignedBB playerBox = player.boundingBox.expand(
            PUSH_CONTACT_MARGIN + Math.min(0.08, horizontalSpeed * PUSH_SPEED_MARGIN),
            PUSH_CONTACT_MARGIN + Math.min(0.08, horizontalSpeed * PUSH_SPEED_MARGIN),
            PUSH_CONTACT_MARGIN + Math.min(0.08, horizontalSpeed * PUSH_SPEED_MARGIN));
        if (traveledDistance > 1.0E-4) {
            playerBox = expandTowards(playerBox, -traveledX, 0.0, -traveledZ);
        }
        BodyPart pushedPart = null;
        Transform pushedTransform = null;
        double nearestDistanceSquared = Double.POSITIVE_INFINITY;
        double playerCenterY = (player.boundingBox.minY + player.boundingBox.maxY) * 0.5D;
        for (BodyPart part : bodies.values()) {
            Transform transform = part.body.getWorldTransform(new Transform());
            org.joml.Vector3f half = part.halfExtents;
            float halfX = Math.abs(transform.basis.m00) * half.x + Math.abs(transform.basis.m01) * half.y
                + Math.abs(transform.basis.m02) * half.z;
            float halfY = Math.abs(transform.basis.m10) * half.x + Math.abs(transform.basis.m11) * half.y
                + Math.abs(transform.basis.m12) * half.z;
            float halfZ = Math.abs(transform.basis.m20) * half.x + Math.abs(transform.basis.m21) * half.y
                + Math.abs(transform.basis.m22) * half.z;
            if (transform.origin.x + halfX < playerBox.minX || transform.origin.x - halfX > playerBox.maxX
                || transform.origin.y + halfY < playerBox.minY
                || transform.origin.y - halfY > playerBox.maxY
                || transform.origin.z + halfZ < playerBox.minZ
                || transform.origin.z - halfZ > playerBox.maxZ) {
                continue;
            }
            double distanceSquared = square(transform.origin.x - player.posX)
                + square(transform.origin.y - playerCenterY)
                + square(transform.origin.z - player.posZ);
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                pushedPart = part;
                pushedTransform = transform;
            }
        }
        boolean appliedPush = false;
        if (pushedPart != null && pushedTransform != null) {
            double directionX = traveledDistance > 0.01 ? traveledX : pushX;
            double directionZ = traveledDistance > 0.01 ? traveledZ : pushZ;
            double directionLength = Math.sqrt(directionX * directionX + directionZ * directionZ);
            double startX = player.posX - traveledX;
            double startZ = player.posZ - traveledZ;
            double toPartX = pushedTransform.origin.x - startX;
            double toPartZ = pushedTransform.origin.z - startZ;
            boolean approaching = directionLength > 1.0E-4 && directionX * toPartX + directionZ * toPartZ > 0.001;
            if (approaching) {
                float normalizedX = (float) (directionX / directionLength);
                float normalizedZ = (float) (directionZ / directionLength);
                float intendedSpeed = (float) Math.max(horizontalSpeed, traveledDistance) * 20.0F;
                float targetSpeed = Math.min(MAX_PUSH_TARGET_SPEED, Math.max(0.8F, intendedSpeed) * 0.7F);
                float response = Math.min(0.85F, 0.25F + scaledEasyPush * 0.03F);
                float speedChange = Math.min(
                    MIN_PUSH_SPEED_CHANGE + scaledEasyPush * PUSH_SPEED_CHANGE_PER_EASE,
                    Math.max(0.0F, targetSpeed - speedAlong(pushedPart, normalizedX, normalizedZ)) * response);
                for (BodyPart part : bodies.values()) {
                    Vector3f bodyVelocity = part.body.getLinearVelocity(new Vector3f());
                    float speedAlongPush = bodyVelocity.x * normalizedX + bodyVelocity.z * normalizedZ;
                    float sharedChange = Math
                        .min(speedChange * SHARED_PUSH_RESPONSE, Math.max(0.0F, targetSpeed - speedAlongPush));
                    if (sharedChange > 0.0F) {
                        Vector3f impulse = new Vector3f(
                            normalizedX * sharedChange * part.part.mass(),
                            0.0F,
                            normalizedZ * sharedChange * part.part.mass());
                        part.body.applyCentralImpulse(impulse);
                        part.body.activate();
                    }
                }
                float contactChange = Math.min(MAX_CONTACT_SPEED_CHANGE, speedChange * CONTACT_PUSH_RESPONSE);
                if (contactChange > 0.0F) {
                    pushedPart.body.applyCentralImpulse(
                        new Vector3f(
                            normalizedX * contactChange * pushedPart.part.mass(),
                            0.0F,
                            normalizedZ * contactChange * pushedPart.part.mass()));
                    pushedPart.body.activate();
                }
                pushLiftSuppressionSteps = PUSH_LIFT_SUPPRESSION_STEPS;
                appliedPush = true;
            }
        }
        if (appliedPush) {
            wakeAll();
        }
    }

    void enforcePlayerCollision(BoxShape playerShape, Transform from, Transform to, Vector3f velocity, boolean sweep) {
        if (disposed || !chunkLoaded) {
            return;
        }
        pushLiftSuppressionSteps = 0;
        playerCollision.resolve(playerShape, from, to, velocity, sweep);
    }

    /**
     * 按原版爆炸实体影响范围计算每个肢体的冲量。
     *
     * <p>
     * 爆炸半径在原版实体处理中会乘以 2 作为搜索范围；这里使用同样范围，并以
     * 肢体盒边缘距离计算线性衰减。每个肢体分别取爆炸到其中心的方向，因此关节会
     * 受到不同方向的力而产生自然的折叠和翻滚。客户端再用一次方块射线近似原版
     * 遮挡，墙后的尸体不会凭空被推动。
     * </p>
     */
    public boolean applyExplosion(World level, EntityPlayer viewer, double explosionX, double explosionY,
        double explosionZ, float radius) {
        if (disposed || !chunkLoaded || radius <= 0.0F) {
            return false;
        }
        int impactIndex = YsmRagdollConfig.explosionImpactIndex();
        if (impactIndex <= 0) {
            return false;
        }
        Vector3f offset = world.currentWorldOffset(new Vector3f());
        Vector3f explosion = new Vector3f(
            (float) explosionX + offset.x,
            (float) explosionY + offset.y,
            (float) explosionZ + offset.z);
        float effectRadius = Math.max(0.5F, radius * 2.0F);
        float radiusStrength = Math.min(2.5F, Math.max(0.35F, radius / 4.0F));
        float indexStrength = impactIndex / 50.0F;
        boolean affected = false;
        for (BodyPart part : bodies.values()) {
            Transform transform = part.body.getWorldTransform(new Transform());
            Vector3f center = new Vector3f(transform.origin);
            Vector3f fromExplosion = new Vector3f(center);
            fromExplosion.sub(explosion);
            float distance = fromExplosion.length();
            float extent = Math.max(part.halfExtents.x, Math.max(part.halfExtents.y, part.halfExtents.z));
            float surfaceDistance = Math.max(0.0F, distance - extent);
            if (surfaceDistance >= effectRadius) {
                continue;
            }
            Vec3 actualSource = Vec3.createVectorHelper(explosionX, explosionY, explosionZ);
            Vec3 actualTarget = Vec3.createVectorHelper(center.x - offset.x, center.y - offset.y, center.z - offset.z);
            if (isExplosionOccluded(level, viewer, actualSource, actualTarget)) {
                continue;
            }
            float exposure = 1.0F - surfaceDistance / effectRadius;
            if (distance < 1.0E-4F) {
                fromExplosion.set(0.0F, 1.0F, 0.0F);
            } else {
                fromExplosion.scale(1.0F / distance);
                // 爆炸位于尸体同一高度时保留少量上抛分量，符合原版爆炸的脱离地面效果。
                fromExplosion.y += 0.12F;
                fromExplosion.normalize();
            }
            float speed = Math.min(24.0F, 12.0F * radiusStrength * indexStrength * exposure);
            if (speed <= 0.0F) {
                continue;
            }
            Vector3f impulse = new Vector3f(fromExplosion);
            impulse.scale(speed * part.part.mass());
            part.body.applyCentralImpulse(impulse);
            part.body.activate(true);
            affected = true;
        }
        if (affected) {
            // 爆炸是独立外力，不能被玩家推动后的防起跳保护吞掉。
            pushLiftSuppressionSteps = 0;
            wakeAll();
        }
        return affected;
    }

    private static boolean isExplosionOccluded(World level, EntityPlayer viewer, Vec3 source, Vec3 target) {
        if (level == null || viewer == null || source.squareDistanceTo(target) < 1.0E-6) {
            return false;
        }
        // 1.7.10 的 World#rayTraceBlocks 会就地改写传入的起点向量，把它沿射线推到命中点。
        // 先把要比较的两个量取出来，再用全新向量去查询，否则距离比较会退化成“命中点到命中点”。
        double sourceX = source.xCoord;
        double sourceY = source.yCoord;
        double sourceZ = source.zCoord;
        double sourceToTarget = source.squareDistanceTo(target);
        MovingObjectPosition hit = level.rayTraceBlocks(
            Vec3.createVectorHelper(sourceX, sourceY, sourceZ),
            Vec3.createVectorHelper(target.xCoord, target.yCoord, target.zCoord),
            false);
        if (hit == null || hit.hitVec == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return false;
        }
        double hitToSource = hit.hitVec.squareDistanceTo(Vec3.createVectorHelper(sourceX, sourceY, sourceZ));
        return hitToSource + 0.05 < sourceToTarget;
    }

    /** 玩家推动后的短时间内限制向上速度，避免地面接触把水平冲量转换成原地起跳。 */
    void suppressPlayerPushLift() {
        if (isGrabbed() || pushLiftSuppressionSteps <= 0) {
            return;
        }
        pushLiftSuppressionSteps--;
        for (BodyPart part : bodies.values()) {
            Vector3f velocity = part.body.getLinearVelocity(new Vector3f());
            if (velocity.y > MAX_PUSH_UPWARD_SPEED) {
                velocity.y = MAX_PUSH_UPWARD_SPEED;
                part.body.setLinearVelocity(velocity);
            }
        }
    }

    /**
     * 绘制与 JBullet 当前世界变换完全一致的旋转碰撞箱。
     *
     * <p>
     * 1.7.10 没有 {@code PoseStack}/{@code VertexConsumer}，这里直接用
     * {@link Tessellator} 的线段模式画出十二根棱。调用方负责设置纹理/光照/深度状态。
     * </p>
     */
    public void renderDebug() {
        if (disposed || bodies.isEmpty()) {
            return;
        }
        Tessellator tessellator = Tessellator.instance;
        Transform transform = new Transform();
        tessellator.startDrawing(GL11.GL_LINES);
        tessellator.setBrightness(0xF000F0);
        for (Map.Entry<RagdollDefinition.Role, BodyPart> entry : bodies.entrySet()) {
            BodyPart part = entry.getValue();
            part.body.getWorldTransform(transform);
            float halfX = part.halfExtents.x;
            float halfY = part.halfExtents.y;
            float halfZ = part.halfExtents.z;
            float[][] corners = new float[8][3];
            for (int corner = 0; corner < 8; corner++) {
                float localX = (corner & 1) == 0 ? -halfX : halfX;
                float localY = (corner & 2) == 0 ? -halfY : halfY;
                float localZ = (corner & 4) == 0 ? -halfZ : halfZ;
                corners[corner][0] = (float) (transform.basis.m00 * localX + transform.basis.m01 * localY
                    + transform.basis.m02 * localZ
                    + transform.origin.x);
                corners[corner][1] = (float) (transform.basis.m10 * localX + transform.basis.m11 * localY
                    + transform.basis.m12 * localZ
                    + transform.origin.y);
                corners[corner][2] = (float) (transform.basis.m20 * localX + transform.basis.m21 * localY
                    + transform.basis.m22 * localZ
                    + transform.origin.z);
            }
            float red;
            float green;
            float blue;
            RagdollDefinition.Role role = entry.getKey();
            if (role == RagdollDefinition.Role.BODY) {
                red = 1.0F;
                green = 0.85F;
                blue = 0.15F;
            } else if (role == RagdollDefinition.Role.HEAD) {
                red = 0.15F;
                green = 0.9F;
                blue = 1.0F;
            } else if (role.name()
                .startsWith("LEFT_")) {
                    red = 0.2F;
                    green = 1.0F;
                    blue = 0.35F;
                } else {
                    red = 1.0F;
                    green = 0.25F;
                    blue = 0.25F;
                }
            tessellator.setColorRGBA_F(red, green, blue, 1.0F);
            for (int[] edge : BOX_EDGES) {
                float[] from = corners[edge[0]];
                float[] to = corners[edge[1]];
                tessellator.addVertex(from[0], from[1], from[2]);
                tessellator.addVertex(to[0], to[1], to[2]);
            }
        }
        tessellator.draw();
    }

    private static final int[][] BOX_EDGES = { { 0, 1 }, { 0, 2 }, { 0, 4 }, { 1, 3 }, { 1, 5 }, { 2, 3 }, { 2, 6 },
        { 3, 7 }, { 4, 5 }, { 4, 6 }, { 5, 7 }, { 6, 7 } };

    /**
     * 强制把可识别的 YSM 部位绑定到对应碰撞箱的完整姿态。
     *
     * <p>
     * 每根骨骼的参数本身就是一个局部变换。这里先用刚体世界中心和世界旋转构成
     * 目标骨骼世界矩阵，再相对父骨骼求局部矩阵，最后按 YSM 的平移、Z/Y/X 旋转、
     * 缩放顺序反解参数。这样碰撞箱移动到哪里，对应网格骨骼的枢轴就移动到哪里。
     * </p>
     */
    public void writePose() {
        if (disposed || !bodies.containsKey(RagdollDefinition.Role.BODY) || initialBoneTransforms.length == 0) {
            return;
        }
        Vec3 displacement = renderDisplacement();
        Matrix4f renderBase = new Matrix4f()
            .translation((float) displacement.xCoord, (float) displacement.yCoord, (float) displacement.zCoord)
            .mul(basePose);
        Matrix4f inverseRenderBase = new Matrix4f(renderBase).invert();
        Map<Integer, BodyPart> physicalBones = new HashMap<>();
        for (BodyPart part : bodies.values()) {
            physicalBones.put(part.part.boneIndex(), part);
        }
        // 按父级优先把矩阵解析成 YSM 接下来真正会重建的结果，避免把分解近似
        // 传播到子刚体的中心。
        Matrix4f[] actualWorld = new Matrix4f[definition.bones()
            .size()];
        boolean[] visiting = new boolean[actualWorld.length];
        System.arraycopy(initialBoneTransforms, 0, model.boneTransforms(), 0, initialBoneTransforms.length);
        for (int index = 0; index < actualWorld.length; index++) {
            writeBone(index, inverseRenderBase, physicalBones, actualWorld, visiting);
        }
    }

    private Matrix4f writeBone(int index, Matrix4f inverseRenderBase, Map<Integer, BodyPart> physicalBones,
        Matrix4f[] actualWorld, boolean[] visiting) {
        if (actualWorld[index] != null) {
            return actualWorld[index];
        }
        if (visiting[index]) {
            throw new IllegalStateException("Cyclic YSM skeleton");
        }
        visiting[index] = true;
        RagdollDefinition.Bone bone = definition.bones()
            .get(index);
        Matrix4f parent = bone.parentIndex() >= 0
            ? writeBone(bone.parentIndex(), inverseRenderBase, physicalBones, actualWorld, visiting)
            : new Matrix4f();
        BodyPart part = physicalBones.get(index);
        if (part != null && Math.abs(parent.determinant3x3()) > 1.0E-12F) {
            Matrix4f target = new Matrix4f(parent).invert()
                .mul(inverseRenderBase)
                .mul(BonePoseMath.rigid(part.body.getWorldTransform(new Transform())))
                .mul(part.boneToBody);
            BonePoseMath.writeLocal(
                target,
                bone.pivot(),
                part.part.center(),
                model.boneTransforms(),
                initialBoneTransforms,
                index);
        }
        // 没有刚体的骨骼保留捕获时的局部参数，包括隐藏骨骼的零缩放。
        actualWorld[index] = new Matrix4f(parent).mul(BonePoseMath.local(bone.pivot(), model.boneTransforms(), index));
        visiting[index] = false;
        return actualWorld[index];
    }

    public String intensiveState() {
        BodyPart body = bodies.get(RagdollDefinition.Role.BODY);
        if (body == null) {
            return "physics=missing-body";
        }
        Transform transform = body.body.getWorldTransform(new Transform());
        Vector3f velocity = body.body.getLinearVelocity(new Vector3f());
        Vector3f angular = body.body.getAngularVelocity(new Vector3f());
        return "physics=active bodyPos=(" + Float.toString(transform.origin.x)
            + ','
            + Float.toString(transform.origin.y)
            + ','
            + Float.toString(transform.origin.z)
            + ") bodyBasis=["
            + Float.toString(transform.basis.m00)
            + ','
            + Float.toString(transform.basis.m01)
            + ','
            + Float.toString(transform.basis.m02)
            + ';'
            + Float.toString(transform.basis.m10)
            + ','
            + Float.toString(transform.basis.m11)
            + ','
            + Float.toString(transform.basis.m12)
            + ';'
            + Float.toString(transform.basis.m20)
            + ','
            + Float.toString(transform.basis.m21)
            + ','
            + Float.toString(transform.basis.m22)
            + "] velocity=("
            + Float.toString(velocity.x)
            + ','
            + Float.toString(velocity.y)
            + ','
            + Float.toString(velocity.z)
            + ") angular=("
            + Float.toString(angular.x)
            + ','
            + Float.toString(angular.y)
            + ','
            + Float.toString(angular.z)
            + ')';
    }

    /** 诊断用：返回指定刚体在骨架中的部位名，未知时返回 null。 */
    public String roleNameOf(RigidBody body) {
        if (body == null) {
            return null;
        }
        for (BodyPart part : bodies.values()) {
            if (part.body == body) {
                return part.role()
                    .name();
            }
        }
        return null;
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (grab != null) {
            grab.close();
            grab = null;
        }
        for (TypedConstraint constraint : constraints) {
            world.removeConstraint(constraint);
        }
        for (BodyPart part : bodies.values()) {
            world.removeBody(part.body);
        }
        constraints.clear();
        bodies.clear();
    }

    private void createConstraints(Matrix4f base, float floorCorrection) {
        for (Map.Entry<RagdollDefinition.Role, BodyPart> entry : bodies.entrySet()) {
            RagdollDefinition.Role childRole = entry.getKey();
            BodyPart parent = bodies.get(parentOf(childRole));
            if (parent == null) {
                continue;
            }
            BodyPart child = entry.getValue();
            RagdollDefinition.Bone childBone = definition.bones()
                .get(child.part.boneIndex());
            Matrix4f childWorld = new Matrix4f(base).mul(childBone.initialGlobal());
            org.joml.Vector3f joint = childWorld
                .transformPosition(new org.joml.Vector3f(childBone.pivot()).mul(1.0F / 16.0F));
            joint.y += floorCorrection;
            Transform frameA = localFrame(parent.initialTransform, child.initialTransform.basis, joint);
            Transform frameB = localFrame(child.initialTransform, child.initialTransform.basis, joint);
            Generic6DofConstraint constraint = new Generic6DofConstraint(parent.body, child.body, frameA, frameB, true);
            constraint.setLinearLowerLimit(new Vector3f());
            constraint.setLinearUpperLimit(new Vector3f());
            AngularLimit limit = limitFor(childRole);
            constraint.setAngularLowerLimit(limit.lower);
            constraint.setAngularUpperLimit(limit.upper);
            world.addConstraint(constraint);
            constraints.add(constraint);
        }
    }

    private void updateMaterial() {
        int frictionSetting = YsmRagdollConfig.groundFriction();
        if (frictionSetting == appliedFriction) {
            return;
        }
        appliedFriction = frictionSetting;
        float friction = frictionSetting * 0.02F;
        float linearDamping = frictionSetting == 0 ? 0.0F : Math.min(0.82F, frictionSetting * 0.0075F);
        for (BodyPart part : bodies.values()) {
            part.body.setFriction(friction);
            // 保留角速度，让高地面摩擦只快速消除整体滑动，不锁死肢体。
            part.body.setDamping(linearDamping, 0.18F);
            part.body.activate();
        }
    }

    /** 与附近方块碰撞体同步平移整个布娃娃，不改变相对接触和关节位置。 */
    void translateCollisionWorld(Vector3f delta) {
        if (delta.lengthSquared() < 1.0E-10F) {
            return;
        }
        translateBodiesOnly(delta);
        for (BodyPart part : bodies.values()) {
            part.initialTransform.origin.add(delta);
        }
        initialBodyPivot.add(delta);
    }

    /** 只移动当前物理状态，保留初始姿态作为渲染位移基准。 */
    private void translateBodiesOnly(Vector3f delta) {
        if (delta.lengthSquared() < 1.0E-10F) {
            return;
        }
        for (BodyPart part : bodies.values()) {
            Transform transform = part.body.getWorldTransform(new Transform());
            transform.origin.add(delta);
            part.body.setWorldTransform(transform);
            part.body.setInterpolationWorldTransform(transform);
            if (part.body.getMotionState() != null) {
                part.body.getMotionState()
                    .setWorldTransform(transform);
            }
            world.updateBodyAabb(part.body);
        }
    }

    private void wakeAll() {
        for (BodyPart part : bodies.values()) {
            part.body.activate(true);
        }
    }

    private static Transform transform(Matrix4f matrix, org.joml.Vector3f center) {
        Transform transform = new Transform();
        transform.setIdentity();
        transform.basis.set(basis(matrix));
        transform.origin.set(center.x, center.y, center.z);
        return transform;
    }

    private static Matrix3f basis(Matrix4f matrix) {
        // Bullet 要求正交旋转，即使捕获的层级包含非等比缩放或剪切。
        // 残余部分留在 boneToBody 中。
        org.joml.Vector3f x = new org.joml.Vector3f(matrix.m00(), matrix.m01(), matrix.m02()).normalize();
        org.joml.Vector3f y = new org.joml.Vector3f(matrix.m10(), matrix.m11(), matrix.m12());
        y.sub(new org.joml.Vector3f(x).mul(x.dot(y)))
            .normalize();
        org.joml.Vector3f z = new org.joml.Vector3f(x).cross(y)
            .normalize();
        return new Matrix3f(x.x, y.x, z.x, x.y, y.y, z.y, x.z, y.z, z.z);
    }

    private static Transform localFrame(Transform body, Matrix3f worldBasis, org.joml.Vector3f worldPoint) {
        Transform frame = new Transform();
        frame.setIdentity();
        Matrix3f inverseBasis = new Matrix3f(body.basis);
        inverseBasis.invert();
        frame.basis.mul(inverseBasis, worldBasis);
        Vector3f point = new Vector3f(worldPoint.x, worldPoint.y, worldPoint.z);
        point.sub(body.origin);
        inverseBasis.transform(point);
        frame.origin.set(point);
        return frame;
    }

    private static Transform copy(Transform source) {
        Transform copy = new Transform();
        copy.set(source);
        return copy;
    }

    private static AxisAlignedBB expandTowards(AxisAlignedBB box, double x, double y, double z) {
        double minX = Math.min(box.minX, box.minX + x);
        double minY = Math.min(box.minY, box.minY + y);
        double minZ = Math.min(box.minZ, box.minZ + z);
        double maxX = Math.max(box.maxX, box.maxX + x);
        double maxY = Math.max(box.maxY, box.maxY + y);
        double maxZ = Math.max(box.maxZ, box.maxZ + z);
        return AxisAlignedBB.getBoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static double square(double value) {
        return value * value;
    }

    private static float retention(float retainedPerSecond, float seconds) {
        return (float) Math.pow(retainedPerSecond, seconds);
    }

    private static float clampAngular(float value, float maximumMagnitude) {
        return Math.max(-maximumMagnitude, Math.min(maximumMagnitude, value));
    }

    private static float speedAlong(BodyPart part, float directionX, float directionZ) {
        Vector3f velocity = part.body.getLinearVelocity(new Vector3f());
        return velocity.x * directionX + velocity.z * directionZ;
    }

    private static RagdollDefinition.Role parentOf(RagdollDefinition.Role role) {
        switch (role) {
            case HEAD:
            case LEFT_UPPER_ARM:
            case RIGHT_UPPER_ARM:
            case LEFT_THIGH:
            case RIGHT_THIGH:
                return RagdollDefinition.Role.BODY;
            case LEFT_FOREARM:
                return RagdollDefinition.Role.LEFT_UPPER_ARM;
            case RIGHT_FOREARM:
                return RagdollDefinition.Role.RIGHT_UPPER_ARM;
            case LEFT_HAND:
                return RagdollDefinition.Role.LEFT_FOREARM;
            case RIGHT_HAND:
                return RagdollDefinition.Role.RIGHT_FOREARM;
            case LEFT_SHIN:
                return RagdollDefinition.Role.LEFT_THIGH;
            case RIGHT_SHIN:
                return RagdollDefinition.Role.RIGHT_THIGH;
            case LEFT_FOOT:
                return RagdollDefinition.Role.LEFT_SHIN;
            case RIGHT_FOOT:
                return RagdollDefinition.Role.RIGHT_SHIN;
            default:
                return null;
        }
    }

    private static AngularLimit limitFor(RagdollDefinition.Role role) {
        switch (role) {
            case HEAD:
                return symmetric(35, 25, 35);
            case LEFT_UPPER_ARM:
            case RIGHT_UPPER_ARM:
                return symmetric(75, 55, 75);
            case LEFT_FOREARM:
            case RIGHT_FOREARM:
                return oneAxis(145);
            case LEFT_HAND:
            case RIGHT_HAND:
                return symmetric(30, 20, 30);
            case LEFT_THIGH:
            case RIGHT_THIGH:
                return symmetric(55, 35, 55);
            case LEFT_SHIN:
            case RIGHT_SHIN:
                return oneAxis(150);
            case LEFT_FOOT:
            case RIGHT_FOOT:
                return symmetric(30, 20, 30);
            default:
                return symmetric(0, 0, 0);
        }
    }

    private static AngularLimit symmetric(float x, float y, float z) {
        return new AngularLimit(
            new Vector3f(-x * DEGREES, -y * DEGREES, -z * DEGREES),
            new Vector3f(x * DEGREES, y * DEGREES, z * DEGREES));
    }

    private static AngularLimit oneAxis(float bend) {
        return new AngularLimit(
            new Vector3f(-5 * DEGREES, -8 * DEGREES, -8 * DEGREES),
            new Vector3f(bend * DEGREES, 8 * DEGREES, 8 * DEGREES));
    }

    private static final class AngularLimit {

        final Vector3f lower;
        final Vector3f upper;

        AngularLimit(Vector3f lower, Vector3f upper) {
            this.lower = lower;
            this.upper = upper;
        }
    }

    private final class BodyPart {

        final RagdollDefinition.Part part;
        final RigidBody body;
        final Transform initialTransform;
        final Matrix4f boneToBody;
        final org.joml.Vector3f halfExtents;
        /** 碰撞盒中心相对刚体原点的局部偏移（已按骨骼姿态旋转前的模型空间）。 */
        final Vector3f centerOffsetLocal;

        BodyPart(RagdollDefinition.Part part, RigidBody body, Transform initialTransform, Matrix4f boneToBody,
            org.joml.Vector3f halfExtents, Vector3f centerOffsetLocal) {
            this.part = part;
            this.body = body;
            this.initialTransform = initialTransform;
            this.boneToBody = boneToBody;
            this.halfExtents = halfExtents;
            this.centerOffsetLocal = centerOffsetLocal;
        }

        RagdollDefinition.Role role() {
            return part.role();
        }
    }
}
