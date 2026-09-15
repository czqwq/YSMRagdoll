package com.Lilith.ysmragdoll;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraftforge.common.MinecraftForge;

import com.Lilith.ysmragdoll.client.ClientRagdollEvents;
import com.Lilith.ysmragdoll.client.ClientRagdollManager;
import com.Lilith.ysmragdoll.client.RagdollKeyMappings;
import com.Lilith.ysmragdoll.network.ExplosionImpulseSnapshot;
import com.Lilith.ysmragdoll.network.PlayerDeathSnapshot;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/** 客户端初始化：快捷键、渲染事件与资源重载清理。 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        RagdollKeyMappings.register();
        ClientRagdollEvents clientEvents = new ClientRagdollEvents();
        MinecraftForge.EVENT_BUS.register(clientEvents);
        FMLCommonHandler.instance()
            .bus()
            .register(clientEvents);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        // YSM 资源重载时可能替换纹理和模型对象。清除旧快照可以避免继续访问已失效的
        // GPU 资源和已经不存在的模型；重新死亡后会从新资源生成新的独立快照。
        IResourceManager manager = Minecraft.getMinecraft()
            .getResourceManager();
        if (manager instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) manager).registerReloadListener(new IResourceManagerReloadListener() {

                @Override
                public void onResourceManagerReload(IResourceManager resourceManager) {
                    ClientRagdollManager.clear("客户端资源重新加载");
                }
            });
        }
    }

    @Override
    public void handleDeathSnapshot(final PlayerDeathSnapshot message) {
        // 网络回调运行在 Netty 线程，物理和模型捕获必须回到客户端线程。
        Minecraft.getMinecraft()
            .func_152344_a(new Runnable() {

                @Override
                public void run() {
                    ClientRagdollManager.onPlayerDeath(message);
                }
            });
    }

    @Override
    public void handleExplosion(final ExplosionImpulseSnapshot message) {
        Minecraft.getMinecraft()
            .func_152344_a(new Runnable() {

                @Override
                public void run() {
                    ClientRagdollManager.onExplosion(message);
                }
            });
    }
}
