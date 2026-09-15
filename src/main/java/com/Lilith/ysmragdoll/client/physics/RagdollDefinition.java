package com.Lilith.ysmragdoll.client.physics;

import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** YSM 物理骨架：pivot 使用像素，几何边界、center 和 halfExtents 使用方块。 */
public final class RagdollDefinition {

    public enum Role {
        BODY,
        HEAD,
        LEFT_UPPER_ARM,
        RIGHT_UPPER_ARM,
        LEFT_FOREARM,
        RIGHT_FOREARM,
        LEFT_HAND,
        RIGHT_HAND,
        LEFT_THIGH,
        RIGHT_THIGH,
        LEFT_SHIN,
        RIGHT_SHIN,
        LEFT_FOOT,
        RIGHT_FOOT
    }

    private final List<Bone> bones;
    private final Map<Role, Part> parts;

    public RagdollDefinition(List<Bone> bones, Map<Role, Part> parts) {
        this.bones = bones;
        this.parts = parts;
    }

    public List<Bone> bones() {
        return bones;
    }

    public Map<Role, Part> parts() {
        return parts;
    }

    public static final class Bone {

        private final int index;
        private final String name;
        private final int parentIndex;
        private final Vector3f pivot;
        private final Vector3f minimum;
        private final Vector3f maximum;
        private final Matrix4f initialGlobal;

        public Bone(int index, String name, int parentIndex, Vector3f pivot, Vector3f minimum, Vector3f maximum,
            Matrix4f initialGlobal) {
            this.index = index;
            this.name = name;
            this.parentIndex = parentIndex;
            this.pivot = pivot;
            this.minimum = minimum;
            this.maximum = maximum;
            this.initialGlobal = initialGlobal;
        }

        public int index() {
            return index;
        }

        public String name() {
            return name;
        }

        public int parentIndex() {
            return parentIndex;
        }

        public Vector3f pivot() {
            return pivot;
        }

        public Vector3f minimum() {
            return minimum;
        }

        public Vector3f maximum() {
            return maximum;
        }

        public Matrix4f initialGlobal() {
            return initialGlobal;
        }

        public boolean hasGeometry() {
            return Float.isFinite(minimum.x) && Float.isFinite(maximum.x);
        }

        public Vector3f center() {
            return hasGeometry() ? new Vector3f(minimum).add(maximum)
                .mul(0.5F) : new Vector3f(pivot).mul(1.0F / 16.0F);
        }

        public Vector3f sizeInBlocks() {
            if (!hasGeometry()) {
                return new Vector3f(0.25F, 0.25F, 0.25F);
            }
            return new Vector3f(maximum).sub(minimum);
        }
    }

    public static final class Part {

        private final Role role;
        private final int boneIndex;
        private final Vector3f center;
        private final Vector3f halfExtents;
        private final float mass;

        public Part(Role role, int boneIndex, Vector3f center, Vector3f halfExtents, float mass) {
            this.role = role;
            this.boneIndex = boneIndex;
            this.center = center;
            this.halfExtents = halfExtents;
            this.mass = mass;
        }

        public Role role() {
            return role;
        }

        public int boneIndex() {
            return boneIndex;
        }

        public Vector3f center() {
            return center;
        }

        public Vector3f halfExtents() {
            return halfExtents;
        }

        public float mass() {
            return mass;
        }
    }
}
