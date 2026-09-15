package com.Lilith.ysmragdoll.client.physics;

import javax.vecmath.Vector3f;

/** 移动抓取坐标系中的有界惯性；只采样真正通过碰撞扫掠的位移。 */
final class GrabInertia {

    private static final float STEP_SECONDS = 1F / 120;

    private final Vector3f carryVelocity = new Vector3f();

    Vector3f step(Vector3f movement) {
        Vector3f measured = new Vector3f(movement);
        measured.scale(1 / STEP_SECONDS);
        limit(measured, 10);
        Vector3f change = new Vector3f(measured);
        change.sub(carryVelocity);
        change.scale(0.12F);
        carryVelocity.add(change);
        // 起拉与反向牵引时自由肢体向后甩，急停时向前摆。
        change.scale(-0.25F);
        limit(change, 0.06F);
        return change;
    }

    Vector3f releaseVelocity() {
        Vector3f velocity = new Vector3f(carryVelocity);
        if (velocity.lengthSquared() < 0.04F) {
            velocity.set(0, 0, 0);
        }
        velocity.scale(0.85F);
        limit(velocity, 6);
        return velocity;
    }

    static void limit(Vector3f vector, float maximum) {
        float squared = vector.lengthSquared();
        if (squared > maximum * maximum) {
            vector.scale(maximum / (float) Math.sqrt(squared));
        }
    }
}
