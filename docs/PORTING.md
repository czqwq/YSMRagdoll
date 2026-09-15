# 1.20.1 → 1.7.10 移植对照

本文件记录 `tmp/YSMRagdoll-main`（Minecraft 1.20.1 / Forge 47.4.10，模组版本 0.5.25）
到本工程（Minecraft 1.7.10 / Forge 10.13.4.1614，GTNH 构建约定）的逐文件对照。
包名从 `com.ysmragdoll` 改为模板约定的 `com.Lilith.ysmragdoll`。

## 文件对照

| 上游文件 | 本工程文件 | 处理 |
| --- | --- | --- |
| `OpenYsmRagdollMod` | `ysmragdoll` | `@Mod` + `@SidedProxy`，协议版本常量保留 |
| `YsmRagdollLog` | `YsmRagdollLog` | Java 8 API 重写（`Paths.get` / `Files.write`） |
| `config/YsmRagdollConfig` | `config/YsmRagdollConfig` | `ForgeConfigSpec` → `Configuration`，键名与范围不变 |
| `network/RagdollNetwork` | `network/RagdollNetwork` | `SimpleChannel` → `SimpleNetworkWrapper`，包编号 0/1 不变 |
| `network/PlayerDeathSnapshot` | `network/PlayerDeathSnapshot` | record → 普通类，`FriendlyByteBuf` → `ByteBuf` |
| `network/ExplosionImpulseSnapshot` | `network/ExplosionImpulseSnapshot` | 同上 |
| `event/ServerDeathEvents` | `event/ServerDeathEvents` | `@EventBusSubscriber` → 实例 `@SubscribeEvent` |
| `event/ServerExplosionEvents` | `event/ServerExplosionEvents` | 去掉 `ObfuscationReflectionHelper`，直接读 public 字段 |
| `client/ClientBootstrap` | `ClientProxy#init` | 资源重载监听内联 |
| `client/RagdollKeyMappings` | `client/RagdollKeyMappings` | `KeyMapping` → `KeyBinding` |
| `client/RagdollLifetime` | `client/RagdollLifetime` | 逐行不变 |
| `client/ClientRagdollEvents` | `client/ClientRagdollEvents` | 事件类型与输入路径重写 |
| `client/ClientPerformanceLogger` | `client/ClientPerformanceLogger` | `Minecraft.getFps()` 是 private，改为自行按秒统计 |
| `client/ClientIntensiveLogger` | `client/ClientIntensiveLogger` | 同上；文件路径工具改为 `YsmRagdollLog.resolveRoot()` |
| `client/OpenYsmModelAdapter` | `client/YsmRagdollModelAdapter` + `client/CapturedModel` + `client/RagdollRenderer` | 拆成捕获、快照、渲染三部分，去掉 Mixin/反射/解密 |
| `mixin/Ysm265MeshCaptureMixin` | 无 | 见下文“为什么不需要 Mixin” |
| `client/physics/OpenYsmGeometryLoader` | 无 | ysmu 已烘焙几何 |
| `client/physics/OpenYsmCrypto` | 无 | 同上 |
| `client/physics/YsmSkeletonExtractor` | `client/physics/YsmSkeletonExtractor` | 反射 → 直接遍历 `CapturedModel.BoneEntry`，映射表与几何算法不变 |
| `client/physics/RagdollDefinition` | 同 | record → 不可变类；其余不变 |
| `client/physics/BonePoseMath` | 同 | 逐行不变（JOML） |
| `client/physics/GrabInertia` | 同 | 逐行不变 |
| `client/physics/PhysicsGrab` | 同 | 逐行不变 |
| `client/physics/GrabJointGuard` | 同 | 逐行不变（仅 Java 8 集合 API） |
| `client/physics/PlayerCollisionResponse` | 同 | 逐行不变 |
| `client/physics/FluidBuoyancy` | 同 | `FluidState` → `Material` + `BlockLiquid` |
| `client/physics/BlockCollisionCache` | 同 | `VoxelShape` → `Block#addCollisionBoxesToList` |
| `client/physics/ClientPhysicsWorld` | 同 | `Level` → `World`，`Player` → `EntityPlayer`，`AABB` → `AxisAlignedBB` |
| `client/physics/PhysicsRagdoll` | 同 | 同上；`renderDebug` 改用 Tessellator 线段 |
| `client/GravityGunController` | 同 | 输入与射线重写 |
| `client/YsmRagdollSettingsScreen` | 同 | `Screen` → `GuiScreen`，自绘滚动区域 |
| `com/elfmcys/yesstevemodel/resource/YSMFolderDeserializer` | 无 | 只服务于二进制解析器，本移植不再需要 |
| `src/test/...`（12 个文件） | `src/test/...`（6 个文件） | 保留与平台无关的 6 个；依赖 Forge/FriendlyByteBuf 捕获缓冲的测试不再适用 |

## 为什么不需要 Mixin

上游把 Mixin 注入 `com.elfmcys.yesstevemodel.ooOOo000OOO0ooO0oo0ooooO`（YSM 2.6.5 的混淆
网格类），原因是官方 YSM 只在渲染的最后一步才把骨骼变换数组交给最终提交网格，模型几何与
骨骼状态在别处不可得。

