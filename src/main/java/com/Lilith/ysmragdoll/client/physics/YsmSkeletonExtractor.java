package com.Lilith.ysmragdoll.client.physics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.Lilith.ysmragdoll.YsmRagdollLog;
import com.Lilith.ysmragdoll.client.CapturedModel;

import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * 把 ysmu 的 GeckoLib 骨骼层级转换成不可变的布娃娃骨架定义。
 *
 * <p>
 * 上游 1.20.1 版本需要用反射读取混淆的运行时网格，并在缺少立方体列表时按
 * OpenYSM 规则解密模型文件补齐几何。ysmu 的 {@code CapturedModel} 已经把
 * 骨骼名称、父级、枢轴和真实立方体包围盒整理好，因此这里只做纯计算：
 * 映射主要部位、生成碰撞尺寸、计算初始世界矩阵。
 * </p>
 *
 * <p>
 * 几何、层级和部位映射可以按来源模型复用；{@code initialGlobal} 属于某一次
 * 死亡快照，每具布娃娃都必须用自己的骨骼参数重建，否则不同死亡动作会共享错误的
 * 绑定姿态。
 * </p>
 */
public final class YsmSkeletonExtractor {

    private static final float COLLISION_SHRINK = 0.95F;
    private static final Map<GeoModel, RagdollDefinition> CACHE = Collections
        .synchronizedMap(new WeakHashMap<GeoModel, RagdollDefinition>());

    private YsmSkeletonExtractor() {}

