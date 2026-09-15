package com.Lilith.ysmragdoll;

import net.minecraftforge.common.MinecraftForge;

import com.Lilith.ysmragdoll.config.YsmRagdollConfig;
import com.Lilith.ysmragdoll.event.ServerDeathEvents;
import com.Lilith.ysmragdoll.event.ServerExplosionEvents;
import com.Lilith.ysmragdoll.network.ExplosionImpulseSnapshot;
import com.Lilith.ysmragdoll.network.PlayerDeathSnapshot;
import com.Lilith.ysmragdoll.network.RagdollNetwork;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/** 双端共用初始化：读取配置、注册网络通道和服务端事件监听。 */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        YsmRagdollConfig.load(event.getSuggestedConfigurationFile());
        RagdollNetwork.register();
        MinecraftForge.EVENT_BUS.register(new ServerDeathEvents());
        MinecraftForge.EVENT_BUS.register(new ServerExplosionEvents());
        YsmRagdollLog.info("ysmragdoll 初始化，协议版本 " + ysmragdoll.PROTOCOL_VERSION);
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {}

    /** 专用服务端不加载任何客户端类，因此这两个回调默认什么都不做。 */
    public void handleDeathSnapshot(PlayerDeathSnapshot message) {}

    public void handleExplosion(ExplosionImpulseSnapshot message) {}
}
