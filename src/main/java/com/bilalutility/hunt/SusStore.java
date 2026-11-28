package com.bilalutility.hunt;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remembers which chunks looked like a player had been there, with a score and the reasons, per server
 * and dimension (bilal_hunt/server/dimension_sus.txt, lines "x,z,blockScore,entityScore,reasons").
 */
public final class SusStore {
    private SusStore() {}

    public static final class Entry {
        public int block, entity;
        public String reasons = "";
        public int total() { return block + entity; }
    }

    private static final Map<String, Map<Long, Entry>> maps = new HashMap<>();
    private static final Set<String> loaded = new HashSet<>();
    private static final Map<String, StringBuilder> pending = new HashMap<>();
    private static int ticks;

    private static String id(String dim) { return ChunkStore.serverKey() + "|" + dim; }

    private static Path file(String dim) {
        return FabricLoader.getInstance().getGameDir().resolve("bilal_hunt").resolve(ChunkStore.serverKey())
                .resolve(dim.replaceAll("[^a-zA-Z0-9._-]", "_") + "_sus.txt");
    }

    public static Map<Long, Entry> map(String dim) {
        String id = id(dim);
        Map<Long, Entry> m = maps.computeIfAbsent(id, k -> new HashMap<>());
        if (loaded.add(id)) {
            try {
                Path p = file(dim);
                if (Files.exists(p)) {
                    for (String line : Files.readAllLines(p)) {
                        String[] a = line.split(",", 5);
                        if (a.length < 4) continue;
                        try {
                            Entry e = new Entry();
                            e.block = Integer.parseInt(a[2].trim());
                            e.entity = Integer.parseInt(a[3].trim());
                            e.reasons = a.length > 4 ? a[4] : "";
                            m.put(ChunkStore.key(Integer.parseInt(a[0].trim()), Integer.parseInt(a[1].trim())), e);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return m;
    }

    public static boolean isSus(String dim, int cx, int cz) {
        Entry e = map(dim).get(ChunkStore.key(cx, cz));
        return e != null && e.total() >= HuntConfig.d.susThreshold;
    }

    /**
     * Records a score for a chunk. The block score is replaced by the newest one, the entity score keeps the
     * highest value seen (entities come and go, so adding them up would inflate it). Returns true when the
     * chunk has just become sus.
     */
    public static boolean update(String dim, int cx, int cz, int block, int entity, String reasons) {
        Map<Long, Entry> m = map(dim);
        long k = ChunkStore.key(cx, cz);
        Entry e = m.get(k);
        boolean was = e != null && e.total() >= HuntConfig.d.susThreshold;
        if (e == null) {
            if (block + entity <= 0) return false;
            e = new Entry();
            m.put(k, e);
        }
        int newBlock = block >= 0 ? block : e.block;
        int newEntity = Math.max(e.entity, entity);
        String newReasons = reasons == null || reasons.isEmpty() ? e.reasons : reasons;
        if (newBlock == e.block && newEntity == e.entity && newReasons.equals(e.reasons)) return false;
        e.block = newBlock;
        e.entity = newEntity;
        e.reasons = newReasons;
        pending.computeIfAbsent(dim, d -> new StringBuilder()).append(cx).append(',').append(cz).append(',')
                .append(e.block).append(',').append(e.entity).append(',').append(e.reasons.replace(',', ';')).append('\n');
        return !was && e.total() >= HuntConfig.d.susThreshold;
    }

    public static List<Map.Entry<Long, Entry>> top(String dim, int n) {
        List<Map.Entry<Long, Entry>> all = new ArrayList<>();
        for (Map.Entry<Long, Entry> e : map(dim).entrySet()) {
            if (e.getValue().total() >= HuntConfig.d.susThreshold) all.add(e);
        }
        all.sort((a, b) -> Integer.compare(b.getValue().total(), a.getValue().total()));
        return all.subList(0, Math.min(n, all.size()));
    }

    public static int count(String dim) {
        int n = 0;
        for (Entry e : map(dim).values()) if (e.total() >= HuntConfig.d.susThreshold) n++;
        return n;
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
}
