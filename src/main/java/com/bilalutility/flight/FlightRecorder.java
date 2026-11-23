package com.bilalutility.flight;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Flight recorder + teach mode. Writes a small text log to .minecraft/bilal_flight_logs/ so the
 * real behaviour can be read later. Game data only (positions, states, decisions): no chat,
 * no server address. Cost: one short line per second while flying, one line per 4 ticks while
 * teaching, a buffered writer that flushes every 5 seconds.
 *
 * Teach mode starts when "ELYTRA FOUND" stops the queue: it records how YOU walk to the frame,
 * hit it, pick the elytra up and take off again, plus a map of the blocks around the frame.
 */
public final class FlightRecorder {
    private static BufferedWriter out;
    private static long ticks;
    private static boolean teach;
    private static long teachStart;
    private static double fx, fy, fz;
    private static int lastElytra = -1;
    private static boolean lastAttack, lastFrameHas = true, lastUse, lastLevitation;
    private static int lastSlot = -1;
    private static String lastHeld = "", lastScreen = "";
    private static BlockPos lastTarget;
    private static String lastTargetId = "";
    private static double lastPx, lastPy, lastPz;
    private static float lastHealth = -1;
    private static long glideSince = -1;

    private FlightRecorder() {}

    public static void begin(MinecraftClient client) {
        // the ship routine starts the flight itself: keep teach mode running during our own take off
        if (teach && !FlightShipRoutine.isActive()) endTeach("flight restarted");
        if (!FlightConfig.recordFlights || out != null) return;
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("bilal_flight_logs");
            Files.createDirectories(dir);
            String name = "flight-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".log";
            out = Files.newBufferedWriter(dir.resolve(name), StandardCharsets.UTF_8);
            ticks = 0;
            line("START points=" + FlightConfig.waypoints.size() + " searchRadius=" + FlightConfig.searchRadius);
        } catch (IOException e) {
            out = null;
        }
    }

    /** Ticks since teach mode started (or since the log started). Used by the ship routine for step times. */
    public static long clock() {
        return teach ? ticks - teachStart : ticks;
    }

    public static void routine(String text) {
        if (out != null) line("ROUTINE " + text);
    }

    public static void event(String text) {
        if (out != null) line("EVENT " + text);
    }

    public static void onStop(String message) {
        FlightShipRoutine.onRunnerStopped(message);   // arms the ship routine after "ELYTRA FOUND"
        if (out == null) return;
        line("STOP " + (message == null ? "" : message));
        if (!teach) close();
    }

    /** Called from FlightRunner.found(): from now on record what the player does by hand. */
    public static void startTeach(MinecraftClient client, double frameX, double frameY, double frameZ) {
        FlightShipRoutine.noteFrame(frameX, frameY, frameZ);
        if (!FlightConfig.recordFlights || client.player == null || client.world == null) return;
        if (out == null) begin(client);
        if (out == null) return;
        teach = true;
        teachStart = ticks;
        fx = frameX;
        fy = frameY;
        fz = frameZ;
        lastElytra = countElytra(client.player);
        lastAttack = false;
        lastFrameHas = true;
        lastSlot = -1;
        lastHeld = "";
        lastScreen = "";
        lastTarget = null;
        lastUse = false;
        lastLevitation = false;
        lastHealth = client.player.getHealth();
        lastPx = client.player.getX();
        lastPy = client.player.getY();
        lastPz = client.player.getZ();
        glideSince = -1;
        line("TEACH_START frame=" + f(fx) + "," + f(fy) + "," + f(fz) + " elytraInInventory=" + lastElytra);
        for (ItemFrameEntity fr : client.world.getEntitiesByClass(ItemFrameEntity.class,
                new Box(fx - 3, fy - 3, fz - 3, fx + 3, fy + 3, fz + 3), e -> !e.isRemoved())) {
            line("FRAME_FACING dir=" + fr.getHorizontalFacing() + " pos=" + f(fr.getX()) + "," + f(fr.getY()) + "," + f(fr.getZ()));
        }
        inventoryLine(client.player);
        dumpMap(client);
    }

    private static void endTeach(String why) {
        if (!teach) return;
        teach = false;
        line("TEACH_END " + why);
        if (!FlightRunner.isRunning()) close();
    }

    /** Called every client tick, also while the runner is idle (that is when teach mode records). */
    public static void tick(MinecraftClient client, String state, boolean idle) {
        if (out == null) return;
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null) return;
        ticks++;
        if (teach) {
            teachTick(client, p);       // also while the ship routine takes off (the runner is not idle then)
        } else if (ticks % 20 == 0) {
            line("S " + state + " pos=" + f(p.getX()) + "," + f(p.getY()) + "," + f(p.getZ())
                    + " v=" + f(p.getVelocity().length()) + " glide=" + p.isGliding() + " gnd=" + p.isOnGround());
        }
        if (ticks % 100 == 0) flush();
    }

    private static void teachTick(MinecraftClient client, ClientPlayerEntity p) {
        long t = ticks - teachStart;
        boolean attack = client.options.attackKey.isPressed();
        if (attack && !lastAttack) line("ATTACK t=" + t + " dist=" + f(Math.sqrt(sq(fx - p.getX()) + sq(fy - p.getEyeY()) + sq(fz - p.getZ()))));
        lastAttack = attack;
        int elytra = countElytra(p);
        if (elytra != lastElytra) {
            line("ELYTRA_COUNT t=" + t + " " + lastElytra + "->" + elytra + " pos=" + f(p.getX()) + "," + f(p.getY()) + "," + f(p.getZ()));
            lastElytra = elytra;
        }
        if (t % 10 == 0) {
            Box box = new Box(fx - 3, fy - 3, fz - 3, fx + 3, fy + 3, fz + 3);
            List<ItemFrameEntity> frames = client.world.getEntitiesByClass(ItemFrameEntity.class, box, e -> !e.isRemoved());
            boolean has = false;
            for (ItemFrameEntity fr : frames) has |= fr.getHeldItemStack().isOf(Items.ELYTRA);
            if (has != lastFrameHas) {
                line("FRAME_ELYTRA t=" + t + " " + lastFrameHas + "->" + has);
                lastFrameHas = has;
            }
        }
        trackEverything(client, p, t, attack);
        if (t % 4 == 0) {
            line("T t=" + t + " pos=" + f(p.getX()) + "," + f(p.getY()) + "," + f(p.getZ())
                    + " yaw=" + f(p.getYaw()) + " pitch=" + f(p.getPitch())
                    + " gnd=" + p.isOnGround() + " glide=" + p.isGliding() + " keys=" + keys(client)
                    + " toFrame=" + f(Math.hypot(fx - p.getX(), fz - p.getZ())) + "," + f(fy - p.getY()));
        }
        if (p.isGliding() && elytra > 0 && t > 40 && glideSince < 0) {
            glideSince = t;
            line("GLIDE_START t=" + t + " (recording 5 more seconds of the take off)");
        }
        if (glideSince >= 0 && t > glideSince + 100) endTeach("player took off");
        else if (t > 20 * 60 * 15) endTeach("timeout 15 min");
    }

    /** Everything else the player does: hotbar, item use (pearl, rocket), mining, teleports, damage, effects, screens. */
    private static void trackEverything(MinecraftClient client, ClientPlayerEntity p, long t, boolean attack) {
        int slot = p.getInventory().getSelectedSlot();
        String held = Registries.ITEM.getId(p.getMainHandStack().getItem()).toString();
        if (slot != lastSlot || !held.equals(lastHeld)) {
            line("HOTBAR t=" + t + " slot=" + slot + " held=" + held);
            lastSlot = slot;
            lastHeld = held;
        }
        boolean use = client.options.useKey.isPressed();
        if (use && !lastUse) {
            line("USE t=" + t + " item=" + held + " yaw=" + f(p.getYaw()) + " pitch=" + f(p.getPitch()) + " rel=" + rel(p.getX(), p.getY(), p.getZ()));
        }
        lastUse = use;

        // mining: which block is being broken, and when it is gone
        if (attack && client.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = bhr.getBlockPos();
            if (!bp.equals(lastTarget)) {
                lastTarget = bp.toImmutable();
                lastTargetId = Registries.BLOCK.getId(client.world.getBlockState(bp).getBlock()).toString();
                line("MINE_START t=" + t + " block=" + lastTargetId + " rel=" + rel(bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5)
                        + " held=" + held + " yaw=" + f(p.getYaw()) + " pitch=" + f(p.getPitch()));
            }
        }
        if (lastTarget != null && client.world.getBlockState(lastTarget).isAir()) {
            line("BROKE t=" + t + " block=" + lastTargetId + " rel=" + rel(lastTarget.getX() + 0.5, lastTarget.getY(), lastTarget.getZ() + 0.5));
            lastTarget = null;
        }

        // pearl / any teleport
        double jump = Math.sqrt(sq(p.getX() - lastPx) + sq(p.getY() - lastPy) + sq(p.getZ() - lastPz));
        if (jump > 6) line("TELEPORT t=" + t + " from=" + rel(lastPx, lastPy, lastPz) + " to=" + rel(p.getX(), p.getY(), p.getZ()));
        lastPx = p.getX();
        lastPy = p.getY();
        lastPz = p.getZ();

        boolean lev = p.hasStatusEffect(StatusEffects.LEVITATION);
        if (lev != lastLevitation) {
            line("LEVITATION t=" + t + " " + lev + " rel=" + rel(p.getX(), p.getY(), p.getZ()));
            lastLevitation = lev;
        }
        float hp = p.getHealth();
        if (lastHealth >= 0 && hp < lastHealth - 0.01f) line("HURT t=" + t + " hp=" + f(hp) + " lost=" + f(lastHealth - hp));
        lastHealth = hp;

        String screen = client.currentScreen == null ? "" : client.currentScreen.getClass().getSimpleName();
        if (!screen.equals(lastScreen)) {
            line("SCREEN t=" + t + " " + (screen.isEmpty() ? "closed" : screen));
            lastScreen = screen;
        }
        if (t % 20 == 0) {
            for (ShulkerEntity sh : client.world.getEntitiesByClass(ShulkerEntity.class,
                    new Box(fx - 15, fy - 15, fz - 15, fx + 15, fy + 15, fz + 15), e -> !e.isRemoved())) {
                line("SHULKER t=" + t + " rel=" + rel(sh.getX(), sh.getY(), sh.getZ()));
            }
        }
        if (t % 100 == 0) inventoryLine(p);
    }

    private static void inventoryLine(ClientPlayerEntity p) {
        int rockets = 0, pearls = 0, pickaxes = 0;
        for (int i = 0; i < 36; i++) {
            var st = p.getInventory().getStack(i);
            if (st.isOf(Items.FIREWORK_ROCKET)) rockets += st.getCount();
            else if (st.isOf(Items.ENDER_PEARL)) pearls += st.getCount();
            else if (Registries.ITEM.getId(st.getItem()).getPath().endsWith("_pickaxe")) pickaxes++;
        }
        line("INV rockets=" + rockets + " pearls=" + pearls + " pickaxes=" + pickaxes + " elytra=" + countElytra(p));
    }

    /** Position relative to the frame, so ships can be compared later. */
    private static String rel(double x, double y, double z) {
        return "(" + f(x - fx) + "," + f(y - fy) + "," + f(z - fz) + ")";
    }

    /** 21x21 map around the frame, 6 height levels, '#' = solid, '.' = air. Rows go north to south. */
    private static void dumpMap(MinecraftClient client) {
        int cx = (int) Math.floor(fx), cz = (int) Math.floor(fz), cy = (int) Math.floor(fy);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dy = 3; dy >= -2; dy--) {
            line("MAP y=" + (cy + dy) + " (center = frame x=" + cx + " z=" + cz + ")");
            for (int dz = -10; dz <= 10; dz++) {
                StringBuilder sb = new StringBuilder();
                for (int dx = -10; dx <= 10; dx++) {
                    pos.set(cx + dx, cy + dy, cz + dz);
                    boolean solid = !client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty();
                    sb.append(solid ? '#' : '.');
                }
                line("M " + sb);
            }
        }
    }

    private static String keys(MinecraftClient c) {
        StringBuilder sb = new StringBuilder();
        if (c.options.forwardKey.isPressed()) sb.append('W');
        if (c.options.leftKey.isPressed()) sb.append('A');
        if (c.options.backKey.isPressed()) sb.append('S');
        if (c.options.rightKey.isPressed()) sb.append('D');
        if (c.options.jumpKey.isPressed()) sb.append('J');
        if (c.options.sneakKey.isPressed()) sb.append('C');
        if (c.options.sprintKey.isPressed()) sb.append('R');
        if (c.options.attackKey.isPressed()) sb.append('L');
        if (c.options.useKey.isPressed()) sb.append('U');
        return sb.toString();
    }

    private static int countElytra(ClientPlayerEntity p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getStack(i).isOf(Items.ELYTRA)) n += p.getInventory().getStack(i).getCount();
        }
        return n;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static void line(String s) {
        if (out == null) return;
        try {
            out.write(s);
            out.write('\n');
        } catch (IOException e) {
            out = null;
        }
    }

    private static void flush() {
        try {
            if (out != null) out.flush();
        } catch (IOException e) {
            out = null;
        }
    }

    private static void close() {
        try {
            if (out != null) {
                out.flush();
                out.close();
            }
        } catch (IOException ignored) {
        }
        out = null;
        teach = false;
    }
}
