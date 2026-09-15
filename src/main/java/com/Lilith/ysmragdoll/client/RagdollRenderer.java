package com.Lilith.ysmragdoll.client;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.provider.GeoModelProvider;

/**
 * 直接用 GeckoLib 的模型遍历器绘制布娃娃快照。
 *
 * <p>
 * 与活玩家渲染不同的是：这里渲染的是每具尸体独占的骨骼副本，所以布娃娃的
 * 姿态不会反过来影响正在渲染的玩家；同时省掉了 {@code renderEarly} 的缩放，
 * 改由 {@link CapturedModel#relativePose()} 在物理根矩阵里体现，保证碰撞箱和
 * 渲染使用同一个根变换。
 * </p>
 */
public final class RagdollRenderer implements IGeoRenderer<CapturedModel> {

    public static final RagdollRenderer INSTANCE = new RagdollRenderer();

    private RagdollRenderer() {}

    @SuppressWarnings("rawtypes")
    @Override
    public GeoModelProvider getGeoModelProvider() {
        try {
            com.fox.ysmu.client.renderer.CustomPlayerRenderer renderer = com.fox.ysmu.client.ClientProxy.getInstance();
            return renderer == null ? null : renderer.getGeoModelProvider();
        } catch (Throwable throwable) {
            return null;
        }
    }

    @Override
    public net.minecraft.util.ResourceLocation getTextureLocation(CapturedModel instance) {
        return instance.texture();
    }

    /**
     * 在当前 GL 世界变换下绘制一具布娃娃。
     *
     * <p>
     * 调用方负责：把 GL 矩阵平移 {@code -RenderManager.renderPos*}、按身体朝向旋转、
     * 绑定纹理并设置混合/剔除状态。
     * </p>
     *
     * @param packedLight 打包后的亮度值，直接给 {@link Tessellator#setBrightness(int)}。
     */
    public void draw(CapturedModel model, int packedLight, float red, float green, float blue, float alpha) {
        if (model == null || !model.hasSnapshot()) {
            return;
        }
        model.applyBones();
        Tessellator tessellator = Tessellator.instance;
        GeoModel owned = model.ownedModel();
        // 与 ysmu 的 renderEarly 保持一致：模型缩放作用在所有骨骼之前。物理侧的
        // relativePose 也包含同一份缩放，渲染出来的外轮廓才会和碰撞箱重合。
        MATRIX_STACK.push();
        try {
            MATRIX_STACK.scale(model.widthScale(), model.heightScale(), model.widthScale());
            tessellator.startDrawing(GL11.GL_QUADS);
            tessellator.setBrightness(packedLight);
            for (GeoBone bone : owned.topLevelBones) {
                renderRecursively(tessellator, model, bone, red, green, blue, alpha);
            }
            tessellator.draw();
        } finally {
            MATRIX_STACK.pop();
        }
    }
}
