package com.Lilith.ysmragdoll.client.physics;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.vecmath.Vector3f;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.Lilith.ysmragdoll.YsmRagdollLog;
import com.Lilith.ysmragdoll.config.YsmRagdollConfig;
import com.bulletphysics.collision.broadphase.DbvtBroadphase;
import com.bulletphysics.collision.dispatch.CollisionDispatcher;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.dispatch.DefaultCollisionConfiguration;
import com.bulletphysics.collision.narrowphase.ManifoldPoint;
import com.bulletphysics.collision.narrowphase.PersistentManifold;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.constraintsolver.SequentialImpulseConstraintSolver;
import com.bulletphysics.dynamics.constraintsolver.TypedConstraint;
import com.bulletphysics.linearmath.Transform;

/**
 * 当前客户端世界中所有布娃娃共享的 JBullet 求解器。
 *
 * <p>
 * 物理时间由渲染帧累积并以 120 Hz 固定子步推进，单帧最多追赶八步；附近的
 * 方块碰撞盒被转换成静态盒刚体。布娃娃刚体只和方块组碰撞，肢体之间不做自碰撞，
 * 关系完全交给关节。深穿透纠正以整具布娃娃为单位上移，防止单个肢体被推出后
 * 由关节储存并释放异常弹跳能量。
 * </p>
 */
public final class ClientPhysicsWorld {

    private static final float FIXED_STEP_SECONDS = 1.0F / 120.0F;
    private static final int MAX_FRAME_SUBSTEPS = 8;
    private static final double MAX_FRAME_SECONDS = 0.05;
    private static final float DEEP_PENETRATION_THRESHOLD = 0.002F;
    private static final float RETAINED_PENETRATION = 0.001F;
    private static final float MAX_GROUND_CORRECTION_PER_STEP = 0.035F;
    /** 方块填入布娃娃所在坑内后，下一次客户端 tick 就应加入新的静态碰撞体。 */
    private static final int COLLISION_REFRESH_TICKS = 1;

    private final DiscreteDynamicsWorld world;
    private final CollisionDispatcher dispatcher;
    private final BlockCollisionCache blockCollisions;
    private final FluidBuoyancy fluids = new FluidBuoyancy();
    private final Map<RigidBody, PhysicsRagdoll> bodyOwners = new IdentityHashMap<>();
    private final Vector3f appliedWorldOffset;
    private World currentWorld;
    private int tickCounter;
    private long lastFrameNanos;
    private double frameAccumulatorSeconds;
    private EntityPlayer collisionPlayer;
    private BoxShape playerShape;
    private final Transform previousPlayer = new Transform();
    private final Transform currentPlayer = new Transform();
    private final Vector3f playerVelocity = new Vector3f();
    private final Vector3f playerHalfExtents = new Vector3f();

    public ClientPhysicsWorld() {
        DefaultCollisionConfiguration configuration = new DefaultCollisionConfiguration();
        dispatcher = new CollisionDispatcher(configuration);
        world = new DiscreteDynamicsWorld(
            dispatcher,
            new DbvtBroadphase(),
            new SequentialImpulseConstraintSolver(),
            configuration);
        world.setGravity(new Vector3f(0.0F, -9.81F, 0.0F));
        world.getSolverInfo().numIterations = 20;
        world.getSolverInfo().splitImpulse = true;
        world.getSolverInfo().splitImpulsePenetrationThreshold = -0.005F;
        appliedWorldOffset = configuredOffset();
        blockCollisions = new BlockCollisionCache(this, appliedWorldOffset);
    }

    public static boolean isChunkLoaded(World world, int x, int z) {
        if (world == null) {
            return false;
        }
        return world.getChunkProvider() != null && world.getChunkProvider()
            .chunkExists(x >> 4, z >> 4);
    }

