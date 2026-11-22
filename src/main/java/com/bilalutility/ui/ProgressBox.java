package com.bilalutility.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/** The small progress box used by the TPAHere queue and the Auto Regear run. Size and position are configurable. */
public final class ProgressBox {
    private ProgressBox() {}

    /**
     * @param pos      0 = off, 1 = top center, 2 = top left, 3 = top right
     * @param scalePct size in percent (50-200)
     * @param ox       offset in pixels (screen scale)
     */
    public static void render(DrawContext ctx, List<String> lines, float progress, int pos,
                              int scalePct, int ox, int oy, int accent) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (pos == 0 || lines.isEmpty() || mc.player == null) return;
        TextRenderer tr = mc.textRenderer;

        float s = scalePct / 100f;
        int w = 120;
        for (String l : lines) w = Math.max(w, tr.getWidth(l));
        int sw = Math.round(ctx.getScaledWindowWidth() / s);
        int x;
        switch (pos) {
            case 2 -> x = 10;
            case 3 -> x = sw - w - 10;
            default -> x = (sw - w) / 2;
        }
        x += Math.round(ox / s);
        int y = 10 + Math.round(oy / s);
        int textH = lines.size() * 11;
        int solid = 0xFF000000 | (accent & 0xFFFFFF);

        var m = ctx.getMatrices();
        m.pushMatrix();
        m.scale(s, s);
        ctx.fill(x - 6, y - 4, x + w + 4, y + textH + 10, 0xB00A0A10);
        ctx.fill(x - 6, y - 4, x - 4, y + textH + 10, solid);
        for (int i = 0; i < lines.size(); i++) {
            ctx.drawTextWithShadow(tr, lines.get(i), x, y + i * 11, i == 0 ? solid : 0xFFFFFFFF);
        }
        int by = y + textH + 1;
        ctx.fill(x, by, x + w, by + 4, 0xFF333340);
        ctx.fill(x, by, x + (int) (w * Math.max(0f, Math.min(1f, progress))), by + 4, solid);
        m.popMatrix();
    }
}
