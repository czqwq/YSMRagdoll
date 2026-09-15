package com.Lilith.ysmragdoll.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;

import com.Lilith.ysmragdoll.YsmRagdollLog;
import com.Lilith.ysmragdoll.client.physics.ClientPhysicsWorld;
import com.Lilith.ysmragdoll.client.physics.PhysicsRagdoll;
import com.Lilith.ysmragdoll.client.physics.YsmSkeletonExtractor;
import com.Lilith.ysmragdoll.config.YsmRagdollConfig;
import com.Lilith.ysmragdoll.network.ExplosionImpulseSnapshot;
import com.Lilith.ysmragdoll.network.PlayerDeathSnapshot;

/**
 * 管理独立模型快照及其客户端物理生命周期。
 *
 * <p>
 * 此类是“死亡网络快照、YSM 绘制快照和 JBullet 刚体”之间的所有权边界：
 * 每具布娃娃持有一份可写骨骼参数数组和一个可选物理对象；共享物理世界只在至少
 * 一具布娃娃启用物理时存在。数量上限、存在时间、手动清除、世界卸载和资源重载
 * 最终都通过这里释放约束和刚体。
 * </p>
 */
public final class ClientRagdollManager {

    private static final Deque<StaticRagdoll> RAGDOLLS = new ArrayDeque<>();
    private static final Deque<PendingRagdoll> PENDING = new ArrayDeque<>();
    private static final Map<String, Long> RECENT_DEATHS = new HashMap<>();
    private static final long DEATH_DEDUPLICATION_WINDOW_MILLIS = 1500L;
    private static final int MAX_CAPTURE_RETRIES = 40;
    private static final int CAPTURE_RETRY_INTERVAL_TICKS = 2;
    private static final List<PhysicsRagdoll> ACTIVE_PHYSICS = new ArrayList<>();
    private static final Deque<PendingExplosion> RECENT_EXPLOSIONS = new ArrayDeque<>();
    private static final long EXPLOSION_REPLAY_WINDOW_MILLIS = 1000L;
    private static ClientPhysicsWorld physicsWorld;
    private static long nextRagdollId = 1;
    /**
     * 网络线程不能直接释放布娃娃。
     *
     * <p>
     * 断开连接与维度切换的事件由 Netty 的 IO 线程派发，而同一个 tick 里客户端线程
     * 正在 {@code ClientPhysicsWorld#simulateFrame} 中步进 JBullet。JBullet 的宽相位
     * （{@code DbvtBroadphase} + {@code HashedOverlappingPairCache}）没有任何同步，
     * 在步进期间从另一个线程移除刚体会直接破坏重叠对缓存，随后
     * {@code HashedOverlappingPairCache#removeOverlappingPair} 会在
     * {@code last.pProxy0} 上抛 NPE。因此网络线程只登记请求，真正的释放在客户端
     * tick 的开头执行。
     * </p>
     */
    private static final AtomicInteger PENDING_CLEARS = new AtomicInteger();
    private static volatile String pendingClearReason;

    private ClientRagdollManager() {}

    public static int ragdollCount() {
        return RAGDOLLS.size();
    }

    public static int physicsRagdollCount() {
        return ACTIVE_PHYSICS.size();
    }

    /** 客户端线程上的只读视图，与移除判定使用同一份截止时间。 */
    public static List<ManagementEntry> managementEntries() {
        long now = System.currentTimeMillis();
        int lifetime = YsmRagdollConfig.lifetimeSeconds();
        boolean manual = YsmRagdollConfig.manualRemoval();
        List<ManagementEntry> result = new ArrayList<>(RAGDOLLS.size());
        for (StaticRagdoll ragdoll : RAGDOLLS) {
            result.add(
                new ManagementEntry(
                    ragdoll.id,
                    ragdoll.snapshot.playerId()
                        .toString(),
                    RagdollLifetime.remainingMillis(ragdoll.createdAtMillis, now, lifetime, manual),
                    manual));
        }
        return result;
    }

