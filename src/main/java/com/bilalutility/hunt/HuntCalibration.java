package com.bilalutility.hunt;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Learn mode. You tell it "the chunks that load now are NEW" (flying into ground nobody has touched)
 * or "OLD" (your own base, places you have been), and it records the palette numbers for each chunk.
 * /bhunt report then shows how well each candidate rule separates the two on THIS server.
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
        ChunkProbe.Features f = ChunkProbe.probe(chunk);
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
            if (fresh) sb.append("label,dimension,cx,cz,sections,informative,nonCanonical,unusedSections,airFirst,skipped,firstPalette\n");
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

    private static final class Rule {
        final String name;
        final Predicate<ChunkProbe.Features> saysNew;
        Rule(String name, Predicate<ChunkProbe.Features> saysNew) { this.name = name; this.saysNew = saysNew; }
    }

    private static final List<Rule> RULES = List.of(
            new Rule("any section out of order", f -> f.nonCanonical > 0),
            new Rule("half the sections out of order", f -> f.informative > 0 && f.nonCanonical * 2 >= f.informative),
            new Rule("any unused palette entries", f -> f.unusedSections > 0),
            new Rule("air first in most sections", f -> f.sections > 0 && f.airFirst * 2 > f.sections)
    );

    public static boolean ruleSaysNew(int index, ChunkProbe.Features f) {
        return RULES.get(Math.max(0, Math.min(RULES.size() - 1, index))).saysNew.test(f);
    }

    public static String ruleName(int index) {
        return RULES.get(Math.max(0, Math.min(RULES.size() - 1, index))).name;
    }

    public static int ruleCount() { return RULES.size(); }

    public static List<String> report() {
        List<String> out = new ArrayList<>();
        int nNew = 0, nOld = 0, blindNew = 0, blindOld = 0;
        for (Sample s : samples) {
            boolean blind = s.f.informative == 0;
            if (s.isNew) { nNew++; if (blind) blindNew++; } else { nOld++; if (blind) blindOld++; }
        }
        HuntLog.line("CALIB", "report requested, new=" + nNew + " old=" + nOld);
        out.add("Samples: " + nNew + " NEW, " + nOld + " OLD (no readable sections: " + blindNew + " new, " + blindOld + " old)");
        if (nNew == 0 || nOld == 0) {
            out.add("Collect both kinds first: /bhunt learn new  (fresh ground) and /bhunt learn old  (your base area).");
            return out;
        }
        for (Rule r : RULES) {
            int newHit = 0, oldWrong = 0;
            for (Sample s : samples) {
                boolean says = r.saysNew.test(s.f);
                if (s.isNew && says) newHit++;
                if (!s.isNew && says) oldWrong++;
            }
            String res = String.format("%s: finds %.1f%% of new, calls %.1f%% of old chunks new by mistake",
                    r.name, 100.0 * newHit / nNew, 100.0 * oldWrong / nOld);
            HuntLog.line("CALIB", res);
            out.add(res);
        }
        out.add("A good rule finds almost all new chunks and calls almost no old ones new.");
        return out;
    }
}
