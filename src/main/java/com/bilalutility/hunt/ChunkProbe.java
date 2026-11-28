package com.bilalutility.hunt;

import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Measures the block and biome palettes of every chunk section the server sent us.
 *
 * Why this tells new from old: the server builds a new chunk in steps, and the palettes keep an entry
 * for everything that was ever in them (biomes start as plains, blocks start as air) in the order things
 * were placed. When a chunk is saved the palettes are rebuilt with only what is really there, in the
 * order the blocks appear. A chunk that was loaded back from disk therefore has clean palettes, and one
 * that was just generated does not. Ideas credited to etianl (Trouser-Streak), crosby and rfresh2
 * (XaeroPlus, MIT), whose public write-ups of the palette method this follows; the code is our own.
 *
 * We copy each container through the packet writer and read the copy, so only public methods are used
 * and the live chunk is never touched again. The same copy feeds the sus-chunk scanner.
 */
public final class ChunkProbe {
    private ChunkProbe() {}

    /** One palette container, copied from the packet format. */
    public static final class Section {
        public int baseY;
        public int bits;
        public int[] palette;   // ids in the order the server wrote them (null = global palette)
        public long[] data;
    }

    public static final class Features {
        public boolean nether;
        public int sections;       // block sections that are not only air
        public int skipped;        // sections we could not read
        public int airFirst;       // block sections where palette entry 0 is air
        public int bioSections;    // biome containers read
        public int bioExtra;       // biome containers with palette entries nothing uses (plains left over from generation)
        public int bioOut;         // biome containers whose palette is not in "first biome seen" order
        public int bioFlag;        // biome containers that show either sign (a chunk saved to disk shows neither)
        public int blkSections;    // block containers with 2 or more palette entries
        public int linearSections; // ... of those, small (linear) palettes
        public int linearOut;      // ... linear palettes whose order is not "first block seen"
        public int hashSections;   // ... of those, large (hash map) palettes
        public int hashExtra;      // ... hash palettes with entries nothing uses
        public int blkOut;         // any palette type out of order
        public int blkPrefixOut;   // any palette type whose first 3 entries are not in "first block seen" order
        public int blkExtra;       // any palette type with unused entries
        public String firstPalette = ""; // first palette entries of the lowest non-empty section
        public final List<Section> data = new ArrayList<>(); // every readable non-empty block section

        public String csv() {
            return sections + "," + bioSections + "," + bioExtra + "," + bioOut + "," + bioFlag + "," + blkSections + ","
                    + linearOut + "," + hashExtra + "," + blkOut + "," + blkPrefixOut + "," + blkExtra + "," + skipped + "," + firstPalette;
        }
    }

    public static final String CSV_HEADER =
            "label,dimension,cx,cz,sections,bioSections,bioExtra,bioOut,bioFlag,blkSections,linearOut,hashExtra,blkOut,blkPrefixOut,blkExtra,skipped,firstPalette";

    // counters for the log (DIAG lines): they show whether the packet format is read the way we expect
    public static int probed, sectionsRead, sectionsSkipped, globalPalette, withLengthPrefix, withoutLengthPrefix, errors;
    private static int errorsLogged;

    public static Features probe(WorldChunk chunk, boolean nether) {
        Features f = new Features();
        f.nether = nether;
        probed++;
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomY();
        boolean gotFirst = false;
        for (int idx = 0; idx < sections.length; idx++) {
            ChunkSection s = sections[idx];
            if (s == null) continue;

            // Biomes first: every section has them, including air-only ones.
            Section bio = snapshot(buf -> s.getBiomeContainer().writePacket(buf), 3, 64);
            if (bio != null && bio.palette != null) {
                f.bioSections++;
                boolean extra = hasUnused(bio, 64);
                boolean out = !inFirstSeenOrder(bio, 64);
                if (extra) f.bioExtra++;
                if (out) f.bioOut++;
                if (extra || out) f.bioFlag++;
            }

            if (s.isEmpty()) continue;
            f.sections++;
            Section snap = snapshot(buf -> s.getBlockStateContainer().writePacket(buf), 8, 4096);
            if (snap == null || snap.palette == null) {
                f.skipped++;
                sectionsSkipped++;
                if (snap != null) globalPalette++;
                continue;
            }
            sectionsRead++;
            snap.baseY = bottom + idx * 16;
            f.data.add(snap);

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

            if (snap.bits <= 0 || snap.palette.length < 2) continue;
            boolean out = !inFirstSeenOrder(snap, 4096);
            boolean extra = hasUnused(snap, 4096);
            f.blkSections++;
            if (out) f.blkOut++;
            if (prefixOut(snap, 4096)) f.blkPrefixOut++;
            if (extra) f.blkExtra++;
            if (snap.bits <= 4) {
                f.linearSections++;
                if (out) f.linearOut++;
            } else {
                f.hashSections++;
                if (extra) f.hashExtra++;
            }
        }
        return f;
    }

