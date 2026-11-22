package com.bilalutility.tpa;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Settings + skip list, saved in config/tpahere.json */
public final class TpaConfig {
    // All times are in ticks (20 ticks = 1 second).
    public static int delayTicks = 10;          // pause between players
    public static int windowTimeoutTicks = 25;  // wait for the confirm window before skipping a player
    public static int roundPauseTicks = 40;     // pause before a new round (auto mode)
    public static int hudPos = 1;               // 0 = off, 1 = top center, 2 = top left, 3 = top right
    public static int autoHitDelayMs = 300;     // auto hit: right click once every X ms
    public static boolean combatPause = true;   // pause the queue while "Combat: Xs" is on the hotbar
    public static int blockedHoldTicks = 60;    // hold the queue this long after "You can't use this command..." (edit in config/tpahere.json)

    public static final String DEFAULT_COMMAND = "tpahere";
    public static String command = DEFAULT_COMMAND; // command sent for every player (no leading slash)
    public static boolean hideWindow = true;        // true = confirm window is clicked without being shown
    public static int maxRounds = 0;                // auto repeat stops after this many rounds (0 = never)
    public static int totalSent = 0;                // teleport requests confirmed, all time
    public static int hudScale = 100;               // progress box size in percent
    public static int hudX = 0, hudY = 0;           // progress box offset in pixels

    private static final Set<String> SKIP = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static final class Data {
        int delayTicks = 10;
        int windowTimeoutTicks = 25;
        int roundPauseTicks = 40;
        int hudPos = 1;
        int autoHitDelayMs = 300;
        boolean combatPause = true;
        int blockedHoldTicks = 60;
        String command = DEFAULT_COMMAND;
        boolean hideWindow = true;
        List<String> skipList = new ArrayList<>();
        int maxRounds = 0;
        int totalSent = 0;
        int hudScale = 100;
        int hudX = 0, hudY = 0;
    }

    private TpaConfig() {}

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("tpahere.json");
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static void load() {
        Path p = path();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p)) {
            Data d = GSON.fromJson(r, Data.class);
            if (d == null) return;
            delayTicks = clamp(d.delayTicks, 2, 100);
            windowTimeoutTicks = clamp(d.windowTimeoutTicks, 10, 100);
            roundPauseTicks = clamp(d.roundPauseTicks, 10, 600);
            hudPos = clamp(d.hudPos, 0, 3);
            autoHitDelayMs = d.autoHitDelayMs <= 0 ? 300 : clamp(d.autoHitDelayMs, 50, 5000);
            combatPause = d.combatPause;
            blockedHoldTicks = d.blockedHoldTicks <= 0 ? 60 : clamp(d.blockedHoldTicks, 10, 600);
            command = cleanCommand(d.command);
            hideWindow = d.hideWindow;
            SKIP.clear();
            if (d.skipList != null) SKIP.addAll(d.skipList);
            maxRounds = clamp(d.maxRounds, 0, 999);
            totalSent = Math.max(0, d.totalSent);
            hudScale = d.hudScale <= 0 ? 100 : clamp(d.hudScale, 50, 200);
            hudX = clamp(d.hudX, -600, 600);
            hudY = clamp(d.hudY, -600, 600);
        } catch (Exception ignored) {
            // keep defaults if the file is broken
        }
    }

    public static void save() {
        Data d = new Data();
        d.delayTicks = delayTicks;
        d.windowTimeoutTicks = windowTimeoutTicks;
        d.roundPauseTicks = roundPauseTicks;
        d.hudPos = hudPos;
        d.autoHitDelayMs = autoHitDelayMs;
        d.combatPause = combatPause;
        d.blockedHoldTicks = blockedHoldTicks;
        d.command = command;
        d.hideWindow = hideWindow;
        d.skipList = new ArrayList<>(SKIP);
        d.maxRounds = maxRounds;
        d.totalSent = totalSent;
        d.hudScale = hudScale;
        d.hudX = hudX;
        d.hudY = hudY;
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

    /**
     * Makes a typed command safe: removes leading slashes and extra spaces, and only allows
     * letters, numbers and _ : . - (plus single spaces). Empty or invalid text gives the default.
     */
    public static String cleanCommand(String raw) {
        if (raw == null) return DEFAULT_COMMAND;
        String s = raw.trim();
        while (s.startsWith("/")) s = s.substring(1).trim();
        s = s.replaceAll("\\s+", " ");
        if (s.length() > 40) s = s.substring(0, 40).trim();
        if (!s.matches("[A-Za-z0-9_:.\\- ]+")) return DEFAULT_COMMAND;
        return s;
    }

    public static boolean isSkipped(String name) {
        return SKIP.contains(name);
    }

    public static void setSkipped(String name, boolean skipped) {
        if (skipped) SKIP.add(name);
        else SKIP.remove(name);
    }

    public static List<String> skipList() {
        return new ArrayList<>(SKIP);
    }

    public static int skipCount() {
        return SKIP.size();
    }
}
