package com.bilalutility.flight;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class FlightClient implements ClientModInitializer {
    private static final KeyBinding.Category CATEGORY = com.bilalutility.BilalKeys.CATEGORY;
    private static KeyBinding toggleKey;
    private static KeyBinding settingsKey;

    @Override
    public void onInitializeClient() {
        FlightConfig.load();
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bilalfly.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bilalfly.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.of("bilal_utility", "flight_hud"), FlightHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                if (FlightRunner.isRunning()) {
                    FlightRunner.stop(client, "Auto Elytra stopped");
                } else if (client.world != null && client.currentScreen == null) {
                    FlightRunner.start(client);
                }
            }
            while (settingsKey.wasPressed()) {
                if (client.world != null && client.currentScreen == null) {
                    client.setScreen(new FlightScreen(null));
                }
            }
            FlightRunner.tick(client);
        });
    }
}
