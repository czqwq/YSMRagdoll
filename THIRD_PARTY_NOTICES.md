# 第三方组件与许可证

本工程自身代码采用 MIT 许可证（见 `LICENSE`）。以下第三方组件在构建或运行时被引用。

## JBullet

- 坐标：`cz.advel.jbullet:jbullet:20101010-1`
- 用途：布娃娃刚体、关节约束与碰撞求解。
- 分发方式：通过 GTNH 构建约定的 `shadowImplementation` 以**重定位后的包名**
  （`com.Lilith.ysmragdoll.shadow.com.bulletphysics`）打包进成品 JAR，
  不使用 `minimizeShadowedDependencies` 之外的额外裁剪。
- 许可证：zlib/libpng 风格（JBullet 为 Bullet Physics 的 Java 移植，见上游仓库的
  `LICENSE`）。发布前请自行确认与项目分发方式一致。

## javax.vecmath（java3d:vecmath）

- 坐标：`java3d:vecmath:1.3.1`
- 用途：JBullet 的向量/矩阵类型，同时也是 ysmu 内置 GeckoLib 的类型。
- 分发方式：**不打包**。该库由 Minecraft 1.7.10 的库集合提供，构建约定把它放进
  `compileClasspath` 与 `runtimeClasspath`。
- 许可证：Java 3D 附带的 vecmath 许可证（GPLv2 + Classpath Exception）。

## JOML

- 坐标：`org.joml:joml:1.10.8`
- 用途：骨骼矩阵、刚体世界矩阵与姿态反解。
- 分发方式：**不打包**，由 GTNHLib 在运行时提供（ysmu 本身也依赖它）。
- 许可证：MIT。

## YesSteveModel-Unofficial（ysmu）

- 用途：主体模组。本模组读取它的 GeckoLib 模型缓存、骨骼层级与玩家模型 EEP。
- 分发方式：**不打包**，玩家需要自行安装。
- 许可证：见上游仓库。

## YesSteveModel / OpenYSM

- 上游 1.20.1 版本的 YSM Ragdoll 曾以 OpenYSM 作为编译期依赖来解密 `.ysm` 模型。
- 本移植版**不再需要** OpenYSM 的解密与二进制解析路径，因为 ysmu 已经把模型烘焙为
  GeckoLib 模型；因此仓库中不包含、也不依赖任何 OpenYSM 代码或二进制。
- 仅保留了上游代码在 `YsmSkeletonExtractor` 中的部位命名启发式规则（纯字符串匹配）。

## 上游 YSM Ragdoll

- 源码基线：`tmp/YSMRagdoll-main`（MIT）。
- 物理常量、坐标契约、部位映射表与大部分物理实现逻辑逐行继承自上游。