    public static RagdollDefinition extract(CapturedModel captured) {
        if (captured == null || !captured.hasSnapshot()) {
            return new RagdollDefinition(
                Collections.<RagdollDefinition.Bone>emptyList(),
                Collections.<RagdollDefinition.Role, RagdollDefinition.Part>emptyMap());
        }
        RagdollDefinition template = CACHE.get(captured.sharedModel());
        if (template == null) {
            template = parse(captured);
            CACHE.put(captured.sharedModel(), template);
        }
        return applyCapturedPose(template, captured.boneTransforms());
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static RagdollDefinition applyCapturedPose(RagdollDefinition template, float[] parameters) {
        if (template.bones()
            .isEmpty()
            || parameters.length < template.bones()
                .size() * CapturedModel.PARAMETERS_PER_BONE) {
            return template;
        }
        Matrix4f[] globals = calculateInitialMatrices(template.bones(), parameters);
        List<RagdollDefinition.Bone> posedBones = new ArrayList<>(
            template.bones()
                .size());
        for (int index = 0; index < template.bones()
            .size(); index++) {
            RagdollDefinition.Bone bone = template.bones()
                .get(index);
            posedBones.add(
                new RagdollDefinition.Bone(
                    bone.index(),
                    bone.name(),
                    bone.parentIndex(),
                    bone.pivot(),
                    bone.minimum(),
                    bone.maximum(),
                    new Matrix4f(globals[index])));
        }
        EnumMap<RagdollDefinition.Role, RagdollDefinition.Part> parts = new EnumMap<>(RagdollDefinition.Role.class);
        for (Map.Entry<RagdollDefinition.Role, RagdollDefinition.Part> entry : template.parts()
            .entrySet()) {
            RagdollDefinition.Part part = entry.getValue();
            parts.put(
                entry.getKey(),
                new RagdollDefinition.Part(
                    part.role(),
                    part.boneIndex(),
                    part.center(),
                    part.halfExtents(),
                    part.mass()));
        }
        return new RagdollDefinition(Collections.unmodifiableList(posedBones), Collections.unmodifiableMap(parts));
    }

    private static RagdollDefinition parse(CapturedModel captured) {
        try {
            List<CapturedModel.BoneEntry> entries = captured.bones();
            float[] parameters = captured.boneTransforms();

            List<RagdollDefinition.Bone> bones = new ArrayList<>(entries.size());
            for (CapturedModel.BoneEntry entry : entries) {
                Vector3f pivot = new Vector3f(entry.pivotX(), entry.pivotY(), entry.pivotZ());
                Vector3f minimum;
                Vector3f maximum;
                if (entry.hasGeometry()) {
                    minimum = new Vector3f(entry.minimumX(), entry.minimumY(), entry.minimumZ());
                    maximum = new Vector3f(entry.maximumX(), entry.maximumY(), entry.maximumZ());
                } else {
                    minimum = new Vector3f(Float.POSITIVE_INFINITY);
                    maximum = new Vector3f(Float.NEGATIVE_INFINITY);
                }
                bones.add(
                    new RagdollDefinition.Bone(
                        entry.index(),
                        entry.name(),
                        entry.parentIndex(),
                        pivot,
                        minimum,
                        maximum,
                        new Matrix4f()));
            }

            // mapParts 里的躯干合并需要真实的世界矩阵，因此先用本次捕获的姿态生成它们。
            // 每具布娃娃自己的 initialGlobal 仍会在 applyCapturedPose 中按各自快照重建。
            Matrix4f[] globals = calculateInitialMatrices(bones, parameters);
            for (int index = 0; index < bones.size(); index++) {
                RagdollDefinition.Bone bone = bones.get(index);
                bones.set(
                    index,
                    new RagdollDefinition.Bone(
                        bone.index(),
                        bone.name(),
                        bone.parentIndex(),
                        bone.pivot(),
                        bone.minimum(),
                        bone.maximum(),
                        globals[index]));
            }

            Map<RagdollDefinition.Role, RagdollDefinition.Part> parts = mapParts(bones);
            YsmRagdollLog.info(
                "YSM 物理骨架解析完成: 骨骼=" + bones.size()
                    + ", 主要刚体="
                    + parts.size()
                    + ", 数据源=ysmu GeckoLib 烘焙立方体"
                    + ", 映射="
                    + describeParts(parts, bones));
            return new RagdollDefinition(Collections.unmodifiableList(bones), Collections.unmodifiableMap(parts));
        } catch (RuntimeException exception) {
            YsmRagdollLog.warn("解析 YSM 物理骨架失败，将保留静态快照: " + exception);
            return new RagdollDefinition(
                Collections.<RagdollDefinition.Bone>emptyList(),
                Collections.<RagdollDefinition.Role, RagdollDefinition.Part>emptyMap());
        }
    }

    private static Matrix4f[] calculateInitialMatrices(List<RagdollDefinition.Bone> bones, float[] params) {
        Matrix4f[] result = new Matrix4f[bones.size()];
        boolean[] visiting = new boolean[bones.size()];
        for (int index = 0; index < bones.size(); index++) {
            calculateInitialMatrix(index, bones, params, result, visiting);
        }
        return result;
    }

    private static Matrix4f calculateInitialMatrix(int index, List<RagdollDefinition.Bone> bones, float[] params,
        Matrix4f[] cache, boolean[] visiting) {
        if (cache[index] != null) {
            return cache[index];
        }
        if (visiting[index]) {
            return cache[index] = new Matrix4f();
        }
        visiting[index] = true;
        RagdollDefinition.Bone bone = bones.get(index);
        int parent = validParent(bone.parentIndex(), index, bones.size());
        Matrix4f matrix = parent >= 0 ? new Matrix4f(calculateInitialMatrix(parent, bones, params, cache, visiting))
            : new Matrix4f();
        matrix.mul(BonePoseMath.local(bone.pivot(), params, index));
        visiting[index] = false;
        return cache[index] = matrix;
    }

    private static Map<RagdollDefinition.Role, RagdollDefinition.Part> mapParts(List<RagdollDefinition.Bone> bones) {
        EnumMap<RagdollDefinition.Role, RagdollDefinition.Bone> mapped = new EnumMap<>(RagdollDefinition.Role.class);
        Set<Integer> used = new HashSet<>();
        for (RagdollDefinition.Role role : RagdollDefinition.Role.values()) {
            RagdollDefinition.Bone best = null;
            int bestScore = 0;
            for (RagdollDefinition.Bone bone : bones) {
                if (used.contains(bone.index())) {
                    continue;
                }
                int score = score(role, normalize(bone.name()));
                if (score > bestScore) {
                    best = bone;
                    bestScore = score;
                }
            }
            if (best == null) {
                continue;
            }
            used.add(best.index());
            mapped.put(role, best);
        }

        // AllHead 是 YSM 的层级容器，真正的头部网格通常位于其子骨骼 Head。
        // 优先选择带有真实网格的 Head，避免把只有颈部定位小方块的容器当成头部刚体。
        RagdollDefinition.Bone physicalHead = findBone(bones, "head");
        RagdollDefinition.Bone selectedHead = mapped.get(RagdollDefinition.Role.HEAD);
        if (physicalHead != null && physicalHead != selectedHead) {
            if (selectedHead != null) {
                used.remove(selectedHead.index());
            }
            mapped.put(RagdollDefinition.Role.HEAD, physicalHead);
            used.add(physicalHead.index());
        }

        EnumMap<RagdollDefinition.Role, RagdollDefinition.Part> result = new EnumMap<>(RagdollDefinition.Role.class);
        for (Map.Entry<RagdollDefinition.Role, RagdollDefinition.Bone> entry : mapped.entrySet()) {
            RagdollDefinition.Role role = entry.getKey();
            RagdollDefinition.Bone bone = entry.getValue();
            Vector3f center;
            Vector3f half;
            Bounds aggregate = role == RagdollDefinition.Role.BODY && !bone.hasGeometry()
                ? aggregateBodyBounds(bone, bones)
                : null;
            if (aggregate != null && aggregate.isFinite()) {
                Vector3f size = new Vector3f(aggregate.maximum).sub(aggregate.minimum);
                center = new Vector3f(aggregate.minimum).add(aggregate.maximum)
                    .mul(0.5F);
                half = clampHalfExtents(role, size);
            } else if (bone.hasGeometry()) {
                Vector3f size = bone.sizeInBlocks();
                center = bone.center();
                half = clampHalfExtents(role, size);
            } else {
                center = fallbackCenter(role, bone, mapped);
                half = fallbackHalfExtents(role, bone, mapped);
            }
            half = shrinkHalfExtents(half);
            result.put(role, new RagdollDefinition.Part(role, bone.index(), center, half, mass(role, half)));
        }
        return result;
    }

    private static RagdollDefinition.Bone findBone(List<RagdollDefinition.Bone> bones, String expectedName) {
        for (RagdollDefinition.Bone bone : bones) {
            if (normalize(bone.name()).equals(expectedName) && bone.hasGeometry()) {
                return bone;
            }
        }
        return null;
    }

    /** 把身体子骨骼的真实网格转换到 AllBody 的局部坐标后合并。 */
    private static Bounds aggregateBodyBounds(RagdollDefinition.Bone reference, List<RagdollDefinition.Bone> bones) {
        Bounds result = new Bounds();
        Matrix4f referenceInverse = new Matrix4f(reference.initialGlobal()).invert();
        for (RagdollDefinition.Bone bone : bones) {
            String name = normalize(bone.name());
            if (!bone.hasGeometry() || !name.contains("body") || name.equals("allbody")) {
                continue;
            }
            Matrix4f toReference = new Matrix4f(referenceInverse).mul(bone.initialGlobal());
            Vector3f minimum = bone.minimum();
            Vector3f maximum = bone.maximum();
            for (int corner = 0; corner < 8; corner++) {
                Vector3f point = new Vector3f(
                    (corner & 1) == 0 ? minimum.x : maximum.x,
                    (corner & 2) == 0 ? minimum.y : maximum.y,
                    (corner & 4) == 0 ? minimum.z : maximum.z);
                toReference.transformPosition(point);
                result.include(point.x, point.y, point.z);
            }
        }
        return result;
    }

    private static Vector3f fallbackCenter(RagdollDefinition.Role role, RagdollDefinition.Bone bone,
        Map<RagdollDefinition.Role, RagdollDefinition.Bone> mapped) {
        Vector3f pivot = pivotInBlocks(bone);
        RagdollDefinition.Role childRole = segmentChild(role);
        RagdollDefinition.Bone child = mapped.get(childRole);
        if (child != null) {
            return pivot.add(pivotInBlocks(child))
                .mul(0.5F);
        }
        if (role == RagdollDefinition.Role.BODY) {
            Vector3f sum = new Vector3f();
            int count = 0;
            for (RagdollDefinition.Role anchor : new RagdollDefinition.Role[] { RagdollDefinition.Role.LEFT_UPPER_ARM,
                RagdollDefinition.Role.RIGHT_UPPER_ARM, RagdollDefinition.Role.LEFT_THIGH,
                RagdollDefinition.Role.RIGHT_THIGH }) {
                RagdollDefinition.Bone anchorBone = mapped.get(anchor);
                if (anchorBone != null) {
                    sum.add(pivotInBlocks(anchorBone));
                    count++;
                }
            }
            if (count > 0) {
                return sum.mul(1.0F / count);
            }
        }
        if (role == RagdollDefinition.Role.HEAD) {
            RagdollDefinition.Bone body = mapped.get(RagdollDefinition.Role.BODY);
            if (body != null) {
                Vector3f upward = new Vector3f(pivot).sub(pivotInBlocks(body));
                float length = upward.length();
                if (length > 1.0E-4F) {
                    pivot.fma(clamp(length * 0.28F, 0.14F, 0.28F) / length, upward);
                }
            }
        }
        return pivot;
    }

    private static Vector3f fallbackHalfExtents(RagdollDefinition.Role role, RagdollDefinition.Bone bone,
        Map<RagdollDefinition.Role, RagdollDefinition.Bone> mapped) {
        if (role == RagdollDefinition.Role.BODY) {
            float shoulderWidth = distance(
                mapped.get(RagdollDefinition.Role.LEFT_UPPER_ARM),
                mapped.get(RagdollDefinition.Role.RIGHT_UPPER_ARM),
                0.65F);
            float bodyHeight = distance(bone, mapped.get(RagdollDefinition.Role.HEAD), 0.75F);
            return new Vector3f(
                clamp(shoulderWidth * 0.34F, 0.20F, 0.55F),
                clamp(bodyHeight * 0.34F, 0.28F, 0.75F),
                clamp(shoulderWidth * 0.24F, 0.17F, 0.42F));
        }
        if (role == RagdollDefinition.Role.HEAD) {
            float scale = distance(bone, mapped.get(RagdollDefinition.Role.BODY), 0.72F);
            // 装饰立方体可能缺失，头部后备箱需要留出更充分的接地余量。
            float radius = clamp(scale * 0.42F, 0.32F, 0.46F);
            return new Vector3f(radius, clamp(radius * 1.15F, 0.36F, 0.52F), radius);
        }

        RagdollDefinition.Bone child = mapped.get(segmentChild(role));
        float length;
        if (child != null) {
            length = distance(bone, child, 0.34F);
        } else {
            RagdollDefinition.Bone parent = mapped.get(segmentParent(role));
            length = distance(parent, bone, 0.30F) * 0.42F;
        }
        float thickness = clamp(length * 0.21F, 0.085F, 0.20F);
        if (role == RagdollDefinition.Role.LEFT_FOOT || role == RagdollDefinition.Role.RIGHT_FOOT) {
            return new Vector3f(thickness, thickness, clamp(length * 0.65F, 0.12F, 0.32F));
        }
        return new Vector3f(thickness, clamp(length * 0.5F, 0.10F, 0.48F), thickness);
    }

    private static Vector3f clampHalfExtents(RagdollDefinition.Role role, Vector3f size) {
        if (role == RagdollDefinition.Role.HEAD) {
            // 头部碰撞体只取真实头部包围盒的最短边，保持中心不变并收缩成长宽高相等的正方体。
            float edge = clamp(Math.min(size.x, Math.min(size.y, size.z)) * 0.5F, 0.06F, 0.38F);
            return new Vector3f(edge, edge, edge);
        }
        return new Vector3f(
            clamp(size.x * 0.5F, 0.06F, role == RagdollDefinition.Role.BODY ? 0.65F : 0.38F),
            clamp(size.y * 0.5F, 0.06F, role == RagdollDefinition.Role.BODY ? 0.85F : 0.55F),
            clamp(size.z * 0.5F, 0.06F, role == RagdollDefinition.Role.BODY ? 0.55F : 0.38F));
    }

    private static Vector3f shrinkHalfExtents(Vector3f half) {
        // 统一收缩到计算值的 95%，减少装饰网格和相邻关节处的误碰撞。
        return new Vector3f(
            Math.max(0.04F, half.x * COLLISION_SHRINK),
            Math.max(0.04F, half.y * COLLISION_SHRINK),
            Math.max(0.04F, half.z * COLLISION_SHRINK));
    }

    private static float distance(RagdollDefinition.Bone first, RagdollDefinition.Bone second, float fallback) {
        if (first == null || second == null) {
            return fallback;
        }
        return clamp(pivotInBlocks(first).distance(pivotInBlocks(second)), 0.12F, 1.8F);
    }

    private static Vector3f pivotInBlocks(RagdollDefinition.Bone bone) {
        return new Vector3f(bone.pivot()).mul(1.0F / 16.0F);
    }

    private static RagdollDefinition.Role segmentChild(RagdollDefinition.Role role) {
        switch (role) {
            case LEFT_UPPER_ARM:
                return RagdollDefinition.Role.LEFT_FOREARM;
            case RIGHT_UPPER_ARM:
                return RagdollDefinition.Role.RIGHT_FOREARM;
            case LEFT_FOREARM:
                return RagdollDefinition.Role.LEFT_HAND;
            case RIGHT_FOREARM:
                return RagdollDefinition.Role.RIGHT_HAND;
            case LEFT_THIGH:
                return RagdollDefinition.Role.LEFT_SHIN;
            case RIGHT_THIGH:
                return RagdollDefinition.Role.RIGHT_SHIN;
            case LEFT_SHIN:
                return RagdollDefinition.Role.LEFT_FOOT;
            case RIGHT_SHIN:
                return RagdollDefinition.Role.RIGHT_FOOT;
            default:
                return null;
        }
    }

    private static RagdollDefinition.Role segmentParent(RagdollDefinition.Role role) {
        switch (role) {
            case LEFT_FOREARM:
                return RagdollDefinition.Role.LEFT_UPPER_ARM;
            case RIGHT_FOREARM:
                return RagdollDefinition.Role.RIGHT_UPPER_ARM;
            case LEFT_HAND:
                return RagdollDefinition.Role.LEFT_FOREARM;
            case RIGHT_HAND:
                return RagdollDefinition.Role.RIGHT_FOREARM;
            case LEFT_SHIN:
                return RagdollDefinition.Role.LEFT_THIGH;
            case RIGHT_SHIN:
                return RagdollDefinition.Role.RIGHT_THIGH;
            case LEFT_FOOT:
                return RagdollDefinition.Role.LEFT_SHIN;
            case RIGHT_FOOT:
                return RagdollDefinition.Role.RIGHT_SHIN;
            default:
                return RagdollDefinition.Role.BODY;
        }
    }

    private static String describeParts(Map<RagdollDefinition.Role, RagdollDefinition.Part> parts,
        List<RagdollDefinition.Bone> bones) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<RagdollDefinition.Role, RagdollDefinition.Part> entry : parts.entrySet()) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(entry.getKey())
                .append('=')
                .append(
                    bones.get(
                        entry.getValue()
                            .boneIndex())
                        .name());
        }
        return result.toString();
    }

    private static int score(RagdollDefinition.Role role, String name) {
        boolean left = name.contains("left") || name.startsWith("l");
        boolean right = name.contains("right") || name.startsWith("r");
        switch (role) {
            case BODY:
                return exact(name, "mallbody", "allbody", "root", "body", "torso");
            case HEAD:
                return exact(name, "allhead", "mhead", "head", "skull");
            case LEFT_UPPER_ARM:
                return left ? exact(name, "leftupperarm", "leftarm", "larm", "armleft") : 0;
            case RIGHT_UPPER_ARM:
                return right ? exact(name, "rightupperarm", "rightarm", "rarm", "armright") : 0;
            case LEFT_FOREARM:
                return left ? exact(name, "leftforearm", "leftlowerarm", "lforearm", "leftelbow") : 0;
            case RIGHT_FOREARM:
                return right ? exact(name, "rightforearm", "rightlowerarm", "rforearm", "rightelbow") : 0;
            case LEFT_HAND:
                return left ? exact(name, "lefthand", "lhand", "lefthandlocator") : 0;
            case RIGHT_HAND:
                return right ? exact(name, "righthand", "rhand", "righthandlocator") : 0;
            case LEFT_THIGH:
                return left ? exact(name, "leftupperleg", "leftleg", "lleg", "legleft") : 0;
            case RIGHT_THIGH:
                return right ? exact(name, "rightupperleg", "rightleg", "rleg", "legright") : 0;
            case LEFT_SHIN:
                return left ? exact(name, "leftlowerleg", "leftshin", "leftcalf") : 0;
            case RIGHT_SHIN:
                return right ? exact(name, "rightlowerleg", "rightshin", "rightcalf") : 0;
            case LEFT_FOOT:
                return left ? exact(name, "leftfoot", "leftsole", "lefttoe") : 0;
            case RIGHT_FOOT:
                return right ? exact(name, "rightfoot", "rightsole", "righttoe") : 0;
            default:
                return 0;
        }
    }

    private static int exact(String value, String... candidates) {
        for (int i = 0; i < candidates.length; i++) {
            if (value.equals(candidates[i])) {
                return 100 - i;
            }
        }
        return contains(value, candidates);
    }

    private static int contains(String value, String... candidates) {
        for (int i = 0; i < candidates.length; i++) {
            if (value.contains(candidates[i])) {
                return 70 - i;
            }
        }
        return 0;
    }

    private static String normalize(String value) {
        return value == null ? ""
            : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private static float mass(RagdollDefinition.Role role, Vector3f half) {
        float volumeMass = Math.max(0.2F, half.x * half.y * half.z * 45.0F);
        switch (role) {
            case BODY:
                return Math.max(5.0F, volumeMass);
            case HEAD:
                return Math.max(1.2F, volumeMass);
            case LEFT_THIGH:
            case RIGHT_THIGH:
                return Math.max(1.5F, volumeMass);
            default:
                return Math.max(0.35F, volumeMass);
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int validParent(int parent, int index, int size) {
        return parent >= 0 && parent < size && parent != index ? parent : -1;
    }

    private static final class Bounds {

        private final Vector3f minimum = new Vector3f(Float.POSITIVE_INFINITY);
        private final Vector3f maximum = new Vector3f(Float.NEGATIVE_INFINITY);

        private void include(float x, float y, float z) {
            minimum.x = Math.min(minimum.x, x);
            minimum.y = Math.min(minimum.y, y);
            minimum.z = Math.min(minimum.z, z);
            maximum.x = Math.max(maximum.x, x);
            maximum.y = Math.max(maximum.y, y);
            maximum.z = Math.max(maximum.z, z);
        }

        private boolean isFinite() {
            return Float.isFinite(minimum.x) && Float.isFinite(minimum.y)
                && Float.isFinite(minimum.z)
                && Float.isFinite(maximum.x)
                && Float.isFinite(maximum.y)
                && Float.isFinite(maximum.z);
        }
    }
}
