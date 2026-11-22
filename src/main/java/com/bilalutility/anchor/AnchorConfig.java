package com.bilalutility.anchor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Anchor macro settings, saved in config/anchormacro.json. All times are in ticks (20 = 1 second). */
public final class AnchorConfig {
    public static int stepDelayTicks = 1;   // pause between "charge" and "detonate"
    public static int placeWaitTicks = 2;   // pause after placing the anchor, before charging it
    public static int chainGapTicks = 4;    // pause between two anchors while the key is held
    public static int maxWaitTicks = 10;    // how long to wait for the server before giving up
    public static boolean requireOffhandTotem = true; // never detonate without a totem in the offhand
    public static int minHealth = 0;        // refuse to blow an anchor below this many hearts (0 = off)
    public static boolean chainEnabled = true; // holding the key keeps going with the next anchor
    public static int maxChain = 0;         // stop the chain after this many anchors (0 = unlimited)
    public static boolean messages = true;  // action-bar messages

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static final class Data {
        int stepDelayTicks = 1;
        int placeWaitTicks = 2;
        int chainGapTicks = 4;
        int maxWaitTicks = 10;
        boolean requireOffhandTotem = true;
        int minHealth = 0;
        boolean chainEnabled = true;
        int maxChain = 0;
        boolean messages = true;
    }

    private AnchorConfig() {}

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("anchormacro.json");
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Sets the four speed values at once (used by the Safe / Normal / Fast buttons). */
    public static void applyPreset(int step, int place, int chain, int maxWait) {
        stepDelayTicks = step;
        placeWaitTicks = place;
        chainGapTicks = chain;
        maxWaitTicks = maxWait;
        save();
    }

    public static void load() {
        Path p = path();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p)) {
            Data d = GSON.fromJson(r, Data.class);
            if (d == null) return;
            stepDelayTicks = clamp(d.stepDelayTicks, 0, 20);
            placeWaitTicks = clamp(d.placeWaitTicks, 0, 20);
            chainGapTicks = clamp(d.chainGapTicks, 0, 40);
            maxWaitTicks = clamp(d.maxWaitTicks, 3, 60);
            requireOffhandTotem = d.requireOffhandTotem;
            minHealth = clamp(d.minHealth, 0, 20);
            chainEnabled = d.chainEnabled;
            maxChain = clamp(d.maxChain, 0, 50);
            messages = d.messages;
        } catch (Exception ignored) {
            // keep defaults if the file is broken
        }
    }

    public static void save() {
        Data d = new Data();
        d.stepDelayTicks = stepDelayTicks;
        d.placeWaitTicks = placeWaitTicks;
        d.chainGapTicks = chainGapTicks;
        d.maxWaitTicks = maxWaitTicks;
        d.requireOffhandTotem = requireOffhandTotem;
        d.minHealth = minHealth;
        d.chainEnabled = chainEnabled;
        d.maxChain = maxChain;
        d.messages = messages;
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p)) {
                GSON.toJson(d, w);
            }
        } catch (IOException ignored) {
            // not fatal
        }
    }
}
