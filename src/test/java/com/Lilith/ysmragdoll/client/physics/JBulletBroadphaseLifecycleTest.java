package com.Lilith.ysmragdoll.client.physics;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import javax.vecmath.Vector3f;

import org.junit.Test;

import com.bulletphysics.collision.broadphase.DbvtBroadphase;
import com.bulletphysics.collision.dispatch.CollisionDispatcher;
import com.bulletphysics.collision.dispatch.DefaultCollisionConfiguration;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.RigidBodyConstructionInfo;
import com.bulletphysics.dynamics.constraintsolver.SequentialImpulseConstraintSolver;
import com.bulletphysics.linearmath.DefaultMotionState;
import com.bulletphysics.linearmath.Transform;

/**
 * 复现“退出世界时 JBullet 宽相位 NPE”的调用序列。
 *
 * <p>
 * 崩溃栈是
 * {@code DbvtBroadphase#destroyProxy -> HashedOverlappingPairCache#removeOverlappingPairsContainingProxy
 * -> removeOverlappingPair -> last.pProxy0}。这里刻意按“先建立接触、再一次性移除整组刚体”
 * 的顺序反复执行同一路径，确认宽相位缓存不会在批量移除后留下空对。
 * </p>
 */
public class JBulletBroadphaseLifecycleTest {

    private static final int PARTS_PER_RAGDOLL = 14;
    private static final int RAGDOLLS = 9;
    private static final int GROUND_BODIES = 40;

    private DiscreteDynamicsWorld world() {
        DefaultCollisionConfiguration config = new DefaultCollisionConfiguration();
        DiscreteDynamicsWorld world = new DiscreteDynamicsWorld(
            new CollisionDispatcher(config),
            new DbvtBroadphase(),
            new SequentialImpulseConstraintSolver(),
            config);
        world.setGravity(new Vector3f(0, -9.81F, 0));
        world.getSolverInfo().numIterations = 20;
        return world;
    }

    private void ground(DiscreteDynamicsWorld world) {
        for (int index = 0; index < GROUND_BODIES; index++) {
            // 每具布娃娃只和静态组碰撞，因此地面必须也是静态刚体。
            box(world, (index % 8) * 1.0F - 4.0F, 0.0F, (index / 8) * 1.0F - 2.0F, 0.0F, (short) 1, (short) -1);
        }
    }

    private RigidBody box(DiscreteDynamicsWorld world, float x, float y, float z, float mass, short group, short mask) {
        BoxShape shape = new BoxShape(new Vector3f(0.35F, 0.35F, 0.35F));
        Vector3f inertia = new Vector3f();
        shape.calculateLocalInertia(mass, inertia);
        Transform transform = new Transform();
        transform.setIdentity();
        transform.origin.set(x, y, z);
        RigidBody body = new RigidBody(
            new RigidBodyConstructionInfo(mass, new DefaultMotionState(transform), shape, inertia));
        world.addRigidBody(body, group, mask);
        return body;
    }

    private List<RigidBody> spawnRagdoll(DiscreteDynamicsWorld world, float baseX) {
        List<RigidBody> bodies = new ArrayList<>(PARTS_PER_RAGDOLL);
        for (int index = 0; index < PARTS_PER_RAGDOLL; index++) {
            float offset = index * 0.25F;
            bodies.add(box(world, baseX + offset * 0.3F, 1.0F + offset, 0.0F, 1.0F, (short) 2, (short) 1));
        }
        return bodies;
    }

    private void step(DiscreteDynamicsWorld world, int steps) {
        for (int index = 0; index < steps; index++) {
            world.stepSimulation(1F / 120F, 0, 1F / 120F);
        }
    }

    @Test
    public void bulkRemovalOfPiledRagdollsKeepsPairCacheConsistent() {
        DiscreteDynamicsWorld world = world();
        ground(world);
        List<List<RigidBody>> groups = new ArrayList<>();
        for (int index = 0; index < RAGDOLLS; index++) {
            groups.add(spawnRagdoll(world, index * 0.6F - 2.0F));
        }
        // 让所有布娃娃真正压在方块上，制造大量重叠对。
        step(world, 240);
        assertEquals(GROUND_BODIES + RAGDOLLS * PARTS_PER_RAGDOLL, world.getNumCollisionObjects());

        // 模拟断开连接：整组整组地移除，中间不步进（这正是崩溃时的时序）。
        for (List<RigidBody> group : groups) {
            for (RigidBody body : group) {
                world.removeRigidBody(body);
            }
        }
        assertEquals(GROUND_BODIES, world.getNumCollisionObjects());
        step(world, 30);
        assertEquals(GROUND_BODIES, world.getNumCollisionObjects());
    }

    @Test
    public void repeatedSpawnAndRemovalCyclesStayConsistent() {
        DiscreteDynamicsWorld world = world();
        ground(world);
        for (int cycle = 0; cycle < 12; cycle++) {
            List<List<RigidBody>> groups = new ArrayList<>();
            for (int index = 0; index < RAGDOLLS; index++) {
                groups.add(spawnRagdoll(world, index * 0.6F - 2.0F));
            }
            step(world, 90);
            for (List<RigidBody> group : groups) {
                for (RigidBody body : group) {
                    world.removeRigidBody(body);
                }
            }
            step(world, 5);
            assertEquals(GROUND_BODIES, world.getNumCollisionObjects());
        }
    }
}
