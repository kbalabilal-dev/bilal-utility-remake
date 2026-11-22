package com.bilalutility.flight;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Auto Elytra: flies to a queue of coordinates (nearest first), lands, then goes on.
 * Elytra hunting: it looks for the elytra item frame from the air and skips ships without an elytra.
 * When it finds one it lands on the ship, plays an alert and stops the queue, so you take the elytra
 * yourself and press the flight key again to go on with the next points.
 * Everything is client side (normal movement, rocket, attack and chat-command packets only).
 * Void safety: high cruise altitude, auto re-glide, rocket reserve, "land at the nearest island",
 * ender pearl rescue and a per-frame /teamhome watchdog.
 */
public final class FlightRunner {
    private enum State { IDLE, PREPARE, TAKEOFF, CRUISE, LAND, PAUSE }

    private static final double ORBIT_R = 30;        // circle radius when we arrive too high
    private static final double FRAME_LAND_RADIUS = 12;   // landing this close to the frame = on the ship

    private static State state = State.IDLE;
    private static net.minecraft.registry.RegistryKey<World> startDimension;
    private static final List<FlightConfig.Waypoint> remaining = new ArrayList<>();
    private static FlightConfig.Waypoint target;
    private static long ticks;
    private static long lastRocket, lastPearl, lastTeamhome, lastSafetyMessage;
    private static int rocketsUsed, takeoffStage, takeoffTimer, pauseLeft, rocketSlot = -1, swapWait;
    private static double legStartDist = 1, flown, blocksPerRocket = 90;
    private static double lastX, lastZ;
    private static boolean emergency;
    // nearest solid ground found in loaded chunks (x, z, top y); kept as "last known island"
    private static boolean islandKnown;
    private static int islandX, islandZ, islandY;
    private static float lastHealth;

    // first rocket check
    private static boolean firstRocketPending;
    private static int rocketTries;
    private static long rocketCheckAt;
    private static double rocketSpeedBefore;

    // current leg
    private static int chunkSeen, landFails, nearGroundTicks;
    private static boolean scoutDone, orbiting, spotSet;
    private static double spotX, spotZ, refY;
    private static double orbitDir = 1;
    private static ItemFrameEntity frameTarget;
    // Last detection snapshot, also shown on the HUD.
    private static int lastShulkerCount, lastFrameCount;
    private static String lastShipVerdict = "unknown";

    // Distances measured from Bilal's known untouched ship. Pairwise distances do not
    // change when the ship is rotated or mirrored.
    private static final double[] SHIP_SHAPE = {6.40, 19.21, 20.35};
    private static final double SHAPE_TOLERANCE = 1.5;

    private static int alertLeft;

    // numbers for the HUD
    private static int foundThisRun, checkedThisRun;
    private static double routeBlocks;

    private FlightRunner() {}

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    public static String label() {
        return switch (state) {
            case IDLE -> FlightConfig.waypoints.size() + " points";
            case PREPARE -> "Preparing";
            case TAKEOFF -> "Take off";
            case CRUISE -> emergency ? "Emergency" : "Flying";
            case LAND -> orbiting ? "Circling down" : "Landing";
            case PAUSE -> "Waiting";
        };
    }

    // ------------------------------------------------------------------ start / stop

    public static void start(MinecraftClient client) {
        if (state != State.IDLE || client.player == null || client.world == null) return;
        if (FlightConfig.waypoints.isEmpty()) {
            msg(client, "Auto Elytra: add at least one point first");
            return;
        }
        remaining.clear();
        for (FlightConfig.Waypoint w : FlightConfig.waypoints) {
            // hunting remembers the result of every point, so finished points are not flown again
            // "empty" was an old v1.7 result based only on an item frame.
            // It is not trustworthy anymore, so allow those points to be retried.
            if (!FlightConfig.hunt || w.result == null || w.result.isEmpty() || w.result.equals("empty")) {
                if ("empty".equals(w.result)) w.result = "";
                remaining.add(w);
            }
        }
        if (remaining.isEmpty()) {
            msg(client, "Auto Elytra: every point already has a result - press Reset results");
            return;
        }
        startDimension = client.world.getRegistryKey();
        ticks = 0;
        lastRocket = -100;      // never leave these from the last flight (this was the "first rocket" bug)
        lastPearl = -100;
        rocketsUsed = 0;
        flown = 0;
        blocksPerRocket = 90;
        emergency = false;
        islandKnown = false;
        foundThisRun = 0;
        checkedThisRun = 0;
        lastShulkerCount = 0;
        lastFrameCount = 0;
        lastShipVerdict = "unknown";
        firstRocketPending = false;
        lastHealth = client.player.getHealth();
        lastX = client.player.getX();
        lastZ = client.player.getZ();
        target = null;
        resetLeg();
        routeBlocks = routeLength(client.player);
        state = State.PREPARE;
    }

    public static void stop(MinecraftClient client, String message) {
        state = State.IDLE;
        target = null;
        frameTarget = null;
        remaining.clear();
        if (client != null && client.options != null) {
            client.options.jumpKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
        }
        if (client != null && message != null) msg(client, message);
    }

    private static void msg(MinecraftClient client, String text) {
        if (client.player != null) client.player.sendMessage(Text.literal(text), true);
    }

