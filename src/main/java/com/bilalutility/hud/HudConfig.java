package com.bilalutility.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** HUD settings, saved in config/bilalhud.json */
public final class HudConfig {
    public static final class Data {
        public boolean enabled = true;
        public boolean armor = true;          // armor durability next to the hotbar
        public int armorText = 0;             // 0 = percent, 1 = durability left as a number
        public boolean swapSides = false;     // false: armor left of the hotbar, counters right
        public boolean hideZero = true;       // hide counters (except totems) when you have none
        // item counters
        public boolean totems = true;
        public boolean crystals = true;
        public boolean pearls = true;
        public boolean gapples = true;
        public boolean glowstone = true;
        public boolean anchors = true;
        public boolean obsidian = true;
        public boolean xp = true;
        // info lines
        public boolean combat = true;         // "Combat Xs" while the server shows combat on the hotbar
        public boolean ping = true;
        public boolean fps = false;
        // enemy gear panel (only what the server sends to every client: armor + both hands)
        public boolean targetGear = true;
        public boolean targetCrosshairOnly = false; // false: also the nearest player within targetRange
        public int targetRange = 12;                // blocks (edit in config/bilalhud.json)
        public int scoreboardScale = 100;           // server sidebar size in percent; 0 = hidden
        public int targetPos = 0;                   // 0 = right side, 1 = left side, 2 = below the crosshair
        // extra info lines
        public boolean coords = false;              // XYZ + facing
        public boolean speed = false;               // horizontal speed in blocks per second
        public boolean armorWarn = true;            // "Armor low!" when a piece is under 15 %
        // layout (all in pixels / percent, set in the HUD layout screen)
        public int scale = 100;                     // size of armor + counters + info lines (50-200 %)
        public int armorX = 0, armorY = 0;
        public int counterX = 0, counterY = 0;
        public int infoX = 0, infoY = 0;
        public int targetScale = 100;               // size of the enemy gear panel (50-200 %)
        public int targetX = 0, targetY = 0;
        public int panelOpacity = 53;               // background of the panels, 0-100 %
        // server scoreboard (sidebar)
        public boolean sbHidden = false;            // hide the sidebar completely
        public int sbMode = 0;                      // 0 = the server's own sidebar (scaled), 1 = custom panel built from its data
        public int sbX = 0, sbY = 0;                // offset from its default spot
        public int sbOpacity = 40;                  // custom panel background, 0-100 %
        public boolean sbNumbers = false;           // custom panel: show the score numbers
        public boolean sbShadow = true;             // custom panel: text shadow
        public int sbMaxLines = 15;                 // custom panel: how many lines (1-15)
    }

    public static Data d = new Data();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private HudConfig() {}

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("bilalhud.json");
    }

    public static void load() {
        Path p = path();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p)) {
            Data loaded = GSON.fromJson(r, Data.class);
            if (loaded == null) return;
            loaded.armorText = Math.max(0, Math.min(1, loaded.armorText));
            loaded.targetPos = Math.max(0, Math.min(2, loaded.targetPos));
            if (loaded.scoreboardScale <= 0) loaded.sbHidden = true;   // old files used 0 for "hidden"
            loaded.scoreboardScale = loaded.scoreboardScale <= 0 ? 100 : Math.max(30, Math.min(150, loaded.scoreboardScale));
            loaded.scale = Math.max(50, Math.min(200, loaded.scale <= 0 ? 100 : loaded.scale));
            loaded.targetScale = Math.max(50, Math.min(200, loaded.targetScale <= 0 ? 100 : loaded.targetScale));
            loaded.panelOpacity = Math.max(0, Math.min(100, loaded.panelOpacity));
            loaded.sbMode = Math.max(0, Math.min(1, loaded.sbMode));
            loaded.sbOpacity = Math.max(0, Math.min(100, loaded.sbOpacity));
            loaded.sbMaxLines = Math.max(1, Math.min(15, loaded.sbMaxLines <= 0 ? 15 : loaded.sbMaxLines));
            loaded.targetRange = loaded.targetRange <= 0 ? 12 : Math.min(64, loaded.targetRange);
            d = loaded;
        } catch (Exception ignored) {
            // keep defaults if the file is broken
        }
    }

    public static void save() {
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
