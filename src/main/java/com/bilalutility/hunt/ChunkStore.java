package com.bilalutility.hunt;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Remembers what every chunk looked like the FIRST time the server sent it to you, per server and
 * per dimension, and keeps it on disk (bilal_hunt/server/dimension.txt, one "x,z,N" or "x,z,O" line each).
 * The first answer is never changed: after you visit a new chunk it is old for everyone, so a later
 * look at it would tell you nothing.
 */
public final class ChunkStore {
    private ChunkStore() {}

    public static final byte NEW = 1, OLD = 2;

    private static final Map<String, Map<Long, Byte>> maps = new HashMap<>();
    private static final Set<String> loaded = new HashSet<>();
    private static final Map<String, StringBuilder> pending = new HashMap<>();
    private static int ticks;

    public static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xffffffffL); }
    public static int keyX(long k) { return (int) (k >> 32); }
    public static int keyZ(long k) { return (int) k; }

    public static String serverKey() {
        ServerInfo info = MinecraftClient.getInstance().getCurrentServerEntry();
        if (info != null && info.address != null) return info.address.replaceAll("[^a-zA-Z0-9._-]", "_");
        return "singleplayer";
    }

    private static Path file(String dim) {
        return FabricLoader.getInstance().getGameDir().resolve("bilal_hunt").resolve(serverKey())
                .resolve(dim.replaceAll("[^a-zA-Z0-9._-]", "_") + ".txt");
    }

    private static String id(String dim) { return serverKey() + "|" + dim; }

    public static Map<Long, Byte> map(String dim) {
        String id = id(dim);
        Map<Long, Byte> m = maps.computeIfAbsent(id, k -> new HashMap<>());
        if (loaded.add(id)) {
            try {
                Path p = file(dim);
                if (Files.exists(p)) {
                    for (String line : Files.readAllLines(p)) {
                        String[] a = line.split(",");
                        if (a.length != 3) continue;
                        try {
                            m.put(key(Integer.parseInt(a[0].trim()), Integer.parseInt(a[1].trim())),
                                    a[2].trim().equals("N") ? NEW : OLD);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return m;
    }

    /** Stores the first verdict for a chunk. Returns false when the chunk was already known. */
    public static boolean put(String dim, int cx, int cz, byte state) {
        Map<Long, Byte> m = map(dim);
        if (m.putIfAbsent(key(cx, cz), state) != null) return false;
        pending.computeIfAbsent(dim, k -> new StringBuilder())
                .append(cx).append(',').append(cz).append(',').append(state == NEW ? 'N' : 'O').append('\n');
        return true;
    }

    public static void tick() {
        if (++ticks % 100 == 0) flush();
    }

    public static void flush() {
        for (Map.Entry<String, StringBuilder> e : pending.entrySet()) {
            if (e.getValue().length() == 0) continue;
            try {
                Path p = file(e.getKey());
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue().toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                e.getValue().setLength(0);
            } catch (IOException ignored) {
            }
        }
    }

    public static void forget(String dim) {
        map(dim).clear();
        pending.remove(dim);
        try {
            Files.deleteIfExists(file(dim));
        } catch (IOException ignored) {
        }
    }

    public static int count(String dim, byte state) {
        int n = 0;
        for (byte b : map(dim).values()) if (b == state) n++;
        return n;
    }
}
