package com.Lilith.ysmragdoll.client.physics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.vecmath.Vector3f;

import net.minecraft.block.Block;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.RigidBodyConstructionInfo;
import com.bulletphysics.linearmath.DefaultMotionState;
import com.bulletphysics.linearmath.Transform;

/**
 * 只维护布娃娃附近的 Minecraft 方块碰撞体，并按几何结果增量复用刚体。
 *
 * <p>
 * 缓存逐 tick 扫描每具布娃娃周围的有限区域，不加载远处区块。1.7.10 没有
 * {@code VoxelShape}，改用 {@link Block#addCollisionBoxesToList}：楼梯、栅栏、
 * 台阶等一个方块会返回多个碰撞盒，和上游把体素形状拆成多个静态盒刚体的行为一致。
 * 方块刚放进布娃娃内部时额外保留二十 tick 的放置保护并持续把整套刚体推出。
 * </p>
 */
final class BlockCollisionCache {

    private static final int HORIZONTAL_RADIUS = 3;
    private static final int VERTICAL_RADIUS = 4;
    private static final int PLACEMENT_PROTECTION_TICKS = 20;

    private final ClientPhysicsWorld physicsWorld;
    private final Map<Long, Entry> entries = new HashMap<>();
    private final Set<Long> required = new HashSet<>();
    private final Set<Long> visited = new HashSet<>();
    private final List<AxisAlignedBB> newlyFilledBoxes = new ArrayList<>();
    private final List<AxisAlignedBB> protectedBoxes = new ArrayList<>();
    private final List<AxisAlignedBB> scratchBoxes = new ArrayList<>();
    private final List<PlacementProtection> placementProtections = new ArrayList<>();
    private final Vector3f worldOffset = new Vector3f();

    BlockCollisionCache(ClientPhysicsWorld physicsWorld, Vector3f initialWorldOffset) {
        this.physicsWorld = physicsWorld;
        this.worldOffset.set(initialWorldOffset);
    }

    void update(World world, Iterable<PhysicsRagdoll> ragdolls) {
        required.clear();
        visited.clear();
        for (PhysicsRagdoll ragdoll : ragdolls) {
            if (!ragdoll.isChunkLoaded()) {
                continue;
            }
            net.minecraft.util.Vec3 center = ragdoll.center();
            int originX = MathHelper.floor_double(center.xCoord - worldOffset.x);
            int originY = MathHelper.floor_double(center.yCoord - worldOffset.y);
            int originZ = MathHelper.floor_double(center.zCoord - worldOffset.z);
            scanRegion(world, originX, originY, originZ);
        }
        List<Long> stale = new ArrayList<>();
        for (Map.Entry<Long, Entry> entry : entries.entrySet()) {
            if (!required.contains(entry.getKey())) {
                stale.add(entry.getKey());
            }
        }
        for (Long key : stale) {
            Entry entry = entries.remove(key);
            if (entry != null) {
                remove(entry);
            }
        }
        for (AxisAlignedBB box : newlyFilledBoxes) {
            placementProtections.add(new PlacementProtection(box, PLACEMENT_PROTECTION_TICKS));
        }
        newlyFilledBoxes.clear();
        if (!placementProtections.isEmpty()) {
            protectedBoxes.clear();
            for (PlacementProtection protection : placementProtections) {
                protectedBoxes.add(protection.box);
            }
            for (PhysicsRagdoll ragdoll : ragdolls) {
                if (ragdoll.isChunkLoaded()) {
                    ragdoll.ejectAboveNewBlocks(protectedBoxes);
                }
            }
            List<PlacementProtection> expired = new ArrayList<>();
            for (PlacementProtection protection : placementProtections) {
                if (protection.tickExpired()) {
                    expired.add(protection);
                }
            }
            placementProtections.removeAll(expired);
        }
    }

