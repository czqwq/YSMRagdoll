package com.Lilith.ysmragdoll.client;

import java.util.Collections;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

import org.joml.Matrix4f;

import com.Lilith.ysmragdoll.YsmRagdollLog;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.data.NPCData;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.util.ModelIdUtil;

import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.AnimatedGeoModel;
import software.bernie.geckolib3.model.provider.data.EntityModelData;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * 布娃娃模型捕获入口：把 ysmu 的运行时模型冻结成本具尸体独占的快照。
 *
 * <p>
 * 上游 1.20.1 版本必须在 YSM 提交最终网格的那一刻用 Mixin 抓取混淆的运行时
 * 网格，并按 OpenYSM 规则解密 {@code .ysm} 文件补齐立方体。ysmu 是 YSM/OpenYSM
 * 在 1.7.10 上的 GeckoLib 移植，模型层级、立方体和骨骼状态都是公开 API，
 * 因此这里直接调用一次模型的动画管线拿到当帧姿态，再深拷贝骨骼树即可，
 * 完全不需要 Mixin、反射或二进制解密。
 * </p>
 *
 * <p>
 * 本类所有入口都做 {@link Throwable} 级别的隔离：没有安装 ysmu 时布娃娃功能
 * 只会在日志里说明原因，不会影响游戏启动或渲染。
 * </p>
 */
public final class YsmRagdollModelAdapter {

    /** 同一个缺失模型只提示一次，避免有限重试窗口刷屏。 */
    private static final java.util.Set<String> REPORTED_MISSING_MODELS = java.util.Collections
        .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private static void reportMissingModel(ResourceLocation mainId) {
        if (REPORTED_MISSING_MODELS.add(mainId.toString())) {
            YsmRagdollLog.warn("未在 ysmu 模型缓存中找到玩家模型: " + mainId + "，本次布娃娃捕获跳过");
        }
    }

    private YsmRagdollModelAdapter() {}

    /** ysmu 是否已经完成客户端初始化。 */
    public static boolean isAvailable() {
        try {
            return com.fox.ysmu.client.ClientProxy.getInstance() != null;
        } catch (Throwable throwable) {
            return false;
        }
    }

    /**
     * 捕获指定玩家当前帧的 YSM 模型。
     *
     * @return 可用的快照；模型未加载、ysmu 缺失或任何兼容问题时返回 null。
     */
    public static CapturedModel capture(EntityPlayer player, float bodyYaw, float partialTick) {
        if (player == null) {
            return null;
        }
        try {
            return captureInternal(player, bodyYaw, partialTick);
        } catch (Throwable throwable) {
            YsmRagdollLog.warn("捕获 YSM 模型失败，当前尸体退回静态快照: " + throwable);
            return null;
        }
    }

    private static CapturedModel captureInternal(EntityPlayer player, float bodyYaw, float partialTick) {
        com.fox.ysmu.client.renderer.CustomPlayerRenderer renderer = com.fox.ysmu.client.ClientProxy.getInstance();
        if (renderer == null) {
            YsmRagdollLog.warn("未加载 ysmu（YesSteveModel-Unofficial），无法捕获玩家模型");
            return null;
        }
        AnimatedGeoModel provider = renderer.getGeoModelProvider();
        if (provider == null) {
            YsmRagdollLog.warn("ysmu 尚未注册玩家模型 provider，无法捕获玩家模型");
            return null;
        }

        ResourceLocation mainId = resolveMainModel(player);
        // 先查缓存再调用 provider.getModel()，否则模型缺失时 getModel 会抛 GeoModelException，
        // 有限重试窗口里会反复打印同一条堆栈。
        GeoModel geoModel = GeckoLibCache.getInstance()
            .getGeoModels()
            .get(mainId);
        if (geoModel == null) {
            reportMissingModel(mainId);
            return null;
        }
        // 该调用同时把模型骨骼注册到动画处理器，动画管线才能写回姿态。
        provider.getModel(mainId);

        com.fox.ysmu.client.entity.CustomPlayerEntity animatable = renderer.getCustomPlayerEntity();
        animatable.setPlayer(player);
        animatable.setMainModel(mainId);
        animatable.setTexture(resolveTexture(player));

        // 直接运行一次模型动画管线。这一步只写入共享模型对象的骨骼状态，
        // 不产生任何 GL 副作用；随后我们立刻把结果拷贝进独占快照。
        provider.setLivingAnimations(
            animatable,
            renderer.getUniqueID(animatable),
            buildAnimationEvent(player, animatable, partialTick));

        float widthScale = animatable.getWidthScale();
        float heightScale = animatable.getHeightScale();
        return CapturedModel.capture(
            geoModel,
            animatable.getTexture(),
            widthScale,
            heightScale,
            relativePose(bodyYaw, widthScale, heightScale),
            mainId.toString());
    }

