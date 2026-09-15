package com.Lilith.ysmragdoll.client.physics;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * 骨骼姿态数学：枢轴/平移是像素，几何顶点是方块，旋转顺序为 Z/Y/X。
 *
 * <p>
 * 参数数组每根骨骼占用 12 个 float：0-2 是弧度旋转，3-5 是像素平移，
 * 6-8 是缩放，其余保留。该布局与 GeckoLib 骨骼的
 * {@code translate * moveToPivot * rotate * scale * moveBackFromPivot} 完全一致，
 * 因此可以直接写回 ysmu 的骨骼对象。
 * </p>
 */
final class BonePoseMath {

    private BonePoseMath() {}

    static Matrix4f local(org.joml.Vector3f pivot, float[] parameters, int index) {
        int p = index * 12;
        return new Matrix4f()
            .translate(
                (pivot.x - parameters[p + 3]) / 16.0F,
                (pivot.y + parameters[p + 4]) / 16.0F,
                (pivot.z + parameters[p + 5]) / 16.0F)
            .rotateZ(parameters[p + 2])
            .rotateY(parameters[p + 1])
            .rotateX(parameters[p])
            .scale(parameters[p + 6], parameters[p + 7], parameters[p + 8])
            .translate(-pivot.x / 16.0F, -pivot.y / 16.0F, -pivot.z / 16.0F);
    }

    static Matrix4f rigid(com.bulletphysics.linearmath.Transform transform) {
        return new Matrix4f().set(
            transform.basis.m00,
            transform.basis.m10,
            transform.basis.m20,
            0,
            transform.basis.m01,
            transform.basis.m11,
            transform.basis.m21,
            0,
            transform.basis.m02,
            transform.basis.m12,
            transform.basis.m22,
            0,
            transform.origin.x,
            transform.origin.y,
            transform.origin.z,
            1);
    }

    /** 保留完整绑定变换，包括根缩放和手性。 */
    static Matrix4f bind(com.bulletphysics.linearmath.Transform body, Matrix4f boneWorld) {
        return rigid(body).invert()
            .mul(boneWorld);
    }

    /** 把模型空间的碰撞盒装进正交刚体坐标系。 */
    static org.joml.Vector3f halfExtents(Matrix4f boneToBody, org.joml.Vector3f half) {
        return new org.joml.Vector3f(
            Math.abs(boneToBody.m00()) * half.x + Math.abs(boneToBody.m10()) * half.y
                + Math.abs(boneToBody.m20()) * half.z,
            Math.abs(boneToBody.m01()) * half.x + Math.abs(boneToBody.m11()) * half.y
                + Math.abs(boneToBody.m21()) * half.z,
            Math.abs(boneToBody.m02()) * half.x + Math.abs(boneToBody.m12()) * half.y
                + Math.abs(boneToBody.m22()) * half.z);
    }

    /**
     * 反解为 YSM 的 TRS 参数。带旋转的非等比父级会产生剪切，参数数组无法表示；
     * 这种情况下仍然保证物理部位几何中心精确，形状和朝向取最接近的正交旋转。
     */
    static void writeLocal(Matrix4f target, org.joml.Vector3f pivotPixels, org.joml.Vector3f center, float[] parameters,
        float[] captured, int index) {
        int p = index * 12;
        org.joml.Vector3f scale = target.getScale(new org.joml.Vector3f());
        scale.x = Math.copySign(scale.x, captured[p + 6]);
        scale.y = Math.copySign(scale.y, captured[p + 7]);
        scale.z = Math.copySign(scale.z, captured[p + 8]);
        if (Math.abs(scale.x * scale.y * scale.z) < 1.0E-12F) {
            return;
        }
        if (target.determinant3x3() * scale.x * scale.y * scale.z < 0) {
            scale.z = -scale.z;
        }
        org.joml.Matrix3f rotation = target.get3x3(new org.joml.Matrix3f());
        // JOML 的 mXY 中 X 表示列，因此按列除以缩放，而不是按行。
        rotation.m00(rotation.m00() / scale.x);
        rotation.m01(rotation.m01() / scale.x);
        rotation.m02(rotation.m02() / scale.x);
        rotation.m10(rotation.m10() / scale.y);
        rotation.m11(rotation.m11() / scale.y);
        rotation.m12(rotation.m12() / scale.y);
        rotation.m20(rotation.m20() / scale.z);
        rotation.m21(rotation.m21() / scale.z);
        rotation.m22(rotation.m22() / scale.z);
        // 直接从旋转矩阵读出 Z/Y/X。JOML 的四元数欧拉辅助函数无法可靠地往返组合旋转。
        new Quaternionf().setFromNormalized(rotation)
            .normalize()
            .get(rotation);
        float ry = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, -rotation.m02())));
        float rx;
        float rz;
        if (Math.abs(Math.cos(ry)) > 1.0E-5) {
            rx = (float) Math.atan2(rotation.m12(), rotation.m22());
            rz = (float) Math.atan2(rotation.m01(), rotation.m00());
        } else {
            rx = 0.0F;
            rz = (float) Math.atan2(-rotation.m10(), rotation.m11());
        }
        parameters[p] = rx;
        parameters[p + 1] = ry;
        parameters[p + 2] = rz;
        parameters[p + 6] = scale.x;
        parameters[p + 7] = scale.y;
        parameters[p + 8] = scale.z;

        Matrix4f linear = new Matrix4f().rotateZ(rz)
            .rotateY(ry)
            .rotateX(rx)
            .scale(scale);
        org.joml.Vector3f pivot = new org.joml.Vector3f(pivotPixels).div(16.0F);
        org.joml.Vector3f d = target.transformPosition(new org.joml.Vector3f(center))
            .sub(linear.transformDirection(new org.joml.Vector3f(center).sub(pivot)));
        parameters[p + 3] = (pivot.x - d.x) * 16.0F;
        parameters[p + 4] = (d.y - pivot.y) * 16.0F;
        parameters[p + 5] = (d.z - pivot.z) * 16.0F;
    }
}