ysmu 是 YSM/OpenYSM 在 1.7.10 上的 GeckoLib 移植：

- 模型层级与立方体在 `GeckoLibCache#getGeoModels()` 中公开可查；
- 骨骼状态就是 `software.bernie.geckolib3.geo.render.built.GeoBone` 的公开字段；
- 动画管线可以显式调用：`AnimatedGeoModel#setLivingAnimations(animatable, uniqueID, event)`
  与渲染路径调用的是同一个方法，且没有任何 GL 副作用。

因此本移植版改为“直接运行一次动画管线 → 读取骨骼状态 → 深拷贝骨骼树”。
这同时消除了上游的若干已知问题（`Duplicate delegates`、捕获缓冲、依赖混淆签名）。

副作用是布娃娃必须依赖 ysmu 的 GeckoLib 版本；升级 ysmu 后如果
`AnimatedGeoModel` 的签名变化，需要同步调整 `YsmRagdollModelAdapter`。

## 坐标契约（与上游完全一致）

采用列向量，矩阵乘积右侧先作用。模型几何顶点、刚体长度和世界位置使用**方块**单位；
骨骼枢轴和参数数组中的平移使用**像素**，16 像素为 1 方块。每根骨骼 12 个 float 中，
0-2 是弧度旋转，3-5 是像素平移，6-8 是缩放，其余保留。

设像素枢轴为 `p`，像素平移为 `t`：

```text
L = T((p.x - t.x)/16, (p.y + t.y)/16, (p.z + t.z)/16)
    * Rz(rz) * Ry(ry) * Rx(rx) * S(sx, sy, sz)
    * T(-p.x/16, -p.y/16, -p.z/16)
```

该式与 GeckoLib 骨骼的 `translate * moveToPivot * rotate * scale * moveBackFromPivot`
完全等价（GeckoLib 的 `rotationPointX/Y/Z` 就是这里的 `p`），因此参数数组可以直接
写回 ysmu 的骨骼对象。

渲染根与物理根共用同一份变换：

```text
根 = T(世界坐标) * Ry(180° - 身体朝向) * S(宽缩放, 高缩放, 宽缩放)
```

其中 `Ry` 与实体渲染器的 `GlStateManager.rotate(180 - rotationYaw, 0, 1, 0)` 一致，
`S` 与 ysmu `IGeoRenderer#renderEarly` 的模型缩放一致。

## 渲染坐标约定

1.7.10 在 `RenderWorldLastEvent` 处的 MODELVIEW 是“相机相对”的
（`RenderManager.renderPosX/Y/Z` = 观察实体的插值位置，实体渲染把世界坐标减去它们后
传给 `RendererLivingEntity#doRender`）。因此：

- `ClientRagdollManager#render` 先 `glTranslated(-RenderManager.renderPosX, ...)`；
- 之后所有绘制（布娃娃、调试线框、牵引光束）都直接使用**世界坐标**。

## 与上游不一致或有意的差异

1. **没有 Mixin**（见上）。
2. **没有 OpenYSM 解密/几何补齐**（见上）。因此日志里不会再出现
   `未定位到当前模型的 OpenYSM main.json`。
3. **配置格式**：`config/ysmragdoll.cfg`（Forge 1.7.10 旧格式）而不是 TOML。
4. **爆炸威力读取**：直接读 public 字段，没有“读取失败后备到受影响方块范围”的分支。
5. **滚轮与快捷栏**：1.7.10 没有可以取消的鼠标输入事件，牵引消费滚轮时改为在同一 tick
   内把 `inventory.currentItem` 还原。
6. **旁观者判断**：1.7.10 没有旁观模式，相关分支被移除。
7. **密集测试日志**：1.7.10 的 `Minecraft.debugFPS` 是 private，改为用渲染帧间隔自行计算
   帧时间与瞬时 FPS。
8. **性能日志**：`Minecraft.getFps()` 不可用，改为按整秒窗口自行统计最低/最高帧率。
9. **设置界面**：用开关按钮代替复选框；滚动区域、可见性裁剪与文本换行自行实现，
   行为（草稿、应用、取消、非法输入提示、“立即保存”开关）保持一致。

## 构建期注意事项

- `usesShadowedDependencies = true`：JBullet 必须重定位后打包，否则会与其他携带
  JBullet 的模组冲突。重定位前缀由 GTNH 约定给出：`com.Lilith.ysmragdoll.shadow.`。
- `usesMixins = false`：本模组不使用 Mixin。ysmu 自己声明的 UniMixins 依赖仍然需要安装。
- `java3d:vecmath` 与 `org.joml` 都**不打包**：前者由 Minecraft 1.7.10 库集合提供，
  后者由 GTNHLib 提供（ysmu 本身也依赖）。
- Spotless 会重排 Java 源码格式；提交前请运行 `gradlew spotlessApply`，
  否则 `build` 会在 `spotlessJavaCheck` 阶段失败。
