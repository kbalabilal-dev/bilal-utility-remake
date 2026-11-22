package com.bilalutility.regear;

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
import java.util.Locale;

/**
 * User-saved kits (config/regear_kits.json).
 * A kit is what you carry: how many of each item, which item goes in the offhand and in each hotbar slot,
 * and the "fill with totems" switch. Shop slots and menu layout are not part of a kit.
 */
public final class RegearKits {
    public static final int MAX_CUSTOM = 6;
    public static final int NAME_MAX = 16;

    public static final class Kit {
        public String name = "";
        public int icon = KitItem.CRYSTAL.ordinal();   // which kit item is drawn as the kit's icon
        public int[] amounts = new int[KitItem.values().length];
        public int[] hotbar = new int[9];
        public int offhand = KitItem.TOTEM.ordinal();
        public boolean fillTotems = false;

        Kit() {
            java.util.Arrays.fill(hotbar, -1);
        }
    }

    private static final List<Kit> CUSTOM = new ArrayList<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static final class Data {
        List<Kit> kits = new ArrayList<>();
    }


    private RegearKits() {}

    public static List<Kit> custom() {
        return CUSTOM;
    }

    public static List<Kit> all() {
        return new ArrayList<>(CUSTOM);
    }

    /** A short list of "Item xN" lines for tooltips. */
    public static String summary(Kit k) {
        StringBuilder sb = new StringBuilder();
        for (KitItem it : KitItem.values()) {
            int n = k.amounts[it.ordinal()];
            if (n > 0) sb.append(it.label).append(" x").append(n).append('\n');
        }
        if (k.fillTotems) sb.append("Fills free slots with totems\n");
        if (sb.length() == 0) sb.append("Nothing to buy\n");
        return sb.toString().trim();
    }

    // ---------- using a kit ----------

    /** Copies a kit into the regear settings and remembers it as the active kit. */
    public static void apply(Kit k) {
        for (KitItem it : KitItem.values()) RegearConfig.kitAmount[it.ordinal()] = k.amounts[it.ordinal()];
        for (int i = 0; i < 9; i++) RegearConfig.hotbarItem[i] = k.hotbar[i];
        RegearConfig.offhandItem = k.offhand;
        RegearConfig.fillTotems = k.fillTotems;
        RegearConfig.activeKit = k.name;
        RegearConfig.save();
    }

    /** What the regear is set to right now, as a kit with the given name. */
    public static Kit fromCurrent(String name) {
        Kit k = new Kit();
        k.name = name;
        int best = -1, bestIcon = KitItem.CRYSTAL.ordinal();
        for (KitItem it : KitItem.values()) {
            int n = RegearConfig.kitAmount[it.ordinal()];
            k.amounts[it.ordinal()] = n;
            if (n > best) {
                best = n;
                bestIcon = it.ordinal();
            }
        }
        k.icon = bestIcon;
        for (int i = 0; i < 9; i++) k.hotbar[i] = RegearConfig.hotbarItem[i];
        k.offhand = RegearConfig.offhandItem;
        k.fillTotems = RegearConfig.fillTotems;
        return k;
    }

    /** Writes the current settings back into the loaded kit, so editing a kit saves it. */
    public static void syncActive() {
        int i = activeIndex();
        if (i < 0) return;
        Kit old = CUSTOM.get(i);
        Kit fresh = fromCurrent(old.name);
        fresh.icon = old.icon == fresh.icon ? old.icon : fresh.icon;
        CUSTOM.set(i, fresh);
        save();
    }

    /** Index of the active kit in all(), or -1. */
    public static int activeIndex() {
        List<Kit> all = all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equalsIgnoreCase(RegearConfig.activeKit)) return i;
        }
        return -1;
    }

    /** Loads the next (dir = 1) or previous (dir = -1) kit and returns it. */
    public static Kit cycle(int dir) {
        List<Kit> all = all();
        if (all.isEmpty()) return null;
        int i = activeIndex();
        int next = i < 0 ? (dir > 0 ? 0 : all.size() - 1) : Math.floorMod(i + dir, all.size());
        Kit k = all.get(next);
        apply(k);
        return k;
    }

    // ---------- your own kits ----------

    /** Letters, numbers, space, - and _ ; 1-16 characters. */
    public static String cleanName(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("\\s+", " ");
        if (s.isEmpty() || s.length() > NAME_MAX) return null;
        if (!s.matches("[A-Za-z0-9 _\\-]+")) return null;
        return s;
    }

    public static boolean canSave(String name) {
        String n = cleanName(name);
        if (n == null) return false;
        for (Kit k : CUSTOM) if (k.name.equalsIgnoreCase(n)) return true;   // overwrite
        return CUSTOM.size() < MAX_CUSTOM;
    }

    /** Saves the current settings as a kit (replaces a kit with the same name). */
    public static boolean saveCurrent(String name) {
        if (!canSave(name)) return false;
        Kit k = fromCurrent(cleanName(name));
        CUSTOM.removeIf(c -> c.name.equalsIgnoreCase(k.name));
        CUSTOM.add(k);
        RegearConfig.activeKit = k.name;
        RegearConfig.save();
        save();
        return true;
    }

    public static void delete(String name) {
        CUSTOM.removeIf(c -> c.name.equalsIgnoreCase(name));
        if (RegearConfig.activeKit.equalsIgnoreCase(name)) {
            RegearConfig.activeKit = "";
            RegearConfig.save();
        }
        save();
    }

    // ---------- file ----------

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("regear_kits.json");
    }

    public static void load() {
        CUSTOM.clear();
        Path p = path();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p)) {
            Data d = GSON.fromJson(r, Data.class);
            if (d == null || d.kits == null) return;
            int kits = KitItem.values().length;
            for (Kit k : d.kits) {
                if (k == null || k.name == null || CUSTOM.size() >= MAX_CUSTOM) continue;
                String n = cleanName(k.name);
                if (n == null) continue;
                Kit c = new Kit();
                c.name = n;
                c.icon = RegearConfig.clamp(k.icon, 0, kits - 1);
                for (int i = 0; i < kits; i++) {
                    c.amounts[i] = k.amounts != null && i < k.amounts.length ? RegearConfig.clamp(k.amounts[i], 0, 9999) : 0;
                }
                for (int i = 0; i < 9; i++) {
                    c.hotbar[i] = k.hotbar != null && i < k.hotbar.length ? RegearConfig.clamp(k.hotbar[i], -1, kits - 1) : -1;
                }
                c.offhand = RegearConfig.clamp(k.offhand, -1, kits - 1);
                c.fillTotems = k.fillTotems;
                CUSTOM.add(c);
            }
        } catch (Exception ignored) {
            // keep what loaded
        }
    }

    public static void save() {
        Data d = new Data();
        d.kits = new ArrayList<>(CUSTOM);
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

    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
