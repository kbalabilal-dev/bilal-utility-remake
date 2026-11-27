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
    private static KeyBinding debugShulkersKey;
    private static KeyBinding shipStepKey;

    /** Name of the key bound to "ship step", for the HUD and the settings screen. */
    public static String stepKeyText() {
        if (shipStepKey == null || shipStepKey.isUnbound()) return "not set - Controls > Bilal";
        return shipStepKey.getBoundKeyLocalizedText().getString();
    }

    @Override
    public void onInitializeClient() {
        FlightConfig.load();
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bilalfly.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bilalfly.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        debugShulkersKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bilalfly.debug_shulkers", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));

        shipStepKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bilalfly.ship_step", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.of("bilal_utility", "flight_hud"), FlightHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                if (FlightShipRoutine.isActive()) {
                    FlightShipRoutine.cancel(client, "Ship routine cancelled - press the flight key again to fly");
                } else if (FlightRunner.isPausedAfterLandingFailure()) {
                    FlightRunner.resumeAfterLandingFailure(client);
                } else if (FlightRunner.isRunning()) {
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
            while (debugShulkersKey.wasPressed()) {
                if (client.world != null && client.player != null) {
                    FlightRunner.debugShulkers(client);
                }
            }
            while (shipStepKey.wasPressed()) {
                FlightShipRoutine.onStepKey(client);
            }
            PingGuard.tick(client);
            FlightRunner.tick(client);
            FlightShipRoutine.tick(client);
        });
    }
}
