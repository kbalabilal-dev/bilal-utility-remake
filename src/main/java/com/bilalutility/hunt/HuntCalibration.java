package com.bilalutility.hunt;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Learn mode. You tell it "the chunks that load now are NEW" (flying into ground nobody has touched)
 * or "OLD" (your own base, places you have been), and it records the palette numbers of each chunk.
 * /bhunt report then shows how well every set of signals separates the two on THIS server, and
 * /bhunt tune picks the thresholds that score best.
 */
public final class HuntCalibration {
    private HuntCalibration() {}

    private static final class Sample {
        final boolean isNew;
        final String dim;
        final int cx, cz;
        final ChunkProbe.Features f;
        Sample(boolean isNew, String dim, int cx, int cz, ChunkProbe.Features f) {
            this.isNew = isNew; this.dim = dim; this.cx = cx; this.cz = cz; this.f = f;
        }
    }

    private static final List<Sample> samples = new ArrayList<>();
    private static int flushed = 0;
    private static Boolean learnNew = null; // null = not learning

    public static boolean isLearning() { return learnNew != null; }
    public static String learnLabel() { return learnNew == null ? "off" : (learnNew ? "NEW" : "OLD"); }
    public static int count() { return samples.size(); }

    public static void setLearn(Boolean label) {
        learnNew = label;
        HuntLog.line("CALIB", "learn " + learnLabel() + " samples=" + samples.size());
        if (label == null) flush();
    }

    public static void clear() { samples.clear(); flushed = 0; }

    public static void onChunk(ClientWorld world, WorldChunk chunk) {
        if (learnNew == null || !HuntDetector.supported(world)) return;
        ChunkProbe.Features f = ChunkProbe.probe(chunk, world.getRegistryKey() == World.NETHER);
        f.data.clear(); // samples only need the numbers, not the raw sections
        int cx = chunk.getPos().getStartX() >> 4;
        int cz = chunk.getPos().getStartZ() >> 4;
        samples.add(new Sample(learnNew, world.getRegistryKey().getValue().toString(), cx, cz, f));
    }

    private static Path file() {
        String server = "singleplayer";
        ServerInfo info = MinecraftClient.getInstance().getCurrentServerEntry();
        if (info != null && info.address != null) server = info.address.replaceAll("[^a-zA-Z0-9._-]", "_");
        return FabricLoader.getInstance().getGameDir().resolve("bilal_hunt").resolve(server + "_calibration.csv");
    }

