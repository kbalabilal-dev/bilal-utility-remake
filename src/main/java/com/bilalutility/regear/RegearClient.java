package com.bilalutility.regear;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class RegearClient implements ClientModInitializer {
    private static final KeyBinding.Category CATEGORY = com.bilalutility.BilalKeys.CATEGORY;
    private static KeyBinding startKey;
    private static KeyBinding pauseKey;
    private static KeyBinding settingsKey;
    private static KeyBinding sortKey;
    private static KeyBinding nextKitKey;
    private static KeyBinding prevKitKey;

    @Override
    public void onInitializeClient() {
        RegearConfig.load();
        RegearKits.load();

        startKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.regear.start", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY));
        pauseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.regear.pause", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.regear.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));

        sortKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.regear.sort", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        nextKitKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.regear.nextkit", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        prevKitKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.regear.prevkit", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.of("bilal_utility", "regear_hud"), RegearHud::render);

        // Purchase / inventory-full / not-enough-money messages from the server.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> RegearRunner.onChat(message.getString()));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (startKey.wasPressed()) {
                if (RegearRunner.isRunning()) {
                    RegearRunner.cancel(client);
                } else if (client.world != null && client.currentScreen == null) {
                    RegearRunner.start(client);
                }
            }
            while (pauseKey.wasPressed()) {
                RegearRunner.togglePause(client);
            }
            while (settingsKey.wasPressed()) {
                if (client.world != null && client.currentScreen == null) {
                    client.setScreen(new RegearConfigScreen(null));
                }
            }
            while (sortKey.wasPressed()) {
                if (client.world != null && client.currentScreen == null) RegearRunner.sortOnly(client);
            }
            while (nextKitKey.wasPressed()) cycleKit(client, 1);
            while (prevKitKey.wasPressed()) cycleKit(client, -1);
            RegearRunner.tick(client);
        });
    }

    private static void cycleKit(MinecraftClient client, int dir) {
        if (RegearRunner.isRunning() || client.world == null || client.currentScreen != null) return;
        RegearKits.Kit k = RegearKits.cycle(dir);
        if (k != null && client.player != null) {
            client.player.sendMessage(Text.literal("Regear kit: " + k.name), true);
        }
    }
}
