package com.Lilith.ysmragdoll.config;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * 玩家可调整的布娃娃生命周期、推动和调试设置。
 *
 * <p>
 * 所有范围同时在 Forge 配置读取和设置界面提交逻辑中校验。界面遇到负数、
 * 非数字或越界值时会保留输入并提示修正，非法文本不会进入物理计算。
 * </p>
 *
 * <p>
 * 1.7.10 的 Forge 只提供 {@link Configuration}，没有 {@code ForgeConfigSpec}，
 * 因此这里用手写字段承载同样的键、默认值和范围，并暴露与上游一致的读写入口。
 * </p>
 */
public final class YsmRagdollConfig {

    /** 数量上限的硬限制。 */
    public static final int RAGDOLL_LIMIT = 10;

    public static final int MIN_OFFSET = -4;
    public static final int MAX_OFFSET = 4;

    private static final String GENERAL = "general";
    private static final String ADVANCED = "advanced";
    private static final String TESTING = "testing";

    private static Configuration configuration;

    private static int lifetimeSeconds = 20;
    private static boolean manualRemoval;
    private static int maximumRagdolls = 2;
    private static int groundFriction = 20;
    private static int easyPushIndex = 90;
    private static int explosionImpactIndex = 50;
    private static boolean showCollisionBoxes;
    private static boolean intensiveTest;
    private static boolean gravityGunMode;
    private static double collisionOffsetX;
    private static double collisionOffsetY;
    private static double collisionOffsetZ;
    private static double renderOffsetX;
    private static double renderOffsetY;
    private static double renderOffsetZ;

    private YsmRagdollConfig() {}

    public static void load(File configFile) {
        Configuration config = new Configuration(configFile);
        config.load();
        configuration = config;

        config.setCategoryComment(GENERAL, "布娃娃生命周期与数量。");
        lifetimeSeconds = config.getInt(
            "lifetimeSeconds",
            GENERAL,
            20,
            0,
            Integer.MAX_VALUE,
            "布娃娃存在时间，单位为秒。0 到 10000 表示对应秒数，大于 10000 表示永久存在。" + "永久存在时仍受数量上限和手动清除规则影响。");
        manualRemoval = config
            .getBoolean("manualRemoval", GENERAL, false, "开启后可空手右键布娃娃进行清除，并完全忽略存在时间。数量上限及低于 Y=-64 的虚空清理仍然生效。");
        maximumRagdolls = config.getInt(
            "maxRagdolls",
            GENERAL,
            2,
            0,
            RAGDOLL_LIMIT,
            "客户端同时保留的布娃娃数量，范围 0 到 10，默认 2。" + "达到上限后生成新的布娃娃时，会优先删除最早生成的一个。");

        config.setCategoryComment(ADVANCED, "物理、调试与偏移校准。");
        groundFriction = config
            .getInt("groundFriction", ADVANCED, 20, 0, 100, "布娃娃整体与地面的摩擦系数，范围 0 到 100。0 为完全无摩擦，100 表示停止受到推动后整体基本静止。");
        easyPushIndex = config.getInt(
            "easyPushIndex",
            ADVANCED,
            90,
            0,
            100,
            "布娃娃接触玩家推动后接近目标速度的难易程度，范围 0 到 100。" + "0-99 使用渐进冲量；100 时布娃娃必须避让本地玩家碰撞箱，并跟随玩家推动速度。");
        explosionImpactIndex = config.getInt(
            "explosionImpactIndex",
            ADVANCED,
            50,
            0,
            100,
            "爆炸施加到布娃娃各肢体的冲击强度，范围 0 到 100。0 表示完全不受爆炸推动，100 为标准强度的两倍。");
        showCollisionBoxes = config
            .getBoolean("showCollisionBoxes", ADVANCED, false, "是否显示每个布娃娃肢体实际参与 JBullet 求解的旋转碰撞箱。该选项只用于调试显示，不改变物理计算。");
        intensiveTest = config
            .getBoolean("intensiveTest", ADVANCED, false, "以 100 Hz 采样最新的客户端布娃娃快照，写入独立密集测试日志。仅用于临时诊断，默认关闭。");
        collisionOffsetX = offset(config, "collisionOffsetX", "碰撞箱 X 偏移");
        collisionOffsetY = offset(config, "collisionOffsetY", "碰撞箱 Y 偏移");
        collisionOffsetZ = offset(config, "collisionOffsetZ", "碰撞箱 Z 偏移");
        renderOffsetX = offset(config, "renderOffsetX", "布娃娃渲染 X 偏移");
        renderOffsetY = offset(config, "renderOffsetY", "布娃娃渲染 Y 偏移");
        renderOffsetZ = offset(config, "renderOffsetZ", "布娃娃渲染 Z 偏移");

        config.setCategoryComment(TESTING, "本地测试功能。");
        gravityGunMode = config.getBoolean("gravityGunMode", TESTING, false, "牵引模式：主手拿木棍，按住右键（使用键）牵引布娃娃，松开释放，滚轮调距。");

        if (config.hasChanged()) {
            config.save();
        }
    }

