package com.bilalutility.hunt;

/**
 * Turns the palette numbers of a chunk into NEW, OLD or UNKNOWN. UNKNOWN is a real answer: when the
 * evidence is thin we say so instead of guessing, which is how a hunter avoids chasing false leads.
 *
 * Modes (HuntConfig.rule), each one a different set of signals so /bhunt report can compare them:
 *   0 biome palette only                      1 block palette, XaeroPlus style (linear order / hash extras)
 *   2 block palette, any section out of order 3 combined (default): biome first, tolerant block check as backup
 *   4 block palette, first entries only (survives builds in old chunks)
 */
public final class HuntClassifier {
    private HuntClassifier() {}

    public static final byte UNKNOWN = ChunkStore.UNK, NEW = ChunkStore.NEW, OLD = ChunkStore.OLD;

    public static final String[] NAMES = {
            "biome palette only",
            "block palette, linear order and hash extras",
            "block palette, any section out of order",
            "combined: biome first, block start as backup",
            "block palette, first entries only"
    };

    public static byte verdict(int mode, ChunkProbe.Features f) {
        return verdict(mode, f, HuntConfig.d.bioThreshold, HuntConfig.d.blkThreshold);
    }

    public static byte verdict(int mode, ChunkProbe.Features f, int bioThr, int blkThr) {
        switch (mode) {
            case 0:
                return biome(f, bioThr);
            case 1:
                return blocks(f.linearOut + f.hashExtra, f, blkThr);
            case 2:
                return blocks(f.blkOut, f, 1);
            case 4:
                return blocks(f.blkPrefixOut, f, blkThr);
            default: {
                // Biome palettes start as plains and keep it until the chunk is saved, and players cannot change
                // biomes, so builds in an old chunk do not disturb this signal. In the Nether and the End plains
                // never really occurs, so a flagged biome palette means new and a clean one means old.
                byte b = biome(f, bioThr);
                if (f.nether && b != UNKNOWN) return b;
                // In the Overworld a flag still proves new. A clean palette proves little, because chunks that are
                // all plains look clean either way, so the block palette has to confirm.
                if (b == NEW) return NEW;
                return blocks(f.blkPrefixOut, f, blkThr);
            }
        }
    }

    private static byte biome(ChunkProbe.Features f, int thr) {
        if (f.bioSections == 0) return UNKNOWN;
        return f.bioFlag >= thr ? NEW : OLD;
    }

    /**
     * It takes several sections to agree: the server can tick a chunk before sending it, which changes a
     * single section without the chunk being old.
     */
    private static byte blocks(int positive, ChunkProbe.Features f, int thr) {
        if (positive >= thr) return NEW;
        return f.blkSections >= HuntConfig.d.minEvidence ? OLD : UNKNOWN;
    }
}
