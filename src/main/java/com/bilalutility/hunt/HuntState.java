package com.bilalutility.hunt;

import net.minecraft.client.MinecraftClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Recomputes patches and trails every few seconds, keeps the results for the radar and screen, and writes the log. */
public final class HuntState {
    private HuntState() {}

    public static List<HotSpots.Spot> spots = List.of();
    public static List<Trails.Trail> trails = List.of();
    public static String dim = "";

    private static boolean dirty = true;
    private static int ticks;
    private static final Map<String, Integer> loggedSpots = new HashMap<>();
    private static final Map<String, String> loggedTrails = new HashMap<>();
    private static int lastNew, lastOld;

    public static void markDirty() { dirty = true; }

    public static void tick(MinecraftClient mc) {
        ticks++;
        if (mc.world == null || mc.player == null || !HuntDetector.supported(mc.world)) {
            spots = List.of();
            trails = List.of();
            return;
        }
        String d = HuntDetector.dim(mc.world);
        if (!d.equals(dim)) {
            dim = d;
            dirty = true;
            HuntLog.line("DIM", "now in " + d + " at " + pos(mc));
        }
        if (ticks % 300 == 0) HuntLog.line("POS", d + " " + pos(mc));
        if (dirty && ticks % 60 == 0) {
            dirty = false;
            recompute(d, mc);
        }
        if (ticks % 1200 == 0) summary(d);
    }

    private static String pos(MinecraftClient mc) {
        return (int) Math.floor(mc.player.getX()) + "," + (int) Math.floor(mc.player.getY()) + "," + (int) Math.floor(mc.player.getZ());
    }

    private static void recompute(String d, MinecraftClient mc) {
        List<HotSpots.Cluster> clusters = HotSpots.clusters(d);
        spots = HotSpots.spots(clusters);
        trails = Trails.find(d, clusters);

        for (HotSpots.Spot s : spots) {
            String key = (s.x >> 6) + "," + (s.z >> 6);
            Integer last = loggedSpots.get(key);
            if (last == null || s.size >= last * 2) {
                loggedSpots.put(key, s.size);
                HuntLog.line("PATCH", d + " " + s.x + "," + s.z + " old chunks=" + s.size);
            }
        }
        for (Trails.Trail t : trails) {
            String key = ((int) t.mx >> 7) + "," + ((int) t.mz >> 7);
            String state = t.axis() + (t.openA ? " A-open" : " A-closed") + (t.openB ? " B-open" : " B-closed");
            if (state.equals(loggedTrails.get(key))) continue;
            loggedTrails.put(key, state);
            HuntLog.line("TRAIL", d + " " + t.axis() + " chunks=" + t.n + " length=" + Math.round(t.len)
                    + " endA=" + t.blockAX() + "," + t.blockAZ() + (t.openA ? " open" : " closed")
                    + " endB=" + t.blockBX() + "," + t.blockBZ() + (t.openB ? " open" : " closed"));
            if (!t.openA) logNearPortals(d, t.blockAX(), t.blockAZ(), "endA");
            if (!t.openB) logNearPortals(d, t.blockBX(), t.blockBZ(), "endB");
        }
    }

    private static void logNearPortals(String d, int x, int z, String which) {
        for (PortalStore.Portal p : PortalStore.near(d, x, z, 300)) {
            HuntLog.line("TRAIL", which + " has a portal near: " + p.x + "," + p.y + "," + p.z);
        }
    }

    private static void summary(String d) {
        int nNew = HuntDetector.classifiedNew, nOld = HuntDetector.classifiedOld;
        HuntLog.line("SUMMARY", d + " stored old=" + ChunkStore.count(d, ChunkStore.OLD) + " new=" + ChunkStore.count(d, ChunkStore.NEW)
                + " | last minute: +" + (nNew - lastNew) + " new, +" + (nOld - lastOld) + " old"
                + " | patches=" + spots.size() + " trails=" + trails.size()
                + " | this session: " + HuntDetector.classifiedNew + " new, " + HuntDetector.classifiedOld + " old, "
                + HuntDetector.classifiedUnknown + " unsure");
        lastNew = nNew;
        lastOld = nOld;
        HuntLog.line("DIAG", "chunks probed=" + ChunkProbe.probed + " sections read=" + ChunkProbe.sectionsRead
                + " skipped=" + ChunkProbe.sectionsSkipped + " globalPalette=" + ChunkProbe.globalPalette
                + " lengthPrefix=" + ChunkProbe.withLengthPrefix + " noPrefix=" + ChunkProbe.withoutLengthPrefix
                + " errors=" + ChunkProbe.errors + " rule=" + (HuntConfig.d.rule + 1)
                + " bioThr=" + HuntConfig.d.bioThreshold + " blkThr=" + HuntConfig.d.blkThreshold);
    }

    /** One line about the closest trail, for the radar and the screen. */
    public static String nearestTrailText(MinecraftClient mc) {
        if (mc.player == null || trails.isEmpty()) return "no trail found yet";
        Trails.Trail t = Trails.nearest(trails, mc.player.getX(), mc.player.getZ());
        return t == null ? "no trail found yet" : t.describe(mc.player.getX(), mc.player.getZ());
    }
}
