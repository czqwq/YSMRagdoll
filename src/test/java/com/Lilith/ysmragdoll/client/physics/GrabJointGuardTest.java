package com.Lilith.ysmragdoll.client.physics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
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
import com.bulletphysics.dynamics.constraintsolver.Generic6DofConstraint;
import com.bulletphysics.dynamics.constraintsolver.SequentialImpulseConstraintSolver;
import com.bulletphysics.dynamics.constraintsolver.TypedConstraint;
import com.bulletphysics.linearmath.DefaultMotionState;
import com.bulletphysics.linearmath.Transform;

public class GrabJointGuardTest {

    private static Transform pose(float x, float y, float z) {
        Transform transform = new Transform();
        transform.setIdentity();
        transform.origin.set(x, y, z);
        return transform;
    }

    private static DiscreteDynamicsWorld world() {
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

    private static RigidBody body(DiscreteDynamicsWorld world, float mass, Transform at, Vector3f half) {
        BoxShape shape = new BoxShape(half);
        Vector3f inertia = new Vector3f();
        if (mass > 0) {
            shape.calculateLocalInertia(mass, inertia);
        }
        RigidBody body = new RigidBody(new RigidBodyConstructionInfo(mass, new DefaultMotionState(at), shape, inertia));
        body.setCcdMotionThreshold(0.025F);
        body.setCcdSweptSphereRadius(0.08F);
        world.addRigidBody(body, (short) (mass > 0 ? 2 : 1), (short) (mass > 0 ? 1 : -1));
        return body;
    }

    @Test
    public void rapidHandPullsAndBlockedFeetKeepJointAnchorsTogether() {
        for (boolean wall : new boolean[] { false, true }) {
            DiscreteDynamicsWorld world = world();
            List<RigidBody> bodies = new ArrayList<>();
            List<TypedConstraint> joints = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                RigidBody part = body(
                    world,
                    i == 3 ? 5 : 0.35F,
                    pose(0, 5 - i * 0.5F, 0),
                    new Vector3f(0.1F, 0.25F, 0.1F));
                bodies.add(part);
                if (i == 0) {
                    continue;
                }
                Generic6DofConstraint joint = new Generic6DofConstraint(
                    bodies.get(i - 1),
                    part,
                    pose(0, -0.25F, 0),
                    pose(0, 0.25F, 0),
                    true);
                joint.setLinearLowerLimit(new Vector3f());
                joint.setLinearUpperLimit(new Vector3f());
                joint.setAngularLowerLimit(new Vector3f(-1, -1, -1));
                joint.setAngularUpperLimit(new Vector3f(1, 1, 1));
                world.addConstraint(joint, true);
                joints.add(joint);
            }
            if (wall) {
                body(world, 0, pose(2, 2, 0), new Vector3f(0.5F, 1, 3));
            }
            GrabJointGuard guard = new GrabJointGuard(world, joints);
            PhysicsGrab grab = new PhysicsGrab(
                bodies.get(0),
                new Vector3f(0.08F, 5.2F, 0),
                world::addConstraint,
                world::removeConstraint,
                () -> true,
                Vector3f::new,
                guard);
            float maxGap = 0;
            for (int frame = 0; frame < 900; frame++) {
                grab.moveTo(
                    wall ? new Vector3f((frame / 180 % 2 == 0) ? 8 : -8, 5.2F, 0)
                        : new Vector3f(
                            (frame / 45 % 2 == 0) ? 12 : -12,
                            (frame / 60 % 2 == 0) ? 9 : 2,
                            (frame / 30 % 2 == 0) ? -8 : 8));
                grab.beforeStep();
                world.stepSimulation(1F / 120, 0, 1F / 120);
                grab.afterStep();
                maxGap = Math.max(maxGap, guard.maximumError());
                for (RigidBody part : bodies) {
                    assertTrue(
                        Float.isFinite(
                            part.getLinearVelocity(new Vector3f())
                                .length()));
                }
            }
            assertTrue("关节不应出现可见分离: " + maxGap, maxGap < 0.02F);
            if (!wall) {
                float trackingError = 0;
                for (int frame = 0; frame < 720; frame++) {
                    float angle = frame * 2F / 120;
                    Vector3f target = new Vector3f(4 * (float) Math.cos(angle), 8, 4 * (float) Math.sin(angle));
                    grab.moveTo(target);
                    grab.beforeStep();
                    world.stepSimulation(1F / 120, 0, 1F / 120);
                    grab.afterStep();
                    Vector3f error = grab.anchor();
                    error.sub(target);
                    if (frame > 120) {
                        trackingError = Math.max(trackingError, error.length());
                    }
                    assertTrue(guard.maximumError() < 0.02F);
                }
                assertTrue("必须跟随移动准星而不是滞后数米: " + trackingError, trackingError < 0.25F);
            }
            Vector3f restingTarget = new Vector3f(-4, 7, 0);
            grab.moveTo(restingTarget);
            for (int i = 0; i < 720; i++) {
                grab.beforeStep();
                world.stepSimulation(1F / 120, 0, 1F / 120);
                grab.afterStep();
            }
            Vector3f heldError = grab.anchor();
            heldError.sub(restingTarget);
            assertTrue("仍然必须能整体托起并保持: " + heldError, heldError.length() < 0.15F);
            float height = bodies.get(0)
                .getWorldTransform(new Transform()).origin.y;
            grab.close();
            assertEquals(joints.size(), world.getNumConstraints());
            for (int i = 0; i < 120; i++) {
                world.stepSimulation(1F / 120, 0, 1F / 120);
            }
            assertTrue(
                bodies.get(0)
                    .getWorldTransform(new Transform()).origin.y < height);
            assertEquals(20, world.getSolverInfo().numIterations);
        }
    }