    private static int entryAt(Section s, int i) {
        int perLong = 64 / s.bits;
        return (int) ((s.data[i / perLong] >>> ((i % perLong) * s.bits)) & ((1L << s.bits) - 1L));
    }

    private static boolean readable(Section s, int entries) {
        if (s.bits <= 0 || s.palette == null || s.data == null) return false;
        int perLong = 64 / s.bits;
        return perLong > 0 && s.data.length >= (entries + perLong - 1) / perLong;
    }

    /** True when the palette has entries that no block (or biome) in the container uses any more. */
    private static boolean hasUnused(Section s, int entries) {
        if (s.bits <= 0) return false;
        if (!readable(s, entries)) return false; // cannot judge
        boolean[] seen = new boolean[s.palette.length];
        int used = 0;
        for (int i = 0; i < entries; i++) {
            int idx = entryAt(s, i);
            if (idx >= seen.length) return false; // unexpected data, do not judge
            if (!seen[idx]) { seen[idx] = true; used++; }
        }
        return used < s.palette.length;
    }

    /**
     * True when the palette lists its entries in the order the blocks first appear in the section
     * (bottom layer first, then row by row), which is how a saved chunk is rebuilt.
     */
    private static boolean inFirstSeenOrder(Section s, int entries) {
        if (!readable(s, entries)) return true; // cannot judge, call it fine
        boolean[] seen = new boolean[s.palette.length];
        int next = 0;
        for (int i = 0; i < entries; i++) {
            int idx = entryAt(s, i);
            if (idx >= seen.length) return true;
            if (!seen[idx]) {
                seen[idx] = true;
                if (idx != next) return false; // a block shows up before the entries that should come first
                next++;
            }
        }
        return true;
    }

    /**
     * Like inFirstSeenOrder but only looks at the first 3 palette entries. Players only add entries at the
     * end of a palette, so a chunk that was saved and then built in still passes this test, while a chunk
     * that was just generated (air first, then stone, water ...) does not.
     */
    private static boolean prefixOut(Section s, int entries) {
        if (!readable(s, entries)) return false;
        int k = Math.min(3, s.palette.length);
        int[] first = new int[k];
        int found = 0;
        boolean[] seen = new boolean[s.palette.length];
        for (int i = 0; i < entries && found < k; i++) {
            int idx = entryAt(s, i);
            if (idx >= seen.length) return false;
            if (!seen[idx]) { seen[idx] = true; first[found++] = idx; }
        }
        for (int j = 0; j < k; j++) {
            if (j >= found || first[j] != j) return true;
        }
        return false;
    }

    /** How many blocks of each palette entry the section really holds (all zero if it cannot be read). */
    public static int[] counts(Section s) {
        int[] c = new int[s.palette.length];
        if (s.bits <= 0) {
            c[0] = 4096;
            return c;
        }
        if (!readable(s, 4096)) return c;
        for (int i = 0; i < 4096; i++) {
            int idx = entryAt(s, i);
            if (idx < c.length) c[idx]++;
        }
        return c;
    }

    /**
     * Copies one container through the packet writer. Newer versions write no length in front of the long
     * array, older ones do, so the expected size decides which one we are looking at.
     */
    private static Section snapshot(Consumer<PacketByteBuf> writer, int maxIndirectBits, int entries) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            writer.accept(buf);
            Section s = new Section();
            s.bits = buf.readUnsignedByte();
            if (s.bits == 0) {
                s.palette = new int[]{buf.readVarInt()};
                return s;
            }
            if (s.bits > maxIndirectBits) return s; // global palette, order carries no information
            int n = buf.readVarInt();
            s.palette = new int[n];
            for (int i = 0; i < n; i++) s.palette[i] = buf.readVarInt();

            int perLong = 64 / s.bits;
            int expected = (entries + perLong - 1) / perLong;
            int longs;
            if (buf.readableBytes() == expected * 8) {
                longs = expected;
                withoutLengthPrefix++;
            } else {
                int start = buf.readerIndex();
                int len = buf.readVarInt();
                if (len >= 0 && buf.readableBytes() == (long) len * 8L) {
                    longs = len;
                    withLengthPrefix++;
                } else {
                    buf.readerIndex(start);
                    longs = buf.readableBytes() / 8;
                    withoutLengthPrefix++;
                }
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