    private static void resetLeg() {
        chunkSeen = 0;
        landFails = 0;
        nearGroundTicks = 0;
        scoutDone = false;
        orbiting = false;
        spotSet = false;
        frameTarget = null;
        lastShulkerCount = 0;
        lastFrameCount = 0;
        lastShipVerdict = "unknown";
    }

    // ------------------------------------------------------------------ per frame watchdog

    /** Called every rendered frame (faster than a tick): the last line of defence against the void. */
    public static void frameCheck(MinecraftClient client) {
        if (!FlightConfig.teamhome || client == null || client.player == null || client.world == null) return;
        if (client.world.getRegistryKey() != World.END) return;
        if (client.player.getY() >= FlightConfig.teamhomeY) return;
        long now = System.currentTimeMillis();
        if (now - lastTeamhome < 250) return;
        lastTeamhome = now;
        client.player.networkHandler.sendChatCommand(FlightConfig.teamhomeCommand);
        if (state != State.IDLE) stop(client, "Under Y " + FlightConfig.teamhomeY + ": /" + FlightConfig.teamhomeCommand + " sent");
    }

    // ------------------------------------------------------------------ main tick

    public static void tick(MinecraftClient client) {
        if (alertLeft > 0) {                 // the "elytra found" alert keeps ringing after the flight stopped
            alertLeft--;
            if (alertLeft % 10 == 0 && client.player != null) {
                client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_LEVELUP,
                        alertLeft % 20 == 0 ? 1.0f : 1.4f));
            }
        }
        if (state == State.IDLE) return;
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.world.getRegistryKey() != startDimension) {
            stop(client, "Auto Elytra stopped: world changed");
            return;
        }
        if (client.currentScreen == null && client.options.sneakKey.isPressed()) {
            stop(client, "Auto Elytra stopped (sneak)");
            return;
        }
        ticks++;
        flown += Math.hypot(p.getX() - lastX, p.getZ() - lastZ);
        lastX = p.getX();
        lastZ = p.getZ();
        if (rocketsUsed >= 3) blocksPerRocket = Math.max(30, flown / rocketsUsed);
        if (ticks % 20 == 0) scanIslands(client);
        if (ticks % 10 == 0 && target != null && FlightConfig.hunt) updateDetectionStatus(client, target.x + 0.5, target.z + 0.5);
        if (ticks % 40 == 0) routeBlocks = routeLength(p);

        switch (state) {
            case PREPARE -> prepare(client);
            case TAKEOFF -> takeoff(client);
            case CRUISE, LAND -> fly(client);
            case PAUSE -> pause(client);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ prepare

    private static void prepare(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (!p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            if (ticks % 10 == 1) equipElytra(client);   // retried until it shows up on the chest slot
            if (ticks > 60) stop(client, "Auto Elytra: no elytra found");
            return;
        }
        if (hotbarSlot(p, Items.FIREWORK_ROCKET) < 0) {
            stop(client, "Auto Elytra: put firework rockets in your hotbar");
            return;
        }
        if (FlightConfig.pearlRescue && hotbarSlot(p, Items.ENDER_PEARL) < 0) {
            stop(client, "Auto Elytra: put an ender pearl in your hotbar (or turn pearl rescue off)");
            return;
        }
        pickNext(client);
        if (target == null) {
            stop(client, "Auto Elytra: nothing to fly to");
            return;
        }
        double dist = distanceTo(p, target);
        int need = (int) Math.ceil(dist / blocksPerRocket * 1.3) + FlightConfig.reserveRockets;
        int have = countRockets(p);
        if (have < need) {
            stop(client, "Auto Elytra: need about " + need + " rockets for the first leg, you have " + have);
            return;
        }
        beginTakeoff();
    }

    private static boolean isChestplate(ItemStack stack) {
        return !stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).getPath().endsWith("_chestplate");
    }

    private static boolean elytraWorn(ClientPlayerEntity p) {
        return p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
    }

    private static boolean chestplateWorn(ClientPlayerEntity p) {
        return isChestplate(p.getEquippedStack(EquipmentSlot.CHEST));
    }

    private static boolean equipElytra(MinecraftClient client) {
        return swapIntoChest(client, false);
    }

    private static boolean equipChestplate(MinecraftClient client) {
        return swapIntoChest(client, true);
    }

    /** Puts an elytra (or a chestplate) from the inventory on the chest slot; the old piece goes back to its slot. */
    private static boolean swapIntoChest(MinecraftClient client, boolean chestplate) {
        ClientPlayerEntity p = client.player;
        PlayerScreenHandler h = p.playerScreenHandler;
        int src = -1;
        for (int s = 9; s <= 44; s++) {
            ItemStack st = h.getSlot(s).getStack();
            if (chestplate ? isChestplate(st) : st.isOf(Items.ELYTRA)) {
                src = s;
                break;
            }
        }
        if (src < 0) return false;
        moveToChest(client, src);
        return true;
    }

    private static void moveToChest(MinecraftClient client, int src) {
        ClientPlayerEntity p = client.player;
        PlayerScreenHandler h = p.playerScreenHandler;
        client.interactionManager.clickSlot(h.syncId, src, 0, SlotActionType.PICKUP, p);
        client.interactionManager.clickSlot(h.syncId, 6, 0, SlotActionType.PICKUP, p);      // 6 = chest armor slot
        if (!h.getCursorStack().isEmpty()) {
            client.interactionManager.clickSlot(h.syncId, src, 0, SlotActionType.PICKUP, p);
        }
    }

    /** Swaps in a spare elytra that has clearly more durability than the one we wear. */
    private static boolean swapSpareElytra(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        PlayerScreenHandler h = p.playerScreenHandler;
        ItemStack worn = p.getEquippedStack(EquipmentSlot.CHEST);
        int wornLeft = worn.isOf(Items.ELYTRA) ? worn.getMaxDamage() - worn.getDamage() : 0;
        int best = -1, bestLeft = wornLeft + 30;
        for (int s = 9; s <= 44; s++) {
            ItemStack st = h.getSlot(s).getStack();
            if (!st.isOf(Items.ELYTRA)) continue;
            int left = st.getMaxDamage() - st.getDamage();
            if (left > bestLeft) {
                bestLeft = left;
                best = s;
            }
        }
        if (best < 0) return false;
        moveToChest(client, best);
        return true;
    }

    private static void pickNext(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        target = null;
        double best = Double.MAX_VALUE;
        for (FlightConfig.Waypoint w : remaining) {
            double d = distanceTo(p, w);
            if (d < best) {
                best = d;
                target = w;
            }
        }
        legStartDist = Math.max(1, best);
        resetLeg();
    }

    private static void beginTakeoff() {
        state = State.TAKEOFF;
        takeoffStage = 0;
        takeoffTimer = 0;
        emergency = false;
    }

    // ------------------------------------------------------------------ take off (jump, release, jump again = glide)

    private static void takeoff(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p.isGliding()) {
            client.options.jumpKey.setPressed(false);
            state = State.CRUISE;
            firstRocketPending = true;      // the first rocket is checked: did it really speed us up?
            rocketTries = 0;
            rocketCheckAt = 0;
            lastRocket = ticks - 100;
            return;
        }
        aimXZ(p, target.x + 0.5, target.z + 0.5, 0f);
        takeoffTimer++;
        if (takeoffTimer > 120) {
            stop(client, "Auto Elytra: could not start gliding");
            return;
        }
        switch (takeoffStage) {
            case 0 -> {
                if (p.isOnGround()) client.options.jumpKey.setPressed(true);
                else takeoffStage = 1;
            }
            case 1 -> {
                client.options.jumpKey.setPressed(false);      // release in the air
                takeoffStage = 2;
            }
            default -> {
                Vec3d v = p.getVelocity();
                client.options.jumpKey.setPressed(v.y < 0 && !p.isOnGround());   // press again while falling
                if (p.isOnGround() && takeoffTimer % 40 == 0) takeoffStage = 0;
            }
        }
    }

    // ------------------------------------------------------------------ flying and landing

    /** How far from the point we start coming down: lower and closer = shorter, higher = longer. */
    private static double landStart(double heightOverSpot) {
        return clamp(heightOverSpot * 5 + 30, 60, 500);
    }

    private static void fly(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        Vec3d v = p.getVelocity();
        double speed = v.length();
        boolean gliding = p.isGliding();

        // ---- void protection: lost the glide while in the air
        if (!gliding && !p.isOnGround()) {
            double gb = groundBelow(client, p, 40);
            if (state == State.LAND && !Double.isNaN(gb) && p.getY() - gb < 6) {
                client.options.jumpKey.setPressed(false);       // nearly down: just let it fall
                return;
            }
            client.options.jumpKey.setPressed(ticks % 2 == 0 && v.y < 0);   // re-glide: release / press pulses
            if (FlightConfig.pearlRescue && p.getY() < FlightConfig.pearlBelowY && ticks - lastPearl > 40) {
                throwPearl(client);
            }
            return;
        }
        client.options.jumpKey.setPressed(false);

        // ---- where to go
        boolean island = emergency && islandKnown;
        double tx, tz;
        if (island) {
            tx = islandX + 0.5;
            tz = islandZ + 0.5;
            refY = islandY;
        } else if (frameTarget != null && !frameTarget.isRemoved()) {
            tx = frameTarget.getX();
            tz = frameTarget.getZ();
            refY = Math.floor(frameTarget.getY());
        } else if (spotSet) {
            tx = spotX;
            tz = spotZ;
        } else {
            tx = target.x + 0.5;
            tz = target.z + 0.5;
        }
        double dist = Math.hypot(tx - p.getX(), tz - p.getZ());

        // ---- on the ground
        if (!gliding) {
            if (state == State.LAND) arrived(client, dist);
            else beginTakeoff();
            return;
        }

        // ---- safety checks every second
        if (ticks % 20 == 0) safetyChecks(client);

        // ---- decide what to do about the point (only while cruising)
        if (state == State.CRUISE) {
            double alt = p.getY();
            if (island) {
                if (dist <= landStart(alt - refY)) enterLand();
            } else {
                boolean loaded = chunkLoaded(client, tx, tz);
                boolean scouting = FlightConfig.hunt && (FlightConfig.scout || FlightConfig.useShulkers)
                        && !scoutDone && frameTarget == null;
                if (scouting) {
                    chunkSeen = loaded ? chunkSeen + 1 : 0;
                    if (chunkSeen >= 10 && ticks % 5 == 0) {
                        // A real elytra frame is always a positive result, even if shulkers are missing.
                        if (FlightConfig.scout) {
                            ItemFrameEntity f = findElytraFrame(client, target.x + 0.5, target.z + 0.5, FlightConfig.searchRadius);
                            if (f != null) {
                                frameTarget = f;
                                scoutDone = true;
                                msg(client, "Auto Elytra: elytra frame seen at " + target.name + ", going down");
                            }
                        }
                        if (!scoutDone && FlightConfig.useShulkers) {
                            ShulkerScan scan = scanShulkers(client, target.x + 0.5, target.z + 0.5, FlightConfig.searchRadius);
                            rememberScan(scan);
                            if (scan.verdict.equals("untouched")) {
                                spotX = scan.centerX;
                                spotZ = scan.centerZ;
                                refY = scan.centerY;
                                spotSet = true;
                                scoutDone = true;
                                msg(client, "Auto Elytra: untouched ship shape found at " + target.name + ", going down");
                            } else if (scan.verdict.equals("looted")) {
                                skipPoint(client, "looted", "looks looted (shulker shape does not match)");
                                return;
                            }
                        }
                    }
                    // Zero visible shulkers is UNKNOWN, never "empty". If we are over the point
                    // and there is ground, approach it and make one closer check after landing.
                    if (!scoutDone && dist <= 12 && chunkSeen >= 30 && loaded) {
                        double g = groundAt(client, (int) Math.floor(tx), (int) Math.floor(tz));
                        if (g >= 0) {
                            refY = g;
                            enterLand();
                        } else if (!spotSet && findSpot(client, tx, tz)) {
                            return;
                        }
                    }
                } else if (loaded) {
                    if (frameTarget != null) {
                        if (dist <= landStart(alt - refY)) enterLand();
                    } else {
                        double g = groundAt(client, (int) Math.floor(tx), (int) Math.floor(tz));
                        if (g >= 0) {
                            refY = g;
                            if (dist <= landStart(alt - refY)) enterLand();
                        } else if (FlightConfig.hunt && !spotSet && findSpot(client, tx, tz)) {
                            // there is ground close by: it is the landing spot from the next tick on
                            return;
                        } else {
                            // the chunk is loaded and the column is empty: over the void, never go down here
                            skipPoint(client, "no ground", "has no ground");
                            return;
                        }
                    }
                }
            }
        }

        float pitch;
        if (state == State.LAND) {
            double gb = groundBelow(client, p, 120);                          // real ground under us
            double alt = p.getY() - refY;                                     // height over the landing spot
            double above = Double.isNaN(gb) ? alt : p.getY() - gb;            // height over the ground below
            double yawToSpot = Math.toDegrees(Math.atan2(-(tx - p.getX()), tz - p.getZ()));

            // arrived too high to come straight down: circle the spot and lose height first
            if (!orbiting && dist < ORBIT_R + 15 && alt > dist * 0.25 + 20) {
                orbiting = true;
                orbitDir = Math.random() < 0.5 ? 1 : -1;
            }
            if (orbiting && alt < 14) orbiting = false;

            if (orbiting) {
                double off = clamp(90 + (ORBIT_R - dist) * 2.5, 40, 140);
                p.setYaw(MathHelper.wrapDegrees((float) (yawToSpot + orbitDir * off)));
                pitch = (float) clamp((alt - 14) * 0.4, 6, 30);
                if (speed > 2.4) pitch = Math.min(pitch, 6f);
                p.setPitch(pitch);
                return;
            }

            if (dist > 25) {
                double want = dist * 0.2 + 3;                                  // gentle slope down to the spot
                pitch = (float) clamp((alt - want) * 0.6, -6, 40);
            } else if (above > 12) {
                pitch = (float) clamp((above - 12) * 0.8 + 6, 6, 30);          // last part of the descent
            } else if (above > 4) {
                pitch = speed > 1.4 ? -6f : 8f;                                // flare: bleed speed
            } else {
                pitch = 14f;                                                   // touch down
            }
            // low and still flying for a long time: force it down
            nearGroundTicks = above < 5 ? nearGroundTicks + 1 : 0;
            if (nearGroundTicks > 100) pitch = 35f;
            if (dist > 3) p.setYaw((float) yawToSpot);
            p.setPitch(pitch);
            return;
        }

        // ---- cruising
        double err = FlightConfig.cruiseY - p.getY();
        pitch = (float) clamp(-err * 0.4, -55, 35);
        if (firstRocketPending) {
            // the first rocket must work: fire it, check the speed, try again if nothing happened
            if (rocketCheckAt == 0) {
                if (ticks - lastRocket >= 5) {
                    if (rocketTries >= 3) {
                        stop(client, "Auto Elytra: the first rocket does nothing (3 tries) - check your hotbar rockets");
                        return;
                    }
                    rocketSpeedBefore = speed;
                    rocketTries++;
                    if (useRocket(client)) rocketCheckAt = ticks + 6;
                }
            } else if (ticks >= rocketCheckAt) {
                rocketCheckAt = 0;
                if (speed >= rocketSpeedBefore + 0.1) firstRocketPending = false;
            }
        } else if (speed < 1.25 && ticks - lastRocket >= 20) {
            useRocket(client);
        }
        double dx = tx - p.getX(), dz = tz - p.getZ();
        p.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setPitch(pitch);
    }

    private static void enterLand() {
        state = State.LAND;
        orbiting = false;
        nearGroundTicks = 0;
    }

    /** Over the void: look around the point for solid ground and use the nearest piece as the landing spot. */
    private static boolean findSpot(MinecraftClient client, double tx, double tz) {
        double best = Double.MAX_VALUE;
        for (int dx = -24; dx <= 24; dx += 4) {
            for (int dz = -24; dz <= 24; dz += 4) {
                double d = (double) dx * dx + (double) dz * dz;
                if (d >= best) continue;
                int top = groundAt(client, (int) Math.floor(tx) + dx, (int) Math.floor(tz) + dz);
                if (top >= 0) {
                    best = d;
                    spotX = Math.floor(tx) + dx + 0.5;
                    spotZ = Math.floor(tz) + dz + 0.5;
                    refY = top;
                    spotSet = true;
                }
            }
        }
        return spotSet;
    }

    private static void arrived(MinecraftClient client, double dist) {
        if (emergency) {
            if (FlightConfig.landWithChestplate) equipChestplate(client);
            stop(client, "Auto Elytra: landed on the nearest island");
            return;
        }
        ClientPlayerEntity p = client.player;
        boolean onFrame = frameTarget != null && !frameTarget.isRemoved();
        double radius = onFrame ? FRAME_LAND_RADIUS : FlightConfig.landRadius;
        if (dist > radius) {          // landed too far from the point (or the ship): take off again
            if (++landFails >= 3) {
                if (onFrame) {
                    found(client, "could not land on the ship, you are " + (int) dist + " blocks from the frame");
                    return;
                }
                msg(client, "Auto Elytra: " + target.name + " cannot be reached, skipped");
                markPoint("unreachable");
                remaining.remove(target);
                enterPause(client);
                return;
            }
            beginTakeoff();
            return;
        }
        if (FlightConfig.hunt) {
            if (onFrame) {
                found(client, null);
                return;
            }
            // Recheck at close range. An empty-looking frame is NOT proof that a ship is looted.
            ItemFrameEntity f = findElytraFrame(client, p.getX(), p.getZ(), FlightConfig.searchRadius);
            if (f != null) {
                frameTarget = f;
                found(client, null);
                return;
            }
            if (FlightConfig.useShulkers) {
                ShulkerScan scan = scanShulkers(client, target.x + 0.5, target.z + 0.5, FlightConfig.searchRadius);
                rememberScan(scan);
                if (scan.verdict.equals("untouched")) {
                    found(client, "three shulkers match the ship");
                    return;
                }
                if (scan.verdict.equals("looted")) {
                    msg(client, "Auto Elytra: " + target.name + " looks looted (shulkers missing or moved), skipped");
                    markPoint("looted");
                    remaining.remove(target);
                    enterPause(client);
                    return;
                }
            }
            // No shulkers were sent by the server: unknown. Do not save a result, so this
            // point can be tried again later (for example with a larger mob tracking range).
            msg(client, "Auto Elytra: " + target.name + " is unknown (no visible elytra frame or shulkers); will retry next run");
            remaining.remove(target);
            enterPause(client);
            return;
        }
        remaining.remove(target);
        msg(client, "Auto Elytra: arrived at " + target.name);
        enterPause(client);
    }

    /** An elytra frame is here: remember the point, ring the alert and stop so the player takes the elytra. */
    private static void found(MinecraftClient client, String note) {
        String name = target != null ? target.name : "point";
        markPoint("elytra");
        if (target != null) remaining.remove(target);
        foundThisRun++;
        alertLeft = 100;
        String text = "ELYTRA FOUND at " + name + (note == null ? "" : " (" + note + ")")
                + " - take it, then press the flight key to go on";
        stop(client, text);
        if (client.player != null) client.player.sendMessage(Text.literal(text), false);
    }

    private static void enterPause(MinecraftClient client) {
        state = State.PAUSE;
        int base = FlightConfig.hunt ? 20 : FlightConfig.pauseSeconds * 20;
        pauseLeft = Math.max(base, FlightConfig.landWithChestplate ? 100 : 0);
        swapWait = 0;
    }

    private static void pause(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        boolean more = !remaining.isEmpty();
        if (FlightConfig.landWithChestplate) {
            boolean elytraTime = more && pauseLeft <= 40;           // put the elytra back shortly before leaving
            if (!elytraTime && !chestplateWorn(p) && ticks % 10 == 0) equipChestplate(client);
            if (elytraTime && !elytraWorn(p) && ticks % 10 == 0) equipElytra(client);
        }
        if (pauseLeft-- > 0) return;
        if (!more) {
            stop(client, "Auto Elytra: queue finished" + (FlightConfig.hunt ? " - elytra found: " + foundThisRun : ""));
            return;
        }
        if (!elytraWorn(p)) {                                        // still swapping
            if (!FlightConfig.landWithChestplate || ++swapWait > 100) {
                stop(client, "Auto Elytra: no elytra on the chest slot");
            } else if (ticks % 10 == 0) {
                equipElytra(client);
            }
            pauseLeft = 0;
            return;
        }
        pickNext(client);
        int need = (int) Math.ceil(distanceTo(p, target) / blocksPerRocket * 1.3) + FlightConfig.reserveRockets;
        if (countRockets(p) < need) {
            stop(client, "Auto Elytra: not enough rockets for the next point (need about " + need + ")");
            return;
        }
        beginTakeoff();
    }

    // ------------------------------------------------------------------ skipping points

    private static void markPoint(String result) {
        if (target == null) return;
        checkedThisRun++;
        if (FlightConfig.hunt) {
            target.result = result;
            FlightConfig.save();
        }
    }

    /** Drops the current point while flying and goes on to the next one. */
    private static void skipPoint(MinecraftClient client, String result, String text) {
        if (target != null) {
            msg(client, "Auto Elytra: " + target.name + " " + text + ", skipped");
            markPoint(result);
            remaining.remove(target);
        }
        pickNext(client);
        if (target == null) queueEmpty(client);
    }

    /** No more points. In the air: land at the nearest island instead of just stopping. */
    private static void queueEmpty(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p != null && !p.isOnGround() && islandKnown) {
            emergency = true;
            state = State.CRUISE;
            msg(client, "Auto Elytra: queue finished - landing on the nearest island");
        } else {
            stop(client, "Auto Elytra: queue finished");
        }
    }

    // ------------------------------------------------------------------ shulker ship detection

    private static final class ShulkerScan {
        final List<ShulkerEntity> shulkers;
        final String verdict;
        final double centerX, centerY, centerZ;
        final double bestError;

        ShulkerScan(List<ShulkerEntity> shulkers, String verdict,
                    double centerX, double centerY, double centerZ, double bestError) {
            this.shulkers = shulkers;
            this.verdict = verdict;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.bestError = bestError;
        }
    }

    /** Collect only shulkers the server has actually sent to this client. */
    private static ShulkerScan scanShulkers(MinecraftClient client, double x, double z, double radius) {
        double bottom = client.world.getBottomY();
        Box box = new Box(x - radius, bottom, z - radius, x + radius, bottom + 400, z + radius);
        List<ShulkerEntity> found = client.world.getEntitiesByClass(ShulkerEntity.class, box,
                e -> !e.isRemoved());
        if (found.isEmpty()) return new ShulkerScan(found, "unknown", 0, 0, 0, Double.MAX_VALUE);
        if (found.size() < 3) return new ShulkerScan(found, "looted", 0, 0, 0, Double.MAX_VALUE);

        // Try every triple. This also handles several nearby ships/cities: the best
        // matching triple wins, instead of depending on entity-list order.
        double best = Double.MAX_VALUE;
        ShulkerEntity ba = null, bb = null, bc = null;
        for (int i = 0; i < found.size() - 2; i++) {
            for (int j = i + 1; j < found.size() - 1; j++) {
                for (int k = j + 1; k < found.size(); k++) {
                    ShulkerEntity a = found.get(i), b = found.get(j), c = found.get(k);
                    double[] ds = {
                            a.getPos().distanceTo(b.getPos()),
                            a.getPos().distanceTo(c.getPos()),
                            b.getPos().distanceTo(c.getPos())
                    };
                    java.util.Arrays.sort(ds);
                    double error = Math.abs(ds[0] - SHIP_SHAPE[0])
                            + Math.abs(ds[1] - SHIP_SHAPE[1])
                            + Math.abs(ds[2] - SHIP_SHAPE[2]);
                    if (error < best) {
                        best = error;
                        ba = a; bb = b; bc = c;
                    }
                }
            }
        }
        if (ba != null) {
            double[] ds = {
                    ba.getPos().distanceTo(bb.getPos()),
                    ba.getPos().distanceTo(bc.getPos()),
                    bb.getPos().distanceTo(bc.getPos())
            };
            java.util.Arrays.sort(ds);
            boolean matches = Math.abs(ds[0] - SHIP_SHAPE[0]) <= SHAPE_TOLERANCE
                    && Math.abs(ds[1] - SHIP_SHAPE[1]) <= SHAPE_TOLERANCE
                    && Math.abs(ds[2] - SHIP_SHAPE[2]) <= SHAPE_TOLERANCE;
            if (matches) {
                return new ShulkerScan(found, "untouched",
                        (ba.getX() + bb.getX() + bc.getX()) / 3.0,
                        (ba.getY() + bb.getY() + bc.getY()) / 3.0,
                        (ba.getZ() + bb.getZ() + bc.getZ()) / 3.0, best);
            }
        }
        return new ShulkerScan(found, "looted", 0, 0, 0, best);
    }

    private static int countFrames(MinecraftClient client, double x, double z, double radius) {
        double bottom = client.world.getBottomY();
        Box box = new Box(x - radius, bottom, z - radius, x + radius, bottom + 400, z + radius);
        return client.world.getEntitiesByClass(ItemFrameEntity.class, box, e -> !e.isRemoved()).size();
    }

    private static void rememberScan(ShulkerScan scan) {
        lastShulkerCount = scan.shulkers.size();
        lastShipVerdict = scan.verdict;
    }

    private static void updateDetectionStatus(MinecraftClient client, double x, double z) {
        ShulkerScan scan = scanShulkers(client, x, z, FlightConfig.searchRadius);
        rememberScan(scan);
        lastFrameCount = countFrames(client, x, z, FlightConfig.searchRadius);
        if (!FlightConfig.useShulkers) lastShipVerdict = "unknown";
    }

    /** Debug key: print the shulkers currently tracked by the server around the player. */
    public static void debugShulkers(MinecraftClient client) {
        double x = client.player.getX(), z = client.player.getZ();
        ShulkerScan scan = scanShulkers(client, x, z, FlightConfig.searchRadius);
        int frames = countFrames(client, x, z, FlightConfig.searchRadius);
        rememberScan(scan);
        lastFrameCount = frames;
        client.player.sendMessage(Text.literal("Auto Elytra debug: Shulkers " + scan.shulkers.size()
                + " | Ship: " + scan.verdict + " | Frames " + frames
                + " | radius " + FlightConfig.searchRadius), false);
        for (int i = 0; i < scan.shulkers.size(); i++) {
            ShulkerEntity sh = scan.shulkers.get(i);
            client.player.sendMessage(Text.literal(String.format(Locale.ROOT,
                    "Shulker %d: %.1f %.1f %.1f", i + 1, sh.getX(), sh.getY(), sh.getZ())), false);
        }
    }

    // ------------------------------------------------------------------ elytra found

    /** Nearest item frame with an elytra around x, z (client side: only frames the server sent us). */
    private static ItemFrameEntity findElytraFrame(MinecraftClient client, double x, double z, double r) {
        double bottom = client.world.getBottomY();
        Box box = new Box(x - r, bottom, z - r, x + r, bottom + 400, z + r);
        ItemFrameEntity best = null;
        double bd = Double.MAX_VALUE;
        for (ItemFrameEntity f : client.world.getEntitiesByClass(ItemFrameEntity.class, box,
                e -> !e.isRemoved() && e.getHeldItemStack().isOf(Items.ELYTRA))) {
            double d = Math.hypot(f.getX() - x, f.getZ() - z);
            if (d < bd) {
                bd = d;
                best = f;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ safety

    private static void safetyChecks(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (emergency || target == null) return;
        ItemStack elytra = p.getEquippedStack(EquipmentSlot.CHEST);
        boolean elytraLow = !elytra.isOf(Items.ELYTRA) || elytra.getMaxDamage() - elytra.getDamage() <= 12;
        if (elytraLow && swapSpareElytra(client)) {
            msg(client, "Auto Elytra: swapped to a spare elytra");
            elytraLow = false;
        }
        boolean healthLow = p.getHealth() < 8.0F;
        double dist = Math.hypot(target.x - p.getX(), target.z - p.getZ());
        int need = (int) Math.ceil(dist / blocksPerRocket) + FlightConfig.reserveRockets;
        boolean rocketsLow = countRockets(p) < need;
        if (state == State.LAND) rocketsLow = false;      // landing needs no rockets
        if (elytraLow || healthLow || rocketsLow) {
            if (islandKnown) {
                emergency = true;
                state = State.CRUISE;
                msg(client, "Auto Elytra: " + (elytraLow ? "elytra low" : healthLow ? "health low" : "rockets low")
                        + " - landing on the nearest island");
            } else if (System.currentTimeMillis() - lastSafetyMessage > 5000) {
                lastSafetyMessage = System.currentTimeMillis();
                msg(client, "Auto Elytra WARNING: " + (elytraLow ? "elytra low" : healthLow ? "health low" : "rockets low")
                        + " and no island known");
            }
        }
        lastHealth = p.getHealth();
    }

    /** Looks for solid ground in the loaded area; remembers the nearest spot. */
    private static void scanIslands(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        int radius = Math.max(48, (client.options.getViewDistance().getValue() - 1) * 16);
        int px = (int) Math.floor(p.getX()), pz = (int) Math.floor(p.getZ());
        double best = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx += 8) {
            for (int dz = -radius; dz <= radius; dz += 8) {
                double d = (double) dx * dx + (double) dz * dz;
                if (d >= best || d > (double) radius * radius) continue;
                int top = groundAt(client, px + dx, pz + dz);
                if (top >= 0) {
                    best = d;
                    islandX = px + dx;
                    islandZ = pz + dz;
                    islandY = top;
                    islandKnown = true;
                }
            }
        }
    }

    /** Top of the ground in a column from the height map (-1 = nothing there or chunk not loaded). */
    private static int groundAt(MinecraftClient client, int x, int z) {
        int top = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
        return top > 8 ? top : -1;
    }

    private static boolean chunkLoaded(MinecraftClient client, double x, double z) {
        return client.world.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4, ChunkStatus.FULL, false) != null;
    }

    private static boolean solid(MinecraftClient client, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return !client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty();
    }

    /** Real ground straight below the player (top of the first solid block), NaN if none within maxDown. */
    private static double groundBelow(MinecraftClient client, ClientPlayerEntity p, int maxDown) {
        int x = (int) Math.floor(p.getX()), z = (int) Math.floor(p.getZ());
        int y0 = (int) Math.floor(p.getY() - 0.01);
        int bottom = Math.max(client.world.getBottomY(), y0 - maxDown);
        for (int y = y0; y >= bottom; y--) {
            if (solid(client, x, y, z)) return y + 1;
        }
        return Double.NaN;
    }

    private static void throwPearl(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        int slot = hotbarSlot(p, Items.ENDER_PEARL);
        if (slot < 0 || !islandKnown) return;
        lastPearl = ticks;
        double dx = islandX + 0.5 - p.getX(), dz = islandZ + 0.5 - p.getZ();
        double d = Math.hypot(dx, dz);
        p.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setPitch(d > 45 ? -45f : -30f);
        PlayerInventory inv = p.getInventory();
        inv.setSelectedSlot(slot);
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
        msg(client, "Auto Elytra: ender pearl thrown at the nearest island");
    }

    // ------------------------------------------------------------------ rockets and inventory

    private static int hotbarSlot(ClientPlayerEntity p, Item item) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private static int countRockets(ClientPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(Items.FIREWORK_ROCKET)) n += s.getCount();
        }
        return n;
    }

    /** Fires one rocket. Returns false when there was none in the hotbar (it refills from the inventory). */
    private static boolean useRocket(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        int slot = hotbarSlot(p, Items.FIREWORK_ROCKET);
        if (slot < 0) {
            refillRockets(client);
            return false;
        }
        rocketSlot = slot;
        p.getInventory().setSelectedSlot(slot);
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
        rocketsUsed++;
        lastRocket = ticks;
        return true;
    }

    private static void refillRockets(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        PlayerScreenHandler h = p.playerScreenHandler;
        int hot = rocketSlot >= 0 && rocketSlot < 9 ? rocketSlot : 8;
        for (int s = 9; s <= 35; s++) {
            if (h.getSlot(s).getStack().isOf(Items.FIREWORK_ROCKET)) {
                client.interactionManager.clickSlot(h.syncId, s, hot, SlotActionType.SWAP, p);
                lastRocket = ticks;
                return;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static double distanceTo(ClientPlayerEntity p, FlightConfig.Waypoint w) {
        return Math.hypot(w.x + 0.5 - p.getX(), w.z + 0.5 - p.getZ());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void aimXZ(ClientPlayerEntity p, double tx, double tz, float pitch) {
        double dx = tx - p.getX(), dz = tz - p.getZ();
        p.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setPitch(pitch);
    }

    /** Length of the whole rest of the route (nearest point first), to estimate the rockets needed. */
    private static double routeLength(ClientPlayerEntity p) {
        double x = p.getX(), z = p.getZ(), total = 0;
        List<FlightConfig.Waypoint> left = new ArrayList<>(remaining);
        while (!left.isEmpty()) {
            FlightConfig.Waypoint best = null;
            double bd = Double.MAX_VALUE;
            for (FlightConfig.Waypoint w : left) {
                double d = Math.hypot(w.x + 0.5 - x, w.z + 0.5 - z);
                if (d < bd) {
                    bd = d;
                    best = w;
                }
            }
            total += bd;
            x = best.x + 0.5;
            z = best.z + 0.5;
            left.remove(best);
        }
        return total;
    }

    // ------------------------------------------------------------------ HUD

    public static float progress() {
        if (target == null || state == State.IDLE) return 0f;
        if (state == State.PAUSE) return 1f;
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player == null) return 0f;
        return (float) clamp(1.0 - distanceTo(c.player, target) / legStartDist, 0, 1);
    }

    public static List<String> hudLines() {
        List<String> lines = new ArrayList<>();
        MinecraftClient c = MinecraftClient.getInstance();
        lines.add("Auto Elytra: " + label());
        if (c.player == null) return lines;
        if (target != null) {
            lines.add(String.format(Locale.ROOT, "To %s  %d blocks", target.name, (int) distanceTo(c.player, target)));
        }
        if (state == State.PAUSE) lines.add("Next leg in " + (pauseLeft / 20 + 1) + " s");
        ItemStack e = c.player.getEquippedStack(EquipmentSlot.CHEST);
        lines.add("Rockets " + countRockets(c.player) + "  Elytra " + (e.isOf(Items.ELYTRA) ? (e.getMaxDamage() - e.getDamage()) : 0)
                + "  Left " + remaining.size());
        if (FlightConfig.hunt) {
            lines.add("Checked " + checkedThisRun + "  Elytra found " + foundThisRun);
            lines.add("Shulkers " + lastShulkerCount + " | Ship: " + lastShipVerdict + " | Frames " + lastFrameCount);
        }
        int needAll = (int) Math.ceil(routeBlocks / blocksPerRocket * 1.3);
        lines.add(String.format(Locale.ROOT, "Route left %d blocks (~%d rockets)", (int) routeBlocks, needAll));
        double speed = c.player.getVelocity().length();
        lines.add(String.format(Locale.ROOT, "Speed %.1f  Y %d", speed, (int) c.player.getY()));
        return lines;
    }
}
