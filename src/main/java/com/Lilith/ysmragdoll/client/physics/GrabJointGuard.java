package com.Lilith.ysmragdoll.client.physics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BiPredicate;

import javax.vecmath.Vector3f;

import com.bulletphysics.collision.dispatch.CollisionWorld;
import com.bulletphysics.collision.shapes.ConvexShape;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.constraintsolver.Generic6DofConstraint;
import com.bulletphysics.dynamics.constraintsolver.TypedConstraint;
import com.bulletphysics.linearmath.Transform;

/** 只在牵引期间修正关节锚点的位置，从不改写关节角度或刚体朝向。 */
final class GrabJointGuard {

    private static final float TOLERANCE = 0.001F;
    private static final int MAX_PASSES = 32;
    private static final float MAX_CORRECTION = 0.04F;
    /** 固定 120 Hz 子步：快速追上，但不给轻肢体注入大速度。 */
    private static final float FOLLOW_FRACTION = 0.65F;
    private static final float MAX_FOLLOW_STEP = 0.6F;

    private final DiscreteDynamicsWorld world;
    private final List<Generic6DofConstraint> joints;
    private final List<RigidBody> bodies;
    private final BiPredicate<List<RigidBody>, Vector3f> prepareSweep;

    GrabJointGuard(DiscreteDynamicsWorld world, List<TypedConstraint> constraints) {
        this(world, constraints, (bodies, movement) -> true);
    }

    GrabJointGuard(DiscreteDynamicsWorld world, List<TypedConstraint> constraints,
        BiPredicate<List<RigidBody>, Vector3f> prepareSweep) {
        this.world = world;
        this.prepareSweep = prepareSweep;
        List<Generic6DofConstraint> found = new ArrayList<>();
        for (TypedConstraint constraint : constraints) {
            if (constraint instanceof Generic6DofConstraint) {
                found.add((Generic6DofConstraint) constraint);
            }
        }
        joints = found;
        LinkedHashSet<RigidBody> connected = new LinkedHashSet<>();
        for (Generic6DofConstraint joint : joints) {
            connected.add(joint.getRigidBodyA());
            connected.add(joint.getRigidBodyB());
        }
        bodies = new ArrayList<>(connected);
    }

    /** 共同位置修正保持关节距离不变；只返回真正被接受的位移。 */
    Vector3f follow(RigidBody grabbed, Vector3f error) {
        Vector3f movement = new Vector3f(error);
        movement.scale(FOLLOW_FRACTION);
        float distance = movement.length();
        if (distance < 0.0001F) {
            return new Vector3f();
        }
        if (distance > MAX_FOLLOW_STEP) {
            movement.scale(MAX_FOLLOW_STEP / distance);
        }
        List<RigidBody> assembly = bodies.isEmpty() ? single(grabbed) : bodies;
        if (!prepareSweep.test(assembly, movement)) {
            return new Vector3f();
        }
        float fraction = 1;
        for (RigidBody part : assembly) {
            fraction = Math.min(fraction, safeFraction(part, movement));
        }
        if (fraction <= 0) {
            return new Vector3f();
        }
        movement.scale(fraction);
        for (RigidBody part : assembly) {
            translate(part, movement);
        }
        return movement;
    }

    void applyInertia(RigidBody grabbed, Vector3f velocityChange) {
        for (RigidBody part : bodies) {
            if (part == grabbed || part.getInvMass() <= 0) {
                continue;
            }
            Vector3f impulse = new Vector3f(velocityChange);
            impulse.scale(1 / part.getInvMass());
            part.applyCentralImpulse(impulse);
            part.activate(true);
        }
    }

    void releaseWithVelocity(RigidBody grabbed, Vector3f carryVelocity) {
        List<RigidBody> assembly = bodies.isEmpty() ? single(grabbed) : bodies;
        Vector3f mean = new Vector3f();
        float mass = 0;
        for (RigidBody part : assembly) {
            if (part.getInvMass() <= 0) {
                continue;
            }
            float partMass = 1 / part.getInvMass();
            mean.scaleAdd(partMass, part.getLinearVelocity(new Vector3f()), mean);
            mass += partMass;
        }
        if (mass == 0) {
            return;
        }
        mean.scale(1 / mass);
        Vector3f delta = new Vector3f(mean);
        delta.add(carryVelocity);
        GrabInertia.limit(delta, 6);
        delta.sub(mean);
        // 共同速度变化在松手时保持相对关节速度。
        for (RigidBody part : assembly) {
            if (part.getInvMass() <= 0) {
                continue;
            }
            Vector3f velocity = part.getLinearVelocity(new Vector3f());
            velocity.add(delta);
            part.setLinearVelocity(velocity);
            part.activate(true);
        }
    }

    float maximumError() {
        float maximum = 0;
        for (Generic6DofConstraint joint : joints) {
            maximum = Math.max(maximum, separation(joint).length());
        }
        return maximum;
    }

