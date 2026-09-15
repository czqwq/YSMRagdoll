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
import com.bulletphysics.linearmath.AabbUtil2;
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

    /**
     * 选取最近的肢体检体；调用方负责先用原版方块射线裁剪距离。
     *
     * <p>
     * 先做一次精确的 JBullet 射线查询。查询落空时不再直接判负，而是退回到
     * “射线附近最近的肢体”：求每个碰撞盒中心到视线所在直线的垂距，取垂距最小且在
     * {@link #GRAB_TOLERANCE} 以内、同时位于眼角前方的一段距离内的那个。这样即便
     * 射线长度退化成零（宽相位没有可用线段），或者准星只是擦着模型边缘过去，
     * 也能稳定选中正对着的那具布娃娃，而不是要求像素级对准。
     * </p>
     *
     * <p>
     * 兜底只影响“选中哪一个”，抓到之后依旧由
     * {@link PhysicsRagdoll#grab} 建立同样的点对点约束，手感与上游一致。
     * </p>
     */
    public PhysicsGrab grab(net.minecraft.util.Vec3 from, net.minecraft.util.Vec3 to) {
        return grab(from, to, distanceTo(from, to));
    }

    /**
     * 与 {@link #grab} 相同，但兜底选取按调用方给出的有效触及距离限制范围。
     *
     * <p>
     * 调用方传入的应该是"方块裁剪之后"的距离：这样隔着方块时兜底也不会抓到墙后的尸体。
     * 实际生效的上限再与线段长度取较小值，任何一方更严格都成立。
     * </p>
     */
    public PhysicsGrab grab(net.minecraft.util.Vec3 from, net.minecraft.util.Vec3 to, double reach) {
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
        RigidBody body = ray.hasHit() && ray.collisionObject instanceof RigidBody ? (RigidBody) ray.collisionObject
            : null;
        PhysicsRagdoll owner = body == null ? null : bodyOwners.get(body);
        boolean precise = body != null && owner != null;
        Vector3f hitPoint = precise ? ray.hitPointWorld : new Vector3f();
        // 精确射线落空时才启用兜底；把落空原因写进 detail，避免再出现
        // “精确命中 + 选中部位=无”这种自相矛盾的日志。
        String detail = precise ? "精确命中" : "精确射线未命中";
        if (!precise) {
            Target fallback = nearestTarget(start, end, reach);
            if (fallback != null) {
                body = fallback.body;
                owner = fallback.owner;
                hitPoint = fallback.point;
                detail = "回退到最近肢体(垂距=" + format(fallback.perpendicular) + ")";
            }
        }
        YsmRagdollLog.info(grabLog(start, end, precise, detail, owner, body, hitPoint));
        if (body == null || owner == null) {
            return null;
        }
        return owner.grab(body, hitPoint);
    }

    /** 一行诊断：区分“输入没进来”“射线没打中”“打中了但不是布娃娃刚体”三种情况。 */
    private String grabLog(Vector3f start, Vector3f end, boolean precise, String detail, PhysicsRagdoll owner,
        RigidBody body, Vector3f hitPoint) {
        return "牵引射线: 起点=" + point(start)
            + ", 终点="
            + point(end)
            + ", 长度="
            + format(distance(start, end))
            + ", "
            + detail
            + ", 选中部位="
            + (owner == null || body == null ? "无" : owner.roleNameOf(body) + "(区块已加载=" + owner.isChunkLoaded() + ')')
            + (body == null ? "" : ", 抓取锚点=" + point(hitPoint))
            + ", 世界刚体="
            + world.getNumCollisionObjects()
            + ", 布娃娃刚体="
            + bodyOwners.size()
            + ", AABB相交="
            + countBodiesNear(start, end);
    }

    private static double distance(Vector3f from, Vector3f to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double distanceTo(net.minecraft.util.Vec3 from, net.minecraft.util.Vec3 to) {
        double dx = to.xCoord - from.xCoord;
        double dy = to.yCoord - from.yCoord;
        double dz = to.zCoord - from.zCoord;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 视线附近最近的肢体。
     *
     * <p>
     * 命中点取“碰撞盒中心在视线上的投影”，并限制在盒半径以内，保证抓取锚点落在
     * 被选中的碰撞盒附近而不是任意远方。
     * </p>
     */
    private Target nearestTarget(Vector3f start, Vector3f end, double reach) {
        if (bodyOwners.isEmpty()) {
            return null;
        }
        double directionX = end.x - start.x;
        double directionY = end.y - start.y;
        double directionZ = end.z - start.z;
        double directionLengthSquared = directionX * directionX + directionY * directionY + directionZ * directionZ;
        if (directionLengthSquared < 1.0E-8D) {
            return null;
        }
        double directionLength = Math.sqrt(directionLengthSquared);
        directionX /= directionLength;
        directionY /= directionLength;
        directionZ /= directionLength;

        List<Candidate> candidates = new ArrayList<>();
        List<PhysicsRagdoll> owners = new ArrayList<>();
        for (PhysicsRagdoll ragdoll : ragdollOwners()) {
            if (!ragdoll.isChunkLoaded()) {
                continue;
            }
            List<PhysicsRagdoll.PartCenter> parts = ragdoll.partCenters();
            for (PhysicsRagdoll.PartCenter part : parts) {
                Candidate candidate = new Candidate(part.x(), part.y(), part.z(), part.radius());
                candidate.body = part.body();
                candidates.add(candidate);
                owners.add(ragdoll);
            }
        }
        // 上限取三者最小：实际线段长度、调用方给出的有效触及距离、硬上限。
        // 任一方更严格都成立，因此隔着方块时不会抓到墙后的尸体。
        double maximumAlong = Math.min(directionLength, Math.min(reach, MAX_TARGET_DISTANCE));
        int selected = selectNearest(
            candidates,
            start.x,
            start.y,
            start.z,
            directionX,
            directionY,
            directionZ,
            maximumAlong);
        if (selected < 0) {
            return null;
        }
        Candidate candidate = candidates.get(selected);
        double along = candidate.along;
        double anchorDistance = along * 0.5D;
        double distanceToCenter = Math.sqrt(candidate.offsetSquared);
        if (distanceToCenter > candidate.radius && distanceToCenter > 1.0E-6D) {
            // 锚点不能落到碰撞盒外：按超出量把落点往眼角方向拉回来。
            anchorDistance = Math.max(0.0D, anchorDistance - (distanceToCenter - candidate.radius));
        }
        Vector3f point = new Vector3f(
            (float) (start.x + directionX * anchorDistance),
            (float) (start.y + directionY * anchorDistance),
            (float) (start.z + directionZ * anchorDistance));
        return new Target(owners.get(selected), candidate.body, point, candidate.perpendicular);
    }

    /**
     * 纯几何选取：返回视线附近最近的一个候选碰撞盒，找不到返回 -1。
     *
     * <p>
     * 判定条件是“位于眼角前方的有限距离内”且“到视线的垂距不超过
     * {@link #GRAB_TOLERANCE}”，命中后取垂距最小者（并列时取更近的）。
     * 与 JBullet 状态无关，因此可以独立测试。
     * </p>
     */
    static int selectNearest(List<Candidate> candidates, double eyeX, double eyeY, double eyeZ, double directionX,
        double directionY, double directionZ, double rayLength) {
        if (candidates == null || candidates.isEmpty()) {
            return -1;
        }
        double directionLengthSquared = directionX * directionX + directionY * directionY + directionZ * directionZ;
        if (!(directionLengthSquared > 1.0E-8D)) {
            return -1;
        }
        double scale = 1.0D / Math.sqrt(directionLengthSquared);
        directionX *= scale;
        directionY *= scale;
        directionZ *= scale;
        // 上限取"调用方给出的有效距离"与硬上限的较小值；退化射线会传入 0，
        // 由调用方用触及距离兜住，这里不再自行放大，否则会绕过方块的遮挡裁剪。
        double maximumAlong = Math.min(Math.max(0.0D, rayLength), MAX_TARGET_DISTANCE);

        int best = -1;
        double bestPerpendicular = 0.0D;
        double bestAlong = 0.0D;
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            double offsetX = candidate.x - eyeX;
            double offsetY = candidate.y - eyeY;
            double offsetZ = candidate.z - eyeZ;
            double along = offsetX * directionX + offsetY * directionY + offsetZ * directionZ;
            if (along < 0.0D || along > maximumAlong) {
                continue;
            }
            double perpendicularSquared = offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ - along * along;
            if (!(perpendicularSquared >= 0.0D)) {
                continue;
            }
            double perpendicular = Math.sqrt(perpendicularSquared);
            if (perpendicular > GRAB_TOLERANCE) {
                continue;
            }
            if (best >= 0
                && (perpendicular > bestPerpendicular || (perpendicular == bestPerpendicular && along >= bestAlong))) {
                continue;
            }
            best = index;
            bestPerpendicular = perpendicular;
            bestAlong = along;
            candidate.perpendicular = perpendicular;
            candidate.along = along;
            candidate.offsetSquared = offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ;
        }
        return best;
    }

    /** 兜底选取只在这一段距离内生效，避免抓到视线之外的远方尸体。 */
    private static final double MAX_TARGET_DISTANCE = 12.0;
    /** 视线到碰撞盒中心的允许垂距；只要准星大致对着模型就能抓住。 */
    private static final double GRAB_TOLERANCE = 0.9;

    /** 去重后的布娃娃列表：同一具布娃娃的多个肢体共享一个 owner。 */
    private List<PhysicsRagdoll> ragdollOwners() {
        List<PhysicsRagdoll> owners = new ArrayList<>();
        for (PhysicsRagdoll owner : bodyOwners.values()) {
            if (!owners.contains(owner)) {
                owners.add(owner);
            }
        }
        return owners;
    }

    private static String point(Vector3f value) {
        return String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f)", value.x, value.y, value.z);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /** 诊断用：统计 AABB 与这段射线相交的布娃娃刚体数量。 */
    private int countBodiesNear(Vector3f from, Vector3f to) {
        Vector3f minimum = new Vector3f();
        Vector3f maximum = new Vector3f();
        int count = 0;
        for (RigidBody body : bodyOwners.keySet()) {
            body.getCollisionShape()
                .getAabb(body.getWorldTransform(new Transform()), minimum, maximum);
            if (AabbUtil2.rayAabb(from, to, minimum, maximum, new float[] { 1.0F }, new Vector3f())) {
                count++;
            }
        }
        return count;
    }

    /** 兜底选取的候选项：一个肢体碰撞盒的中心、外接球半径与所属刚体。 */
    static final class Candidate {

        final double x;
        final double y;
        final double z;
        final double radius;
        RigidBody body;
        double perpendicular;
        double along;
        double offsetSquared;

        Candidate(double x, double y, double z, double radius) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
        }
    }

    /** 一次兜底选取的结果。 */
    private static final class Target {

        final PhysicsRagdoll owner;
        final RigidBody body;
        final Vector3f point;
        final double perpendicular;

        Target(PhysicsRagdoll owner, RigidBody body, Vector3f point, double perpendicular) {
            this.owner = owner;
            this.body = body;
            this.point = point;
            this.perpendicular = perpendicular;
        }
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