    @Test
    public void movingAndStoppingSwaysLimbsAndOnlyExplicitReleaseAddsCarryVelocity() {
        DiscreteDynamicsWorld world = world();
        RigidBody hand = body(world, 0.35F, pose(0, 5, 0), new Vector3f(0.1F, 0.25F, 0.1F));
        RigidBody torso = body(world, 5, pose(0, 4.5F, 0), new Vector3f(0.2F, 0.25F, 0.1F));
        Generic6DofConstraint joint = new Generic6DofConstraint(
            hand,
            torso,
            pose(0, -0.25F, 0),
            pose(0, 0.25F, 0),
            true);
        joint.setLinearLowerLimit(new Vector3f());
        joint.setLinearUpperLimit(new Vector3f());
        joint.setAngularLowerLimit(new Vector3f(-1, -1, -1));
        joint.setAngularUpperLimit(new Vector3f(1, 1, 1));
        world.addConstraint(joint, true);
        GrabJointGuard guard = new GrabJointGuard(world, Collections.<TypedConstraint>singletonList(joint));
        PhysicsGrab grab = new PhysicsGrab(
            hand,
            new Vector3f(0, 5.2F, 0),
            world::addConstraint,
            world::removeConstraint,
            () -> true,
            Vector3f::new,
            guard);
        float lag = 0;
        float swayAfterStop = 0;
        for (int step = 0; step < 180; step++) {
            grab.moveTo(new Vector3f(Math.min(step, 60) * 0.05F, 5.2F, 0));
            grab.beforeStep();
            world.stepSimulation(1F / 120, 0, 1F / 120);
            grab.afterStep();
            float relativeX = torso.getWorldTransform(new Transform()).origin.x
                - hand.getWorldTransform(new Transform()).origin.x;
            if (step < 60) {
                lag = Math.min(lag, relativeX);
            } else {
                swayAfterStop = Math.max(swayAfterStop, relativeX);
            }
            assertTrue(guard.maximumError() < 0.02F);
        }
        assertTrue("躯干必须滞后于被抓住的手", lag < -0.01F);
        assertTrue("急停后躯干必须继续摆动", swayAfterStop > 0.01F);
        for (int i = 0; i < 120; i++) {
            grab.moveTo(new Vector3f(3 + i * 0.05F, 5.2F, 0));
            grab.beforeStep();
            world.stepSimulation(1F / 120, 0, 1F / 120);
            grab.afterStep();
        }
        Vector3f beforeHand = hand.getLinearVelocity(new Vector3f());
        Vector3f beforeTorso = torso.getLinearVelocity(new Vector3f());
        grab.releaseWithInertia();
        Vector3f handChange = hand.getLinearVelocity(new Vector3f());
        handChange.sub(beforeHand);
        Vector3f torsoChange = torso.getLinearVelocity(new Vector3f());
        torsoChange.sub(beforeTorso);
        assertTrue(handChange.x > 1);
        assertTrue(handChange.epsilonEquals(torsoChange, 0.0001F));
        Vector3f releasedVelocity = hand.getLinearVelocity(new Vector3f());
        grab.releaseWithInertia();
        assertTrue(releasedVelocity.epsilonEquals(hand.getLinearVelocity(new Vector3f()), 0));
        assertEquals(1, world.getNumConstraints());

        PhysicsGrab cancelled = new PhysicsGrab(
            hand,
            hand.getWorldTransform(new Transform()).origin,
            world::addConstraint,
            world::removeConstraint,
            () -> true,
            Vector3f::new,
            guard);
        for (int i = 0; i < 30; i++) {
            cancelled.moveTo(new Vector3f(15 + i * 0.05F, 6, 0));
            cancelled.beforeStep();
            world.stepSimulation(1F / 120, 0, 1F / 120);
            cancelled.afterStep();
        }
        Vector3f beforeCancel = hand.getLinearVelocity(new Vector3f());
        cancelled.close();
        assertTrue(beforeCancel.epsilonEquals(hand.getLinearVelocity(new Vector3f()), 0));
    }

