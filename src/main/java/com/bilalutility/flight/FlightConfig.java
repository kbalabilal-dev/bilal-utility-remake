package com.bilalutility.flight;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Settings and the waypoint queue of the Auto Elytra module (config/bilal_flight.json). */
public final class FlightConfig {
    public static class Waypoint {
        public String name = "";
        public int x, z;
        public String result = "";   // elytra hunting: "", "elytra", "empty", "unreachable" ...

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
    public static boolean landWithChestplate = false; // normal waypoint stops
    public static boolean shipLandingChestplateDrop = true; // safe mid-air chestplate drop on confirmed ship roofs // wear a chestplate at every stop, elytra again before take off
    public static int reserveRockets = 3;     // rockets that are never counted as usable
    public static boolean pearlRescue = true; // throw an ender pearl at the nearest island when falling low
    public static int pearlBelowY = 20;
    public static boolean teamhome = true;    // send the command below when the player is under teamhomeY (End only)
    public static int teamhomeY = -30;
    public static String teamhomeCommand = "teamhome";
    public static boolean hunt = true;        // look for the elytra item frame; when found: land on the ship, alert, stop
    public static boolean scout = true;       // search for frames while flying
    public static boolean useShulkers = true; // use the three shulkers to recognize untouched ships
    public static boolean recordFlights = false; // write a flight log + teach mode to .minecraft/bilal_flight_logs
    public static boolean debugMode = false; // detailed Auto Elytra diagnostics in chat
    public static int searchRadius = 100;     // how far around a point it looks for the elytra item frame
    public static int landRadius = 40;        // landing this close to the point counts as "arrived"
    public static int routineMode = 1;        // ship routine: 0 off, 1 assisted (one key press per step), 2 full auto
    public static boolean routineGlideExit = true;   // leave the ship by gliding out of the tunnel; the pearl is the backup (off = pearl only)
    public static boolean routineDryRun = true; // only check and print what it would do
    public static int hudPos = 1;             // 0 off, 1 top centre, 2 top left, 3 top right
    public static int hudScale = 100;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static class Data {
        List<Waypoint> waypoints = new ArrayList<>();
        int cruiseY = 180, pauseSeconds = 10, reserveRockets = 3, pearlBelowY = 20, teamhomeY = -30;
        int routineMode = 1;
        boolean routineGlideExit = true;
        boolean routineDryRun = true;
        int hudPos = 1, hudScale = 100, searchRadius = 100, landRadius = 40;
        boolean pearlRescue = true, teamhome = true, landWithChestplate = false, shipLandingChestplateDrop = true, hunt = true, scout = true, useShulkers = true, debugMode = false, recordFlights = false;
        String teamhomeCommand = "teamhome";
    }

    private FlightConfig() {}

    // ---------- paste helpers (v2.0.15) ----------
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    public static final int MAX_POINTS = 300;

