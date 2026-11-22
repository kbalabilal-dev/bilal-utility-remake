package com.bilalutility.hud;

import com.bilalutility.BilalKeys;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class HudClient implements ClientModInitializer {
    private static KeyBinding toggleKey;
    private static KeyBinding settingsKey;

    @Override
    public void onInitializeClient() {
        HudConfig.load();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bilalhud.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, BilalKeys.CATEGORY));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bilalhud.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, BilalKeys.CATEGORY));

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.of("bilal_utility", "bilal_hud"), BilalHud::render);

        // The server's side panel (scoreboard sidebar): hidden, scaled and moved, or redrawn as a custom panel.
        HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD, original -> (ctx, tickCounter) -> {
            HudConfig.Data c = HudConfig.d;
            if (c.sbHidden) return;
            if (c.sbMode == 1) {
                ScoreboardPanel.render(ctx);
                return;
            }
            if (c.scoreboardScale == 100 && c.sbX == 0 && c.sbY == 0) {
                original.render(ctx, tickCounter);
                return;
            }
            float scale = c.scoreboardScale / 100f;
            float sw = ctx.getScaledWindowWidth();
            float sh = ctx.getScaledWindowHeight();
            var m = ctx.getMatrices();
            m.pushMatrix();
            // scale towards the right edge, vertically centered (where the sidebar sits), then move by the offset
            m.translate(sw + c.sbX, sh / 2f + c.sbY);
            m.scale(scale, scale);
            m.translate(-sw, -sh / 2f);
            original.render(ctx, tickCounter);
            m.popMatrix();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                HudConfig.d.enabled = !HudConfig.d.enabled;
                HudConfig.save();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(HudConfig.d.enabled ? "HUD: ON" : "HUD: OFF"), true);
                }
            }
            while (settingsKey.wasPressed()) {
                if (client.world != null && client.currentScreen == null) {
                    client.setScreen(new HudConfigScreen(null));
                }
            }
        });
    }
}