    void correct() {
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean moved = false;
            for (Generic6DofConstraint joint : joints) {
                Vector3f delta = separation(joint);
                float gap = delta.length();
                if (gap <= TOLERANCE) {
                    continue;
                }
                RigidBody a = joint.getRigidBodyA();
                RigidBody b = joint.getRigidBodyB();
                float inverseSum = a.getInvMass() + b.getInvMass();
                if (inverseSum <= 0) {
                    continue;
                }
                Vector3f normal = new Vector3f(delta);
                normal.scale(1 / gap);
                float correction = Math.min(MAX_CORRECTION, gap - TOLERANCE);
                Vector3f move = new Vector3f(normal);
                move.scale(correction * a.getInvMass() / inverseSum);
                moved |= translateSafely(a, move);
                move.set(normal);
                move.scale(-correction * b.getInvMass() / inverseSum);
                moved |= translateSafely(b, move);
                removeSeparatingVelocity(joint, normal);
            }
            if (!moved) {
                break;
            }
        }
    }

    private Vector3f separation(Generic6DofConstraint joint) {
        joint.calculateTransforms();
        Vector3f delta = joint.getCalculatedTransformB(new Transform()).origin;
        delta.sub(joint.getCalculatedTransformA(new Transform()).origin);
        return delta;
    }

    private void removeSeparatingVelocity(Generic6DofConstraint joint, Vector3f normal) {
        RigidBody a = joint.getRigidBodyA();
        RigidBody b = joint.getRigidBodyB();
        Vector3f pivotA = joint.getFrameOffsetA(new Transform()).origin;
        Vector3f pivotB = joint.getFrameOffsetB(new Transform()).origin;
        a.getWorldTransform(new Transform()).basis.transform(pivotA);
        b.getWorldTransform(new Transform()).basis.transform(pivotB);
        Vector3f relative = b.getVelocityInLocalPoint(pivotB, new Vector3f());
        relative.sub(a.getVelocityInLocalPoint(pivotA, new Vector3f()));
        float separating = relative.dot(normal);
        if (separating <= 0) {
            return;
        }
        Vector3f pointA = new Vector3f(pivotA);
        pointA.add(a.getWorldTransform(new Transform()).origin);
        Vector3f pointB = new Vector3f(pivotB);
        pointB.add(b.getWorldTransform(new Transform()).origin);
        float denominator = a.computeImpulseDenominator(pointA, normal) + b.computeImpulseDenominator(pointB, normal);
        // 计入角向等效质量：只修正线速度会把快速旋转转成额外平移而失稳。
        if (denominator <= 0) {
            return;
        }
        Vector3f impulse = new Vector3f(normal);
        impulse.scale(separating / denominator);
        a.applyImpulse(impulse, pivotA);
        impulse.negate();
        b.applyImpulse(impulse, pivotB);
    }

    private boolean translateSafely(RigidBody body, Vector3f movement) {
        if (movement.lengthSquared() < 1.0E-10F || body.getInvMass() == 0) {
            return false;
        }
        movement.scale(safeFraction(body, movement));
        if (movement.lengthSquared() < 1.0E-10F) {
            return false;
        }
        translate(body, movement);
        return true;
    }

    private float safeFraction(RigidBody body, Vector3f movement) {
        if (body.getInvMass() == 0 || !(body.getCollisionShape() instanceof ConvexShape)) {
            return 0;
        }
        ConvexShape shape = (ConvexShape) body.getCollisionShape();
        Transform from = body.getWorldTransform(new Transform());
        Transform to = new Transform(from);
        to.origin.add(movement);
        CollisionWorld.ClosestConvexResultCallback hit = new CollisionWorld.ClosestConvexResultCallback(
            from.origin,
            to.origin) {

            @Override
            public float addSingleResult(CollisionWorld.LocalConvexResult result, boolean worldNormal) {
                Vector3f normal = new Vector3f(result.hitNormalLocal);
                if (!worldNormal) {
                    result.hitCollisionObject.getWorldTransform(new Transform()).basis.transform(normal);
                }
                if (normal.dot(movement) >= -1.0E-8F) {
                    return 1;
                }
                return super.addSingleResult(result, worldNormal);
            }
        };
        hit.collisionFilterGroup = 2;
        hit.collisionFilterMask = 1;
        world.convexSweepTest(shape, from, to, hit);
        return hit.hasHit() ? Math.max(0, hit.closestHitFraction - TOLERANCE / movement.length()) : 1;
    }

    private void translate(RigidBody body, Vector3f movement) {
        Transform to = body.getWorldTransform(new Transform());
        to.origin.add(movement);
        body.setWorldTransform(to);
        body.setInterpolationWorldTransform(to);
        if (body.getMotionState() != null) {
            body.getMotionState()
                .setWorldTransform(to);
        }
        world.updateSingleAabb(body);
        body.activate(true);
    }

    private static List<RigidBody> single(RigidBody body) {
        List<RigidBody> result = new ArrayList<>(1);
        result.add(body);
        return result;
    }
}