    public void tick(World world, EntityPlayer localPlayer, List<PhysicsRagdoll> ragdolls) {
        currentWorld = world;
        updateWorldOffset(ragdolls);
        fluids.beginTick(world, appliedWorldOffset);
        for (PhysicsRagdoll ragdoll : ragdolls) {
            ragdoll.updateChunkLoaded(world);
        }
        if (tickCounter++ % COLLISION_REFRESH_TICKS == 0) {
            blockCollisions.update(world, ragdolls);
        }
        for (PhysicsRagdoll ragdoll : ragdolls) {
            if (ragdoll.isChunkLoaded()) {
                ragdoll.applyPlayerPush(localPlayer);
            }
        }
        updatePlayerCollider(localPlayer, ragdolls);
    }

    /** 按渲染帧累计时间，以 120 Hz 固定子步推进物理并更新骨骼姿态。 */
    public void simulateFrame(List<PhysicsRagdoll> ragdolls) {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return;
        }
        double elapsedSeconds = Math.min(MAX_FRAME_SECONDS, Math.max(0.0, (now - lastFrameNanos) * 1.0E-9));
        lastFrameNanos = now;
        frameAccumulatorSeconds = Math
            .min(FIXED_STEP_SECONDS * MAX_FRAME_SUBSTEPS, frameAccumulatorSeconds + elapsedSeconds);

