package com.Lilith.ysmragdoll.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.util.ResourceLocation;

import org.joml.Matrix4f;

import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.geo.render.built.GeoVertex;

/**
 * 一具布娃娃独占的 YSM 模型快照。
 *
 * <p>
 * 上游 1.20.1 版本必须在 YSM 提交网格的那一刻用 Mixin 抓取混淆的运行时网格；
 * ysmu 已经是 1.7.10 上的 GeckoLib 移植，模型层级、立方体和骨骼状态都是公开的，
 * 因此这里直接深拷贝骨骼树并冻结骨骼参数数组，不需要再经过 OpenYSM 的二进制解密
 * 与几何补齐路径。
 * </p>
 *
 * <p>
 * 立方体对象在拷贝时共享：它们是只读资源，只有骨骼的位置/旋转/缩放需要每具
 * 尸体独立。这样同一模型的多个布娃娃互不干扰，也不会写回正在渲染的活玩家。
 * </p>
 */
public final class CapturedModel {

    /** 每根骨骼在参数数组中占用的 float 数量。 */
    public static final int PARAMETERS_PER_BONE = 12;

    /** 扁平化后的骨骼条目；枢轴使用像素，几何边界使用方块且相对该骨骼枢轴。 */
    public static final class BoneEntry {

        private final int index;
        private final String name;
        private final int parentIndex;
        private final float pivotX;
        private final float pivotY;
        private final float pivotZ;
        private final float minimumX;
        private final float minimumY;
        private final float minimumZ;
        private final float maximumX;
        private final float maximumY;
        private final float maximumZ;
        private final GeoBone ownedBone;

        BoneEntry(int index, String name, int parentIndex, float pivotX, float pivotY, float pivotZ, float minimumX,
            float minimumY, float minimumZ, float maximumX, float maximumY, float maximumZ, GeoBone ownedBone) {
            this.index = index;
            this.name = name;
            this.parentIndex = parentIndex;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.pivotZ = pivotZ;
            this.minimumX = minimumX;
            this.minimumY = minimumY;
            this.minimumZ = minimumZ;
            this.maximumX = maximumX;
            this.maximumY = maximumY;
            this.maximumZ = maximumZ;
            this.ownedBone = ownedBone;
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

        public float pivotX() {
            return pivotX;
        }

        public float pivotY() {
            return pivotY;
        }

        public float pivotZ() {
            return pivotZ;
        }

        public boolean hasGeometry() {
            return minimumX <= maximumX && !Float.isInfinite(minimumX) && !Float.isInfinite(maximumX);
        }

        public float minimumX() {
            return minimumX;
        }

        public float minimumY() {
            return minimumY;
        }

        public float minimumZ() {
            return minimumZ;
        }

        public float maximumX() {
            return maximumX;
        }

        public float maximumY() {
            return maximumY;
        }

        public float maximumZ() {
            return maximumZ;
        }

        public GeoBone ownedBone() {
            return ownedBone;
        }
    }

    private final GeoModel sharedModel;
    private final GeoModel ownedModel;
    private final List<BoneEntry> bones;
    private final float[] boneTransforms;
    private final ResourceLocation texture;
    private final float widthScale;
    private final float heightScale;
    private final Matrix4f relativePose;
    private final String modelName;

    CapturedModel(GeoModel sharedModel, GeoModel ownedModel, List<BoneEntry> bones, float[] boneTransforms,
        ResourceLocation texture, float widthScale, float heightScale, Matrix4f relativePose, String modelName) {
        this.sharedModel = sharedModel;
        this.ownedModel = ownedModel;
        this.bones = Collections.unmodifiableList(bones);
        this.boneTransforms = boneTransforms;
        this.texture = texture;
        this.widthScale = widthScale;
        this.heightScale = heightScale;
        this.relativePose = relativePose;
        this.modelName = modelName;
    }

    /** 几何来源模型，仅用于缓存键与诊断，不参与渲染。 */
    public GeoModel sharedModel() {
        return sharedModel;
    }

    /** 本具尸体独占的骨骼副本，渲染前由 {@link #applyBones()} 写入参数。 */
    public GeoModel ownedModel() {
        return ownedModel;
    }

    public List<BoneEntry> bones() {
        return bones;
    }

    /** 可写的骨骼参数数组，布局见 {@link #PARAMETERS_PER_BONE}。 */
    public float[] boneTransforms() {
        return boneTransforms;
    }

