package com.bilalutility.hunt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Settings of the base hunting tools (config/bilal_hunt.json). */
public final class HuntConfig {
    private HuntConfig() {}

    public static class Data {
        public boolean detect = true;     // classify chunks as new or old while you fly
        public int rule = 1;              // index of the rule picked from /bhunt report
        public boolean radar = true;      // small chunk map on screen
        public int radarRadius = 32;      // chunks around you shown on the radar
        public int minCluster = 3;        // smallest compact patch of old chunks that counts as a hot spot
        public int minTrail = 10;         // shortest row of old chunks (in chunks) that counts as a trail
        public int trailShape = 25;       // how stretched a patch must be to be a row, in tenths (25 = 2.5x longer than wide)
        public int ignoreSpawn = 2000;    // old chunks closer than this to 0,0 are ignored (spawn traffic)
        public boolean logs = true;       // write bilal_hunt_logs/hunt-*.log
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static Data d = new Data();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("bilal_hunt.json");
    }

    public static void load() {
        try {
            Path p = file();
            if (Files.exists(p)) {
                Data x = GSON.fromJson(Files.readString(p), Data.class);
                if (x != null) d = x;
            }
        } catch (Exception ignored) {
        }
        d.rule = Math.max(0, Math.min(HuntCalibration.ruleCount() - 1, d.rule));
        d.radarRadius = Math.max(8, Math.min(64, d.radarRadius));
        d.minCluster = Math.max(1, d.minCluster);
        d.minTrail = Math.max(3, d.minTrail);
        d.trailShape = Math.max(12, Math.min(80, d.trailShape));
        d.ignoreSpawn = Math.max(0, d.ignoreSpawn);
    }

    public static void save() {
        try {
            Files.writeString(file(), GSON.toJson(d));
        } catch (Exception ignored) {
        }
    }
}
