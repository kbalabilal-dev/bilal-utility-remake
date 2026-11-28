package com.bilalutility.hunt;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.Map;

/** Small map in the top right: red = new chunks, green = old chunks, yellow = patch, cyan/pink = open/closed trail ends. */
public final class RadarHud {
    private RadarHud() {}

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!HuntConfig.d.radar || mc.world == null || mc.player == null || mc.options.hudHidden) return;
        if (!HuntDetector.supported(mc.world)) return;

        String dim = HuntDetector.dim(mc.world);
        Map<Long, Byte> m = ChunkStore.map(dim);
        int r = HuntConfig.d.radarRadius;
        int cell = 2;
        int size = (2 * r + 1) * cell;
        int x0 = ctx.getScaledWindowWidth() - size - 6;
        int y0 = 30;
        int pcx = (int) Math.floor(mc.player.getX()) >> 4;
        int pcz = (int) Math.floor(mc.player.getZ()) >> 4;

        ctx.fill(x0 - 1, y0 - 1, x0 + size + 1, y0 + size + 1, 0x99000000);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                Byte s = m.get(ChunkStore.key(pcx + dx, pcz + dz));
                if (s == null || s == ChunkStore.UNK) continue;
                int px = x0 + (dx + r) * cell;
                int pz = y0 + (dz + r) * cell;
                ctx.fill(px, pz, px + cell, pz + cell, s == ChunkStore.OLD ? 0xCC33DD55 : 0xCCDD3333);
            }
        }
        if (HuntConfig.d.sus) {
            for (Map.Entry<Long, SusStore.Entry> e : SusStore.map(dim).entrySet()) {
                if (e.getValue().total() < HuntConfig.d.susThreshold) continue;
                int dx = ChunkStore.keyX(e.getKey()) - pcx, dz = ChunkStore.keyZ(e.getKey()) - pcz;
                if (Math.abs(dx) > r || Math.abs(dz) > r) continue;
                int px = x0 + (dx + r) * cell;
                int pz = y0 + (dz + r) * cell;
                ctx.fill(px, pz, px + cell, pz + cell, 0xFFFF9900);
            }
        }
        for (HotSpots.Spot s : HuntState.spots) mark(ctx, x0, y0, r, cell, (s.x >> 4) - pcx, (s.z >> 4) - pcz, 0xFFFFDD33);
        for (Trails.Trail t : HuntState.trails) {
            mark(ctx, x0, y0, r, cell, t.ax - pcx, t.az - pcz, t.openA ? 0xFF33DDFF : 0xFFFF55CC);
            mark(ctx, x0, y0, r, cell, t.bx - pcx, t.bz - pcz, t.openB ? 0xFF33DDFF : 0xFFFF55CC);
        }
        int mid = x0 + r * cell;
        ctx.fill(mid - 1, y0 + r * cell - 1, mid + 3, y0 + r * cell + 3, 0xFFFFFFFF);

        String line1 = "old " + ChunkStore.count(dim, ChunkStore.OLD) + "  patches " + HuntState.spots.size()
                + "  trails " + HuntState.trails.size() + "  sus " + SusStore.count(dim);
        ctx.drawTextWithShadow(mc.textRenderer, line1, x0, y0 + size + 4, 0xFFFFFFFF);
        if (!HuntState.trails.isEmpty()) {
            ctx.drawTextWithShadow(mc.textRenderer, HuntState.nearestTrailText(mc), Math.max(4, x0 - 160), y0 + size + 16, 0xFF33DDFF);
        }
    }

    private static void mark(DrawContext ctx, int x0, int y0, int r, int cell, int dx, int dz, int color) {
        if (Math.abs(dx) > r || Math.abs(dz) > r) return;
        int px = x0 + (dx + r) * cell - 1;
        int pz = y0 + (dz + r) * cell - 1;
        ctx.fill(px, pz, px + 4, pz + 4, color);
    }
}
