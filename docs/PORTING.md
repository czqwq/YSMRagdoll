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

### ysmu API 契约（每次改 ysmu 都要重新核对）

本模组用 `compileOnly files("libs/ysmu-<version>-dev.jar")` 依赖 ysmu 的 MCP 名 dev jar，
**改了 ysmu 源码却没刷新这个 jar** 是最容易踩的坑：jar 停留在旧日期，编译期一切正常，
运行时行为却按旧 API 走。正确流程是在 ysmu 工程执行 `gradlew shadowJar`，把
`build/libs/ysmu-<version>-dev.jar` 覆盖到本工程 `libs/` 下，再重新构建。

当前依赖的全部 ysmu 表面：

| 类型 | 成员 |
| --- | --- |
| `client.ClientProxy` | `getInstance()` |
| `client.renderer.CustomPlayerRenderer` | `getGeoModelProvider()`、`getCustomPlayerEntity()`、`getUniqueID(animatable)` |
| `client.entity.CustomPlayerEntity` | `setPlayer`、`setMainModel`、`setTexture`、`getTexture`、`getWidthScale`、`getHeightScale` |
| `client.model.CustomPlayerModel` | `DEFAULT_MAIN_MODEL`、`DEFAULT_TEXTURE` |
| `eep.ExtendedModelInfo` | `get(player)`、`getModelId()`、`getSelectTexture()` |
| `data.NPCData` | `getData(entity)` → `EntityModelData#getModelId()/getTextureId()` |
| `util.ModelIdUtil` | `getMainId(id)` |
| `software.bernie.geckolib3.*` | 随 ysmu dev jar 一起打包（132 个类），签名需与运行时一致 |

**模型/贴图解析必须与 `CustomPlayerRenderer#applyEntityModel` 同序**：
`NPCData` 实体级覆盖 → 玩家 `ExtendedModelInfo` → ysmu 默认。
ysmu 把 `NPCData` 从 UUID 键改成实体 id 键之后，这套覆盖对玩家也生效；
只读 EEP 会让被覆盖的玩家出现"活人是 A 模型、尸体是 B 模型"的错位。
该优先级由 `RagdollModelResolutionTest` 锁定，改动 `chooseMainModel`/`chooseTexture` 时必须同步。

已知的有意差异：请求的模型在本客户端缺失时，ysmu 的替换渲染器会退回默认模型让实体
永远可见，而 `capture` 直接放弃捕获并只提示一次。两者都会让尸体与活人不一致，但放弃
捕获可以避免 `provider.getModel()` 抛 `GeoModelException` 在有限重试窗口里刷屏。

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

## 退出世界时的线程约束（重要）

1.7.10 的 `FMLNetworkEvent.ClientDisconnectionFromServerEvent` 由 **Netty 的 IO 线程** 派发，
而同一时刻客户端线程正可能在 `ClientPhysicsWorld#simulateFrame` 里步进 JBullet。
`DbvtBroadphase` + `HashedOverlappingPairCache` 没有任何同步，从另一个线程移除刚体会破坏
重叠对缓存，随后就在

```text
HashedOverlappingPairCache.removeOverlappingPair
  -> last.pProxy0  (NullPointerException)
```

上抛异常（9 具布娃娃 × 14 刚体的现场最容易命中）。

因此所有“世界之外”的清理入口都只做登记：

| 入口 | 线程 | 处理 |
| --- | --- | --- |
| 客户端断开连接 | Netty IO | `ClientRagdollManager#requestClear` |
| 世界卸载 / 切换维度 | 客户端（防御性） | 同上 |
| 资源重载 | 客户端 | 直接 `#clear` |

`requestClear` 只递增一个 `AtomicInteger` 并释放牵引约束，真正的 `clear()` 在下一个
`ClientRagdollManager#tick()`（客户端线程）开头执行；`render()` 在此期间跳过 `simulateFrame`。
`dispose()` 另外做了隔离：某具布娃娃的刚体移除失败时直接丢弃整个物理世界引用，
而不是让异常冒泡到事件总线。

## 牵引模式的输入路径

上游 1.20.1 只有 `InputEvent.InteractionKeyMappingTriggered` 一条输入路径。1.7.10 的
`PlayerInteractEvent` 会被原版方块交互分支与其他模组影响，因此本移植版同时提供：

1. `PlayerInteractEvent`（保留上游行为，并取消原版交互）；
2. `ClientTickEvent` 里对“使用键”的上升沿轮询。

“使用键是否按住”优先读 `GameSettings.keyBindUseItem`（支持玩家把使用键改绑到键盘），
再退回 `Mouse.isButtonDown(1)`。

瞄准射线与准星严格同源：

- 眼位：`prevPos + (pos - prevPos) * partialTick`，再按
  `posY + (getEyeHeight() - getDefaultEyeHeight())` 修正。1.7.10 里
  `EntityPlayer#posY` 已经把 `yOffset` 算进去了，原版 `getPosition` 也只用这个差值，
  直接再加一次 `getEyeHeight()` 会把视线抬高一截。
