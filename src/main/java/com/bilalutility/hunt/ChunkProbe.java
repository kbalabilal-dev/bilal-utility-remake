package com.bilalutility.hunt;

import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Reads the block palette of every chunk section the server sent us and turns it into numbers.
 *
 * Why this tells new from old: a chunk the server has just generated keeps its block palette in the
 * order blocks were placed during generation, and may keep entries that are no longer used. A chunk
 * that was saved and loaded from disk has its palette rebuilt in "first block seen" order with
 * nothing unused. We copy the palette through the packet writer, so we only use public methods and
 * we never touch the live chunk again.
 *
 * Nothing here decides "new" or "old". HuntCalibration measures which signal works on your server.
 */
public final class ChunkProbe {
    private ChunkProbe() {}

    public static final class Features {
        public int sections;       // sections that are not only air
        public int informative;    // sections with 3 or more palette entries (order can show)
        public int nonCanonical;   // informative sections whose palette is out of order or has unused entries
        public int unusedSections; // sections with palette entries nobody uses
        public int airFirst;       // sections where palette entry 0 is air
        public int skipped;        // sections we could not read
        public String firstPalette = ""; // first palette entries of the lowest non-empty section

        public String csv() {
            return sections + "," + informative + "," + nonCanonical + "," + unusedSections + ","
                    + airFirst + "," + skipped + "," + firstPalette;
        }
    }

    // counters for the log (DIAG lines): they show whether the packet format is read the way we expect
    public static int probed, sectionsRead, sectionsSkipped, globalPalette, withLengthPrefix, withoutLengthPrefix, errors;
    private static int errorsLogged;

    private static final class Snap {
        int bits;
        int[] palette;   // raw block state ids, in the order the server wrote them
        long[] data;
    }

    public static Features probe(WorldChunk chunk) {
        Features f = new Features();
        probed++;
        ChunkSection[] sections = chunk.getSectionArray();
        boolean gotFirst = false;
        for (ChunkSection s : sections) {
            if (s == null || s.isEmpty()) continue;
            f.sections++;
            Snap snap = snapshot(s.getBlockStateContainer());
            if (snap == null || snap.palette == null) {
                f.skipped++;
                sectionsSkipped++;
                if (snap != null) globalPalette++;
                continue;
            }
            sectionsRead++;

            BlockState first = Block.STATE_IDS.get(snap.palette[0]);
            if (first != null && first.isOf(Blocks.AIR)) f.airFirst++;

            if (!gotFirst) {
                gotFirst = true;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(3, snap.palette.length); i++) {
                    BlockState st = Block.STATE_IDS.get(snap.palette[i]);
                    if (i > 0) sb.append('|');
                    sb.append(st == null ? "?" : Registries.BLOCK.getId(st.getBlock()).getPath());
                }
                f.firstPalette = sb.toString();
            }

            if (snap.palette.length < 3) continue;
            f.informative++;

            boolean ok = analyse(snap);
            if (!ok) f.nonCanonical++;
            if (lastUnused) f.unusedSections++;
        }
        return f;
    }

    // set by analyse(); the probe runs on one thread, so a plain field is enough
    private static boolean lastUnused;

    /** True when the palette is exactly "first block seen" order with no unused entries. */
    private static boolean analyse(Snap s) {
        lastUnused = false;
        int bits = s.bits;
        if (bits <= 0) return true;
        int perLong = 64 / bits;
        if (perLong <= 0 || s.data.length < (4096 + perLong - 1) / perLong) return true; // cannot judge
        long mask = (1L << bits) - 1L;
        boolean[] seen = new boolean[s.palette.length];
        int next = 0;
        boolean ordered = true;
        int used = 0;
        for (int i = 0; i < 4096; i++) {
            int idx = (int) ((s.data[i / perLong] >>> ((i % perLong) * bits)) & mask);
            if (idx >= seen.length) return true; // corrupt or unexpected, do not judge
            if (!seen[idx]) { seen[idx] = true; used++; }
            if (idx == next) next++;
            else if (idx > next) ordered = false;
        }
        if (used < s.palette.length) lastUnused = true;
        return ordered && used == s.palette.length;
    }

    private static Snap snapshot(PalettedContainer<BlockState> container) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            container.writePacket(buf);
            Snap s = new Snap();
            s.bits = buf.readUnsignedByte();
            if (s.bits == 0) {
                s.palette = new int[]{buf.readVarInt()};
            } else if (s.bits <= 8) {
                int n = buf.readVarInt();
                s.palette = new int[n];
                for (int i = 0; i < n; i++) s.palette[i] = buf.readVarInt();
            } else {
                return s; // global palette, order carries no information
            }
            // Newer versions write no length in front of the long array, older ones do. Handle both.
            int start = buf.readerIndex();
            int longs;
            int len = buf.readVarInt();
            if (len >= 0 && buf.readableBytes() == (long) len * 8L) {
                longs = len;
                withLengthPrefix++;
            } else {
                buf.readerIndex(start);
                longs = buf.readableBytes() / 8;
                withoutLengthPrefix++;
            }
            s.data = new long[longs];
            for (int i = 0; i < longs; i++) s.data[i] = buf.readLong();
            return s;
        } catch (Throwable t) {
            errors++;
            if (errorsLogged < 10) {
                errorsLogged++;
                HuntLog.line("ERROR", "palette read failed: " + t);
            }
            return null;
        } finally {
            buf.release();
        }
    }
}
