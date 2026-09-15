package com.Lilith.ysmragdoll.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.input.Mouse;

import com.Lilith.ysmragdoll.YsmRagdollLog;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/** 客户端布娃娃的更新、绘制、手动删除和设置入口。 */
public final class ClientRagdollEvents {

    private static boolean openKeyDown;
    /** 使用键（默认右键）上一 tick 的状态，用于轮询牵引模式的按下沿。 */
    private static boolean useKeyDown;
    private static int lastHotbarSlot = -1;
    /**
     * 1.7.10 的 {@code Minecraft.timer} 是 private，模型捕获需要的帧内插值只能从
     * FML 的渲染 tick 事件取得。
     */
    private static float partialTicks = 1.0F;

    static float partialTicks() {
        return partialTicks;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            partialTicks = event.renderTickTime;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // 与 ClientRagdollManager#render 里的那次调用共用同一份每 tick 采样：
        // 客户端 tick 这一次负责在 PlayerInteractEvent 之后确认“使用键仍然按住”，
        // 渲染帧那一次负责按帧跟随准星。
        ClientRagdollManager.tick();
        Minecraft minecraft = Minecraft.getMinecraft();
        // 使用键的上升沿用本模组自己的轮询实现，而不是只依赖可被取消、可被其他模组
        // 抢先处理的原版交互事件：1.7.10 没有 InputEvent.InteractionKeyMappingTriggered，
        // 只靠 PlayerInteractEvent 会让“按住右键牵引”在某些交互分支下完全收不到输入。
        boolean usePressed = GravityGunController.isUseKeyDown();
        if (usePressed && !useKeyDown && GravityGunController.isArmed()) {
            GravityGunController.start();
        }
        useKeyDown = usePressed;
        GravityGunController.update(1.0F);
        if (minecraft.thePlayer != null && minecraft.currentScreen == null) {
            handleScroll(minecraft);
        } else {
            lastHotbarSlot = -1;
        }
        // 即使打开了界面也要把按键状态消费掉：排队的按下事件绝不能在离开聊天、
        // 控制菜单或设置界面之后才把设置页面打开。
        boolean requested = RagdollKeyMappings.isDown() && !openKeyDown;
        openKeyDown = RagdollKeyMappings.isDown();
        if (requested && !RagdollKeyMappings.isUnbound()
            && minecraft.theWorld != null
            && minecraft.thePlayer != null
            && minecraft.currentScreen == null) {
            minecraft.displayGuiScreen(new YsmRagdollSettingsScreen(null));
            YsmRagdollLog.info("通过快捷键打开设置页面");
        }
    }

    /**
     * 牵引模式消费滚轮时把快捷栏槽位还原。
     *
     * <p>
     * 1.7.10 没有可以取消的鼠标输入事件，原版在同一个 tick 里已经切换过快捷栏，
     * 因此这里在渲染之前把槽位改回去，视觉上不会出现切换。
     * </p>
     */
    private static void handleScroll(Minecraft minecraft) {
        EntityPlayer player = minecraft.thePlayer;
        if (lastHotbarSlot < 0) {
            lastHotbarSlot = player.inventory.currentItem;
            return;
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && GravityGunController.scroll(wheel / 120.0D)) {
            player.inventory.currentItem = lastHotbarSlot;
        } else {
            lastHotbarSlot = player.inventory.currentItem;
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        long startedAt = System.nanoTime();
        try {
            ClientRagdollManager.render(event.partialTicks);
        } catch (RuntimeException exception) {
            YsmRagdollLog.warn("渲染布娃娃阶段失败，已跳过本帧: " + exception);
        }
        ClientIntensiveLogger.recordFrame();
        ClientPerformanceLogger.recordFrame(
            System.nanoTime() - startedAt,
            ClientRagdollManager.ragdollCount(),
            ClientRagdollManager.physicsRagdollCount());
    }

    @SubscribeEvent
    public void onPlayerInteract(PlayerInteractEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (event.entityPlayer != minecraft.thePlayer || minecraft.currentScreen != null) {
            return;
        }
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK
            && event.action != PlayerInteractEvent.Action.RIGHT_CLICK_AIR) {
            return;
        }
        if (GravityGunController.isArmed()) {
            GravityGunController.start();
            event.setCanceled(true);
            return;
        }
        ItemStack held = event.entityPlayer.getCurrentEquippedItem();
        if (held != null) {
            return;
        }
        if (ClientRagdollManager.removeLookingAt(event.entityPlayer)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ClientPerformanceLogger.reset();
        // 该事件由 Netty 的 IO 线程派发：只登记请求，真正的释放在客户端 tick 完成。
        ClientRagdollManager.requestClear("客户端断开连接");
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world != null && event.world.isRemote) {
            ClientPerformanceLogger.reset();
            ClientRagdollManager.requestClear("客户端世界卸载或切换维度");
        }
    }
}
