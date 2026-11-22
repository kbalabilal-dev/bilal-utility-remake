package com.bilalutility.flight;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Settings and the waypoint queue of the Auto Elytra module (config/bilal_flight.json). */
public final class FlightConfig {
    public static class Waypoint {
        public String name = "";
        public int x, z;

        public Waypoint() {}

        public Waypoint(String name, int x, int z) {
            this.name = name;
            this.x = x;
            this.z = z;
        }
    }

    public static List<Waypoint> waypoints = new ArrayList<>();
    public static int cruiseY = 180;          // flight altitude
    public static int pauseSeconds = 10;      // stop time at every point
    public static boolean landWithChestplate = false; // wear a chestplate at every stop, elytra again before take off
    public static int reserveRockets = 3;     // rockets that are never counted as usable
    public static boolean pearlRescue = true; // throw an ender pearl at the nearest island when falling low
    public static int pearlBelowY = 20;
    public static boolean teamhome = true;    // send the command below when the player is under teamhomeY (End only)
    public static int teamhomeY = -30;
    public static String teamhomeCommand = "teamhome";
    public static int hudPos = 1;             // 0 off, 1 top centre, 2 top left, 3 top right
    public static int hudScale = 100;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static class Data {
        List<Waypoint> waypoints = new ArrayList<>();
        int cruiseY = 180, pauseSeconds = 10, reserveRockets = 3, pearlBelowY = 20, teamhomeY = -30;
        int hudPos = 1, hudScale = 100;
        boolean pearlRescue = true, teamhome = true, landWithChestplate = false;
        String teamhomeCommand = "teamhome";
    }

    private FlightConfig() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("bilal_flight.json");
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static String cleanCommand(String s) {
        String t = s == null ? "" : s.trim();
        while (t.startsWith("/")) t = t.substring(1).trim();
        return t;
    }

    public static void load() {
        try {
            if (!Files.exists(file())) return;
            Data d = GSON.fromJson(Files.readString(file()), Data.class);
            if (d == null) return;
            waypoints = d.waypoints == null ? new ArrayList<>() : d.waypoints;
            cruiseY = clamp(d.cruiseY, 60, 300);
            pauseSeconds = clamp(d.pauseSeconds, 0, 120);
            reserveRockets = clamp(d.reserveRockets, 0, 40);
            pearlRescue = d.pearlRescue;
            landWithChestplate = d.landWithChestplate;
            pearlBelowY = clamp(d.pearlBelowY, -60, 120);
            teamhome = d.teamhome;
            teamhomeY = clamp(d.teamhomeY, -64, 40);
            String c = cleanCommand(d.teamhomeCommand);
            teamhomeCommand = c.isEmpty() ? "teamhome" : c;
            hudPos = clamp(d.hudPos, 0, 3);
            hudScale = d.hudScale <= 0 ? 100 : clamp(d.hudScale, 50, 200);
        } catch (Exception ignored) {
            // keep defaults when the file is broken
        }
    }

    public static void save() {
        Data d = new Data();
        d.waypoints = waypoints;
        d.cruiseY = cruiseY;
        d.pauseSeconds = pauseSeconds;
        d.reserveRockets = reserveRockets;
        d.pearlRescue = pearlRescue;
        d.landWithChestplate = landWithChestplate;
        d.pearlBelowY = pearlBelowY;
        d.teamhome = teamhome;
        d.teamhomeY = teamhomeY;
        d.teamhomeCommand = teamhomeCommand;
        d.hudPos = hudPos;
        d.hudScale = hudScale;
        try {
            Files.writeString(file(), GSON.toJson(d));
        } catch (IOException ignored) {
            // not fatal
        }
    }
}