    /**
     * 构造与 {@code GeoReplacedEntityRenderer#doRender} 一致的动画事件。
     *
     * <p>
     * 必须提供 {@link EntityModelData}：ysmu 的 {@code CustomPlayerModel} 只在
     * 额外数据恰好包含一个 {@code EntityModelData} 时才应用头部朝向和动画状态。
     * </p>
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static AnimationEvent<?> buildAnimationEvent(EntityPlayer player,
        software.bernie.geckolib3.core.IAnimatable animatable, float partialTick) {
        EntityModelData data = new EntityModelData();
        data.isSitting = player.isRiding() && player.ridingEntity != null && player.ridingEntity.shouldRiderSit();
        data.isChild = player.isChild();

        float bodyYaw = player.renderYawOffset;
        float headYaw = player.rotationYawHead;
        float netHeadYaw = MathHelper.wrapAngleTo180_float(headYaw - bodyYaw);
        float headPitch = player.rotationPitch;
        data.headPitch = -headPitch;
        data.netHeadYaw = -netHeadYaw;

        float limbSwingAmount = player.limbSwingAmount;
        float limbSwing = player.limbSwing - player.limbSwingAmount * (1.0F - partialTick);
        if (limbSwingAmount > 1.0F) {
            limbSwingAmount = 1.0F;
        }
        return new AnimationEvent(
            animatable,
            limbSwing,
            limbSwingAmount,
            partialTick,
            !(limbSwingAmount > -0.15F && limbSwingAmount < 0.15F),
            Collections.singletonList(data));
    }

    /**
     * 模型坐标到世界坐标的根变换，必须与 {@code RagdollRenderer} 实际使用的 GL 变换一致：
     * 实体渲染器先按身体朝向旋转 {@code 180 - yaw}，GeckoLib 的 {@code renderEarly}
     * 再按模型缩放缩放一次。两者都写进相对矩阵，物理碰撞箱才会和渲染重合。
     */
    private static Matrix4f relativePose(float bodyYaw, float widthScale, float heightScale) {
        float radians = (float) Math.toRadians(180.0D - bodyYaw);
        return new Matrix4f().rotateY(radians)
            .scale(widthScale, heightScale, widthScale);
    }

    /**
     * 解析玩家当前应当渲染的主模型，优先级与 ysmu 的
      * {@code CustomPlayerRenderer#resolveOverride} 完全一致。
     *
     * <p>
     * 布娃娃必须显出"活人正在渲染的那套模型"，所以这里不能自己再定一套规则。
     * ysmu 现在把 {@code NPCData} 从 UUID 键改成了实体 id 键，并且**先查实体级覆盖、
     * 再查玩家 EEP**——同一套覆盖对玩家也生效（{@code EntityModelApi} /
     * {@code EntityModelRenderApi} 就是给同伴模组写这份覆盖用的）。若仍只读 EEP，
     * 被覆盖过的玩家会出现"活人是 A 模型、尸体是 B 模型"。
     * </p>
     */
    private static ResourceLocation resolveMainModel(EntityPlayer player) {
        com.fox.ysmu.data.EntityModelData override = NPCData.getData(player);
        ExtendedModelInfo info = ExtendedModelInfo.get(player);
        return chooseMainModel(
            override == null ? null : override.getModelId(),
            info == null ? null : info.getModelId());
    }

    /** 与 {@link #resolveMainModel} 同源的贴图解析，优先级同样对齐 ysmu。 */
    private static ResourceLocation resolveTexture(EntityPlayer player) {
        com.fox.ysmu.data.EntityModelData override = NPCData.getData(player);
        ExtendedModelInfo info = ExtendedModelInfo.get(player);
        return chooseTexture(
            override == null ? null : override.getTextureId(),
            info == null ? null : info.getSelectTexture());
    }

    /** 实体级覆盖 &gt; 玩家 EEP &gt; ysmu 默认模型；抽成纯函数便于单测锁定优先级。 */
    static ResourceLocation chooseMainModel(ResourceLocation overrideModel, ResourceLocation eepModel) {
        if (overrideModel != null) {
            return ModelIdUtil.getMainId(overrideModel);
        }
        if (eepModel != null) {
            return ModelIdUtil.getMainId(eepModel);
        }
        return CustomPlayerModel.DEFAULT_MAIN_MODEL;
    }

    /** 实体级覆盖 &gt; 玩家 EEP &gt; ysmu 默认贴图。 */
    static ResourceLocation chooseTexture(ResourceLocation overrideTexture, ResourceLocation eepTexture) {
        if (overrideTexture != null) {
            return overrideTexture;
        }
        if (eepTexture != null) {
            return eepTexture;
        }
        return CustomPlayerModel.DEFAULT_TEXTURE;
    }
}
