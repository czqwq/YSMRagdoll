# YSM Ragdoll（1.7.10 / GTNH 移植版）

`ysmragdoll` 是面向 Minecraft **1.7.10 Forge**（GTNH 环境）与
[YesSteveModel-Unofficial](https://github.com/Huli-fox/YesSteveModel-Unofficial)（mod id: `ysmu`）
的客户端布娃娃物理模组。玩家死亡后，服务端只广播一份很小的死亡状态；安装模组的客户端从
本机已经加载的 YSM 模型中冻结骨骼姿态与真实几何，再用 JBullet 创建独立物理尸体。
高级设置也可以直接用当前玩家模型创建本地测试布娃娃，不需要玩家死亡或服务端发送快照。

本工程是 1.20.1 Forge 版 [YSM Ragdoll](https://github.com/Esnowflake/YSMRagdoll) 的完整移植，
源码基线为 `tmp/YSMRagdoll-main`（版本 0.5.25）。

## 运行环境

| 项目 | 要求 / 当前值                                                                                                                       |
| --- |-------------------------------------------------------------------------------------------------------------------------------------|
| Minecraft | `1.7.10`                                                                                                                            |
| Forge | `10.13.4.1614`                                                                                                                      |
| Java | 编译使用 JDK 17+（Jabel 现代语法，产物为 JVM 8 字节码）                                                                             |
| 主体模组 | `ysmu`（YesSteveModel-Unofficial），已验证源码版本 `5.09.52.417`，[依赖下载地址](https://github.com/czqwq/YesSteveModel-Unofficial) |
| 运行时库 | UniMixins、GTNHLib（joml 1.10.8 由 GTNHLib 提供）、`java3d:vecmath:1.3.1`（Minecraft 自带库）                                       |
| 物理引擎 | JBullet `20101010-1`（重定位后打包进成品 JAR）                                                                                      |
| Mod ID | `ysmragdoll`                                                                                                                        |
| 网络协议 | `2`                                                                                                                                 |

## 构建

```powershell
.\gradlew.bat build        # 编译、测试、重定位依赖、重混淆
.\gradlew.bat test         # 只跑自动化测试
.\gradlew.bat runClient    # 开发客户端（需要 ysmu 与至少一个玩家模型）
```

主要产物：

```text
build/libs/ysmragdoll-<version>.jar        # 发布用（已内含重定位后的 JBullet）
build/libs/ysmragdoll-<version>-dev.jar    # 开发用
```

### 主体模组依赖

本工程直接编译 ysmu 的公开 API（GeckoLib 模型缓存、骨骼层级、玩家模型 EEP），
因此需要一个 **MCP 名称的 ysmu dev jar**：

```powershell
# 在 YesSteveModel-Unofficial 工程目录
.\gradlew.bat shadowJar
copy build\libs\ysmu-<version>-dev.jar <本工程>\libs\
```

`dependencies.gradle` 中当前引用的是 `libs/ysmu-5.09.52.417-dev.jar`。
升级 ysmu 后只需要替换该文件并更新依赖坐标中的版本号。

## 架构

### 数据流

```text
服务端                                客户端
------                                ------
玩家死亡 ──► PlayerDeathSnapshot ──►  YsmRagdollModelAdapter.capture()
(TrackingEntity 广播)                   ├─ 读取 ysmu 的 GeoModel（模型缓存）
                                        ├─ 运行一次模型动画管线
爆炸      ──► ExplosionImpulseSnapshot ─┤  └─ 读取每根骨骼的 TRS 参数
(整维度广播)                             ├─ 深拷贝骨骼树（立方体只读共享）
                                        └─ 生成 CapturedModel（独占快照）
                                              │
                                              ▼
                                        YsmSkeletonExtractor.extract()
                                        └─ 映射 14 个主要部位 + 生成旋转盒尺寸
                                              │
                                              ▼
                                        PhysicsRagdoll (JBullet)
                                        ├─ BoxShape 刚体 + Generic6DofConstraint 关节
                                        ├─ 120 Hz 固定子步 + 方块静态碰撞
                                        ├─ 玩家推动 / 爆炸冲量 / 流体浮力 / 牵引
                                        └─ writePose() 反解回骨骼参数
                                              │
                                              ▼
                                        RagdollRenderer.draw()
                                        └─ 用 GeckoLib 遍历器绘制独占骨骼副本
```

### 与上游 1.20.1 版本的关键差异

| 上游 1.20.1 实现 | 本移植版实现 | 原因 |
| --- | --- | --- |
| Mixin 注入 YSM 2.6.5 混淆类抓取最终网格 | 直接调用 ysmu 的 `AnimatedGeoModel#setLivingAnimations`，再读取公开的 `GeoBone` 状态 | ysmu 建立在 GeckoLib 之上，模型层级、立方体和骨骼状态都是公开 API，不再需要混淆网格钩子，因此本模组**不使用 Mixin** |
| `OpenYsmGeometryLoader` + `OpenYsmCrypto`（CityHash / MT19937 / XChaCha20 / YSM Zstd）解密 `.ysm` 补齐立方体 | 直接读取 `GeoBone.childCubes` → `GeoCube.quads` 顶点 | ysmu 已把模型烘焙成 GeckoLib 模型，几何始终可用，不需要二进制解密路径 |
| PoseStack + VertexConsumer + RenderType 渲染 | `Tessellator` + `GL11` + GeckoLib `MatrixStack` | 1.7.10 渲染管线 |
| `ForgeConfigSpec`（TOML） | `net.minecraftforge.common.config.Configuration`（`config/ysmragdoll.cfg`） | 1.7.10 只有旧版配置 API |
| `SimpleChannel` + `DistExecutor` | `SimpleNetworkWrapper` + `SidedProxy` | 1.7.10 网络与双端隔离方式 |
| `Explosion` 私有字段混淆安全反射 | 直接读 public 字段 `explosionSize` | 1.7.10 该字段就是 public，不需要反射与后备路径 |
| `VoxelShape#toAabbs()` | `Block#addCollisionBoxesToList` | 同样支持楼梯/栅栏等多盒方块 |
| `FluidState#getHeight` | `Material#isLiquid` + `BlockLiquid.getLiquidHeightPercent` | 1.7.10 流体表示方式 |
| `Screen` + `EditBox` + `Checkbox` | `GuiScreen` + `GuiTextField` + 开关按钮，自绘滚动区域 | 1.7.10 GUI 组件集 |
| `RenderLevelStageEvent.AFTER_ENTITIES` | `RenderWorldLastEvent` + `-RenderManager.renderPos*` 平移 | 1.7.10 世界渲染挂钩 |

上游的 `BonePoseMath`、`GrabInertia`、`PhysicsGrab`、`GrabJointGuard`、
`PlayerCollisionResponse`、`RagdollDefinition` 与物理常量**逐行保留**，
坐标契约（枢轴/平移为像素，顶点为方块，旋转顺序 Z/Y/X，每骨骼 12 个 float）完全一致。

## 功能

### 死亡布娃娃

服务端在玩家死亡时发送 `PlayerDeathSnapshot`（实体编号、UUID、位置、线速度、身体朝向，
不含模型数据）。客户端在玩家仍在追踪范围内时捕获模型快照并建立物理尸体。
捕获失败时进入最多 40 次、每 2 tick 一次的有限重试。

### 物理

- 最多 14 个主要刚体：躯干、头部、左右上臂/前臂/手、左右大腿/小腿/脚；
- 父子刚体使用 `Generic6DofConstraint`，平移全锁，旋转按人体关节限位；
- 所有客户端布娃娃共享一个 JBullet 世界，渲染帧累积后以 **120 Hz 固定子步**推进，
  单帧最多追赶 8 步；
- 只与方块静态碰撞盒碰撞（水平 3 格、垂直 4 格），布娃娃之间不自碰撞；
- 离散穿透纠正以整具布娃娃为单位上移，避免关节储存弹性能量；
- 新放置的方块有 20 tick 的推出保护。

### 玩家推动

易推动指数 0-99 使用渐进冲量（约 55% 给整套刚体、45% 给接触肢体）；
指数为 100 时改用玩家碰撞箱的凸体扫掠与穿透分离，整组平移避免拉伸关节。

### 爆炸冲击

服务端监听 `ExplosionEvent.Detonate`，广播爆心与威力。客户端按肢体盒边缘距离线性衰减，
并用方块射线近似遮挡；爆炸快照保留 1 秒，覆盖“先爆炸、后死亡”的顺序。

### 牵引模式（原“重力枪”）

测试分类里开启后，主手拿木棍按住右键牵引布娃娃，滚轮调距（1.5-12 格），松开继承有限速度。
1.7.10 没有可取消的鼠标输入事件，因此牵引消费滚轮时会把快捷栏槽位还原，视觉上不切换物品。

### 液体浮力

按刚体包围盒与液体体积的交集计算浸入比例，水与岩浆使用不同阻力，离开液体后停止。

### 设置界面

快捷键（默认未绑定）打开；包含常规 / 高级 / 布娃娃管理 / 测试四个分类，
草稿式编辑（取消不保存、应用写回并落盘）、滚轮与滚动条翻页、非法输入提示。
标有“立即保存”的开关不受取消按钮影响。

## 日志与排查

关键日志写入 `<工程目录或游戏工作目录>/ysm-ragdoll-logs/ysm-ragdoll.log`，
达到 8 MiB 轮转为 `ysm-ragdoll.previous.log`；常规信息同时镜像到 Forge 主日志（前缀
`[ysmragdoll]`）。密集测试（高级设置）额外写入 `ysm-ragdoll-intensive.log`。

强制指定目录：`-Dysmragdoll.projectDir=D:\path\to\project`。

| 日志 / 现象 | 含义与检查方向 |
| --- | --- |
| `未加载 ysmu（YesSteveModel-Unofficial）` | 客户端没有安装主体模组；布娃娃功能整体不可用 |
| `未在 ysmu 模型缓存中找到玩家模型` | 玩家当前模型尚未加载完成；同一模型只提示一次 |
| `YSM 模型没有可识别的躯干骨骼` | 模型命名无法映射物理骨架，尸体保留静态快照 |
| `忽略短时间内重复的死亡快照` | 1.5 秒去重窗口内收到重复包 |
| `死亡玩家已离开客户端追踪范围` | 死亡包到达时实体已经不在客户端世界中 |
| `布娃娃所在区块未加载，暂停物理并隐藏尸体` | 区块卸载；重新靠近会自动恢复 |
| 模型与红/黄线框不重合 | 打开高级设置检查碰撞偏移与渲染偏移，正常使用建议全部为 0 |
| 没有任何布娃娃 | 检查服务端是否安装、数量上限是否为 0、是否低于 Y=-64 |

## 自动化测试

`gradlew test` 运行 25 项与平台无关的回归测试：

| 测试 | 覆盖 |
| --- | --- |
| `BonePoseMathTest` | 平移/旋转/镜像缩放往返、绑定矩阵、碰撞盒尺寸变换 |
| `GrabInertiaTest` | 起拉/急停/反向的惯性反馈上限、受阻后速度衰减 |
| `PhysicsGrabTest` | 点约束牵引、旋转肢体锚点、世界偏移、失效释放 |
| `GrabJointGuardTest` | 关节防拉伸、整组跟随、受阻降级、分离速度消除 |
| `PlayerCollisionResponseTest` | 高速穿透、静止挤出、后退不拖动、整组分离、旋转凸体 |
| `RagdollLifetimeTest` | 寿命/永久/手动清除与虚空边界 |

这些测试使用简化刚体，**不能替代游戏内验证**。

## 已知边界

- 底层仍然依赖 ysmu 的模型与骨骼命名；模型骨骼差异过大时只能得到部分刚体，
  缺少躯干时退回静态快照；
- 当前物理是客户端视觉模拟，不是服务端权威实体，不能参与伤害、寻路、红石或原版实体碰撞；
- 不保存跨世界尸体，也不把玩家模型写入存档；
- 头发、裙摆、披风等装饰骨骼没有独立刚体（但会保留捕获时的局部姿态）；
- 碰撞体是旋转盒而不是逐三角形网格，这是稳定性与性能的有意取舍；
- 玩家死亡到复活之间，原版玩家实体仍在世界中，ysmu 也会继续渲染它的死亡姿态，
  因此短时间内可能同时看到死亡玩家与布娃娃（与上游行为一致）。

## 游戏内验证状态

本移植版已完成编译、25 项自动测试、`shadowJar`（JBullet 重定位打包）与 `reobfJar`
全流程构建。**尚未在本机进入实际游戏验证**，建议按以下顺序确认：

1. 单人游戏中安装 ysmu 并选好一个玩家模型，确认模型正常显示；
2. `/kill` 或受到致命伤害，确认出现布娃娃而不是静态模型；
3. 打开高级设置 → “创建一个布娃娃”，确认手动创建与数量上限淘汰；
4. 打开“显示碰撞箱”，确认线框与模型部位同心（先保证六个偏移都是 0）；
5. 在台阶、半砖、楼梯、栅栏旁倒地，确认没有持续抖动或穿地；
6. 玩家走过布娃娃，确认推动生效；把易推动指数改为 100 再验证强制分离；
7. TNT / 苦力怕爆炸，确认肢体被冲开而不是整体平移；
8. 进入水中确认上浮、岩浆中确认缓慢下沉；
9. 开启牵引模式验证抓取、滚轮调距与松手甩出。

## 许可证

本工程代码采用 MIT 许可证，见 `LICENSE`。第三方组件与许可证见
`THIRD_PARTY_NOTICES.md`。

本工程与发行 JAR 不包含 Minecraft、ysmu 主体模组，也不包含任何玩家模型、纹理或动画资源。