    private static final String N = "-?\\d+(?:\\.\\d+)?";
    private static final Pattern LABELED = Pattern.compile(
            "\\bx\\s*[:=]\\s*(" + N + ")[^zZ]*?\\bz\\s*[:=]\\s*(" + N + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMA_GROUP = Pattern.compile(
            "(" + N + ")\\s*,\\s*(" + N + ")(?:\\s*,\\s*(" + N + "))?");

    /**
     * Reads X/Z points from any text. Accepts, in any mix:
     *   one point per line:        25773, -72911   |   25773 -72911   |   X: 25773 Z: -72911   |   x, y, z
     *   many points on ONE line:   31752, -97560 32392, -98168 31048, -97832 ...
     * Decimals are floored. Lines that do not hold a usable point are skipped.
     */
    public static List<int[]> parsePoints(String text) {
        List<int[]> out = new ArrayList<>();
        if (text == null) return out;
        text = text.replace('\u2212', '-').replace('\u2013', '-');
        for (String line : text.split("\\R")) {
            // 1) labeled: "X: 1 Z: 2" (any number per line, y label is skipped)
            Matcher lab = LABELED.matcher(line);
            boolean labeled = false;
            while (lab.find()) {
                labeled = true;
                addParsed(out, lab.group(1), lab.group(2));
            }
            if (labeled) continue;

            Matcher m = NUMBER.matcher(line);
            List<String> nums = new ArrayList<>();
            while (m.find()) nums.add(m.group());
            if (nums.size() == 2) {
                addParsed(out, nums.get(0), nums.get(1));
            } else if (nums.size() == 3) {
                addParsed(out, nums.get(0), nums.get(2));      // x y z -> y ignored
            } else if (nums.size() >= 4) {
                // 2) several points on one line: "x, z x, z x, z" (or "x, y, z x, y, z")
                Matcher g = COMMA_GROUP.matcher(line);
                boolean any = false;
                while (g.find()) {
                    any = true;
                    addParsed(out, g.group(1), g.group(3) != null ? g.group(3) : g.group(2));
                }
                // 3) plain numbers "x z x z x z"
                if (!any && nums.size() % 2 == 0) {
                    for (int i = 0; i < nums.size(); i += 2) addParsed(out, nums.get(i), nums.get(i + 1));
                }
            }
        }
        return out;
    }

    private static void addParsed(List<int[]> out, String xs, String zs) {
        double x = Double.parseDouble(xs), z = Double.parseDouble(zs);
        if (Math.abs(x) > 30000000 || Math.abs(z) > 30000000) return;
        out.add(new int[]{(int) Math.floor(x), (int) Math.floor(z)});
    }

    /** Adds the points to the queue. Returns {added, alreadyInQueue, queueFull}. */
    public static int[] addPoints(List<int[]> points) {
        int added = 0, duplicates = 0, full = 0;
        for (int[] p : points) {
            boolean dup = false;
            for (Waypoint w : waypoints) {
                if (Math.abs(w.x - p[0]) <= 2 && Math.abs(w.z - p[1]) <= 2) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                duplicates++;
            } else if (waypoints.size() >= MAX_POINTS) {
                full++;
            } else {
                waypoints.add(new Waypoint("P" + (waypoints.size() + 1), p[0], p[1]));
                added++;
            }
        }
        return new int[]{added, duplicates, full};
    }

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
            shipLandingChestplateDrop = d.shipLandingChestplateDrop;
            pearlBelowY = clamp(d.pearlBelowY, -60, 120);
            teamhome = d.teamhome;
            teamhomeY = clamp(d.teamhomeY, -64, 40);
            String c = cleanCommand(d.teamhomeCommand);
            teamhomeCommand = c.isEmpty() ? "teamhome" : c;
            for (Waypoint w : waypoints) {
                if (w.result == null) w.result = "";
                if (w.name == null) w.name = "";
            }
            hunt = d.hunt;
            scout = d.scout;
            useShulkers = d.useShulkers;
            recordFlights = d.recordFlights;
            debugMode = d.debugMode;
            searchRadius = d.searchRadius <= 0 ? 100 : clamp(d.searchRadius, 30, 200);
            landRadius = d.landRadius <= 0 ? 40 : clamp(d.landRadius, 10, 100);
            routineMode = clamp(d.routineMode, 0, 2);
            routineDryRun = d.routineDryRun;
            routineGlideExit = d.routineGlideExit;
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
        d.shipLandingChestplateDrop = shipLandingChestplateDrop;
        d.pearlBelowY = pearlBelowY;
        d.teamhome = teamhome;
        d.teamhomeY = teamhomeY;
        d.teamhomeCommand = teamhomeCommand;
        d.hunt = hunt;
        d.scout = scout;
        d.useShulkers = useShulkers;
        d.recordFlights = recordFlights;
        d.debugMode = debugMode;
        d.searchRadius = searchRadius;
        d.landRadius = landRadius;
        d.routineMode = routineMode;
        d.routineDryRun = routineDryRun;
        d.routineGlideExit = routineGlideExit;
        d.hudPos = hudPos;
        d.hudScale = hudScale;
        try {
            Files.writeString(file(), GSON.toJson(d));
        } catch (IOException ignored) {
            // not fatal
        }
    }
}