    public ResourceLocation texture() {
        return texture;
    }

    public float widthScale() {
        return widthScale;
    }

    public float heightScale() {
        return heightScale;
    }

    /** 模型坐标到世界坐标的根变换：{@code Ry(180 - 身体朝向) * S(宽, 高, 宽)}。 */
    public Matrix4f relativePose() {
        return relativePose;
    }

    public String modelName() {
        return modelName;
    }

    public boolean hasSnapshot() {
        return !bones.isEmpty() && boneTransforms.length >= bones.size() * PARAMETERS_PER_BONE;
    }

    /** 把参数数组写入独占骨骼。渲染和物理回写之后都必须调用一次。 */
    public void applyBones() {
        for (BoneEntry bone : bones) {
            int p = bone.index * PARAMETERS_PER_BONE;
            GeoBone owned = bone.ownedBone;
            owned.setRotationX(boneTransforms[p]);
            owned.setRotationY(boneTransforms[p + 1]);
            owned.setRotationZ(boneTransforms[p + 2]);
            owned.setPositionX(boneTransforms[p + 3]);
            owned.setPositionY(boneTransforms[p + 4]);
            owned.setPositionZ(boneTransforms[p + 5]);
            owned.setScaleX(boneTransforms[p + 6]);
            owned.setScaleY(boneTransforms[p + 7]);
            owned.setScaleZ(boneTransforms[p + 8]);
        }
    }

    /** 供密集测试日志使用的骨骼姿态摘要。 */
    public String describeBonePose() {
        StringBuilder result = new StringBuilder(bones.size() * 16);
        result.append("bones=")
            .append(bones.size());
        for (BoneEntry bone : bones) {
            int p = bone.index * PARAMETERS_PER_BONE;
            if (boneTransforms[p] == 0.0F && boneTransforms[p + 1] == 0.0F && boneTransforms[p + 2] == 0.0F) {
                continue;
            }
            result.append(" ")
                .append(bone.name)
                .append('(')
                .append(Float.toString(boneTransforms[p]))
                .append(',')
                .append(Float.toString(boneTransforms[p + 1]))
                .append(',')
                .append(Float.toString(boneTransforms[p + 2]))
                .append(')');
        }
        return result.toString();
    }

    /** 复制骨骼树并冻结当前骨骼参数。返回 null 表示该模型没有可用骨骼。 */
    static CapturedModel capture(GeoModel sharedModel, ResourceLocation texture, float widthScale, float heightScale,
        Matrix4f relativePose, String modelName) {
        return capture(sharedModel, null, texture, widthScale, heightScale, relativePose, modelName);
    }

