package com.bilalutility.tpa;

import com.bilalutility.ui.ProgressBox;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/** Small progress box shown while a queue is running. */
public final class TpaHud {
    private TpaHud() {}

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        if (!TpaQueue.isRunning()) return;
        ProgressBox.render(context, TpaQueue.hudLines(), TpaQueue.progress(), TpaConfig.hudPos,
                TpaConfig.hudScale, TpaConfig.hudX, TpaConfig.hudY, 0x3FA9FF);
    }
}
