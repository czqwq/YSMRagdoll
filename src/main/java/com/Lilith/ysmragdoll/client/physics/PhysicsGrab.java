package com.Lilith.ysmragdoll.client.physics;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.vecmath.Vector3f;

import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.constraintsolver.Point2PointConstraint;
import com.bulletphysics.dynamics.constraintsolver.TypedConstraint;
import com.bulletphysics.linearmath.Transform;

/** 点约束牵引，并在每个物理子步后可选用关节防拉伸修正跟进。 */
public final class PhysicsGrab implements AutoCloseable {

    private static final float MAX_DRIVE_ERROR = 0.15F;
    private static final float STRAIN_LIMIT = 0.025F;

    private final RigidBody body;
    private final Point2PointConstraint constraint;
    private final Consumer<TypedConstraint> remove;
    private final BooleanSupplier valid;
    private final Supplier<Vector3f> offset;
    private final Transform transform = new Transform();
    private final Vector3f localAnchor;
    private final GrabJointGuard jointGuard;
    private final Vector3f requestedPoint = new Vector3f();
    private final GrabInertia inertia = new GrabInertia();
    private boolean closed;

    PhysicsGrab(RigidBody body, Vector3f hit, Consumer<TypedConstraint> add, Consumer<TypedConstraint> remove,
        BooleanSupplier valid, Supplier<Vector3f> offset) {
        this(body, hit, add, remove, valid, offset, null);
    }

    PhysicsGrab(RigidBody body, Vector3f hit, Consumer<TypedConstraint> add, Consumer<TypedConstraint> remove,
        BooleanSupplier valid, Supplier<Vector3f> offset, GrabJointGuard jointGuard) {
        this.jointGuard = jointGuard;
        this.body = body;
        this.remove = remove;
        this.valid = valid;
        this.offset = offset;
        body.getWorldTransform(transform);
        transform.inverse();
        localAnchor = new Vector3f(hit);
        transform.transform(localAnchor);
        constraint = new Point2PointConstraint(body, localAnchor);
        constraint.setting.tau = 0.15F;
        constraint.setting.damping = 1.0F;
        constraint.setting.impulseClamp = 8.0F;
        constraint.setPivotB(hit);
        requestedPoint.set(hit);
        requestedPoint.sub(offset.get());
        add.accept(constraint);
        body.activate(true);
    }

    public boolean isActive() {
        return !closed && valid.getAsBoolean();
    }

    public void moveTo(Vector3f worldPoint) {
        if (!isActive()) {
            close();
            return;
        }
        requestedPoint.set(worldPoint);
        if (jointGuard != null) {
            return;
        }
        Vector3f target = new Vector3f(worldPoint);
        target.add(offset.get());
        constraint.setPivotB(target);
        body.activate(true);
    }

    void beforeStep() {
        if (!isActive()) {
            close();
            return;
        }
        if (jointGuard == null) {
            return;
        }
        float error = jointGuard.maximumError();
        Vector3f current = new Vector3f(localAnchor);
        body.getWorldTransform(transform);
        transform.transform(current);
        Vector3f delta = new Vector3f(requestedPoint);
        delta.add(offset.get());
        delta.sub(current);
        Vector3f movement = error < STRAIN_LIMIT ? jointGuard.follow(body, delta) : new Vector3f();
        Vector3f feedback = inertia.step(movement);
        if (error < STRAIN_LIMIT) {
            jointGuard.applyInertia(body, feedback);
        }
        current.set(localAnchor);
        body.getWorldTransform(transform);
        transform.transform(current);
        delta.set(requestedPoint);
        delta.add(offset.get());
        delta.sub(current);
        // 只约束驱动误差，不覆盖整套刚体的既有速度。
        float allowance = Math.max(0.005F, MAX_DRIVE_ERROR * (1 - error / STRAIN_LIMIT));
        float length = delta.length();
        if (length > allowance) {
            delta.scale(allowance / length);
        }
        current.add(delta);
        constraint.setPivotB(current);
        // 关节受阻时的优先级高于跟随准星。
        constraint.setting.impulseClamp = error > STRAIN_LIMIT ? 0.05F : 1.0F;
        body.activate(true);
    }

    void afterStep() {
        if (isActive() && jointGuard != null) {
            jointGuard.correct();
        }
    }

    public Vector3f anchor() {
        body.getWorldTransform(transform);
        Vector3f result = new Vector3f(localAnchor);
        transform.transform(result);
        result.sub(offset.get());
        return result;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        remove.accept(constraint);
        body.activate(true);
    }

    /** 只有明确松开使用键才继承速度；关界面/切物品/失效清理都不允许抛出尸体。 */
    public void releaseWithInertia() {
        if (isActive() && jointGuard != null) {
            jointGuard.releaseWithVelocity(body, inertia.releaseVelocity());
        }
        close();
    }
}
