package com.bilalutility.hunt;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Text log of the base hunting tools, same idea as the flight recorder: one file per session in
 * .minecraft/bilal_hunt_logs/. Game data only (chunk counts, coordinates, decisions, errors): no
 * chat, no player names, no server address. Flushed every 5 seconds.
 *
 * Tags: SESSION, DIM, POS, SUMMARY, PATCH, TRAIL, PORTAL, CALIB, DIAG, ERROR.
 */
public final class HuntLog {
    private HuntLog() {}

    private static BufferedWriter out;
    private static Path path;
    private static boolean failed;
    private static int ticks;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS");

    public static String path() {
        return path == null ? "(no log written yet)" : path.toString();
    }

    public static void line(String tag, String text) {
        if (!HuntConfig.d.logs || failed) return;
        try {
            if (out == null) {
                Path dir = FabricLoader.getInstance().getGameDir().resolve("bilal_hunt_logs");
                Files.createDirectories(dir);
                path = dir.resolve("hunt-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".log");
                out = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            }
            out.write(TIME.format(new Date()) + " " + tag + " " + text + "\n");
        } catch (IOException e) {
            failed = true;
            out = null;
        }
    }

    public static void tick() {
        if (++ticks % 100 == 0) flush();
    }

    public static void flush() {
        try {
            if (out != null) out.flush();
        } catch (IOException ignored) {
        }
    }

    public static void close() {
        try {
            if (out != null) {
                out.flush();
                out.close();
            }
        } catch (IOException ignored) {
        }
        out = null;
        failed = false;
    }
}