    /** 快速牵引前刷新整个扫掠体积，即使它超出了上一 tick 的覆盖范围。 */
    boolean prepareSweep(World world, List<RigidBody> bodies, Vector3f movement) {
        if (world == null) {
            return false;
        }
        Set<Long> checked = new HashSet<>();
        Vector3f min = new Vector3f();
        Vector3f max = new Vector3f();
        for (RigidBody body : bodies) {
            body.getAabb(min, max);
            int startX = MathHelper.floor_double(min.x - worldOffset.x + Math.min(0, movement.x) - 0.05);
            int startY = MathHelper.floor_double(min.y - worldOffset.y + Math.min(0, movement.y) - 0.05);
            int startZ = MathHelper.floor_double(min.z - worldOffset.z + Math.min(0, movement.z) - 0.05);
            int endX = MathHelper.floor_double(max.x - worldOffset.x + Math.max(0, movement.x) + 0.05);
            int endY = MathHelper.floor_double(max.y - worldOffset.y + Math.max(0, movement.y) + 0.05);
            int endZ = MathHelper.floor_double(max.z - worldOffset.z + Math.max(0, movement.z) + 0.05);
            for (int x = startX; x <= endX; x++) {
                for (int y = startY; y <= endY; y++) {
                    for (int z = startZ; z <= endZ; z++) {
                        if (!checked.add(FluidBuoyancy.key(x, y, z))) {
                            continue;
                        }
                        if (!ClientPhysicsWorld.isChunkLoaded(world, x, z)) {
                            return false;
                        }
                        refresh(world, x, y, z);
                    }
                }
            }
        }
        return true;
    }

    /** 共享覆盖范围每个 update 只查询一次，然后再做方块碰撞查询。 */
    void scanRegion(World world, int originX, int originY, int originZ) {
        for (int x = -HORIZONTAL_RADIUS; x <= HORIZONTAL_RADIUS; x++) {
            for (int y = -VERTICAL_RADIUS; y <= VERTICAL_RADIUS; y++) {
                for (int z = -HORIZONTAL_RADIUS; z <= HORIZONTAL_RADIUS; z++) {
                    int blockX = originX + x;
                    int blockY = originY + y;
                    int blockZ = originZ + z;
                    long key = FluidBuoyancy.key(blockX, blockY, blockZ);
                    if (!visited.add(key) || !ClientPhysicsWorld.isChunkLoaded(world, blockX, blockZ)
                        || blockY < 0
                        || blockY > 255) {
                        continue;
                    }
                    required.add(key);
                    refresh(world, blockX, blockY, blockZ);
                }
            }
        }
    }

    /**
     * 刷新一个已加载的位置。
     *
     * <p>
     * 1.7.10 的方块碰撞盒可以随相邻方块变化（栅栏、墙、红石、门），因此这里每个
     * tick 都重新计算几何，只在几何完全相同时复用原有刚体。这样既不会出现陈旧碰撞，
     * 也保留了上游的增量复用收益。
     * </p>
     */
    void refresh(World world, int x, int y, int z) {
        long key = FluidBuoyancy.key(x, y, z);
        collectBoxes(world, x, y, z);
        Entry current = entries.get(key);
        if (current != null && sameBoxes(current.boxes, scratchBoxes)) {
            return;
        }
        if (current != null) {
            remove(current);
        }
        entries.put(key, create(x, y, z, scratchBoxes));
        if (current != null) {
            for (AxisAlignedBB box : scratchBoxes) {
                newlyFilledBoxes.add(box.getOffsetBoundingBox(worldOffset.x, worldOffset.y, worldOffset.z));
            }
        }
    }

    void clear() {
        for (Entry entry : entries.values()) {
            remove(entry);
        }
        entries.clear();
        required.clear();
        visited.clear();
        newlyFilledBoxes.clear();
        protectedBoxes.clear();
        placementProtections.clear();
    }

