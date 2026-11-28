package com.bilalutility.hunt;

import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/** Looks at every chunk the server sends (Overworld and Nether only) and records whether it was new or old on arrival. */
public final class HuntDetector {
    private HuntDetector() {}

    public static int classifiedNew, classifiedOld, unreadable;

    public static boolean supported(ClientWorld world) {
        return world.getRegistryKey() == World.OVERWORLD || world.getRegistryKey() == World.NETHER;
    }

    public static String dim(ClientWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    public static void onChunk(ClientWorld world, WorldChunk chunk) {
        if (!supported(world)) return;
        String dim = dim(world);
        int cx = chunk.getPos().getStartX() >> 4;
        int cz = chunk.getPos().getStartZ() >> 4;

        scanPortals(dim, chunk);

        if (!HuntConfig.d.detect) return;
        if (ChunkStore.map(dim).containsKey(ChunkStore.key(cx, cz))) return; // first look wins

        ChunkProbe.Features f = ChunkProbe.probe(chunk);
        if (f.informative == 0) { unreadable++; return; } // nothing readable, do not guess
        boolean isNew = HuntCalibration.ruleSaysNew(HuntConfig.d.rule, f);
        if (ChunkStore.put(dim, cx, cz, isNew ? ChunkStore.NEW : ChunkStore.OLD)) {
            if (isNew) classifiedNew++; else classifiedOld++;
            HuntState.markDirty();
        }
    }

    /** Nether portal blocks are placed by players, so every one found is logged and remembered. */
    private static void scanPortals(String dim, WorldChunk chunk) {
        ChunkSection[] secs = chunk.getSectionArray();
        int baseY = chunk.getBottomY();
        for (int i = 0; i < secs.length; i++) {
            ChunkSection s = secs[i];
            if (s == null || s.isEmpty() || !s.hasAny(st -> st.isOf(Blocks.NETHER_PORTAL))) continue;
            long sx = 0, sy = 0, sz = 0;
            int n = 0;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (s.getBlockState(x, y, z).isOf(Blocks.NETHER_PORTAL)) { sx += x; sy += y; sz += z; n++; }
                    }
                }
            }
            if (n > 0) {
                PortalStore.add(dim, chunk.getPos().getStartX() + (int) (sx / n),
                        baseY + i * 16 + (int) (sy / n), chunk.getPos().getStartZ() + (int) (sz / n));
            }
        }
    }
}
