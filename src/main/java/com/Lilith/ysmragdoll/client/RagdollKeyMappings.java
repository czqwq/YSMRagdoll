package com.Lilith.ysmragdoll.client;

import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;

/** 可在 Minecraft 控制设置中重新绑定的设置快捷键，默认未绑定。 */
public final class RagdollKeyMappings {

    public static final KeyBinding OPEN_SETTINGS = new KeyBinding(
        "key.ysmragdoll.open_settings",
        Keyboard.KEY_NONE,
        "key.categories.ysmragdoll");

    private RagdollKeyMappings() {}

    public static void register() {
        ClientRegistry.registerKeyBinding(OPEN_SETTINGS);
    }

    public static boolean isDown() {
        int code = OPEN_SETTINGS.getKeyCode();
        return code != Keyboard.KEY_NONE && Keyboard.isKeyDown(code);
    }

    public static boolean isUnbound() {
        return OPEN_SETTINGS.getKeyCode() == Keyboard.KEY_NONE;
    }
}
