package com.bilalutility.hunt;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Base hunting tools for the Overworld and the Nether: chunk detector, radar, patches, trails, portals, logs (/bhunt). */
public class HuntClient implements ClientModInitializer {
    private static void say(FabricClientCommandSource s, String text) {
        s.sendFeedback(Text.literal(text));
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    @Override
    public void onInitializeClient() {
        HuntConfig.load();

        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            HuntCalibration.onChunk(world, chunk);
            HuntDetector.onChunk(world, chunk);
        });
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            ChunkStore.tick();
            HuntLog.tick();
            HuntState.tick(c);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                HuntLog.line("SESSION", "join, minecraft " + modVersion("minecraft") + ", bilal_utility " + modVersion("bilal_utility")
                        + ", rule " + (HuntConfig.d.rule + 1) + ", detector " + (HuntConfig.d.detect ? "on" : "off")));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ChunkStore.flush();
            HuntLog.line("SESSION", "disconnect, classified this session: new " + HuntDetector.classifiedNew + ", old " + HuntDetector.classifiedOld);
            HuntLog.close();
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.of("bilal_utility", "hunt_radar"), RadarHud::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("bhunt")
                        .then(literal("help").executes(c -> {
                            say(c.getSource(), "/bhunt learn new|old|off  - calibrate on ground you know");
                            say(c.getSource(), "/bhunt report  - which rule works on this server");
                            say(c.getSource(), "/bhunt rule <1-" + HuntCalibration.ruleCount() + ">  - choose the rule the detector uses");
                            say(c.getSource(), "/bhunt on|off  and  /bhunt radar on|off");
                            say(c.getSource(), "/bhunt trails  - rows of old chunks with both ends (open = go there)");
                            say(c.getSource(), "/bhunt spots  - compact patches of old chunks (possible bases)");
                            say(c.getSource(), "/bhunt portals  - nether portals seen, with the other dimension's coordinates");
                            say(c.getSource(), "/bhunt portal <x> <z>  - nether coordinates to overworld (x8)");
                            say(c.getSource(), "/bhunt log  - where the log file is,  /bhunt forget  - delete saved chunks of this dimension");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("learn")
                                .then(literal("new").executes(c -> {
                                    HuntCalibration.setLearn(true);
                                    say(c.getSource(), "Learning: chunks that load now count as NEW. Fly into ground nobody has been to.");
                                    return Command.SINGLE_SUCCESS;
                                }))
                                .then(literal("old").executes(c -> {
                                    HuntCalibration.setLearn(false);
                                    say(c.getSource(), "Learning: chunks that load now count as OLD. Stay where you know people have been.");
                                    return Command.SINGLE_SUCCESS;
                                }))
                                .then(literal("off").executes(c -> {
                                    HuntCalibration.setLearn(null);
                                    say(c.getSource(), "Learning off, " + HuntCalibration.count() + " samples (" + HuntCalibration.flush() + ")");
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(literal("report").executes(c -> {
                            for (String line : HuntCalibration.report()) say(c.getSource(), line);
                            say(c.getSource(), "Detector uses rule " + (HuntConfig.d.rule + 1) + ": " + HuntCalibration.ruleName(HuntConfig.d.rule));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("status").executes(c -> {
                            say(c.getSource(), "Learn: " + HuntCalibration.learnLabel() + " (" + HuntCalibration.count() + " samples), detector: "
                                    + (HuntConfig.d.detect ? "on" : "off") + ", rule " + (HuntConfig.d.rule + 1));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("clear").executes(c -> {
                            HuntCalibration.clear();
                            say(c.getSource(), "Calibration samples cleared (the saved CSV file is kept).");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("rule").then(argument("n", IntegerArgumentType.integer(1, HuntCalibration.ruleCount())).executes(c -> {
                            HuntConfig.d.rule = IntegerArgumentType.getInteger(c, "n") - 1;
                            HuntConfig.save();
                            HuntLog.line("CALIB", "rule set to " + (HuntConfig.d.rule + 1) + ": " + HuntCalibration.ruleName(HuntConfig.d.rule));
                            say(c.getSource(), "Rule set: " + HuntCalibration.ruleName(HuntConfig.d.rule));
                            return Command.SINGLE_SUCCESS;
                        })))
                        .then(literal("on").executes(c -> {
                            HuntConfig.d.detect = true; HuntConfig.save();
                            say(c.getSource(), "Detector on.");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("off").executes(c -> {
                            HuntConfig.d.detect = false; HuntConfig.save();
                            say(c.getSource(), "Detector off.");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("radar")
                                .then(literal("on").executes(c -> {
                                    HuntConfig.d.radar = true; HuntConfig.save();
                                    say(c.getSource(), "Radar on.");
                                    return Command.SINGLE_SUCCESS;
                                }))
                                .then(literal("off").executes(c -> {
                                    HuntConfig.d.radar = false; HuntConfig.save();
                                    say(c.getSource(), "Radar off.");
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(literal("trails").executes(c -> {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc.player == null) return 0;
                            List<Trails.Trail> trails = HuntState.trails;
                            if (trails.isEmpty()) {
                                say(c.getSource(), "No trails yet. Fly along old chunks until a long row shows up (or lower Shortest trail / Straightness).");
                                return Command.SINGLE_SUCCESS;
                            }
                            say(c.getSource(), trails.size() + " trails (longest first). open = nothing known beyond that end yet, closed = new chunks after it:");
                            for (int i = 0; i < Math.min(8, trails.size()); i++) {
                                Trails.Trail t = trails.get(i);
                                say(c.getSource(), (i + 1) + ". " + t.describe(mc.player.getX(), mc.player.getZ())
                                        + " (" + Math.round(t.distanceTo(mc.player.getX(), mc.player.getZ())) + " blocks away)");
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("spots").executes(c -> {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc.player == null) return 0;
                            List<HotSpots.Spot> spots = HuntState.spots;
                            if (spots.isEmpty()) {
                                say(c.getSource(), "No compact patches yet.");
                                return Command.SINGLE_SUCCESS;
                            }
                            say(c.getSource(), spots.size() + " patches (biggest first):");
                            for (int i = 0; i < Math.min(10, spots.size()); i++) {
                                HotSpots.Spot s = spots.get(i);
                                say(c.getSource(), (i + 1) + ". " + s.x + ", " + s.z + "  (" + s.size + " old chunks, "
                                        + Math.round(Math.hypot(s.x - mc.player.getX(), s.z - mc.player.getZ())) + " blocks away)");
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("portals").executes(c -> {
                            List<PortalStore.Portal> all = PortalStore.all();
                            if (all.isEmpty()) {
                                say(c.getSource(), "No portals seen yet.");
                                return Command.SINGLE_SUCCESS;
                            }
                            say(c.getSource(), all.size() + " portals seen:");
                            for (int i = Math.max(0, all.size() - 15); i < all.size(); i++) {
                                PortalStore.Portal p = all.get(i);
                                boolean nether = p.dim.endsWith("the_nether");
                                say(c.getSource(), (nether ? "Nether " : "Overworld ") + p.x + ", " + p.y + ", " + p.z
                                        + (nether ? "  ->  overworld about " + (p.x * 8) + ", " + (p.z * 8)
                                        : "  ->  nether about " + (p.x / 8) + ", " + (p.z / 8)));
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("portal").then(argument("x", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer()).executes(c -> {
                            int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
                            say(c.getSource(), "Nether " + x + ", " + z + " links to about Overworld " + (x * 8) + ", " + (z * 8)
                                    + " (the real portal can be up to ~128 blocks off).");
                            return Command.SINGLE_SUCCESS;
                        }))))
                        .then(literal("log").executes(c -> {
                            say(c.getSource(), "Log file: " + HuntLog.path());
                            say(c.getSource(), "Calibration samples: .minecraft/bilal_hunt/<server>_calibration.csv");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(literal("forget").executes(c -> {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc.world == null || !HuntDetector.supported(mc.world)) return 0;
                            ChunkStore.forget(HuntDetector.dim(mc.world));
                            HuntState.markDirty();
                            say(c.getSource(), "Saved chunks of this dimension cleared.");
                            return Command.SINGLE_SUCCESS;
                        }))
        ));
    }
}