- 方向：`EntityLivingBase#getLook(partialTick)`（原版 `getMouseOver` 用的同一个方法），
  使用前归一化，零长度或 NaN 退回 `+Z`。
- 触及距离：`PlayerControllerMP#getBlockReachDistance()` 与 `MAX_DISTANCE` 的较大者，
  再按原版方块射线裁剪。

直接用 `player.posX` 会取到上一 tick 的位置，近距离瞄准时会稳定偏出一个身位。

### 眼位绝不能存成 `Vec3`：原版射线会就地改写起点（现场 bug 的根因）

1.7.10 的 `World#rayTraceBlocks` → `func_147447_a` **会就地改写传入的起点向量**：
它直接对参数 `p_147447_1_` 的 `xCoord/yCoord/zCoord` 做增减，把起点沿射线一路推进到
命中点，最后才用这个状态构造 `MovingObjectPosition`。

历史上本控制器把 `Aim.eye` 存成 `Vec3` 并直接交给这条射线，于是调用方之后读到的
“视线起点”其实是**准星打到地面的那个点**：

```
牵引射线: 起点=(-1424.93, 2.00, -379.63), 终点=(-1424.93, 2.00, -379.63), 长度=0.00
        玩家位置=(-1424.61, 3.54, -380.70), 视线方向=(-0.16, -0.83, 0.54)
```

`起点 == 终点 == 地面命中点`，长度为零，JBullet 的 `CollisionWorld#rayTest` 于是把它
当成一次点查询，永远返回 `hasHit() == false`；方块裁剪也因为“命中点到命中点”的距离为
零而归零，整条牵引链路必然抓不到任何东西。

修法是让坐标离开可变向量：

- `Aim` 的字段全部是 `double`，`eyeVector()` / `endVector()` 每次调用都新建 `Vec3`；
- 传给方块射线的 `from`/`to` 是数组副本，原版改什么都没关系；
- `hit.hitVec` 读出来立刻转成 `double`，不长期持有。

同样的坑在另外两处也已修掉：`ClientRagdollManager#removeLookingAt`（空手右键清除）
与 `PhysicsRagdoll#isExplosionOccluded`（爆炸遮挡）都曾在射线之后继续使用被改写的起点。
`GravityGunAimTest#vanillaInPlaceMutationCannotCorruptTheAim` 用一个会就地改写
`from` 的假 tracer 固定住这条行为。

另外，方块把射线裁剪到 0.5 格以内时（眼睛本身就贴着方块）保留完整触及距离，否则裁剪出来
的线段又会退化成一次点查询。

### 兜底选取：视线附近最近的肢体

精确射线查询落空时不再直接判负，而是交给
`ClientPhysicsWorld#selectNearest` 做一次纯几何选取：

1. 对每个肢体的碰撞盒中心求它在视线直线上的投影，得到沿视线的距离 `along` 与垂距
   `perpendicular`；
2. 只保留 `0 <= along <= 触及距离` 且 `perpendicular <= 0.9` 的候选；
3. 取垂距最小者（并列时取更近的），抓取锚点落在该次投影处，并保证不超出碰撞盒外接球。

这条兜底是刻意的偏离：上游要求准星像素级命中，1.7.10 下模型与碰撞盒更容易错开。
它只改变"选中哪一个肢体"，抓到之后仍然走 `PhysicsRagdoll#grab` 建立同样的点对点约束，
手感与上游一致。范围上限取三者最小：实际线段长度、方块裁剪后的触及距离
（`Aim#clippedReach`）、硬上限 12 格，因此隔着方块时不会抓到墙后的尸体。

相关日志有两行：

- `ClientPhysicsWorld#grab` 每次调用打印 `牵引射线:`，包含线段端点与长度、
  是"精确命中"还是"精确射线未命中"还是"回退到最近肢体(垂距=…)"、选中部位、抓取锚点、
  刚体数量和 AABB 相交数量；
- 按下后第一次没抓到时 `GravityGunController` 打印一次 `牵引模式未命中布娃娃:`，
  额外包含视线方向、线段长度、玩家坐标、眼高修正与偏航/俯仰——线段长度为零（射线退化）、
  方向为零、玩家坐标非有限值这三种情况都能从这一行直接读出来。

确认现场稳定后可以删除这两行与 `countBodiesNear`、`format(double, double, double)`。

## 构建期注意事项

- `usesShadowedDependencies = true`：JBullet 必须重定位后打包，否则会与其他携带
  JBullet 的模组冲突。重定位前缀由 GTNH 约定给出：`com.Lilith.ysmragdoll.shadow.`。
- `usesMixins = false`：本模组不使用 Mixin。ysmu 自己声明的 UniMixins 依赖仍然需要安装。
- `java3d:vecmath` 与 `org.joml` 都**不打包**：前者由 Minecraft 1.7.10 库集合提供，
  后者由 GTNHLib 提供（ysmu 本身也依赖）。
- Spotless 会重排 Java 源码格式；提交前请运行 `gradlew spotlessApply`，
  否则 `build` 会在 `spotlessJavaCheck` 阶段失败。