    /**
     * 复制骨骼树并冻结参数。
     *
     * @param capturedParameters 为 null 时读取骨骼当前状态；否则使用调用方提供的参数数组，
     *                           这样可以在模型已经被重新动画之后仍然复现某一帧的姿态。
     * @return null 表示该模型没有可用骨骼。
     */
    static CapturedModel capture(GeoModel sharedModel, float[] capturedParameters, ResourceLocation texture,
        float widthScale, float heightScale, Matrix4f relativePose, String modelName) {
        if (sharedModel == null) {
            return null;
        }
        List<GeoBone> sources = new ArrayList<>();
        List<Integer> parents = new ArrayList<>();
        for (GeoBone topLevel : sharedModel.topLevelBones) {
            flatten(topLevel, -1, sources, parents);
        }
        if (sources.isEmpty()) {
            return null;
        }

        List<GeoBone> owned = new ArrayList<>(sources.size());
        for (GeoBone source : sources) {
            GeoBone copy = new GeoBone();
            copy.name = source.name;
            copy.mirror = source.mirror;
            copy.inflate = source.inflate;
            copy.dontRender = source.dontRender;
            copy.reset = source.reset;
            copy.isHidden = source.isHidden;
            copy.areCubesHidden = source.areCubesHidden;
            copy.hideChildBonesToo = source.hideChildBonesToo;
            copy.rotationPointX = source.rotationPointX;
            copy.rotationPointY = source.rotationPointY;
            copy.rotationPointZ = source.rotationPointZ;
            // 立方体是只读资源，直接共享引用。
            copy.childCubes = new ArrayList<>(source.childCubes);
            owned.add(copy);
        }
        GeoModel ownedModel = new GeoModel();
        ownedModel.properties = sharedModel.properties;
        for (int index = 0; index < owned.size(); index++) {
            int parent = parents.get(index);
            if (parent >= 0) {
                owned.get(index).parent = owned.get(parent);
                owned.get(parent).childBones.add(owned.get(index));
            } else {
                ownedModel.topLevelBones.add(owned.get(index));
            }
        }

        float[] parameters = new float[sources.size() * PARAMETERS_PER_BONE];
        boolean reuseCaptured = capturedParameters != null && capturedParameters.length >= parameters.length;
        if (reuseCaptured) {
            System.arraycopy(capturedParameters, 0, parameters, 0, parameters.length);
        }
        List<BoneEntry> entries = new ArrayList<>(sources.size());
        for (int index = 0; index < sources.size(); index++) {
            GeoBone source = sources.get(index);
            int p = index * PARAMETERS_PER_BONE;
            if (!reuseCaptured) {
                parameters[p] = source.getRotationX();
                parameters[p + 1] = source.getRotationY();
                parameters[p + 2] = source.getRotationZ();
                parameters[p + 3] = source.getPositionX();
                parameters[p + 4] = source.getPositionY();
                parameters[p + 5] = source.getPositionZ();
                parameters[p + 6] = source.getScaleX();
                parameters[p + 7] = source.getScaleY();
                parameters[p + 8] = source.getScaleZ();
            }
            float[] bounds = geometryBounds(source);
            entries.add(
                new BoneEntry(
                    index,
                    source.name,
                    parents.get(index),
                    source.rotationPointX,
                    source.rotationPointY,
                    source.rotationPointZ,
                    bounds[0],
                    bounds[1],
                    bounds[2],
                    bounds[3],
                    bounds[4],
                    bounds[5],
                    owned.get(index)));
        }
        CapturedModel captured = new CapturedModel(
            sharedModel,
            ownedModel,
            entries,
            parameters,
            texture,
            widthScale,
            heightScale,
            relativePose,
            modelName);
        captured.applyBones();
        return captured;
    }

    private static void flatten(GeoBone bone, int parentIndex, List<GeoBone> sources, List<Integer> parents) {
        int index = sources.size();
        sources.add(bone);
        parents.add(parentIndex);
        for (GeoBone child : bone.childBones) {
            flatten(child, index, sources, parents);
        }
    }

    /**
     * 计算一根骨骼所有立方体的包围盒。
     *
     * <p>
     * GeckoLib 的立方体顶点不包含立方体自身的枢轴旋转，渲染时才由
     * {@code moveToPivot * rotate * moveBackFromPivot} 施加，因此这里先把顶点按同一
     * 顺序变换，得到的包围盒才和实际渲染的外轮廓一致。返回值为
     * {@code {minX,minY,minZ,maxX,maxY,maxZ}}，没有几何时返回全 ±Infinity。
     * </p>
     */
    private static float[] geometryBounds(GeoBone bone) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (GeoCube cube : bone.childCubes) {
            Matrix4f transform = new Matrix4f()
                .translate(cube.pivot.x / 16.0F, cube.pivot.y / 16.0F, cube.pivot.z / 16.0F)
                .rotateZ(cube.rotation.z)
                .rotateY(cube.rotation.y)
                .rotateX(cube.rotation.x)
                .translate(-cube.pivot.x / 16.0F, -cube.pivot.y / 16.0F, -cube.pivot.z / 16.0F);
            if (cube.quads == null) {
                continue;
            }
            for (GeoQuad quad : cube.quads) {
                if (quad == null || quad.vertices == null) {
                    continue;
                }
                for (GeoVertex vertex : quad.vertices) {
                    if (vertex == null) {
                        continue;
                    }
                    org.joml.Vector3f point = new org.joml.Vector3f(
                        vertex.position.x,
                        vertex.position.y,
                        vertex.position.z);
                    transform.transformPosition(point);
                    minX = Math.min(minX, point.x);
                    minY = Math.min(minY, point.y);
                    minZ = Math.min(minZ, point.z);
                    maxX = Math.max(maxX, point.x);
                    maxY = Math.max(maxY, point.y);
                    maxZ = Math.max(maxZ, point.z);
                }
            }
        }
        return new float[] { minX, minY, minZ, maxX, maxY, maxZ };
    }
}