    /** 填充 {@link #scratchBoxes}，结果已经是世界坐标。 */
    private void collectBoxes(World world, int x, int y, int z) {
        scratchBoxes.clear();
        Block block = world.getBlock(x, y, z);
        if (block == null || block.getMaterial() == null) {
            return;
        }
        AxisAlignedBB mask = AxisAlignedBB.getBoundingBox(
            (double) x,
            (double) y,
            (double) z,
            (double) x + 1.0D,
            (double) y + 1.0D,
            (double) z + 1.0D);
        try {
            block.addCollisionBoxesToList(world, x, y, z, mask, scratchBoxes, null);
        } catch (RuntimeException exception) {
            // 个别第三方方块在 collider 为 null 时会抛异常；退回到单盒查询而不是让物理崩掉。
            scratchBoxes.clear();
            AxisAlignedBB fallback = block.getCollisionBoundingBoxFromPool(world, x, y, z);
            if (fallback != null) {
                scratchBoxes.add(fallback);
            }
        }
    }

    private Entry create(int x, int y, int z, List<AxisAlignedBB> boxes) {
        List<AxisAlignedBB> stored = new ArrayList<>(boxes.size());
        List<RigidBody> bodies = new ArrayList<>(boxes.size());
        for (AxisAlignedBB box : boxes) {
            stored.add(copy(box));
            float halfX = (float) Math.max(0.001, (box.maxX - box.minX) * 0.5D);
            float halfY = (float) Math.max(0.001, (box.maxY - box.minY) * 0.5D);
            float halfZ = (float) Math.max(0.001, (box.maxZ - box.minZ) * 0.5D);
            BoxShape shape = new BoxShape(new Vector3f(halfX, halfY, halfZ));
            shape.setMargin(0.01F);
            Transform transform = new Transform();
            transform.setIdentity();
            transform.origin.set(
                (float) ((box.minX + box.maxX) * 0.5D),
                (float) ((box.minY + box.maxY) * 0.5D),
                (float) ((box.minZ + box.maxZ) * 0.5D));
            transform.origin.add(worldOffset);
            RigidBody body = new RigidBody(
                new RigidBodyConstructionInfo(0.0F, new DefaultMotionState(transform), shape, new Vector3f()));
            body.setFriction(0.9F);
            body.setRestitution(0.0F);
            physicsWorld.addStaticBody(body);
            bodies.add(body);
        }
        return new Entry(stored, bodies);
    }

    private void remove(Entry entry) {
        for (RigidBody body : entry.bodies) {
            physicsWorld.removeBody(body);
        }
    }

    /** 设置变化时同步平移已缓存的方块碰撞体，新缓存也会使用更新后的偏移。 */
    void translateWorld(Vector3f delta) {
        if (delta.lengthSquared() < 1.0E-10F) {
            return;
        }
        worldOffset.add(delta);
        placementProtections.clear();
        for (Entry entry : entries.values()) {
            for (RigidBody body : entry.bodies) {
                Transform transform = body.getWorldTransform(new Transform());
                transform.origin.add(delta);
                body.setWorldTransform(transform);
                if (body.getMotionState() != null) {
                    body.getMotionState()
                        .setWorldTransform(transform);
                }
                physicsWorld.updateBodyAabb(body);
            }
        }
    }

    private static boolean sameBoxes(List<AxisAlignedBB> first, List<AxisAlignedBB> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            AxisAlignedBB a = first.get(index);
            AxisAlignedBB b = second.get(index);
            if (a.minX != b.minX || a.minY != b.minY
                || a.minZ != b.minZ
                || a.maxX != b.maxX
                || a.maxY != b.maxY
                || a.maxZ != b.maxZ) {
                return false;
            }
        }
        return true;
    }

    private static AxisAlignedBB copy(AxisAlignedBB box) {
        return AxisAlignedBB.getBoundingBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static final class Entry {

        final List<AxisAlignedBB> boxes;
        final List<RigidBody> bodies;

        Entry(List<AxisAlignedBB> boxes, List<RigidBody> bodies) {
            this.boxes = boxes;
            this.bodies = bodies;
        }
    }

    private static final class PlacementProtection {

        final AxisAlignedBB box;
        private int remainingTicks;

        PlacementProtection(AxisAlignedBB box, int remainingTicks) {
            this.box = box;
            this.remainingTicks = remainingTicks;
        }

        boolean tickExpired() {
            return --remainingTicks <= 0;
        }
    }
}