    /** Appends the samples not saved yet to the CSV file. */
    public static String flush() {
        if (flushed >= samples.size()) return "nothing new to save";
        try {
            Path p = file();
            Files.createDirectories(p.getParent());
            boolean fresh = Files.notExists(p);
            StringBuilder sb = new StringBuilder();
            if (fresh) sb.append(ChunkProbe.CSV_HEADER).append('\n');
            for (int i = flushed; i < samples.size(); i++) {
                Sample s = samples.get(i);
                sb.append(s.isNew ? "new" : "old").append(',').append(s.dim).append(',')
                  .append(s.cx).append(',').append(s.cz).append(',').append(s.f.csv()).append('\n');
            }
            Files.writeString(p, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            int n = samples.size() - flushed;
            flushed = samples.size();
            return "saved " + n + " samples to " + p;
        } catch (IOException e) {
            return "could not save: " + e.getMessage();
        }
    }

    public static String ruleName(int index) {
        return HuntClassifier.NAMES[Math.max(0, Math.min(HuntClassifier.NAMES.length - 1, index))];
    }

    public static int ruleCount() { return HuntClassifier.NAMES.length; }

    /** Per-class results for one set of signals: [right on new, right on old, unknown on new, unknown on old]. */
    private static double[] score(int mode, int bioThr, int blkThr, int nNew, int nOld) {
        int newRight = 0, oldRight = 0, newUnk = 0, oldUnk = 0;
        for (Sample s : samples) {
            byte v = HuntClassifier.verdict(mode, s.f, bioThr, blkThr);
            if (v == HuntClassifier.UNKNOWN) { if (s.isNew) newUnk++; else oldUnk++; }
            else if (s.isNew && v == HuntClassifier.NEW) newRight++;
            else if (!s.isNew && v == HuntClassifier.OLD) oldRight++;
        }
        return new double[]{100.0 * newRight / nNew, 100.0 * oldRight / nOld, 100.0 * newUnk / nNew, 100.0 * oldUnk / nOld};
    }

    public static List<String> report() {
        List<String> out = new ArrayList<>();
        int nNew = 0, nOld = 0;
        for (Sample s : samples) { if (s.isNew) nNew++; else nOld++; }
        HuntLog.line("CALIB", "report requested, new=" + nNew + " old=" + nOld);
        out.add("Samples: " + nNew + " NEW, " + nOld + " OLD");
        if (nNew == 0 || nOld == 0) {
            out.add("Collect both kinds first: /bhunt learn new  (fresh ground) and /bhunt learn old  (your base area).");
            return out;
        }
        out.add("Each line: how often it is right on new chunks / right on old chunks / unsure. Wrong answers are what is left over.");
        for (int mode = 0; mode < HuntClassifier.NAMES.length; mode++) {
            double[] r = score(mode, HuntConfig.d.bioThreshold, HuntConfig.d.blkThreshold, nNew, nOld);
            String res = String.format("%d. %s: new %.1f%%, old %.1f%%, unsure %.1f%% / %.1f%%",
                    mode + 1, HuntClassifier.NAMES[mode], r[0], r[1], r[2], r[3]);
            HuntLog.line("CALIB", res);
            out.add(res);
        }
        out.add("Good = both numbers near 100. Try /bhunt tune to pick the best thresholds, then /bhunt rule <number>.");
        return out;
    }

    /** Tries every threshold pair on the samples, keeps the best one for the rule in use, and says what it did. */
    public static List<String> tune() {
        List<String> out = new ArrayList<>();
        int nNew = 0, nOld = 0;
        for (Sample s : samples) { if (s.isNew) nNew++; else nOld++; }
        if (nNew < 20 || nOld < 20) {
            out.add("Need at least 20 NEW and 20 OLD samples first (have " + nNew + " and " + nOld + ").");
            return out;
        }
        int mode = HuntConfig.d.rule;
        int bestBio = HuntConfig.d.bioThreshold, bestBlk = HuntConfig.d.blkThreshold;
        double best = -1;
        for (int bio = 1; bio <= 4; bio++) {
            for (int blk = 1; blk <= 8; blk++) {
                double[] r = score(mode, bio, blk, nNew, nOld);
                // wrong answers cost 1, unsure answers cost half, so the balanced score is 1 - mistakes
                double wrongNew = 100 - r[0] - r[2], wrongOld = 100 - r[1] - r[3];
                double value = 100 - (wrongNew + wrongOld) / 2.0 - (r[2] + r[3]) / 4.0;
                boolean better = value > best + 1e-9
                        || (Math.abs(value - best) < 1e-9 && bio == 1 && blk == 3);
                if (better) { best = value; bestBio = bio; bestBlk = blk; }
            }
        }
        HuntConfig.d.bioThreshold = bestBio;
        HuntConfig.d.blkThreshold = bestBlk;
        HuntConfig.save();
        double[] r = score(mode, bestBio, bestBlk, nNew, nOld);
        String msg = String.format("Rule %d with biome threshold %d and block threshold %d: new %.1f%%, old %.1f%%, unsure %.1f%% / %.1f%%",
                mode + 1, bestBio, bestBlk, r[0], r[1], r[2], r[3]);
        HuntLog.line("CALIB", "tuned: " + msg);
        out.add("Saved the best thresholds for the rule you use.");
        out.add(msg);
        return out;
    }
}
