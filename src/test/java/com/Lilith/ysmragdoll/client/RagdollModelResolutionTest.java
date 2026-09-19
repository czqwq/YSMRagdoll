package com.Lilith.ysmragdoll.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import net.minecraft.util.ResourceLocation;

import org.junit.Test;

/**
 * 锁定布娃娃模型/贴图的解析优先级：实体级覆盖 &gt; 玩家 EEP &gt; ysmu 默认。
 *
 * <p>
 * ysmu 把 {@code NPCData} 从 UUID 键改成实体 id 键之后，这套覆盖对玩家也生效，
 * 而 {@code CustomPlayerRenderer#resolveOverride} 是先查覆盖、再查 EEP。
 * 本模组旧代码只读 EEP，被覆盖过的玩家在<b>死亡当帧</b>会出现"活人是 A 模型、尸体是 B 模型"。
 * 这里把优先级固定成回归用例，防止适配器再退回只读 EEP 的写法。
 * </p>
 *
 * <p>
 * 设计边界：尸体是死亡瞬间的<b>冻结快照</b>（{@code CapturedModel} 深拷贝骨骼树，并记下
 * mainId 与贴图），玩家事后更换模型不会改变已生成的尸体。本文件锁定的只是"取哪一套模型
 * 来冻结"，不是让尸体跟随玩家实时变化。
 * </p>
 *
 * <p>
 * 期望值一律写字面量、不调用 ysmu 的 {@code ModelIdUtil}：ysmu 的 dev jar 在主源集是
 * {@code compileOnly}，而测试源集不继承 compileOnly（{@code compileTestJava} 看不到它），
 * 所以本文件不能出现任何 ysmu 的 import。运行时该 jar 由
 * {@code runtimeOnlyNonPublishable} 提供，{@code chooseMainModel} 内部仍能命中 ysmu 常量。
 * </p>
 */
public class RagdollModelResolutionTest {

    private static final ResourceLocation OVERRIDE_MODEL = new ResourceLocation("ysmu", "override_model");
    private static final ResourceLocation EEP_MODEL = new ResourceLocation("ysmu", "eep_model");
    private static final ResourceLocation OVERRIDE_MAIN = new ResourceLocation("ysmu", "override_model/main");
    private static final ResourceLocation EEP_MAIN = new ResourceLocation("ysmu", "eep_model/main");
    private static final ResourceLocation OVERRIDE_TEXTURE = new ResourceLocation("ysmu", "override.png");
    private static final ResourceLocation EEP_TEXTURE = new ResourceLocation("ysmu", "eep.png");

    @Test
    public void entityOverrideWinsOverPlayerEep() {
        assertEquals(OVERRIDE_MAIN, YsmRagdollModelAdapter.chooseMainModel(OVERRIDE_MODEL, EEP_MODEL));
        // 回归点：覆盖存在时绝不能走 EEP 分支
        assertNotEquals(EEP_MAIN, YsmRagdollModelAdapter.chooseMainModel(OVERRIDE_MODEL, EEP_MODEL));
    }

    @Test
    public void eepIsUsedWhenThereIsNoOverride() {
        assertEquals(EEP_MAIN, YsmRagdollModelAdapter.chooseMainModel(null, EEP_MODEL));
    }

    @Test
    public void overrideTextureWinsOverEepTexture() {
        assertEquals(OVERRIDE_TEXTURE, YsmRagdollModelAdapter.chooseTexture(OVERRIDE_TEXTURE, EEP_TEXTURE));
        assertEquals(EEP_TEXTURE, YsmRagdollModelAdapter.chooseTexture(null, EEP_TEXTURE));
    }

    @Test
    public void missingEverythingFallsBackToYsmDefaults() {
        assertEquals(new ResourceLocation("ysmu", "default/main"), YsmRagdollModelAdapter.chooseMainModel(null, null));
        assertEquals(
            new ResourceLocation("ysmu", "default/default.png"),
            YsmRagdollModelAdapter.chooseTexture(null, null));
    }
}
