package com.bilalutility.hunt;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.registry.Registries;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Scores a chunk by how much of it a player must have made: blocks that never generate, blocks out of
 * place for their height (anything above the Nether roof, bedrock outside the bedrock layers), storage
 * and sign block entities, and entities that do not spawn on their own (item frames, armor stands,
 * thrown pearls, name-tagged mobs, dropped items). Uses only what the server sent to you.
 */
public final class SusScanner {
    private SusScanner() {}

    private static final Set<Long> scannedThisSession = new HashSet<>();
    private static final Map<Long, Integer> entityPoints = new HashMap<>();
    private static final Map<Long, Map<String, Integer>> entityKinds = new HashMap<>();
    private static String sessionDim = "";

    public static void resetSession() {
        scannedThisSession.clear();
        entityPoints.clear();
        entityKinds.clear();
    }

    /** True the first time this session a chunk is seen. Needed so a chunk is scanned once, not on every reload. */
    public static boolean firstTime(String dim, long key) {
        if (!dim.equals(sessionDim)) {
            sessionDim = dim;
            resetSession();
        }
        if (scannedThisSession.size() > 300000) scannedThisSession.clear();
        return scannedThisSession.add(key);
    }

    public static void scan(String dim, WorldChunk chunk, ChunkProbe.Features f) {
        boolean nether = dim.endsWith("the_nether");
        int bottom = chunk.getBottomY();
        Map<String, Integer> t1 = new LinkedHashMap<>();
        int t2 = 0, roof = 0, badBedrock = 0;

        for (ChunkProbe.Section s : f.data) {
            int[] counts = null;
            for (int i = 0; i < s.palette.length; i++) {
                BlockState st = Block.STATE_IDS.get(s.palette[i]);
                if (st == null) continue;
                String p = Registries.BLOCK.getId(st.getBlock()).getPath();
                if (SusRules.isAir(p)) continue;

                boolean aboveRoof = nether && s.baseY >= 128 && !p.equals("bedrock");
                boolean misplacedBedrock = p.equals("bedrock")
                        && (nether ? (s.baseY != bottom && s.baseY != 112) : s.baseY != bottom);
                int kind = SusRules.kind(p, nether);
                if (!aboveRoof && !misplacedBedrock && kind == 0) continue;

                if (counts == null) counts = ChunkProbe.counts(s);
                int n = counts[i];
                if (n <= 0) continue; // palette entry nobody uses
                if (aboveRoof) roof += n;
                if (misplacedBedrock) badBedrock += n;
                if (kind == 1) t1.merge(p, n, Integer::sum);
                if (kind == 2) t2 += n;
            }
        }

        int score = 0;
        StringBuilder why = new StringBuilder();
        if (roof > 0) { score += 40; why.append("above-nether-roof x").append(roof).append(' '); }
        if (badBedrock > 0) { score += 25; why.append("bedrock-out-of-place x").append(badBedrock).append(' '); }
        int t1Total = 0;
        for (Map.Entry<String, Integer> e : t1.entrySet()) {
            score += 12;
            t1Total += e.getValue();
            if (why.length() < 90) why.append(e.getKey()).append(" x").append(e.getValue()).append(' ');
        }
        score += Math.min(10, t1Total / 6);
        if (t2 >= 100) { score += 20; why.append("many-built-blocks "); }
        else if (t2 >= 24) { score += 12; why.append("some-built-blocks "); }

        int be = 0;
        Map<String, Integer> beKinds = new LinkedHashMap<>();
        for (BlockEntity b : chunk.getBlockEntities().values()) {
            String type = Registries.BLOCK_ENTITY_TYPE.getId(b.getType()).getPath();
            int pts = SusRules.blockEntityPoints(type);
            if (pts > 0) { be += pts; beKinds.merge(type, 1, Integer::sum); }
        }
        if (be > 0) {
            score += Math.min(50, be);
            for (Map.Entry<String, Integer> e : beKinds.entrySet()) {
                if (why.length() < 140) why.append(e.getKey()).append(" x").append(e.getValue()).append(' ');
            }
        }

        if (f.blkSections > 0 && f.blkExtra > 0 && f.blkOut == 0) {
            score += 8;
            why.append("recent-change ");
        }

        int cx = chunk.getPos().getStartX() >> 4;
        int cz = chunk.getPos().getStartZ() >> 4;
        String reasons = why.toString().trim().replace(' ', '_');
        long k = ChunkStore.key(cx, cz);
        int ent = entityPoints.getOrDefault(k, 0);
        if (SusStore.update(dim, cx, cz, score, ent, reasons)) {
            HuntLog.line("SUS", dim + " chunk " + cx + "," + cz + " block " + (cx * 16 + 8) + "," + (cz * 16 + 8)
                    + " score " + (score + ent) + " " + reasons);
            HuntState.markDirty();
        }
    }

    /** Called for every entity the client creates. Players are ignored on purpose. */
    public static void onEntity(Entity e, ClientWorld world) {
        if (!HuntConfig.d.sus || !HuntDetector.supported(world) || e instanceof PlayerEntity) return;
        int pts = 0;
        String why = null;
        if (e instanceof ItemFrameEntity) { pts = 5; why = "item-frame"; }
        else if (e instanceof ArmorStandEntity) { pts = 6; why = "armor-stand"; }
        else if (e instanceof EnderPearlEntity) { pts = 10; why = "thrown-pearl"; }
        else if (e instanceof AbstractMinecartEntity) { pts = 4; why = "minecart"; }
        else if (e instanceof ItemEntity) { pts = 1; why = "dropped-item"; }
        else if (e instanceof ExperienceOrbEntity) { pts = 1; why = "xp-orb"; }
        else if (e.hasCustomName()) { pts = 8; why = "named-mob"; }
        if (pts == 0) return;

        int cx = e.getBlockX() >> 4, cz = e.getBlockZ() >> 4;
        long k = ChunkStore.key(cx, cz);
        int total = Math.min(40, entityPoints.merge(k, pts, Integer::sum));
        entityPoints.put(k, total);

        String dim = HuntDetector.dim(world);
        if (SusStore.update(dim, cx, cz, -1, total, why)) {
            HuntLog.line("SUS", dim + " chunk " + cx + "," + cz + " block " + (cx * 16 + 8) + "," + (cz * 16 + 8) + " entity " + why);
            HuntState.markDirty();
        }
    }
}
