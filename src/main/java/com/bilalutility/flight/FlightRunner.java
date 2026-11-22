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
    private enum State { IDLE, PREPARE, TAKEOFF, CRUISE, LAND, PAUSE, PAUSED_FAILURE }

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
    private static int chunkSeen, landFails, nearGroundTicks, landingAttempts, shipApproaches, goAroundTicks;
    private static boolean shipDropActive;
    private static long lastLandDebug;
    private static double shipRoofY = Double.NaN;
    private static long lastDebugHeartbeat;
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

    private static final double SHIP_LAND_RADIUS = 14;   // usable ship body footprint around the body centre

    // ship detection / landing state of the current leg
    private static boolean shipLanding;
    private static int verdictStreak, recoveryRockets;
    private static String lastVerdictSeen = "";
    private static String lastScanNote = "";

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
            case PAUSED_FAILURE -> "Landing failed - paused";
        };
    }

    // ------------------------------------------------------------------ start / stop

    public static void start(MinecraftClient client) {
        if (state == State.PAUSED_FAILURE) {
            resumeAfterLandingFailure(client);
            return;
        }
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
            msg(client, "Auto Elytra: all points have results - use Reactivate on a looted point, or Reset results");
            return;
        }
        startDimension = client.world.getRegistryKey();
        ticks = 0;
        lastDebugHeartbeat = 0;
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
        debug(client, "Run started. Queue points=" + remaining.size() + ", search radius=" + FlightConfig.searchRadius
                + ", hunt=" + FlightConfig.hunt + ", shulkers=" + FlightConfig.useShulkers);
    }

    public static boolean isPausedAfterLandingFailure() { return state == State.PAUSED_FAILURE; }

    public static void resumeAfterLandingFailure(MinecraftClient client) {
        if (state != State.PAUSED_FAILURE || client == null || client.player == null) return;
        if (target == null || !remaining.contains(target)) {
            state = State.IDLE;
            start(client);
            return;
        }
        landingAttempts = 0;
        landFails = 0;
        debug(client, "User resumed failed destination " + target.name + " at " + target.x + ", " + target.z);
        beginTakeoff();
        msg(client, "Auto Elytra: retrying " + target.name);
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
        landingAttempts = 0;
        shipRoofY = Double.NaN;
        nearGroundTicks = 0;
        shipApproaches = 0;
        goAroundTicks = 0;
        shipDropActive = false;
        lastLandDebug = 0;
        scoutDone = false;
        orbiting = false;
        spotSet = false;
        shipLanding = false;
        verdictStreak = 0;
        lastVerdictSeen = "";
        lastScanNote = "";
        recoveryRockets = 0;
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
                client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.ENTITY_PLAYER_LEVELUP,
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
        if (FlightConfig.debugMode && ticks - lastDebugHeartbeat >= 40) {
            lastDebugHeartbeat = ticks;
            debug(client, "Status=" + state + ", player=" + fmt(p.getX()) + "," + fmt(p.getY()) + "," + fmt(p.getZ())
                    + ", target=" + (target == null ? "none" : target.name + "@" + target.x + "," + target.z)
                    + ", speed=" + fmt(p.getVelocity().length()) + ", rockets=" + countRockets(p)
                    + ", shulkers=" + lastShulkerCount + ", verdict=" + lastShipVerdict + ", frames=" + lastFrameCount
                    + ", searchRadius=" + FlightConfig.searchRadius + ", attempts=" + landingAttempts + "/3");
        }
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
        return chestplate ? chestplateWorn(p) : elytraWorn(p);
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
            if (state == State.LAND && shipDropActive) {
                double roof = Double.isNaN(shipRoofY) ? refY : shipRoofY;
                if (Double.isNaN(gb) || p.getY() - gb > 8 || p.getY() < roof - 10) {
                    if (equipElytra(client)) {
                        shipDropActive = false;
                        debug(client, "Chestplate-drop safety net: elytra restored; re-glide");
                        client.options.jumpKey.setPressed(ticks % 2 == 0);
                        return;
                    }
                } else if (p.getY() - gb < 6) {
                    client.options.jumpKey.setPressed(false);
                    return;
                }
            }
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
        } else if (shipLanding && spotSet) {
            // Once the ship is confirmed, navigate to the hull-body midpoint,
            // not the item frame or the third shulker at the frame end.
            tx = spotX;
            tz = spotZ;
        } else if (frameTarget != null && !frameTarget.isRemoved()) {
            tx = frameTarget.getX();
            tz = frameTarget.getZ();
            refY = Double.isNaN(shipRoofY) ? Math.floor(frameTarget.getY()) : shipRoofY;
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
            } else if (shipLanding) {
                if (dist <= landStart(alt - refY)) enterLand();
            } else {
                boolean loaded = chunkLoaded(client, tx, tz);
                boolean scouting = FlightConfig.hunt && (FlightConfig.scout || FlightConfig.useShulkers)
                        && !scoutDone && frameTarget == null;
                if (scouting) {
                    chunkSeen = loaded ? chunkSeen + 1 : 0;
                    if (chunkSeen >= 10 && ticks % 5 == 0) {
                        if (FlightConfig.useShulkers) {
                            ShulkerScan scan = scanShulkers(client, target.x + 0.5, target.z + 0.5, FlightConfig.searchRadius);
                            rememberScan(scan);
                            lastFrameCount = countFrames(client, target.x + 0.5, target.z + 0.5, FlightConfig.searchRadius);
                            verdictStreak = scan.verdict.equals(lastVerdictSeen) ? verdictStreak + 1 : 1;
                            lastVerdictSeen = scan.verdict;
                            double shipDist = Math.hypot(scan.centerX - p.getX(), scan.centerZ - p.getZ());
                            debug(client, "Air scan: shulkers=" + scan.shulkers.size() + ", verdict=" + scan.verdict
                                    + " x" + verdictStreak + ", " + lastScanNote + ", frames=" + lastFrameCount
                                    + ", shipDist=" + fmt(shipDist));
                            // the same answer 8 scans in a row (about 2 s) before it is trusted
                            if (verdictStreak >= 8) {
                                if (scan.verdict.equals("untouched")) {
                                    setShipLanding(client, scan.centerX, scan.centerY, scan.centerZ, "third shulker in place");
                                } else if (scan.verdict.equals("looted") && shipDist <= 120) {
                                    skipPoint(client, "looted", "looks looted (" + lastScanNote + ")");
                                    return;
                                }
                            }
                        }
                        // An elytra in a frame is a definite positive (the server only shows it when we are close).
                        if (!scoutDone && FlightConfig.scout) {
                            ItemFrameEntity f = findElytraFrame(client, target.x + 0.5, target.z + 0.5, FlightConfig.searchRadius);
                            if (f != null) {
                                frameTarget = f;
                                setShipLanding(client, f.getX(), f.getY(), f.getZ(), "elytra frame seen");
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
            // Ship landing is a straight, committed pass over the BODY of the ship.
            // Do not orbit the shulker/frame, and do not fire rockets near the hull.
            if (shipLanding) {
                double roof = Double.isNaN(shipRoofY) ? refY : shipRoofY;
                double height = p.getY() - roof;
                double horizontalSpeed = Math.hypot(v.x, v.z);
                double desiredYaw = Math.toDegrees(Math.atan2(-(tx - p.getX()), tz - p.getZ()));
                p.setYaw((float) desiredYaw); // immediate correction: avoid the wide rate-limited orbit
                double aimY = roof + (dist > 25.0 ? 4.0 : 1.0);
                double lineHeight = p.getY() - aimY;
                double desiredPitch = Math.toDegrees(Math.atan2(lineHeight, Math.max(1.0, dist)));
                pitch = (float) clamp(desiredPitch, -12, 32);
                if (speed > 2.6 && pitch > 10) pitch = 10f;

                // Keep the old recovery rule, but only allow a rocket when genuinely far
                // from the confirmed ship. Never boost into the hull during the final approach.
                boolean needsRecovery = !Double.isNaN(roof) && !p.isOnGround()
                        && dist > 80 && p.getY() < roof - 1.5;
                if (needsRecovery && ticks - lastRocket > 60 && speed < 1.2 && recoveryRockets < 2) {
                    recoveryRockets++;
                    debug(client, "Far-below-ship recovery rocket " + recoveryRockets + "/2");
                    useRocket(client);
                }

                // Start braking much earlier. A long, straight flare gives horizontal
                // momentum time to bleed off before the player reaches the deck.
                if (dist <= 85 && height >= 2.0 && height <= 30.0 && horizontalSpeed > 0.8) {
                    pitch = -25f;
                    p.setPitch(pitch);
                    landDecision(client, dist, height, horizontalSpeed, pitch, "straight braking pass");
                    return;
                }

                // Swap as soon as the player is over the deck, low, slow, and the complete
                // predicted fall corridor is confirmed to be ship deck (never void).
                if (FlightConfig.shipLandingChestplateDrop && hasInventoryChestplate(p)
                        && dist <= 9.0 && height >= 1.5 && height <= 5.5
                        && horizontalSpeed < 0.8) {
                    String refusal = shipDropRefusal(client, p, tx, tz, roof, horizontalSpeed);
                    if (refusal == null && equipChestplate(client)) {
                        shipDropActive = true;
                        client.options.jumpKey.setPressed(false);
                        debug(client, "Ship chestplate drop SWAPPED: safe deck corridor confirmed");
                        landDecision(client, dist, height, horizontalSpeed, pitch, "chestplate swap");
                        return;
                    }
                    if (refusal == null) refusal = "chestplate inventory swap did not complete";
                    pitch = -18f;
                    p.setPitch(pitch);
                    landDecision(client, dist, height, horizontalSpeed, pitch, "swap refused: " + refusal);
                    // Do not launch another tight orbit or fire a rocket. Keep the glide
                    // controlled while the player remains over the ship; never blind-drop.
                }

                // If no chestplate is available, or the swap is disabled/refused, make a
                // controlled descent onto the body instead of circling the item frame.
                if (!FlightConfig.shipLandingChestplateDrop || !hasInventoryChestplate(p)) {
                    if (dist <= 18 && height <= 8) pitch = 22f;
                }
                p.setPitch(pitch);
                landDecision(client, dist, height, horizontalSpeed, pitch,
                        shipDropActive ? "chestplate drop active"
                                : (!hasInventoryChestplate(p) ? "normal landing fallback" : "straight ship approach"));
                return;
            }

            // Recovery remains deliberately conservative for ordinary island/waypoint landing.
            // Never rocket merely because we are below a ship while already close to its roof.
            // below a ship while already close to its roof.
            boolean needsRecovery = !Double.isNaN(shipRoofY) && !p.isOnGround()
                    && (shipLanding
                        ? (dist > 40 && p.getY() < shipRoofY - 1.5)
                        : (p.getY() < shipRoofY - 1.5 || (dist > 25 && p.getY() < shipRoofY + 8)));
            if (needsRecovery) {
                // Never fire a recovery rocket near a confirmed ship. Only allow the
                // existing two-rocket reserve when far away and already below its roof.
                if (ticks - lastRocket > 60 && speed < (shipLanding ? 1.2 : 2.0)
                        && recoveryRockets < 2) {
                    recoveryRockets++;
                    debug(client, "Below the roof approach: playerY=" + fmt(p.getY()) + ", roofY=" + fmt(shipRoofY)
                            + "; recovery rocket " + recoveryRockets + "/2");
                    useRocket(client);
                }
                turnYaw(p, Math.toDegrees(Math.atan2(-(tx - p.getX()), tz - p.getZ())), 6f);
                p.setPitch(-8f);
                return;
            }

            double roof = Double.isNaN(shipRoofY) ? refY : shipRoofY;
            double pointY = roof + 4.0;
            double height = p.getY() - roof;
            double horizontalSpeed = Math.hypot(v.x, v.z);
            double lineHeight = p.getY() - pointY;
            double desiredPitch = Math.toDegrees(Math.atan2(lineHeight, Math.max(1.0, dist)));
            pitch = (float) clamp(desiredPitch, -8, 40);
            if (speed > 2.6 && pitch > 10) pitch = 10f;

            // Once inside the final 30 blocks, do not chase an unreachable point in a tight
            // circle. Level out, fly past on a wide arc, and make another straight approach.
            if (goAroundTicks > 0 && !shipDropActive) {
                double heading = Math.toDegrees(Math.atan2(-(tx - p.getX()), tz - p.getZ()));
                turnYaw(p, heading + (shipApproaches % 2 == 0 ? -115 : 115), 3.5f);
                p.setPitch(0f);
                goAroundTicks--;
                landDecision(client, dist, height, horizontalSpeed, 0f, "go-around arc " + shipApproaches + "/3");
                return;
            }
            boolean tooHighOrFast = dist <= 30 && (height > Math.max(12, dist * 0.65) || horizontalSpeed > 3.0);
            if (tooHighOrFast && !shipDropActive) {
                if (shipApproaches >= 3) {
                    retryLanding(client, "three ship approaches could not safely reach the roof");
                    return;
                }
                shipApproaches++;
                goAroundTicks = 50;
                double heading = Math.toDegrees(Math.atan2(-(tx - p.getX()), tz - p.getZ()));
                turnYaw(p, heading + (shipApproaches % 2 == 0 ? -115 : 115), 3.5f);
                p.setPitch(0f);
                landDecision(client, dist, height, horizontalSpeed, 0f, "go-around " + shipApproaches + "/3");
                return;
            }

            // Start bleeding horizontal momentum before reaching the narrow drop window.
            // The old code only flared inside 8 blocks, which was too late to get below 0.8.
            if (FlightConfig.shipLandingChestplateDrop && shipLanding && !shipDropActive
                    && hasInventoryChestplate(p) && dist <= 32
                    && height >= 1.5 && height <= 14 && horizontalSpeed >= 0.8) {
                p.setPitch(-18f);
                turnYaw(p, Math.toDegrees(Math.atan2(-(tx - p.getX()), tz - p.getZ())), 6f);
                landDecision(client, dist, height, horizontalSpeed, p.getPitch(), "flaring to bleed speed");
                return;
            }

            if (FlightConfig.shipLandingChestplateDrop && shipLanding && !shipDropActive
                    && dist <= 8 && height >= 1.5 && height <= 5 && hasInventoryChestplate(p)) {
                String refusal = shipDropRefusal(client, p, tx, tz, roof, horizontalSpeed);
                if (refusal == null && hasInventoryChestplate(p) && equipChestplate(client)) {
                    shipDropActive = true;
                    debug(client, "Ship chestplate drop SWAPPED: all roof/impact samples solid");
                    landDecision(client, dist, height, horizontalSpeed, pitch, "swap allowed");
                    return;
                }
                if (refusal == null) refusal = hasInventoryChestplate(p) ? "swap did not complete this tick" : "no chestplate in inventory";
                landDecision(client, dist, height, horizontalSpeed, pitch, "swap refused: " + refusal);
                // A refusal is a go-around, never a blind drop over the void or roof edge.
                if (shipApproaches >= 3) {
                    retryLanding(client, "safe chestplate drop unavailable: " + refusal);
                    return;
                }
                shipApproaches++;
                goAroundTicks = 50;
                double heading = Math.toDegrees(Math.atan2(-(tx - p.getX()), tz - p.getZ()));
                turnYaw(p, heading + (shipApproaches % 2 == 0 ? -115 : 115), 3.5f);
                p.setPitch(0f);
                return;
            }

            // If a drop has started, recover immediately if the fall is no longer over nearby
            // solid ground. The normal frameCheck/teamhome and pearl rescue remain untouched.
            if (shipDropActive && !p.isOnGround()) {
                double gb = groundBelow(client, p, 40);
                if ((Double.isNaN(gb) || p.getY() - gb > 8 || p.getY() < roof - 10)
                        && equipElytra(client)) {
                    shipDropActive = false;
                    debug(client, "Chestplate-drop safety net: elytra restored; re-glide");
                    client.options.jumpKey.setPressed(ticks % 2 == 0);
                    return;
                }
            }

            // If the drop toggle is off or no chestplate exists, use the line-of-sight approach.
            if (dist > 30 && height > 10) {
                pitch = (float) clamp(desiredPitch, -8, 40);
                if (speed > 2.6 && pitch > 10) pitch = 10f;
            }
            turnYaw(p, Math.toDegrees(Math.atan2(-(tx - p.getX()), tz - p.getZ())), 6f);
            p.setPitch(pitch);
            landDecision(client, dist, height, horizontalSpeed, pitch, shipDropActive ? "drop active" : (FlightConfig.shipLandingChestplateDrop && shipLanding && !hasInventoryChestplate(p) ? "no chestplate: normal landing fallback" : "approach"));
            return;
        }

        // ---- cruising
        double err = FlightConfig.cruiseY - p.getY();
        pitch = (float) clamp(-err * 0.4, -55, 35);
        if (shipLanding) {
            // A confirmed ship is a no-firework approach zone. Retries and go-arounds
            // must not accelerate the player into the ship's hull or walls.
            firstRocketPending = false;
            rocketCheckAt = 0;
        } else if (firstRocketPending) {
            // the first rocket must work: fire it, check the speed, try again if nothing happened
            if (rocketCheckAt == 0) {
                if (ticks - lastRocket >= 5) {
                    if (rocketTries >= 3) {
                        debug(client, "First rocket failed verification after 3 attempts; stopping flight");
                        stop(client, "Auto Elytra: the first rocket does nothing (3 tries) - check your hotbar rockets");
                        return;
                    }
                    rocketSpeedBefore = speed;
                    rocketTries++;
                    debug(client, "First rocket attempt " + rocketTries + "/3 at " + fmt(p.getX()) + "," + fmt(p.getY()) + "," + fmt(p.getZ()));
                    if (useRocket(client)) rocketCheckAt = ticks + 6;
                }
            } else if (ticks >= rocketCheckAt) {
                rocketCheckAt = 0;
                if (speed >= rocketSpeedBefore + 0.1) firstRocketPending = false;
            }
        } else if (!shipLanding && speed < 1.25 && ticks - lastRocket >= 20) {
            useRocket(client);
        }
        double dx = tx - p.getX(), dz = tz - p.getZ();
        turnYaw(p, Math.toDegrees(Math.atan2(-dx, dz)), 8f);
        p.setPitch(pitch);
    }

    private static void landDecision(MinecraftClient client, double dist, double height, double speed, float pitch, String decision) {
        if (!FlightConfig.debugMode || ticks - lastLandDebug < 20) return;
        lastLandDebug = ticks;
        debug(client, "LAND distance=" + fmt(dist) + " heightRoof=" + fmt(height)
                + " horizontalSpeed=" + fmt(speed) + " pitch=" + fmt(pitch) + " decision=" + decision);
    }

    private static boolean hasInventoryChestplate(ClientPlayerEntity p) {
        PlayerScreenHandler h = p.playerScreenHandler;
        for (int s = 9; s <= 44; s++) if (isChestplate(h.getSlot(s).getStack())) return true;
        return false;
    }

    /** Null means safe. The player and predicted impact must stay over the ship deck. */
    private static String shipDropRefusal(MinecraftClient client, ClientPlayerEntity p,
                                           double tx, double tz, double roof, double horizontalSpeed) {
        if (horizontalSpeed >= 0.8) return "horizontal speed is not below 0.8";

        double under = groundBelow(client, p, 8);
        if (Double.isNaN(under) || Math.abs(under - roof) > 2.5 || p.getY() - under > 5.5)
            return "no ship deck directly below the player";

        // At this low speed, six ticks is a conservative estimate of the fall drift.
        double impactX = p.getX() + p.getVelocity().x * 6.0;
        double impactZ = p.getZ() + p.getVelocity().z * 6.0;
        if (Math.hypot(impactX - tx, impactZ - tz) > SHIP_LAND_RADIUS - 1.0)
            return "predicted impact is outside the ship body";

        // Check the actual drift line, not a huge 5x5 square at every point.
        for (int i = 0; i <= 6; i++) {
            double t = i / 6.0;
            int x = (int) Math.floor(p.getX() + (impactX - p.getX()) * t);
            int z = (int) Math.floor(p.getZ() + (impactZ - p.getZ()) * t);
            if (!chunkLoaded(client, x, z)) return "drop corridor is not loaded";
            int top = groundAt(client, x, z);
            if (top < 0 || Math.abs(top - roof) > 2
                    || (!solid(client, x, (int) Math.floor(roof) - 1, z)
                    && !solid(client, x, (int) Math.floor(roof), z))) {
                return "gap or void along predicted drop corridor";
            }
        }

        // Keep a two-block margin around the final impact point only. Requiring that
        // margin along the entire corridor rejected almost every real End Ship deck.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = (int) Math.floor(impactX) + dx;
                int z = (int) Math.floor(impactZ) + dz;
                if (!chunkLoaded(client, x, z)) return "impact safety margin is not loaded";
                int top = groundAt(client, x, z);
                if (top < 0 || Math.abs(top - roof) > 2
                        || (!solid(client, x, (int) Math.floor(roof) - 1, z)
                        && !solid(client, x, (int) Math.floor(roof), z))) {
                    return "void or roof edge inside impact safety margin";
                }
            }
        }
        return null;
    }

    /** Turns the camera towards a heading by at most maxStep degrees per tick. */
    private static void turnYaw(ClientPlayerEntity p, double desired, float maxStep) {
        float cur = p.getYaw();
        float diff = MathHelper.wrapDegrees((float) desired - cur);
        p.setYaw(cur + Math.max(-maxStep, Math.min(maxStep, diff)));
    }

    /** A ship is confirmed: aim for the middle of the hull between the rear shulker pair and third shulker. */
    private static void setShipLanding(MinecraftClient client, double x, double y, double z, String why) {
        spotX = x;
        spotZ = z;
        spotSet = true;
        shipLanding = true;
        shipApproaches = 0;
        goAroundTicks = 0;
        shipDropActive = false;
        scoutDone = true;
        firstRocketPending = false;
        rocketCheckAt = 0;
        shipRoofY = estimateShipRoofY(client, x, z, 5);
        refY = Double.isNaN(shipRoofY) ? y : shipRoofY;
        debug(client, "Ship confirmed (" + why + "): body centre=" + fmt(x) + "," + fmt(z)
                + ", roofY=" + fmt(shipRoofY));
        msg(client, "Auto Elytra: untouched ship at " + target.name + " (" + why + "), going down");
    }

    private static void enterLand() {
        state = State.LAND;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) debug(client, "Landing phase entered: player=" + fmt(client.player.getX())
                + "," + fmt(client.player.getY()) + "," + fmt(client.player.getZ())
                + ", roofY=" + fmt(shipRoofY) + ", destination=" + (target == null ? "none" : target.name));
        orbiting = false;
        nearGroundTicks = 0;
        recoveryRockets = 0;
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

    private static boolean isShipDeckTouchdown(MinecraftClient client, ClientPlayerEntity p, double dist) {
        double surface = groundBelow(client, p, 3);
        double roof = Double.isNaN(shipRoofY) ? refY : shipRoofY;
        return !Double.isNaN(surface) && !Double.isNaN(roof)
                && Math.abs(p.getY() - surface) <= 0.75
                && surface >= roof - 2.0 && surface <= roof + 6.0
                && dist <= SHIP_LAND_RADIUS;
    }

    private static void pauseUnsafeShipTouchdown(MinecraftClient client, String reason) {
        state = State.PAUSED_FAILURE;
        if (client != null && client.options != null) {
            client.options.jumpKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
        }
        debug(client, "Ship landing stopped without retry: " + reason);
        msg(client, "Auto Elytra: " + reason + " - paused safely; no retry rockets");
    }

    private static void arrived(MinecraftClient client, double dist) {
        if (emergency) {
            if (FlightConfig.landWithChestplate) equipChestplate(client);
            stop(client, "Auto Elytra: landed on the nearest island");
            return;
        }
        ClientPlayerEntity p = client.player;

        // A ship touchdown is valid only on a real block at deck height inside the hull.
        if (shipLanding) {
            double surface = groundBelow(client, p, 3);
            double roof = Double.isNaN(shipRoofY) ? refY : shipRoofY;
            boolean validDeck = isShipDeckTouchdown(client, p, dist);
            debug(client, "Ship touchdown: distance=" + fmt(dist) + ", playerY=" + fmt(p.getY())
                    + ", surfaceY=" + fmt(surface) + ", roofY=" + fmt(roof)
                    + ", validDeck=" + validDeck);
            if (validDeck) {
                found(client, "landed on the ship body");
                return;
            }
            pauseUnsafeShipTouchdown(client, "touchdown was not on the ship deck");
            return;
        }

        boolean onFrame = frameTarget != null && !frameTarget.isRemoved();
        if (onFrame && Double.isNaN(shipRoofY)) {
            shipRoofY = estimateShipRoofY(client, frameTarget.getX(), frameTarget.getZ(), 5);
        }
        double radius = onFrame ? SHIP_LAND_RADIUS : FlightConfig.landRadius;
        boolean expectedShip = FlightConfig.hunt && (onFrame || shipLanding);
        double standingSurface = expectedShip ? groundBelow(client, p, 2) : Double.NaN;
        boolean solidUnderPlayer = !expectedShip || !Double.isNaN(standingSurface);
        boolean onShipRoof = !expectedShip || (!Double.isNaN(shipRoofY)
                && p.getY() >= shipRoofY - 1.5 && dist <= radius && solidUnderPlayer);
        debug(client, "Touchdown check: position=" + fmt(p.getX()) + "," + fmt(p.getY()) + "," + fmt(p.getZ())
                + ", shipDistance=" + fmt(dist) + ", roofY=" + fmt(shipRoofY)
                + ", solidUnderPlayer=" + solidUnderPlayer + ", onShipRoof=" + onShipRoof);
        if (expectedShip && !onShipRoof) {
            String reason = Double.isNaN(shipRoofY) ? "ship roof height could not be confirmed"
                    : (!solidUnderPlayer ? "no solid ship block directly under player" : "landed below or away from the ship");
            if (onFrame) pauseUnsafeShipTouchdown(client, reason);
            else retryLanding(client, reason);
            return;
        }
        if (dist > radius) {
            String reason = "touchdown distance " + fmt(dist) + " exceeds " + fmt(radius);
            if (onFrame) pauseUnsafeShipTouchdown(client, reason);
            else retryLanding(client, reason);
            return;
        }
        if (FlightConfig.hunt) {
            if (onFrame) {
                if (isShipDeckTouchdown(client, p, dist)) found(client, null);
                else pauseUnsafeShipTouchdown(client, "elytra frame found but player is not on the ship deck");
                return;
            }
            ItemFrameEntity f = findElytraFrame(client, p.getX(), p.getZ(), FlightConfig.searchRadius);
            if (f != null) {
                frameTarget = f;
                shipRoofY = estimateShipRoofY(client, f.getX(), f.getZ(), 5);
                if (Double.isNaN(shipRoofY) || p.getY() < shipRoofY - 1.5
                        || !isShipDeckTouchdown(client, p, dist)) {
                    pauseUnsafeShipTouchdown(client, "elytra frame found but touchdown is not on the ship deck");
                    return;
                }
                found(client, null);
                return;
            }
            if (FlightConfig.useShulkers) {
                ShulkerScan scan = scanShulkers(client, target.x + 0.5, target.z + 0.5, FlightConfig.searchRadius);
                rememberScan(scan);
                debug(client, "Close shulker check: count=" + scan.shulkers.size() + ", verdict=" + scan.verdict + ", frames=" + countFrames(client, target.x + .5, target.z + .5, FlightConfig.searchRadius));
                if (scan.verdict.equals("untouched")) {
                    shipRoofY = estimateShipRoofY(client, scan.centerX, scan.centerZ, 5);
                    if (Double.isNaN(shipRoofY) || p.getY() < shipRoofY - 1.5) {
                        spotX = scan.centerX;
                        spotZ = scan.centerZ;
                        spotSet = true;
                        refY = shipRoofY;
                        pauseUnsafeShipTouchdown(client, "untouched ship detected but touchdown is below its deck");
                        return;
                    }
                    if (isShipDeckTouchdown(client, p, dist)) {
                        found(client, "three shulkers match the ship");
                    } else {
                        pauseUnsafeShipTouchdown(client, "ship detected but touchdown is not on its deck");
                    }
                    return;
                }
                if (scan.verdict.equals("looted")) {
                    double nearbyRoof = estimateShipRoofY(client, p.getX(), p.getZ(), 12);
                    if (Double.isNaN(nearbyRoof) || p.getY() < nearbyRoof - 1.5) {
                        shipRoofY = nearbyRoof;
                        pauseUnsafeShipTouchdown(client, "possible looted ship detected but touchdown is not on its deck");
                        return;
                    }
                    if (!isShipDeckTouchdown(client, p, dist)) {
                        pauseUnsafeShipTouchdown(client, "possible ship found but touchdown is not on its deck");
                        return;
                    }
                    msg(client, "Auto Elytra: " + target.name + " looks looted (shulkers missing or moved), skipped");
                    debug(client, "Destination marked looted. It stays in the queue; use Reactivate to try again.");
                    markPoint("looted");
                    remaining.remove(target);
                    enterPause(client);
                    return;
                }
            }
            double possibleRoof = estimateShipRoofY(client, target.x + 0.5, target.z + 0.5, 12);
            if (!Double.isNaN(possibleRoof) && p.getY() < possibleRoof - 1.5) {
                shipRoofY = possibleRoof;
                pauseUnsafeShipTouchdown(client, "touchdown was below the nearby ship deck; detection remains unknown");
                return;
            }
            msg(client, "Auto Elytra: " + target.name + " is unknown (no visible elytra frame or shulkers); will retry next run");
            debug(client, "Destination verdict unknown. Not marked complete.");
            remaining.remove(target);
            enterPause(client);
            return;
        }
        remaining.remove(target);
        msg(client, "Auto Elytra: arrived at " + target.name);
        enterPause(client);
    }

    /** Retry a ship landing without marking the destination complete. Three failed attempts pause the queue. */
    private static void retryLanding(MinecraftClient client, String reason) {
        if (shipLanding) {
            pauseUnsafeShipTouchdown(client, reason);
            return;
        }
        landingAttempts++;
        if (landingAttempts >= 3) {
            state = State.PAUSED_FAILURE;
            client.options.jumpKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            debug(client, "Landing attempt 3 failed: " + reason + ". Queue paused; destination remains active.");
            msg(client, "Auto Elytra: could not land on " + target.name + " after 3 tries (" + reason + "). Queue paused. Press Start to retry.");
            return;
        }
        debug(client, "Landing attempt " + landingAttempts + "/3 failed: " + reason + ". Taking off for another approach.");
        msg(client, "Auto Elytra: " + reason + " - retry " + (landingAttempts + 1) + "/3");
        beginTakeoff();
    }

    /** An elytra frame is here: remember the point, ring the alert and stop so the player takes the elytra. */
    private static void found(MinecraftClient client, String note) {
        String name = target != null ? target.name : "point";
        debug(client, "Confirmed elytra at " + name + "; marking destination and stopping queue");
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

    private static void debug(MinecraftClient client, String text) {
        if (FlightConfig.debugMode && client != null && client.player != null) {
            client.player.sendMessage(Text.literal("[Auto Elytra DEBUG] " + text), false);
        }
    }

    private static String fmt(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? "unknown" : String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * Estimate the deck level from the most common loaded surface in the local area.
     * A single raised mast, decoration, or frame must not pull the landing height upward.
     */
    private static double estimateShipRoofY(MinecraftClient client, double x, double z, int radius) {
        int side = radius * 2 + 1;
        int[] tops = new int[side * side];
        int count = 0;
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int top = groundAt(client, cx + dx, cz + dz);
                if (top >= 0) tops[count++] = top;
            }
        }
        if (count == 0) return Double.NaN;

        int bestY = -1;
        int bestCount = 0;
        for (int i = 0; i < count; i++) {
            int matches = 0;
            for (int j = 0; j < count; j++) if (tops[j] == tops[i]) matches++;
            if (matches > bestCount || (matches == bestCount && (bestY < 0 || tops[i] < bestY))) {
                bestY = tops[i];
                bestCount = matches;
            }
        }
        return bestY;
    }

    /** Drops the current point while flying and goes on to the next one. */
    private static void skipPoint(MinecraftClient client, String result, String text) {
        if (target != null) {
            msg(client, "Auto Elytra: " + target.name + " " + text + ", skipped");
            debug(client, "Skipping destination: " + text + "; result=" + result);
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

    private static double dist3(net.minecraft.entity.Entity a, net.minecraft.entity.Entity b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Ship detection from the shulkers the server sent us.
     *  - The two BACK shulkers (about 6.40 apart) mean "this is a ship".
     *  - The third shulker (next to the elytra frame, about 19.21 / 20.35 from the back pair) decides:
     *    in place = untouched, missing (killed) or moved (mined under it) = looted.
     *  - Fewer than two shulkers, or no back pair = unknown (never "looted").
     * The landing target is the midpoint between the third shulker and the rear-pair midpoint.
     */
    private static ShulkerScan scanShulkers(MinecraftClient client, double x, double z, double radius) {
        double bottom = client.world.getBottomY();
        Box box = new Box(x - radius, bottom, z - radius, x + radius, bottom + 400, z + radius);
        List<ShulkerEntity> found = client.world.getEntitiesByClass(ShulkerEntity.class, box,
                e -> !e.isRemoved());
        if (found.size() < 2) {
            lastScanNote = "fewer than 2 shulkers loaded";
            return new ShulkerScan(found, "unknown", 0, 0, 0, Double.MAX_VALUE);
        }

        ShulkerEntity pa = null, pb = null;
        double bestPair = Double.MAX_VALUE;
        for (int i = 0; i < found.size() - 1; i++) {
            for (int j = i + 1; j < found.size(); j++) {
                double err = Math.abs(dist3(found.get(i), found.get(j)) - SHIP_SHAPE[0]);
                if (err <= SHAPE_TOLERANCE && err < bestPair) {
                    bestPair = err;
                    pa = found.get(i);
                    pb = found.get(j);
                }
            }
        }
        if (pa == null) {
            lastScanNote = "no back pair (6.4 apart) among " + found.size() + " shulkers";
            return new ShulkerScan(found, "unknown", 0, 0, 0, Double.MAX_VALUE);
        }
        double pcx = (pa.getX() + pb.getX()) / 2, pcy = (pa.getY() + pb.getY()) / 2, pcz = (pa.getZ() + pb.getZ()) / 2;

        ShulkerEntity third = null;
        double bestThird = Double.MAX_VALUE;
        boolean otherNearby = false;
        for (ShulkerEntity c : found) {
            if (c == pa || c == pb) continue;
            double d1 = dist3(c, pa), d2 = dist3(c, pb);
            if (Math.hypot(c.getX() - pcx, c.getZ() - pcz) <= 45) otherNearby = true;
            double lo = Math.min(d1, d2), hi = Math.max(d1, d2);
            double e1 = Math.abs(lo - SHIP_SHAPE[1]), e2 = Math.abs(hi - SHIP_SHAPE[2]);
            if (e1 <= SHAPE_TOLERANCE && e2 <= SHAPE_TOLERANCE && e1 + e2 < bestThird) {
                bestThird = e1 + e2;
                third = c;
            }
        }
        if (third != null) {
            lastScanNote = "back pair + third in place (error " + fmt(bestThird) + ")";
            // The third shulker is near the elytra/frame end, not the middle of the hull.
            // The midpoint between it and the rear-pair midpoint is a much better deck target.
            double bodyX = (pcx + third.getX()) * 0.5;
            double bodyZ = (pcz + third.getZ()) * 0.5;
            return new ShulkerScan(found, "untouched", bodyX, third.getY(), bodyZ, bestThird);
        }
        lastScanNote = otherNearby ? "back pair found, third shulker moved (mined under it)"
                : "back pair found, third shulker missing (killed)";
        return new ShulkerScan(found, "looted", pcx, pcy, pcz, bestPair);
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
                + " | Ship: " + scan.verdict + " (" + lastScanNote + ") | Frames " + frames
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
        debug(client, "Rocket fired from hotbar slot " + slot + "; remaining=" + countRockets(p));
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
        if (state == State.PAUSED_FAILURE) lines.add("Landing failed after 3 tries - press Start to retry");
        if (state == State.LAND && !Double.isNaN(shipRoofY)) lines.add("Ship roof Y " + (int) shipRoofY + " | Attempts " + landingAttempts + "/3");
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
