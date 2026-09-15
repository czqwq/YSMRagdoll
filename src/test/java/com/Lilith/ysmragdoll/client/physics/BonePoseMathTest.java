package com.Lilith.ysmragdoll.client.physics;

import static org.junit.Assert.assertEquals;

import java.util.Random;

import org.joml.Matrix4f;
import org.junit.Test;

import com.bulletphysics.linearmath.Transform;

/**
 * 骨骼 TRS 参数与刚体绑定矩阵的往返测试。
 *
 * <p>
 * 上游的 {@code BoneBindingTest} 还覆盖了完整的 {@code PhysicsRagdoll} 姿态回写，
 * 那部分依赖 1.20.1 的 Forge 配置规范和捕获网格视图。移植版本保留与平台无关的
 * 坐标契约部分：平移/旋转/镜像缩放往返、绑定矩阵和碰撞盒尺寸变换。
 * </p>
 */
public class BonePoseMathTest {

    private static final float EPS = 0.0003F;

    @Test
    public void pixelTranslationRotationAndSignedNonuniformScaleRoundTrip() {
        Random random = new Random(1782);
        for (int i = 0; i < 100; i++) {
            float[] captured = parameters(1);
            captured[0] = random.nextFloat() * 2 - 1;
            captured[1] = random.nextFloat() * 2 - 1;
            captured[2] = random.nextFloat() * 2 - 1;
            captured[3] = 7;
            captured[4] = -11;
            captured[5] = 3;
            captured[6] = i % 2 == 0 ? -0.7F : 0.7F;
            captured[7] = 1.8F;
            captured[8] = 1.2F;
            captured[9] = 42;
            org.joml.Vector3f pivot = new org.joml.Vector3f(-5, 23, 2);
            Matrix4f target = rendererLocal(pivot, captured, 0);
            float[] output = captured.clone();
            BonePoseMath.writeLocal(target, pivot, new org.joml.Vector3f(0.4F, 1.1F, -0.2F), output, captured, 0);
            assertMatrix(target, rendererLocal(pivot, output, 0));
            assertEquals(42, output[9], 0);
            assertEquals(captured[3], output[3], EPS);
            assertEquals(captured[4], output[4], EPS);
            assertEquals(captured[5], output[5], EPS);
        }
    }

    @Test
    public void collisionExtentsIncludeRootAndBoneScale() {
        Matrix4f model = new Matrix4f().translation(13, 64, -8)
            .rotateY(0.7F)
            .scale(-2, 3, 4);
        org.joml.Vector3f center = new org.joml.Vector3f(0.2F, 1.2F, -0.3F);
        org.joml.Vector3f worldCenter = model.transformPosition(new org.joml.Vector3f(center));
        Transform body = new Transform();
        body.setIdentity();
        body.basis.rotY(0.7F);
        body.origin.set(worldCenter.x, worldCenter.y, worldCenter.z);
        Matrix4f binding = BonePoseMath.bind(body, model);
        org.joml.Vector3f half = BonePoseMath.halfExtents(binding, new org.joml.Vector3f(0.1F, 0.2F, 0.3F));
        assertVector(new org.joml.Vector3f(0.2F, 0.6F, 1.2F), half);
        assertVector(new org.joml.Vector3f(), binding.transformPosition(new org.joml.Vector3f(center)));
    }

    /** 独立复刻渲染器的局部矩阵：T * Rz * Ry * Rx * S * T(-pivot)。 */
    private static Matrix4f rendererLocal(org.joml.Vector3f pivot, float[] p, int bone) {
        int i = bone * 12;
        Matrix4f matrix = new Matrix4f();
        matrix
            .translate((pivot.x - p[i + 3]) * 0.0625F, (pivot.y + p[i + 4]) * 0.0625F, (pivot.z + p[i + 5]) * 0.0625F);
        matrix.rotateZ(p[i + 2]);
        matrix.rotateY(p[i + 1]);
        matrix.rotateX(p[i]);
        matrix.scale(p[i + 6], p[i + 7], p[i + 8]);
        matrix.translate(-pivot.x / 16, -pivot.y / 16, -pivot.z / 16);
        return matrix;
    }

    private static float[] parameters(int count) {
        float[] p = new float[count * 12];
        for (int i = 0; i < count; i++) {
            p[i * 12 + 6] = p[i * 12 + 7] = p[i * 12 + 8] = 1;
        }
        return p;
    }

    private static void assertVector(org.joml.Vector3f expected, org.joml.Vector3f actual) {
        assertEquals(expected.x, actual.x, EPS);
        assertEquals(expected.y, actual.y, EPS);
        assertEquals(expected.z, actual.z, EPS);
    }

    private static void assertMatrix(Matrix4f expected, Matrix4f actual) {
        org.joml.Vector3f[] points = { new org.joml.Vector3f(), new org.joml.Vector3f(1, 0, 0),
            new org.joml.Vector3f(0, 1, 0), new org.joml.Vector3f(0, 0, 1) };
        for (org.joml.Vector3f point : points) {
            assertVector(
                expected.transformPosition(new org.joml.Vector3f(point)),
                actual.transformPosition(new org.joml.Vector3f(point)));
        }
    }
}
