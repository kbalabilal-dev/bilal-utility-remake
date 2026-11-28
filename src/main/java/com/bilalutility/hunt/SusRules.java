package com.bilalutility.hunt;

import java.util.Set;

/**
 * Which blocks, block entities and entities a player has to have put somewhere. Learned from how the
 * public base finders work: lists of blocks that never generate naturally (strong), blocks that can
 * generate in structures (only counted in quantity), blocks that are out of place for their height,
 * and objects like item frames and shulker boxes.
 */
public final class SusRules {
    private SusRules() {}

    /** Never generate naturally in either dimension. One of these is already a strong sign. */
    private static final Set<String> TIER1 = Set.of(
            "beacon", "command_block", "chain_command_block", "repeating_command_block", "dropper", "observer",
            "piston", "sticky_piston", "hopper", "comparator", "repeater", "daylight_detector", "ender_chest",
            "enchanting_table", "netherite_block", "diamond_block", "emerald_block", "iron_block", "lapis_block",
            "redstone_block", "redstone_lamp", "respawn_anchor", "lodestone", "conduit", "beehive", "jukebox",
            "note_block", "slime_block", "honey_block", "glass", "tinted_glass", "target", "shulker_box");

    private static final Set<String> NETHER_TIER1 = Set.of(
            "cobblestone", "mossy_cobblestone", "stone", "stone_bricks", "bricks", "smooth_stone", "dirt",
            "grass_block", "sand", "torch", "wall_torch", "ladder", "rail", "furnace", "crafting_table", "water",
            "quartz_block", "quartz_bricks", "quartz_pillar", "chiseled_quartz_block", "smooth_quartz");

    /** Can appear in villages, mineshafts and other structures, so only a large amount counts. */
    private static final Set<String> TIER2 = Set.of(
            "bricks", "polished_andesite", "polished_diorite", "polished_granite", "smooth_stone",
            "glowstone", "jack_o_lantern", "soul_torch");

    private static final String[] WOOD = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
            "cherry", "bamboo", "pale_oak"};
    private static final String[] WOOD_PARTS = {"_planks", "_log", "_wood", "_stairs", "_slab", "_fence", "_door", "_trapdoor"};

    public static boolean isAir(String p) {
        return p.equals("air") || p.equals("cave_air") || p.equals("void_air");
    }

    private static boolean wood(String p) {
        for (String w : WOOD) {
            if (!p.startsWith(w)) continue;
            for (String part : WOOD_PARTS) if (p.equals(w + part)) return true;
        }
        return false;
    }

    /** 1 = strong sign, 2 = counts in quantity, 0 = nothing special. */
    public static int kind(String p, boolean nether) {
        if (TIER1.contains(p) || p.endsWith("_shulker_box") || p.endsWith("_stained_glass")
                || p.endsWith("_concrete") || p.endsWith("_concrete_powder") || p.endsWith("_glazed_terracotta")) return 1;
        if (nether && (NETHER_TIER1.contains(p) || p.endsWith("_wool") || wood(p))) return 1;
        if (p.endsWith("_planks") || TIER2.contains(p)) return 2;
        return 0;
    }

    /** Points for a block entity type (chest, hopper, sign ...). Natural structures have some chests, so those are weak. */
    public static int blockEntityPoints(String type) {
        return switch (type) {
            case "beacon" -> 20;
            case "shulker_box" -> 10;
            case "ender_chest" -> 10;
            case "crafter" -> 6;
            case "hopper", "dropper", "trapped_chest", "enchanting_table", "brewing_stand", "jukebox", "lectern" -> 4;
            case "barrel", "hanging_sign", "banner" -> 3;
            case "sign", "furnace", "smoker", "blast_furnace", "bed", "decorated_pot", "skull", "dispenser" -> 2;
            case "chest" -> 1;
            default -> 0;
        };
    }
}
