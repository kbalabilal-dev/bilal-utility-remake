package com.bilalutility.flight;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Auto Elytra: flies to a queue of coordinates (nearest first), lands, waits, then goes on.
 * Everything is client side (normal movement, rocket and chat-command packets only).
 * Void safety: high cruise altitude, auto re-glide, rocket reserve, "land at the nearest island",
 * ender pearl rescue and a per-frame /teamhome watchdog.
 */
public final class FlightRunner {
    private enum State { IDLE, PREPARE, TAKEOFF, CRUISE, LAND, PAUSE }

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
    private static int groundY = -1;
    // nearest solid ground found in loaded chunks (x, z, top y); kept as "last known island"
    private static boolean islandKnown;
    private static int islandX, islandZ, islandY;
    private static float lastHealth;

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
            case LAND -> "Landing";
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
        remaining.addAll(FlightConfig.waypoints);
        startDimension = client.world.getRegistryKey();
        ticks = 0;
        rocketsUsed = 0;
        flown = 0;
        blocksPerRocket = 90;
        emergency = false;
        islandKnown = false;
        lastHealth = client.player.getHealth();
        lastX = client.player.getX();
        lastZ = client.player.getZ();
        target = null;
        state = State.PREPARE;
    }

    public static void stop(MinecraftClient client, String message) {
        state = State.IDLE;
        target = null;
        remaining.clear();
        if (client != null && client.options != null) client.options.jumpKey.setPressed(false);
        if (client != null && message != null) msg(client, message);
    }

    private static void msg(MinecraftClient client, String text) {
        if (client.player != null) client.player.sendMessage(Text.literal(text), true);
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
        client.interactionManager.clickSlot(h.syncId, src, 0, SlotActionType.PICKUP, p);
        client.interactionManager.clickSlot(h.syncId, 6, 0, SlotActionType.PICKUP, p);      // 6 = chest armor slot
        if (!h.getCursorStack().isEmpty()) {
            client.interactionManager.clickSlot(h.syncId, src, 0, SlotActionType.PICKUP, p);
        }
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
    }

    private static void beginTakeoff() {
        state = State.TAKEOFF;
        takeoffStage = 0;
        takeoffTimer = 0;
        emergency = false;
        groundY = -1;
    }

    // ------------------------------------------------------------------ take off (jump, release, jump again = glide)

    private static void takeoff(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p.isGliding()) {
            client.options.jumpKey.setPressed(false);
            state = State.CRUISE;
            return;
        }
        aim(p, target, 0f);
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

    private static void fly(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        Vec3d v = p.getVelocity();
        double speed = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
        boolean gliding = p.isGliding();

        // ---- void protection: lost the glide while in the air
        if (!gliding && !p.isOnGround()) {
            client.options.jumpKey.setPressed(ticks % 2 == 0 && v.y < 0);   // re-glide: release / press pulses
            if (FlightConfig.pearlRescue && p.getY() < FlightConfig.pearlBelowY && ticks - lastPearl > 40) {
                throwPearl(client);
            }
            return;
        }
        client.options.jumpKey.setPressed(false);
        if (!gliding && p.isOnGround() && state == State.CRUISE) {   // standing on the ground: take off again
            beginTakeoff();
            return;
        }

        // ---- safety checks every second
        if (ticks % 20 == 0) safetyChecks(client);

        // ---- where to go
        double tx = target.x, tz = target.z;
        int islandGround = -1;
        if (emergency && islandKnown) {
            tx = islandX;
            tz = islandZ;
        }
        double dist = Math.hypot(tx + 0.5 - p.getX(), tz + 0.5 - p.getZ());
        double desiredAlt = FlightConfig.cruiseY;

        int viewRadius = Math.max(48, (client.options.getViewDistance().getValue() - 1) * 16);
        if (state == State.CRUISE && dist <= viewRadius) {
            islandGround = groundAt(client, (int) Math.floor(tx), (int) Math.floor(tz));
            if (islandGround >= 0) {
                state = State.LAND;
                groundY = islandGround;
            } else if (!emergency) {
                // target is over the void (or not loaded): never descend, skip it
                remaining.remove(target);
                msg(client, "Auto Elytra: " + target.name + " has no ground, skipped");
                pickNext(client);
                if (target == null) stop(client, "Auto Elytra: queue finished");
                return;
            }
        }

        float pitch;
        if (state == State.LAND) {
            desiredAlt = groundY + 6;
            double lg = groundAt(client, (int) Math.floor(p.getX()), (int) Math.floor(p.getZ()));
            double above = lg >= 0 ? p.getY() - lg : p.getY() - groundY;
            if (above < 24) {
                pitch = (float) clamp((above - 6) * 1.0, -20, 25);                 // flare close to the ground
            } else {
                pitch = (float) clamp((p.getY() - desiredAlt) * 0.5, -10, 45);   // dive towards the island
            }
            if (p.isOnGround() || (above < 1.5 && speed < 0.5)) {
                arrived(client, dist);
                return;
            }
        } else {
            double err = desiredAlt - p.getY();
            pitch = (float) clamp(-err * 0.4, -55, 35);
            if (speed < 1.25 && gliding && ticks - lastRocket >= 20) useRocket(client);
        }
        aimXZ(p, tx, tz, pitch);
    }

    private static void arrived(MinecraftClient client, double dist) {
        if (emergency) {
            if (FlightConfig.landWithChestplate) equipChestplate(client);
            stop(client, "Auto Elytra: landed on the nearest island");
            return;
        }
        if (dist > 60) {          // landed too far from the point: take off again
            beginTakeoff();
            return;
        }
        remaining.remove(target);
        state = State.PAUSE;
        pauseLeft = Math.max(FlightConfig.pauseSeconds * 20, FlightConfig.landWithChestplate ? 100 : 0);
        swapWait = 0;
        msg(client, "Auto Elytra: arrived at " + target.name);
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
            stop(client, "Auto Elytra: queue finished");
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

    // ------------------------------------------------------------------ safety

    private static void safetyChecks(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (emergency) return;
        ItemStack elytra = p.getEquippedStack(EquipmentSlot.CHEST);
        boolean elytraLow = !elytra.isOf(Items.ELYTRA) || elytra.getMaxDamage() - elytra.getDamage() <= 12;
        boolean healthLow = p.getHealth() < 8.0F;
        double dist = Math.hypot(target.x - p.getX(), target.z - p.getZ());
        int need = (int) Math.ceil(dist / blocksPerRocket) + FlightConfig.reserveRockets;
        boolean rocketsLow = countRockets(p) < need;
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

    private static int groundAt(MinecraftClient client, int x, int z) {
        int top = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
        return top > 8 ? top : -1;
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

    private static void useRocket(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        int slot = hotbarSlot(p, Items.FIREWORK_ROCKET);
        if (slot < 0) {
            refillRockets(client);
            return;
        }
        rocketSlot = slot;
        p.getInventory().setSelectedSlot(slot);
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
        rocketsUsed++;
        lastRocket = ticks;
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

    private static void aim(ClientPlayerEntity p, FlightConfig.Waypoint w, float pitch) {
        aimXZ(p, w.x, w.z, pitch);
    }

    private static void aimXZ(ClientPlayerEntity p, double tx, double tz, float pitch) {
        double dx = tx + 0.5 - p.getX(), dz = tz + 0.5 - p.getZ();
        p.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setPitch(pitch);
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
        return lines;
    }
}
