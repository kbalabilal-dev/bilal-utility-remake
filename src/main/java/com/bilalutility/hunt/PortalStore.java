package com.bilalutility.hunt;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Nether portal blocks seen in chunks the server sent. Players build these, so each one is a lead. */
public final class PortalStore {
    private PortalStore() {}

    public static final class Portal {
        public final String dim;
        public final int x, y, z;
        Portal(String dim, int x, int y, int z) { this.dim = dim; this.x = x; this.y = y; this.z = z; }
    }

    private static final List<Portal> list = new ArrayList<>();
    private static boolean loaded;

    private static Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("bilal_hunt").resolve(ChunkStore.serverKey()).resolve("portals.txt");
    }

    public static List<Portal> all() {
        load();
        return list;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path p = file();
            if (!Files.exists(p)) return;
            for (String line : Files.readAllLines(p)) {
                String[] a = line.split(",");
                if (a.length != 4) continue;
                try {
                    list.add(new Portal(a[0], Integer.parseInt(a[1].trim()), Integer.parseInt(a[2].trim()), Integer.parseInt(a[3].trim())));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** Adds a portal unless one is already known within 8 blocks. Returns true when it is new. */
    public static boolean add(String dim, int x, int y, int z) {
        load();
        for (Portal p : list) {
            if (p.dim.equals(dim) && Math.abs(p.x - x) <= 8 && Math.abs(p.y - y) <= 8 && Math.abs(p.z - z) <= 8) return false;
        }
        list.add(new Portal(dim, x, y, z));
        try {
            Path p = file();
            Files.createDirectories(p.getParent());
            Files.writeString(p, dim + "," + x + "," + y + "," + z + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
        HuntLog.line("PORTAL", dim + " " + x + "," + y + "," + z + (dim.endsWith("the_nether")
                ? " -> overworld about " + (x * 8) + "," + (z * 8) : " -> nether about " + (x / 8) + "," + (z / 8)));
        return true;
    }

    public static List<Portal> near(String dim, double x, double z, double radius) {
        load();
        List<Portal> out = new ArrayList<>();
        for (Portal p : list) if (p.dim.equals(dim) && Math.hypot(p.x - x, p.z - z) <= radius) out.add(p);
        return out;
    }
}