        int steps = 0;
        while (frameAccumulatorSeconds + 1.0E-9 >= FIXED_STEP_SECONDS && steps < MAX_FRAME_SUBSTEPS) {
            // 玩家接触在 tick() 中按玩家真实位移解决一次。这里每个渲染追赶子步
            // 重复整组平移会把一个 tick 放大成一次弹射，因此不再重复。
            for (PhysicsRagdoll ragdoll : ragdolls) {
                ragdoll.beforeGrabStep();
                ragdoll.applyFluidForces(fluids, FIXED_STEP_SECONDS);
            }
            world.stepSimulation(FIXED_STEP_SECONDS, 0, FIXED_STEP_SECONDS);
            resolveBlockPenetration();
            for (PhysicsRagdoll ragdoll : ragdolls) {
                if (ragdoll.isChunkLoaded()) {
                    ragdoll.afterGrabStep();
                    ragdoll.suppressPlayerPushLift();
                    ragdoll.biasTowardSideRoll(FIXED_STEP_SECONDS);
                }
            }
            frameAccumulatorSeconds -= FIXED_STEP_SECONDS;
            steps++;
        }
        if (steps > 0) {
            for (PhysicsRagdoll ragdoll : ragdolls) {
                if (ragdoll.isChunkLoaded()) {
                    ragdoll.writePose();
                }
            }
        }
    }

    /** 暂停或切换界面后丢弃时间积压，避免恢复时物理瞬间追帧。 */
    public void resetFrameClock() {
        lastFrameNanos = 0L;
        frameAccumulatorSeconds = 0.0;
    }

    /**
     * Bullet 的离散求解在高速推动或关节纠正时可能留下极小的方块穿透。
     * 这里只处理动态布娃娃与静态方块的接触，并沿接触法线推出刚体；
     * 布娃娃刚体之间没有碰撞组，因此不会破坏关节解算。
     */
    private void resolveBlockPenetration() {
        Map<PhysicsRagdoll, Float> groundCorrections = new IdentityHashMap<>();
        int manifoldCount = dispatcher.getNumManifolds();
        for (int index = 0; index < manifoldCount; index++) {
            PersistentManifold manifold = dispatcher.getManifoldByIndexInternal(index);
            CollisionObject objectA = (CollisionObject) manifold.getBody0();
            CollisionObject objectB = (CollisionObject) manifold.getBody1();
            boolean staticA = objectA.isStaticObject();
            boolean staticB = objectB.isStaticObject();
            if (staticA == staticB) {
                continue;
            }
            RigidBody dynamic = staticA ? (RigidBody) objectB : (RigidBody) objectA;
            ManifoldPoint deepestContact = null;
            float deepestPenetration = DEEP_PENETRATION_THRESHOLD;
            for (int contactIndex = 0; contactIndex < manifold.getNumContacts(); contactIndex++) {
                ManifoldPoint contact = manifold.getContactPoint(contactIndex);
                float penetration = -contact.distance1;
                if (penetration > deepestPenetration) {
                    deepestPenetration = penetration;
                    deepestContact = contact;
                }
            }
            if (deepestContact == null) {
                continue;
            }
            Vector3f separationNormal = new Vector3f(deepestContact.normalWorldOnB);
            if (staticA) {
                separationNormal.negate();
            }
            // 用接触法线而不是两个形状的中心差来判断地面：又高又长的躯干或肢体
            // 完全可能中心低于方块中心，而它的下表面正贴在方块顶面上。
            if (separationNormal.y < 0.55F) {
                continue;
            }
            float correction = Math
                .min(MAX_GROUND_CORRECTION_PER_STEP, Math.max(0.0F, deepestPenetration - RETAINED_PENETRATION));
            PhysicsRagdoll owner = bodyOwners.get(dynamic);
            // 去除仍然朝方块内部的速度，保留沿表面滑动和反弹的分量。
            Vector3f velocity = dynamic.getLinearVelocity(new Vector3f());
            float inwardSpeed = velocity.dot(separationNormal);
            boolean needsWake = inwardSpeed < -0.005F || correction > 0.0005F;
            if (inwardSpeed < -0.005F) {
                velocity.scaleAdd(-inwardSpeed, separationNormal, velocity);
                dynamic.setLinearVelocity(velocity);
            }
            if (needsWake) {
                dynamic.activate(true);
            }
            if (owner != null) {
                float verticalCorrection = Math
                    .min(MAX_GROUND_CORRECTION_PER_STEP, correction / Math.max(0.55F, separationNormal.y));
                Float existing = groundCorrections.get(owner);
                groundCorrections
                    .put(owner, existing == null ? verticalCorrection : Math.max(existing, verticalCorrection));
            }
        }
        for (Map.Entry<PhysicsRagdoll, Float> entry : groundCorrections.entrySet()) {
            entry.getKey()
                .correctGroundPenetration(entry.getValue());
        }
    }

    /** 显式使用静态方块碰撞组，避免过滤设置改变后地面被误排除。 */
    void addStaticBody(RigidBody body) {
        world.addRigidBody(body, (short) 1, (short) -1);
    }

    /** 布娃娃自身不互相碰撞，避免重叠的模型包围盒与锁定关节产生持续抖动。 */
    void addRagdollBody(RigidBody body, PhysicsRagdoll owner) {
        world.addRigidBody(body, (short) 2, (short) 1);
        bodyOwners.put(body, owner);
    }

    /** 选取最近的肢体检体；调用方负责先用原版方块射线裁剪距离。 */
    public PhysicsGrab grab(net.minecraft.util.Vec3 from, net.minecraft.util.Vec3 to) {
        Vector3f start = new Vector3f((float) from.xCoord, (float) from.yCoord, (float) from.zCoord);
        Vector3f end = new Vector3f((float) to.xCoord, (float) to.yCoord, (float) to.zCoord);
        start.add(appliedWorldOffset);
        end.add(appliedWorldOffset);
        com.bulletphysics.collision.dispatch.CollisionWorld.ClosestRayResultCallback ray = new com.bulletphysics.collision.dispatch.CollisionWorld.ClosestRayResultCallback(
            start,
            end);
        ray.collisionFilterGroup = 1;
        ray.collisionFilterMask = 2;
        world.rayTest(start, end, ray);
        if (!ray.hasHit() || !(ray.collisionObject instanceof RigidBody)) {
            return null;
        }
        RigidBody body = (RigidBody) ray.collisionObject;
        PhysicsRagdoll owner = bodyOwners.get(body);
        return owner == null ? null : owner.grab(body, ray.hitPointWorld);
    }

    void removeBody(RigidBody body) {
        bodyOwners.remove(body);
        world.removeRigidBody(body);
    }

    /** 碰撞形状内部偏移改变后，立即刷新该刚体在宽相位中的范围。 */
    void updateBodyAabb(RigidBody body) {
        world.updateSingleAabb(body);
    }

    Vector3f currentWorldOffset(Vector3f destination) {
        destination.set(appliedWorldOffset);
        return destination;
    }

    GrabJointGuard grabJointGuard(List<TypedConstraint> constraints) {
        return new GrabJointGuard(
            world,
            constraints,
            (bodies, movement) -> currentWorld != null && blockCollisions.prepareSweep(currentWorld, bodies, movement));
    }

    void addConstraint(TypedConstraint constraint) {
        world.addConstraint(constraint, true);
    }

    void removeConstraint(TypedConstraint constraint) {
        world.removeConstraint(constraint);
    }

    public void clear() {
        currentWorld = null;
        fluids.clear();
        blockCollisions.clear();
        bodyOwners.clear();
        collisionPlayer = null;
        playerShape = null;
    }

    private void updatePlayerCollider(EntityPlayer player, List<PhysicsRagdoll> ragdolls) {
        if (YsmRagdollConfig.easyPushIndex() != 100 || player == null || !player.isEntityAlive()) {
            collisionPlayer = null;
            playerShape = null;
            return;
        }
        AxisAlignedBB bounds = player.boundingBox;
        Vector3f half = new Vector3f(
            (float) (bounds.maxX - bounds.minX) * 0.5F,
            (float) (bounds.maxY - bounds.minY) * 0.5F,
            (float) (bounds.maxZ - bounds.minZ) * 0.5F);
        boolean continuous = collisionPlayer == player && playerShape != null
            && half.epsilonEquals(playerHalfExtents, 0.0001F);
        previousPlayer.set(currentPlayer);
        currentPlayer.setIdentity();
        currentPlayer.origin.set(
            (float) ((bounds.minX + bounds.maxX) * 0.5D),
            (float) ((bounds.minY + bounds.maxY) * 0.5D),
            (float) ((bounds.minZ + bounds.maxZ) * 0.5D));
        currentPlayer.origin.add(appliedWorldOffset);
        if (!continuous) {
            playerHalfExtents.set(half);
            playerShape = new BoxShape(half);
            playerShape.setMargin(0.0F);
        }
        collisionPlayer = player;
        playerVelocity.sub(currentPlayer.origin, previousPlayer.origin);
        // 大的位置跳变是传送，不是穿过中间尸体的运动。
        if (!continuous || playerVelocity.lengthSquared() > 64.0F) {
            previousPlayer.set(currentPlayer);
            playerVelocity.set(0.0F, 0.0F, 0.0F);
            continuous = false;
        } else {
            playerVelocity.scale(20.0F);
        }
        for (PhysicsRagdoll ragdoll : ragdolls) {
            enforcePlayerCollision(ragdoll, continuous);
        }
    }

    private void enforcePlayerCollision(PhysicsRagdoll ragdoll, boolean sweep) {
        if (playerShape != null && YsmRagdollConfig.easyPushIndex() == 100) {
            ragdoll.enforcePlayerCollision(playerShape, previousPlayer, currentPlayer, playerVelocity, sweep);
        }
    }

    private void updateWorldOffset(List<PhysicsRagdoll> ragdolls) {
        Vector3f requested = configuredOffset();
        Vector3f delta = new Vector3f(requested);
        delta.sub(appliedWorldOffset);
        if (delta.lengthSquared() < 1.0E-10F) {
            return;
        }
        blockCollisions.translateWorld(delta);
        for (PhysicsRagdoll ragdoll : ragdolls) {
            ragdoll.translateCollisionWorld(delta);
        }
        appliedWorldOffset.set(requested);
        if (playerShape != null) {
            previousPlayer.origin.add(delta);
            currentPlayer.origin.add(delta);
        }
        YsmRagdollLog.info("应用物理世界偏移: X=" + requested.x + ", Y=" + requested.y + ", Z=" + requested.z);
    }

    private static Vector3f configuredOffset() {
        return new Vector3f(
            (float) YsmRagdollConfig.collisionOffsetX(),
            (float) YsmRagdollConfig.collisionOffsetY(),
            (float) YsmRagdollConfig.collisionOffsetZ());
    }

    /** 供物理调试与旧代码使用的坐标辅助。 */
    static int floor(double value) {
        return MathHelper.floor_double(value);
    }

    static List<RigidBody> bodyList() {
        return new ArrayList<>();
    }
}
