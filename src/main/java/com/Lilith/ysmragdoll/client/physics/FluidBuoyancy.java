package com.Lilith.ysmragdoll.client.physics;

import java.util.HashMap;
import java.util.Map;

import javax.vecmath.Vector3f;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.bulletphysics.dynamics.RigidBody;

/**
 * 按浸入体积计算每个刚体的浮力，液体单元在一个客户端 tick 内缓存。
 *
 * <p>
 * 1.7.10 没有 {@code VoxelShape}/{@code FluidState}，这里用
 * {@code Material.isLiquid()} 判定液体，用
 * {@code 1 - BlockLiquid.getLiquidHeightPercent(metadata)} 还原液面高度，
 * 这样源液体和流动液体的实际高度都会参与计算。
 * </p>
 */
final class FluidBuoyancy {

    private static final Cell EMPTY = new Cell(0.0F, false);

    private final Map<Long, Cell> cells = new HashMap<>();
    private final Vector3f offset = new Vector3f();
    private World world;

    void beginTick(World world, Vector3f offset) {
        this.world = world;
        this.offset.set(offset);
        cells.clear();
    }

    void clear() {
        world = null;
        cells.clear();
    }

    boolean apply(RigidBody body, float seconds, boolean grabbed) {
        if (world == null || body.getInvMass() <= 0) {
            return false;
        }
        Vector3f min = new Vector3f();
        Vector3f max = new Vector3f();
        body.getAabb(min, max);
        min.sub(offset);
        max.sub(offset);
        Wetness wetness = sample(min, max);
        apply(body, wetness, seconds, grabbed);
        return wetness.fraction > 0;
    }

    Wetness sample(Vector3f min, Vector3f max) {
        World currentWorld = world;
        if (currentWorld == null) {
            return new Wetness(0, 0);
        }
        double volume = (double) (max.x - min.x) * (max.y - min.y) * (max.z - min.z);
        if (volume <= 0) {
            return new Wetness(0, 0);
        }
        double submerged = 0;
        double lava = 0;
        int startX = MathHelper.floor_double(min.x);
        int startY = MathHelper.floor_double(min.y);
        int startZ = MathHelper.floor_double(min.z);
        int endX = MathHelper.floor_double(max.x);
        int endY = MathHelper.floor_double(max.y);
        int endZ = MathHelper.floor_double(max.z);
        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    Cell cell = cellAt(currentWorld, x, y, z);
                    if (cell.height <= 0) {
                        continue;
                    }
                    double overlap = Math.max(0, Math.min(max.x, x + 1) - Math.max(min.x, x))
                        * Math.max(0, Math.min(max.z, z + 1) - Math.max(min.z, z))
                        * Math.max(0, Math.min(max.y, y + cell.height) - Math.max(min.y, y));
                    submerged += overlap;
                    if (cell.lava) {
                        lava += overlap;
                    }
                }
            }
        }
        return new Wetness((float) Math.min(1, submerged / volume), (float) Math.min(1, lava / volume));
    }

    private Cell cellAt(World currentWorld, int x, int y, int z) {
        long key = key(x, y, z);
        Cell cached = cells.get(key);
        if (cached != null) {
            return cached;
        }
        Cell cell = read(currentWorld, x, y, z);
        cells.put(key, cell);
        return cell;
    }

    private Cell read(World currentWorld, int x, int y, int z) {
        if (!ClientPhysicsWorld.isChunkLoaded(currentWorld, x, z) || y < 0 || y > 255) {
            return EMPTY;
        }
        Block block = currentWorld.getBlock(x, y, z);
        Material material = block.getMaterial();
        if (material == null || !material.isLiquid()) {
            return EMPTY;
        }
        int metadata = currentWorld.getBlockMetadata(x, y, z);
        float height = 1.0F - BlockLiquid.getLiquidHeightPercent(metadata);
        if (height <= 0.0F) {
            return EMPTY;
        }
        return new Cell(Math.min(1.0F, height), material == Material.lava);
    }

    static void apply(RigidBody body, Wetness wetness, float seconds, boolean grabbed) {
        if (wetness.fraction <= 0 || seconds <= 0 || body.getInvMass() <= 0) {
            return;
        }
        body.activate(true);
        // 80% 浸入即可平衡重力；按质量缩放让很小的手和很重的躯干都能用同一套系数。
        Vector3f impulse = new Vector3f(0, 9.81F * 1.25F * wetness.fraction * seconds, 0);
        if (!grabbed) {
            float drag = 2.5F * wetness.fraction + 2.5F * wetness.lavaFraction;
            Vector3f velocity = body.getLinearVelocity(new Vector3f());
            velocity.scale((float) Math.expm1(-drag * seconds));
            impulse.add(velocity);
            Vector3f angular = body.getAngularVelocity(new Vector3f());
            angular.scale((float) Math.exp(-0.6F * drag * seconds));
            body.setAngularVelocity(angular);
        }
        impulse.scale(1 / body.getInvMass());
        body.applyCentralImpulse(impulse);
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static final class Wetness {

        final float fraction;
        final float lavaFraction;

        Wetness(float fraction, float lavaFraction) {
            this.fraction = fraction;
            this.lavaFraction = lavaFraction;
        }
    }

    private static final class Cell {

        final float height;
        final boolean lava;

        Cell(float height, boolean lava) {
            this.height = height;
            this.lava = lava;
        }
    }
}