    @Test
    public void wholeAssemblyStopsWhenOnlyLowerLimbHitsWall() {
        DiscreteDynamicsWorld world = world();
        RigidBody upper = body(world, 1, pose(0, 3, 0), new Vector3f(0.1F, 0.1F, 0.1F));
        RigidBody lower = body(world, 1, pose(0, 1, 0), new Vector3f(0.1F, 0.1F, 0.1F));
        body(world, 0, pose(1, 1, 0), new Vector3f(0.2F, 0.3F, 1));
        Generic6DofConstraint joint = new Generic6DofConstraint(upper, lower, pose(0, -1, 0), pose(0, 1, 0), true);
        GrabJointGuard guard = new GrabJointGuard(world, Collections.<TypedConstraint>singletonList(joint));
        for (int i = 0; i < 10; i++) {
            guard.follow(upper, new Vector3f(2, 0, 0));
        }
        float lowerX = lower.getWorldTransform(new Transform()).origin.x;
        assertTrue(lowerX > 0.5F && lowerX <= 0.701F);
        assertEquals(lowerX, upper.getWorldTransform(new Transform()).origin.x, 0.0001F);
        assertEquals(0, guard.maximumError(), 0.0001F);
        assertEquals(
            0,
            upper.getLinearVelocity(new Vector3f())
                .length(),
            0);
        GrabJointGuard unloaded = new GrabJointGuard(
            world,
            Collections.<TypedConstraint>singletonList(joint),
            (bodies, movement) -> false);
        unloaded.follow(upper, new Vector3f(0, 5, 0));
        assertEquals(3, upper.getWorldTransform(new Transform()).origin.y, 0);
    }

    @Test
    public void correctionStopsAtWallWhenJoiningAnchorsWouldCrossIt() {
        DiscreteDynamicsWorld world = world();
        RigidBody a = body(world, 1, pose(0, 3, 0), new Vector3f(0.1F, 0.1F, 0.1F));
        RigidBody b = body(world, 1, pose(2, 3, 0), new Vector3f(0.1F, 0.1F, 0.1F));
        body(world, 0, pose(1, 3, 0), new Vector3f(0.2F, 1, 1));
        Generic6DofConstraint joint = new Generic6DofConstraint(a, b, pose(0, 0, 0), pose(0, 0, 0), true);
        GrabJointGuard guard = new GrabJointGuard(world, Collections.<TypedConstraint>singletonList(joint));
        guard.correct();
        assertTrue(a.getWorldTransform(new Transform()).origin.x <= 0.701F);
        assertTrue(b.getWorldTransform(new Transform()).origin.x >= 1.299F);
        assertTrue("受阻的修正必须保留误差而不是穿过墙体", guard.maximumError() > 0.59F);
    }

    @Test
    public void positionCorrectionDoesNotRotateBodiesAndRemovesSeparatingVelocity() {
        DiscreteDynamicsWorld world = world();
        RigidBody a = body(world, 1, pose(0, 3, 0), new Vector3f(0.1F, 0.1F, 0.1F));
        RigidBody b = body(world, 1, pose(0.2F, 3, 0), new Vector3f(0.1F, 0.1F, 0.1F));
        Generic6DofConstraint joint = new Generic6DofConstraint(a, b, pose(0, 0, 0), pose(0, 0, 0), true);
        a.setLinearVelocity(new Vector3f(-1, 0, 0));
        b.setLinearVelocity(new Vector3f(1, 0, 0));
        GrabJointGuard guard = new GrabJointGuard(world, Collections.<TypedConstraint>singletonList(joint));
        guard.correct();
        assertTrue(guard.maximumError() < 0.0011F);
        assertEquals(1, a.getWorldTransform(new Transform()).basis.m00, 0);
        assertEquals(1, b.getWorldTransform(new Transform()).basis.m11, 0);
        assertEquals(0, b.getLinearVelocity(new Vector3f()).x - a.getLinearVelocity(new Vector3f()).x, 0.001F);
    }
}
