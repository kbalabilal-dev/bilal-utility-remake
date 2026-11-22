package com.bilalutility.anchor;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.screen.Screen;

public class AnchorConfigScreen extends BilalScreen {
    private static final int[][] PRESETS = {{2, 3, 6, 14}, {1, 2, 4, 10}, {0, 1, 2, 8}};
    private static final String[] PRESET_NAMES = {"Safe", "Normal", "Fast"};

    public AnchorConfigScreen(Screen parent) {
        super("Anchor Macro", "One key: place, charge and blow a respawn anchor", 0xFF8A2A, parent);
    }

    public static String currentPreset() {
        for (int i = 0; i < PRESETS.length; i++) {
            int[] p = PRESETS[i];
            if (AnchorConfig.stepDelayTicks == p[0] && AnchorConfig.placeWaitTicks == p[1]
                    && AnchorConfig.chainGapTicks == p[2] && AnchorConfig.maxWaitTicks == p[3]) return PRESET_NAMES[i];
        }
        return "Custom";
    }

    private static String ticks(int v) {
        return v + " t";
    }

    @Override
    protected void build() {
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int y = contentTop();

        section("Speed preset", y);
        y += 12;
        int w = (inner - 8) / 3;
        for (int i = 0; i < PRESETS.length; i++) {
            int[] p = PRESETS[i];
            button(PRESET_NAMES[i], left + i * (w + 4), y, w, () -> {
                AnchorConfig.applyPreset(p[0], p[1], p[2], p[3]);
                client.setScreen(new AnchorConfigScreen(parent));
            }, "Sets all four timings below at once");
        }
        dynamic(() -> "Current: " + currentPreset(), left + inner - 90, y - 16, 90, 0xFFFFCC66);

        y += 28;
        section("Timing (20 ticks = 1 second)", y);
        y += 12;
        stepper(y, "Step delay", () -> AnchorConfig.stepDelayTicks, v -> AnchorConfig.stepDelayTicks = v,
                0, 20, 1, 5, AnchorConfigScreen::ticks, AnchorConfig::save, "Pause between charge and blow");
        stepper(y + 22, "Place wait", () -> AnchorConfig.placeWaitTicks, v -> AnchorConfig.placeWaitTicks = v,
                0, 20, 1, 5, AnchorConfigScreen::ticks, AnchorConfig::save, "Pause after placing, before charging");
        stepper(y + 44, "Chain gap", () -> AnchorConfig.chainGapTicks, v -> AnchorConfig.chainGapTicks = v,
                0, 40, 1, 5, AnchorConfigScreen::ticks, AnchorConfig::save, "Pause between two anchors while held");
        stepper(y + 66, "Max wait", () -> AnchorConfig.maxWaitTicks, v -> AnchorConfig.maxWaitTicks = v,
                3, 60, 1, 5, AnchorConfigScreen::ticks, AnchorConfig::save, "How long to wait for the server before giving up");

        y += 94;
        section("Safety and chaining", y);
        y += 12;
        stepper(y, "Min health", () -> AnchorConfig.minHealth, v -> AnchorConfig.minHealth = v,
                0, 20, 1, 5, v -> v == 0 ? "off" : v + " hearts", AnchorConfig::save,
                "Never blow an anchor below this many hearts (0 = off)");
        stepper(y + 22, "Chain limit", () -> AnchorConfig.maxChain, v -> AnchorConfig.maxChain = v,
                0, 50, 1, 5, v -> v == 0 ? "no limit" : v + " anchors", AnchorConfig::save,
                "Stop the held-key chain after this many anchors");

        y += 48;
        int half = (inner - 4) / 2;
        toggle(left, y, half, "Offhand totem", () -> AnchorConfig.requireOffhandTotem,
                v -> AnchorConfig.requireOffhandTotem = v, AnchorConfig::save,
                "Never blow an anchor without a totem in your offhand");
        toggle(left + half + 4, y, half, "Hold to chain", () -> AnchorConfig.chainEnabled,
                v -> AnchorConfig.chainEnabled = v, AnchorConfig::save,
                "Holding the key keeps going with the next anchor");
        toggle(left, y + 22, half, "Messages", () -> AnchorConfig.messages,
                v -> AnchorConfig.messages = v, AnchorConfig::save, "Action-bar messages from the macro");

        setHint("Lower timings are faster but can fail on laggy servers");
    }

    @Override
    protected void saveAndLeave() {
        AnchorConfig.save();
    }
}