    public static final class ManagementEntry {

        private final long id;
        private final String playerId;
        private final long remainingMillis;
        private final boolean manualRemoval;

        ManagementEntry(long id, String playerId, long remainingMillis, boolean manualRemoval) {
            this.id = id;
            this.playerId = playerId;
            this.remainingMillis = remainingMillis;
            this.manualRemoval = manualRemoval;
        }

        public long id() {
            return id;
        }

        public String playerId() {
            return playerId;
        }

        public long remainingMillis() {
            return remainingMillis;
        }

        public boolean manualRemoval() {
            return manualRemoval;
        }
    }

    static com.Lilith.ysmragdoll.client.physics.PhysicsGrab grab(Vec3 from, Vec3 to, double reach) {
        return physicsWorld == null ? null : physicsWorld.grab(from, to, reach);
    }

    public enum TestSpawnResult {
        CREATED,
        STATIC_CREATED,
        NO_PLAYER,
        DISABLED,
        BELOW_VOID,
        CAPTURE_FAILED
    }

    /** 一次界面点击创建一份本地快照；不发送死亡包，也不参与去重。 */
    public static TestSpawnResult createFromCurrentPlayer() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer player = minecraft.thePlayer;
        if (minecraft.theWorld == null || player == null || !player.isEntityAlive()) {
            return TestSpawnResult.NO_PLAYER;
        }
        if (YsmRagdollConfig.maximumRagdolls() <= 0) {
            return TestSpawnResult.DISABLED;
        }
        if (RagdollLifetime.belowVoid(player.posY)) {
            return TestSpawnResult.BELOW_VOID;
        }
        PlayerDeathSnapshot snapshot = new PlayerDeathSnapshot(
            player.getEntityId(),
            player.getUniqueID(),
            player.posX,
            player.posY,
            player.posZ,
            player.motionX,
            player.motionY,
            player.motionZ,
            player.renderYawOffset);
        CapturedModel captured = YsmRagdollModelAdapter
            .capture(player, snapshot.bodyYaw(), ClientRagdollEvents.partialTicks());
        if (captured == null || !captured.hasSnapshot()) {
            // 不做延迟重试：否则连续点击会在之后产生意料之外的生成。
            return TestSpawnResult.CAPTURE_FAILED;
        }
        StaticRagdoll ragdoll = promote(new PendingRagdoll(snapshot, captured, player, 0, System.currentTimeMillis()));
        if (ragdoll == null) {
            return TestSpawnResult.DISABLED;
        }
        initializePhysics(ragdoll, false);
        YsmRagdollLog.info(
            "手动创建测试布娃娃: 玩家=" + player
                .getUniqueID() + ", 当前数量=" + RAGDOLLS.size() + ", 物理=" + (ragdoll.physics != null));
        return ragdoll.physics == null ? TestSpawnResult.STATIC_CREATED : TestSpawnResult.CREATED;
    }

    /** 死亡与手动创建共用的初始化；测试对象不继承过去的爆炸。 */
    private static void initializePhysics(StaticRagdoll ragdoll, boolean replayExplosions) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (ragdoll.physicsAttempted || minecraft.theWorld == null) {
            return;
        }
        ragdoll.physicsAttempted = true;
        if (physicsWorld == null) {
            physicsWorld = new ClientPhysicsWorld();
        }
        ragdoll.physics = PhysicsRagdoll.create(physicsWorld, ragdoll.snapshot, ragdoll.model);
        if (ragdoll.physics != null) {
            ACTIVE_PHYSICS.add(ragdoll.physics);
            ragdoll.physics.writePose();
            ragdoll.physics.updateChunkLoaded(minecraft.theWorld);
            if (replayExplosions && ragdoll.physics.isChunkLoaded()) {
                long now = System.currentTimeMillis();
                for (PendingExplosion explosion : RECENT_EXPLOSIONS) {
                    if (explosion.expiresAtMillis > now) {
                        applyExplosion(ragdoll.physics, explosion.snapshot);
                    }
                }
            }
        }
    }

    static String intensiveState() {
        StringBuilder state = new StringBuilder(512);
        state.append("ragdolls=")
            .append(RAGDOLLS.size())
            .append(" physics=")
            .append(ACTIVE_PHYSICS.size());
        int index = 0;
        for (StaticRagdoll ragdoll : RAGDOLLS) {
            state.append(" | ragdoll[")
                .append(index++)
                .append("] player=")
                .append(ragdoll.snapshot.playerId())
                .append(' ');
            if (ragdoll.physics == null) {
                state.append("physics=pending ");
            } else {
                state.append(ragdoll.physics.intensiveState())
                    .append(' ');
            }
            state.append(ragdoll.model.describeBonePose());
        }
        return state.toString();
    }

    public static void onPlayerDeath(PlayerDeathSnapshot snapshot) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.theWorld == null || YsmRagdollConfig.maximumRagdolls() == 0
            || RagdollLifetime.belowVoid(snapshot.y())) {
            return;
        }
        EntityPlayer player = findPlayer(minecraft.theWorld, snapshot);
        if (player == null) {
            YsmRagdollLog.warn("死亡玩家已离开客户端追踪范围: " + snapshot.playerId());
            return;
        }
        String deathKey = deathKey(snapshot);
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> deaths = RECENT_DEATHS.entrySet()
            .iterator();
        while (deaths.hasNext()) {
            if (now - deaths.next()
                .getValue() > DEATH_DEDUPLICATION_WINDOW_MILLIS) {
                deaths.remove();
            }
        }
        Long previousDeath = RECENT_DEATHS.put(deathKey, now);
        if (previousDeath != null && now - previousDeath <= DEATH_DEDUPLICATION_WINDOW_MILLIS) {
            YsmRagdollLog.warn("忽略短时间内重复的死亡快照: " + deathKey);
            return;
        }

        CapturedModel captured = YsmRagdollModelAdapter
            .capture(player, snapshot.bodyYaw(), ClientRagdollEvents.partialTicks());
        if (captured == null) {
            // 死亡瞬间模型可能还没准备好，进入有限重试而不是当场放弃。
            PENDING.addLast(new PendingRagdoll(snapshot, null, player, 0, System.currentTimeMillis()));
            YsmRagdollLog.warn("模型捕获失败，暂不创建布娃娃，进入有限重试: 玩家=" + snapshot.playerId());
            return;
        }
        PendingRagdoll pending = new PendingRagdoll(snapshot, captured, player, 0, System.currentTimeMillis());
        if (captured.hasSnapshot()) {
            promote(pending);
        } else {
            PENDING.addLast(pending);
            YsmRagdollLog.warn("模型快照为空，暂不创建布娃娃，进入有限重试: 玩家=" + snapshot.playerId());
        }
    }

    public static void tick() {
        processPendingClears();
        processPending();
        long currentTimeMillis = System.currentTimeMillis();
        Iterator<PendingExplosion> explosions = RECENT_EXPLOSIONS.iterator();
        while (explosions.hasNext()) {
            if (explosions.next().expiresAtMillis <= currentTimeMillis) {
                explosions.remove();
            }
        }
        int limit = YsmRagdollConfig.maximumRagdolls();
        while (RAGDOLLS.size() > limit) {
            removeOldest("数量上限降低");
        }
        int lifetime = YsmRagdollConfig.lifetimeSeconds();
        boolean manual = YsmRagdollConfig.manualRemoval();
        Iterator<StaticRagdoll> iterator = RAGDOLLS.iterator();
        while (iterator.hasNext()) {
            StaticRagdoll ragdoll = iterator.next();
            if (isBelowVoid(ragdoll)
                || RagdollLifetime.remainingMillis(ragdoll.createdAtMillis, currentTimeMillis, lifetime, manual) == 0) {
                iterator.remove();
                dispose(ragdoll);
            }
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.theWorld == null || RAGDOLLS.isEmpty()) {
            releaseWorldIfUnused();
            return;
        }
        for (StaticRagdoll ragdoll : RAGDOLLS) {
            initializePhysics(ragdoll, true);
        }
        if (physicsWorld != null && !ACTIVE_PHYSICS.isEmpty()) {
            for (PhysicsRagdoll physics : ACTIVE_PHYSICS) {
                physics.updateChunkLoaded(minecraft.theWorld);
            }
            physicsWorld.tick(minecraft.theWorld, minecraft.thePlayer, ACTIVE_PHYSICS);
        }
    }

    private static void trimForNewRagdoll() {
        int limit = YsmRagdollConfig.maximumRagdolls();
        while (!RAGDOLLS.isEmpty() && RAGDOLLS.size() >= limit) {
            removeOldest("为新布娃娃腾出数量上限");
        }
    }

    /** 处理捕获失败的暂存对象；成功后才转入正式列表。 */
    private static void processPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.theWorld == null) {
            return;
        }
        // 每个客户端 tick 只处理队首一个等待对象，并将未完成对象放回队尾，
        // 防止多个死亡快照在同一帧同时进入 YSM 渲染流程。
        PendingRagdoll pending = PENDING.pollFirst();
        if (pending == null) {
            return;
        }
        pending.ageTicks++;
        if (pending.ageTicks > MAX_CAPTURE_RETRIES) {
            YsmRagdollLog.warn("YSM 模型在有限重试窗口内仍未捕获，丢弃布娃娃: " + pending.snapshot.playerId());
            return;
        }
        boolean complete = false;
        if (pending.ageTicks % CAPTURE_RETRY_INTERVAL_TICKS == 0) {
            EntityPlayer carrier = pending.player;
            if (carrier == null || !carrier.isEntityAlive()) {
                carrier = findPlayer(minecraft.theWorld, pending.snapshot);
                pending.player = carrier;
            }
            if (carrier != null) {
                CapturedModel captured = YsmRagdollModelAdapter
                    .capture(carrier, pending.snapshot.bodyYaw(), ClientRagdollEvents.partialTicks());
                if (captured != null && captured.hasSnapshot()) {
                    pending.model = captured;
                    complete = true;
                }
            }
        }
        if (complete) {
            promote(pending);
        } else {
            PENDING.addLast(pending);
        }
    }

    private static StaticRagdoll promote(PendingRagdoll pending) {
        if (pending.model == null || YsmRagdollConfig.maximumRagdolls() <= 0
            || RagdollLifetime.belowVoid(pending.snapshot.y())) {
            return null;
        }
        trimForNewRagdoll();
        StaticRagdoll ragdoll = new StaticRagdoll(pending.snapshot, pending.model, pending.createdAtMillis);
        RAGDOLLS.addLast(ragdoll);
        YsmRagdollLog.info(
            "创建布娃娃快照: 玩家=" + pending.snapshot
                .playerId() + ", 位置=" + pending.snapshot.x() + "," + pending.snapshot.y() + "," + pending.snapshot.z());
        return ragdoll;
    }

    private static EntityPlayer findPlayer(World world, PlayerDeathSnapshot snapshot) {
        if (world == null) {
            return null;
        }
        Entity entity = world.getEntityByID(snapshot.entityId());
        if (entity instanceof EntityPlayer) {
            return (EntityPlayer) entity;
        }
        UUID playerId = snapshot.playerId();
        for (Object candidate : world.playerEntities) {
            if (candidate instanceof EntityPlayer && playerId.equals(((EntityPlayer) candidate).getUniqueID())) {
                return (EntityPlayer) candidate;
            }
        }
        return null;
    }

    private static String deathKey(PlayerDeathSnapshot snapshot) {
        return snapshot.playerId() + ":"
            + snapshot.entityId()
            + ":"
            + Double.doubleToLongBits(snapshot.x())
            + ":"
            + Double.doubleToLongBits(snapshot.y())
            + ":"
            + Double.doubleToLongBits(snapshot.z());
    }

    private static void removeOldest(String reason) {
        StaticRagdoll removed = RAGDOLLS.pollFirst();
        if (removed != null) {
            dispose(removed);
            YsmRagdollLog.info("清除最早的布娃娃: " + removed.snapshot.playerId() + ", 原因=" + reason);
        }
    }

    public static void clear(String reason) {
        GravityGunController.release();
        int count = RAGDOLLS.size();
        for (StaticRagdoll ragdoll : RAGDOLLS) {
            dispose(ragdoll);
        }
        RAGDOLLS.clear();
        PENDING.clear();
        RECENT_DEATHS.clear();
        RECENT_EXPLOSIONS.clear();
        releaseWorldIfUnused();
        YsmSkeletonExtractor.clearCache();
        ClientIntensiveLogger.reset();
        if (count > 0) {
            YsmRagdollLog.info("清理全部静态布娃娃: 数量=" + count + ", 原因=" + reason);
        }
    }

    /**
     * 供网络线程与卸载事件调用的线程安全入口。
     *
     * <p>
     * 只登记请求并立即释放牵引状态（牵引对象本身只是一点约束，不需要触碰宽相位），
     * 刚体与约束的移除留到客户端线程的 {@code tick()} 开头，避免与正在进行的
     * JBullet 步进竞争。
     * </p>
     */
    public static void requestClear(String reason) {
        pendingClearReason = reason;
        GravityGunController.release();
        PENDING_CLEARS.incrementAndGet();
        YsmRagdollLog.info("已排队清理布娃娃（等待客户端线程）: 原因=" + reason);
    }

    /** 仅用于验证跨线程登记的请求不会丢失。 */
    static boolean hasPendingClear() {
        return PENDING_CLEARS.get() > 0;
    }

    private static void processPendingClears() {
        // 用 getAndSet 而不是比较后清零：清空期间新到的请求会留下新的计数，
        // 由下一次 tick 继续处理，不会被丢掉。
        if (PENDING_CLEARS.getAndSet(0) <= 0) {
            return;
        }
        String reason = pendingClearReason;
        pendingClearReason = null;
        clear(reason == null ? "网络线程请求" : reason);
    }

    /** 接收服务端爆炸并立即作用于当前尸体，同时短暂缓存以覆盖爆炸后才完成的死亡快照。 */
    public static void onExplosion(ExplosionImpulseSnapshot snapshot) {
        long expiresAt = System.currentTimeMillis() + EXPLOSION_REPLAY_WINDOW_MILLIS;
        RECENT_EXPLOSIONS.addLast(new PendingExplosion(snapshot, expiresAt));
        int affected = 0;
        for (PhysicsRagdoll physics : ACTIVE_PHYSICS) {
            if (physics.isChunkLoaded() && applyExplosion(physics, snapshot)) {
                affected++;
            }
        }
        YsmRagdollLog.info(
            "客户端收到爆炸冲击: 中心=" + snapshot
                .x() + "," + snapshot.y() + "," + snapshot.z() + ", 威力=" + snapshot.radius() + ", 受影响布娃娃=" + affected);
    }

    private static boolean applyExplosion(PhysicsRagdoll physics, ExplosionImpulseSnapshot snapshot) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.theWorld == null) {
            return false;
        }
        try {
            return physics.applyExplosion(
                minecraft.theWorld,
                minecraft.thePlayer,
                snapshot.x(),
                snapshot.y(),
                snapshot.z(),
                snapshot.radius());
        } catch (RuntimeException | LinkageError exception) {
            YsmRagdollLog.warn("应用客户端爆炸冲击失败，已跳过当前布娃娃: " + exception);
            return false;
        }
    }

    /**
     * 在世界渲染之后绘制所有布娃娃；此时 Tessellator 是空闲的。
     *
     * <p>
     * 1.7.10 在 {@code RenderWorldLastEvent} 处的 MODELVIEW 是“相机相对”的：
     * 实体渲染通过 {@code RenderManager.renderPos*} 把世界坐标换成相对坐标。因此这里
     * 统一平移 {@code -RenderManager.renderPos*}，随后所有绘制都直接使用世界坐标。
     * </p>
     */
    public static void render(float partialTick) {
        Minecraft minecraft = Minecraft.getMinecraft();
        GravityGunController.update(partialTick);
        if (minecraft.theWorld == null || RAGDOLLS.isEmpty()) {
            return;
        }
        if (physicsWorld != null && !ACTIVE_PHYSICS.isEmpty()) {
            if (minecraft.isGamePaused() || PENDING_CLEARS.get() > 0) {
                physicsWorld.resetFrameClock();
            } else {
                physicsWorld.simulateFrame(ACTIVE_PHYSICS);
            }
        }
        // 物理在渲染阶段推进，因此移除必须发生在步进之后、绘制之前。
        Iterator<StaticRagdoll> fallen = RAGDOLLS.iterator();
        while (fallen.hasNext()) {
            StaticRagdoll ragdoll = fallen.next();
            if (isBelowVoid(ragdoll)) {
                fallen.remove();
                dispose(ragdoll);
            }
        }
        if (RAGDOLLS.isEmpty()) {
            return;
        }
        double renderOffsetX = YsmRagdollConfig.renderOffsetX();
        double renderOffsetY = YsmRagdollConfig.renderOffsetY();
        double renderOffsetZ = YsmRagdollConfig.renderOffsetZ();

        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_LIGHTING_BIT
                | GL11.GL_TEXTURE_BIT);
        GL11.glPushMatrix();
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        RenderHelper.enableStandardItemLighting();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        try {
            for (StaticRagdoll ragdoll : RAGDOLLS) {
                if (ragdoll.physics != null) {
                    ragdoll.physics.updateChunkLoaded(minecraft.theWorld);
                    if (!ragdoll.physics.isChunkLoaded()) {
                        continue;
                    }
                } else if (!ClientPhysicsWorld.isChunkLoaded(
                    minecraft.theWorld,
                    MathHelper.floor_double(ragdoll.snapshot.x()),
                    MathHelper.floor_double(ragdoll.snapshot.z()))) {
                        continue;
                    }
                PlayerDeathSnapshot snapshot = ragdoll.snapshot;
                Vec3 displacement = ragdoll.physics == null ? Vec3.createVectorHelper(0, 0, 0)
                    : ragdoll.physics.renderDisplacement();
                double renderX = snapshot.x() + displacement.xCoord + renderOffsetX;
                double renderY = snapshot.y() + displacement.yCoord + renderOffsetY;
                double renderZ = snapshot.z() + displacement.zCoord + renderOffsetZ;
                Vec3 lightSample = ragdoll.physics == null ? Vec3.createVectorHelper(renderX, renderY + 1.0D, renderZ)
                    : ragdoll.physics.center()
                        .addVector(0.0D, 0.65D, 0.0D);
                int packedLight = packedLight(minecraft.theWorld, lightSample);
                GL11.glPushMatrix();
                GL11.glTranslated(renderX, renderY, renderZ);
                GL11.glRotatef(180.0F - snapshot.bodyYaw(), 0.0F, 1.0F, 0.0F);
                try {
                    minecraft.getTextureManager()
                        .bindTexture(ragdoll.model.texture());
                    RagdollRenderer.INSTANCE.draw(ragdoll.model, packedLight, 1.0F, 1.0F, 1.0F, 1.0F);
                } catch (RuntimeException exception) {
                    YsmRagdollLog.warn("渲染布娃娃失败，已跳过当前帧: " + exception);
                } finally {
                    GL11.glPopMatrix();
                }
            }
        } finally {
            RenderHelper.disableStandardItemLighting();
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }

        if (YsmRagdollConfig.showCollisionBoxes()) {
            renderDebugBoxes();
        }
        GravityGunController.render(partialTick);
    }

    private static void renderDebugBoxes() {
        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_LIGHTING_BIT
                | GL11.GL_LINE_BIT);
        GL11.glPushMatrix();
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        GL11.glLineWidth(2.0F);
        try {
            for (StaticRagdoll ragdoll : RAGDOLLS) {
                if (ragdoll.physics != null && ragdoll.physics.isChunkLoaded()) {
                    ragdoll.physics.renderDebug();
                }
            }
        } catch (RuntimeException exception) {
            YsmRagdollLog.warn("绘制调试碰撞箱失败: " + exception);
        } finally {
            GL11.glDepthMask(true);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    /** 空手右键清除看向的布娃娃；返回 true 表示本次点击已经被消费。 */
    public static boolean removeLookingAt(EntityPlayer player) {
        if (!YsmRagdollConfig.manualRemoval() || RAGDOLLS.isEmpty()) {
            return false;
        }
        // 1.7.10 的 World#rayTraceBlocks 会就地改写传入的起点向量（把它沿射线推到命中点），
        // 所以查询用副本，后面的几何计算继续使用原始眼位。
        double eyeX = player.posX;
        double eyeY = player.posY + (player.getEyeHeight() - player.getDefaultEyeHeight());
        double eyeZ = player.posZ;
        Vec3 look = player.getLook(1.0F);
        MovingObjectPosition blockHit = player.worldObj.rayTraceBlocks(
            Vec3.createVectorHelper(eyeX, eyeY, eyeZ),
            Vec3.createVectorHelper(eyeX + look.xCoord * 5.0D, eyeY + look.yCoord * 5.0D, eyeZ + look.zCoord * 5.0D),
            false);
        double maximum = 5.0;
        if (blockHit != null && blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
            && blockHit.hitVec != null) {
            maximum = distance(eyeX, eyeY, eyeZ, blockHit.hitVec) + 0.05;
        }
        Vec3 eye = Vec3.createVectorHelper(eyeX, eyeY, eyeZ);

        StaticRagdoll selected = null;
        double nearest = maximum;
        for (StaticRagdoll ragdoll : RAGDOLLS) {
            PlayerDeathSnapshot snapshot = ragdoll.snapshot;
            Vec3 center = ragdoll.physics == null
                ? Vec3.createVectorHelper(snapshot.x(), snapshot.y() + 0.9D, snapshot.z())
                : ragdoll.physics.center();
            double radius = ragdoll.physics == null ? 0.75 : ragdoll.physics.selectionRadius();
            double hit = raySphere(eye, look, center, radius);
            if (hit >= 0 && hit < nearest) {
                selected = ragdoll;
                nearest = hit;
            }
        }
        if (selected == null) {
            return false;
        }
        RAGDOLLS.remove(selected);
        dispose(selected);
        YsmRagdollLog.info("玩家空手右键清除布娃娃: " + selected.snapshot.playerId());
        return true;
    }

    private static double distance(double fromX, double fromY, double fromZ, Vec3 to) {
        double dx = to.xCoord - fromX;
        double dy = to.yCoord - fromY;
        double dz = to.zCoord - fromZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double raySphere(Vec3 eye, Vec3 direction, Vec3 center, double radius) {
        double offsetX = eye.xCoord - center.xCoord;
        double offsetY = eye.yCoord - center.yCoord;
        double offsetZ = eye.zCoord - center.zCoord;
        double projection = offsetX * direction.xCoord + offsetY * direction.yCoord + offsetZ * direction.zCoord;
        double squared = offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ;
        double discriminant = projection * projection - squared + radius * radius;
        if (discriminant < 0) {
            return -1;
        }
        double root = Math.sqrt(discriminant);
        double near = -projection - root;
        return near >= 0 ? near : Math.max(-1, -projection + root);
    }

    /** 使用原版光照采样，保证布娃娃和周围实体的亮度一致。 */
    private static int packedLight(World world, Vec3 sample) {
        if (world == null) {
            return 0xF000F0;
        }
        int x = MathHelper.floor_double(sample.xCoord);
        int y = MathHelper.floor_double(sample.yCoord);
        int z = MathHelper.floor_double(sample.zCoord);
        if (y < 0) {
            y = 0;
        } else if (y > 255) {
            y = 255;
        }
        if (!ClientPhysicsWorld.isChunkLoaded(world, x, z)) {
            return 0xF000F0;
        }
        return world.getLightBrightnessForSkyBlocks(x, y, z, 0);
    }

    private static void dispose(StaticRagdoll ragdoll) {
        if (ragdoll.physics != null) {
            PhysicsRagdoll physics = ragdoll.physics;
            // 先从活动列表摘除：即使 JBullet 的移除路径抛出异常（宽相位缓存可能
            // 已经被先前的失败操作破坏），也不会留下仍被步进的僵尸刚体。
            ACTIVE_PHYSICS.remove(physics);
            ragdoll.physics = null;
            try {
                physics.dispose();
            } catch (RuntimeException exception) {
                // 一具布娃娃的清理失败不能阻止其余布娃娃和物理世界被丢弃。
                YsmRagdollLog.warn("释放 JBullet 刚体失败，已放弃该物理世界: " + exception);
                discardPhysicsWorld();
                return;
            }
        }
        releaseWorldIfUnused();
    }

    /** 物理世界已经不可信时直接丢弃引用，不再尝试逐个移除刚体。 */
    private static void discardPhysicsWorld() {
        ACTIVE_PHYSICS.clear();
        if (physicsWorld != null) {
            physicsWorld.clear();
            physicsWorld = null;
        }
    }

    private static boolean isBelowVoid(StaticRagdoll ragdoll) {
        return RagdollLifetime
            .belowVoid(ragdoll.physics == null ? ragdoll.snapshot.y() : ragdoll.physics.worldCenterY());
    }

    private static void releaseWorldIfUnused() {
        if (physicsWorld != null && ACTIVE_PHYSICS.isEmpty()) {
            physicsWorld.clear();
            physicsWorld = null;
        }
    }

    /** 供客户端事件读取当前绑定的抓取对象，避免额外的静态状态。 */
    static boolean hasPhysicsWorld() {
        return physicsWorld != null;
    }

    static final class StaticRagdoll {

        final long id = nextRagdollId++;
        final PlayerDeathSnapshot snapshot;
        final CapturedModel model;
        final long createdAtMillis;
        PhysicsRagdoll physics;
        boolean physicsAttempted;

        StaticRagdoll(PlayerDeathSnapshot snapshot, CapturedModel model, long createdAtMillis) {
            this.snapshot = snapshot;
            this.model = model;
            this.createdAtMillis = createdAtMillis;
        }
    }

    private static final class PendingRagdoll {

        final PlayerDeathSnapshot snapshot;
        final long createdAtMillis;
        CapturedModel model;
        EntityPlayer player;
        int ageTicks;

        PendingRagdoll(PlayerDeathSnapshot snapshot, CapturedModel model, EntityPlayer player, int ageTicks,
            long createdAtMillis) {
            this.snapshot = snapshot;
            this.model = model;
            this.player = player;
            this.ageTicks = ageTicks;
            this.createdAtMillis = createdAtMillis;
        }
    }

    private static final class PendingExplosion {

        final ExplosionImpulseSnapshot snapshot;
        final long expiresAtMillis;

        PendingExplosion(ExplosionImpulseSnapshot snapshot, long expiresAtMillis) {
            this.snapshot = snapshot;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
