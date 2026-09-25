package com.Lilith.ysmragdoll;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * 模组公共入口，不引用任何仅在客户端存在的类。
 *
 * <p>
 * 服务端只负责把很小的死亡/爆炸状态广播给声明了本模组网络通道的客户端；
 * 模型捕获、JBullet 刚体和渲染全部发生在客户端。
 * </p>
 */
@Mod(
    modid = ysmragdoll.MODID,
    version = Tags.VERSION,
    name = "YSM Ragdoll",
    acceptedMinecraftVersions = "[1.7.10]",
    // geckolib 是硬依赖：引擎已从 ysmu 剥离为独立模组，本模组直接引用它的几何与渲染类型。
    // FML 1.7.10 的多依赖分隔符是分号（写成逗号会抛 LoaderException）。
    dependencies = "required-after:geckolib;after:ysmu")
public class ysmragdoll {

    public static final String MODID = "ysmragdoll";

    /** 客户端与服务端之间共享的网络协议版本。 */
    public static final String PROTOCOL_VERSION = "2";

    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.Lilith.ysmragdoll.ClientProxy", serverSide = "com.Lilith.ysmragdoll.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }
}