    /** 设置界面点击“应用”或“确定”后写回磁盘。 */
    public static void save() {
        Configuration config = configuration;
        if (config == null) {
            return;
        }
        config.get(GENERAL, "lifetimeSeconds", lifetimeSeconds)
            .set(lifetimeSeconds);
        config.get(GENERAL, "manualRemoval", manualRemoval)
            .set(manualRemoval);
        config.get(GENERAL, "maxRagdolls", maximumRagdolls)
            .set(maximumRagdolls);
        config.get(ADVANCED, "groundFriction", groundFriction)
            .set(groundFriction);
        config.get(ADVANCED, "easyPushIndex", easyPushIndex)
            .set(easyPushIndex);
        config.get(ADVANCED, "explosionImpactIndex", explosionImpactIndex)
            .set(explosionImpactIndex);
        config.get(ADVANCED, "showCollisionBoxes", showCollisionBoxes)
            .set(showCollisionBoxes);
        config.get(ADVANCED, "intensiveTest", intensiveTest)
            .set(intensiveTest);
        config.get(ADVANCED, "collisionOffsetX", collisionOffsetX)
            .set(collisionOffsetX);
        config.get(ADVANCED, "collisionOffsetY", collisionOffsetY)
            .set(collisionOffsetY);
        config.get(ADVANCED, "collisionOffsetZ", collisionOffsetZ)
            .set(collisionOffsetZ);
        config.get(ADVANCED, "renderOffsetX", renderOffsetX)
            .set(renderOffsetX);
        config.get(ADVANCED, "renderOffsetY", renderOffsetY)
            .set(renderOffsetY);
        config.get(ADVANCED, "renderOffsetZ", renderOffsetZ)
            .set(renderOffsetZ);
        config.get(TESTING, "gravityGunMode", gravityGunMode)
            .set(gravityGunMode);
        config.save();
    }

    private static double offset(Configuration config, String key, String description) {
        return config
            .get(
                ADVANCED,
                key,
                0.0D,
                description + "，单位为方块，允许负数和小数，范围 -4.0 到 4.0。" + "碰撞箱偏移与渲染偏移相互独立，便于现场校准模型和物理位置。",
                -4.0D,
                4.0D)
            .getDouble(0.0D);
    }

    public static int lifetimeSeconds() {
        return lifetimeSeconds;
    }

    public static void lifetimeSeconds(int value) {
        lifetimeSeconds = value;
    }

    public static boolean manualRemoval() {
        return manualRemoval;
    }

    public static void manualRemoval(boolean value) {
        manualRemoval = value;
    }

    public static int maximumRagdolls() {
        return maximumRagdolls;
    }

    public static void maximumRagdolls(int value) {
        maximumRagdolls = value;
    }

    public static int groundFriction() {
        return groundFriction;
    }

    public static void groundFriction(int value) {
        groundFriction = value;
    }

    public static int easyPushIndex() {
        return easyPushIndex;
    }

    public static void easyPushIndex(int value) {
        easyPushIndex = value;
    }

    public static int explosionImpactIndex() {
        return explosionImpactIndex;
    }

    public static void explosionImpactIndex(int value) {
        explosionImpactIndex = value;
    }

    public static boolean showCollisionBoxes() {
        return showCollisionBoxes;
    }

    public static void showCollisionBoxes(boolean value) {
        showCollisionBoxes = value;
    }

    public static boolean intensiveTest() {
        return intensiveTest;
    }

    public static void intensiveTest(boolean value) {
        intensiveTest = value;
    }

    public static boolean gravityGunMode() {
        return gravityGunMode;
    }

    public static void gravityGunMode(boolean value) {
        gravityGunMode = value;
    }

    public static double collisionOffsetX() {
        return collisionOffsetX;
    }

    public static void collisionOffsetX(double value) {
        collisionOffsetX = value;
    }

    public static double collisionOffsetY() {
        return collisionOffsetY;
    }

    public static void collisionOffsetY(double value) {
        collisionOffsetY = value;
    }

    public static double collisionOffsetZ() {
        return collisionOffsetZ;
    }

    public static void collisionOffsetZ(double value) {
        collisionOffsetZ = value;
    }

    public static double renderOffsetX() {
        return renderOffsetX;
    }

    public static void renderOffsetX(double value) {
        renderOffsetX = value;
    }

    public static double renderOffsetY() {
        return renderOffsetY;
    }

    public static void renderOffsetY(double value) {
        renderOffsetY = value;
    }

    public static double renderOffsetZ() {
        return renderOffsetZ;
    }

    public static void renderOffsetZ(double value) {
        renderOffsetZ = value;
    }
}
