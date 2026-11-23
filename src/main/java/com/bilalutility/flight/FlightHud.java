package com.bilalutility.flight;

import com.bilalutility.ui.ProgressBox;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/** Runs every frame: the void watchdog (fast) and the small status box. */
public final class FlightHud {
    private FlightHud() {}

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        FlightRunner.frameCheck(MinecraftClient.getInstance());
        if (FlightRunner.isRunning()) {
            ProgressBox.render(context, FlightRunner.hudLines(), FlightRunner.progress(), FlightConfig.hudPos,
                    FlightConfig.hudScale, 0, 0, 0x66CCFF);
        } else if (FlightShipRoutine.isVisible()) {
            ProgressBox.render(context, FlightShipRoutine.hudLines(), FlightShipRoutine.progress(), FlightConfig.hudPos,
                    FlightConfig.hudScale, 0, 0, 0xFFAA33);
        }
    }
}
