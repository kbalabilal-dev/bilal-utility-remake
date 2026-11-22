package com.bilalutility.regear;

import com.bilalutility.ui.ProgressBox;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/** Small progress box shown while a regear is running. */
public final class RegearHud {
    private RegearHud() {}

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        if (!RegearRunner.isRunning()) return;
        ProgressBox.render(context, RegearRunner.hudLines(), RegearRunner.progress(), RegearConfig.hudPos,
                RegearConfig.hudScale, RegearConfig.hudX, RegearConfig.hudY, 0x55FF55);
    }
}
