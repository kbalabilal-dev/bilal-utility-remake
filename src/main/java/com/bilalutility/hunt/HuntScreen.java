package com.bilalutility.hunt;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Settings and buttons of the base hunting tools. */
public class HuntScreen extends BilalScreen {
    public HuntScreen(Screen parent) {
        super("Base Hunting", "Old chunks, trails, patches, logs", 0x55DD77, parent);
    }

    @Override
    protected void build() {
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int half = (inner - 4) / 2;
        int y = contentTop();

        section("Chunk detector (Overworld and Nether)", y);
        y += 14;
        toggle(left, y, half, "Detector", () -> HuntConfig.d.detect, v -> HuntConfig.d.detect = v,
                HuntConfig::save, "Records every chunk the server sends as new or old (kept per server and dimension)");
        toggle(left + half + 4, y, half, "Radar", () -> HuntConfig.d.radar, v -> HuntConfig.d.radar = v,
                HuntConfig::save, "Small map top right: red = new, green = old, yellow = patch, cyan/pink = trail ends");
        y += 24;
        cycle(left, y, inner, "Rule", HuntCalibration.ruleCount(), () -> HuntConfig.d.rule,
                v -> HuntConfig.d.rule = v, HuntCalibration::ruleName, HuntConfig::save,
                "Which signal calls a chunk new. Pick the one /bhunt report scores best on your server");
        y += 24;
        toggle(left, y, inner, "Write logs", () -> HuntConfig.d.logs, v -> HuntConfig.d.logs = v,
                HuntConfig::save, "One text file per session in .minecraft/bilal_hunt_logs (no chat, no names, no server address)");
        y += 24;
        dynamic(() -> {
            if (client.world == null) return "Join a world to see counts";
            if (!HuntDetector.supported(client.world)) return "The End is not tracked";
            String dim = HuntDetector.dim(client.world);
            return "This dimension: " + ChunkStore.count(dim, ChunkStore.OLD) + " old, " + ChunkStore.count(dim, ChunkStore.NEW) + " new";
        }, left, y + 2, inner, 0xFFCCCCCC);

        y += 20;
        section("Trails (rows of old chunks)", y);
        y += 14;
        stepper(y, "Shortest trail", () -> HuntConfig.d.minTrail, v -> HuntConfig.d.minTrail = v,
                3, 200, 1, 5, v -> v + " chunks", HuntConfig::save, "A row of old chunks must be at least this long");
        y += 24;
        stepper(y, "Straightness", () -> HuntConfig.d.trailShape, v -> HuntConfig.d.trailShape = v,
                12, 80, 1, 5, v -> (v / 10.0) + "x", HuntConfig::save, "How much longer than wide a patch must be to count as a row");
        y += 24;
        stepper(y, "Smallest patch", () -> HuntConfig.d.minCluster, v -> HuntConfig.d.minCluster = v,
                1, 50, 1, 5, v -> v + " chunks", HuntConfig::save, "Compact patches (possible bases) smaller than this are ignored");
        y += 24;
        stepper(y, "Ignore around 0,0", () -> HuntConfig.d.ignoreSpawn, v -> HuntConfig.d.ignoreSpawn = v,
                0, 100000, 500, 5000, v -> v + " blocks", HuntConfig::save, "Old chunks this close to spawn are just traffic");
        y += 24;
        stepper(y, "Radar size", () -> HuntConfig.d.radarRadius, v -> HuntConfig.d.radarRadius = v,
                8, 64, 4, 8, v -> v + " chunks", HuntConfig::save, "How far around you the radar shows");
        y += 28;
        dynamic(() -> "Nearest trail: " + (client.world == null ? "-" : HuntState.nearestTrailText(client)), left, y + 2, inner, 0xFF33DDFF);
        y += 16;
        dynamic(() -> "Patches: " + HuntState.spots.size() + "   Trails: " + HuntState.trails.size()
                + "   Portals known: " + PortalStore.all().size(), left, y + 2, inner, 0xFFFFDD33);
        y += 22;
        button("Forget saved chunks (this dimension)", left, y, inner, () -> {
            if (client.world != null && HuntDetector.supported(client.world)) {
                ChunkStore.forget(HuntDetector.dim(client.world));
                HuntState.markDirty();
                if (client.player != null) client.player.sendMessage(Text.literal("Saved chunks of this dimension cleared."), true);
            }
        }, "Deletes the new/old memory of this dimension on this server");

        setHint("Commands: /bhunt help  -  calibrate with /bhunt learn new|old, then /bhunt report");
    }
}
