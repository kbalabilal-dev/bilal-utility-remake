package com.bilalutility.flight;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Ship routine: after "ELYTRA FOUND" it does  land -> take elytra -> leave -> fly again.
 * All numbers come from the 3 recorded ships (log 20261122). Local coordinates:
 *   a    = forward, away from the ship wall (the frame's facing)
 *   left = to the left when you look along "a"
 *   h    = height relative to the frame entity (for blocks: bottom edge of the block, like the log)
 * Modes: Off / Assisted (one key press per step) / Full auto. Dry run = only checks and prints.
 */
public final class FlightShipRoutine {
    public enum Step {
        EQUIP_PICKAXE("Hold the pickaxe", 120),
        DROP("Walk to the drop block and fall in", 900),
        DIG_UNDER_SHULKER("Break the block under the shulker", 500),
        TAKE_ELYTRA("Hit the frame and take the elytra", 700),
        DIG_EXIT("Dig the exit tunnel", 700),
        PEARL_OUT("Pearl to the ground", 900),
        TAKEOFF("Take off and fly again", 700);

        final String text;
        final int timeout;

        Step(String text, int timeout) {
            this.text = text;
            this.timeout = timeout;
        }
    }

    private enum Phase { IDLE, WAIT_KEY, DELAY, RUN, ABORTED, DONE }

    private static final Step[] STEPS = Step.values();
    private static final Random RND = new Random();

    // planned blocks, local (a, left, h)
    private static final double[] DROP_BLOCK = {3.5, 1.0, 1.5};
    private static final double[] UNDER_BLOCK = {1.5, 0.0, -2.5};
    private static final double[] EXIT_UP = {-0.5, 1.0, 0.5};
    private static final double[] EXIT_LOW = {-0.5, 1.0, -0.5};
    private static final double[] EXIT_FLOOR = {-0.5, 1.0, -1.5};
    // standing spots, local (a, left, feet height)
    private static final double[] SPOT_FRAME = {0.7, 0.2, -1.5};
    private static final double[] SPOT_DIG = {0.8, 1.2, -0.5};
    private static final double[] SPOT_PEARL = {-0.8, 1.3, -0.5};
    // where to aim on a block (offsets from its centre), tried one after the other
    private static final double[][] AIM = {{0, 0, 0}, {0, 0.35, 0}, {0.35, 0, 0}, {-0.35, 0, 0}, {0, 0, 0.35}, {0, 0, -0.35}, {0, -0.35, 0}};

    private static final int W_MOVING = 0, W_ARRIVED = 1, W_UNSAFE = 2, W_STUCK = 3;
    private static final int M_WORKING = 0, M_DONE = 1, M_FAIL = 2;
    private static final double MAX_WALK_DROP = 3.3;
    private static final float TURN_BASE = 11f, TURN_RAND = 9f;

    private static Phase phase = Phase.IDLE;
    private static int stepIndex;
    private static long now;
    private static long stepStartNow, stepStartClock;
    private static int stepTicks, sub, subTick, waitLeft = -1, delayLeft, alertLeft, airTicks, levTicks, doneHold;
    private static int stuckTicks;
    private static double walkBest = 1e9;
    private static String abortReason = "", lastLine = "";
    private static RegistryKey<World> dimension;
    private static ItemFrameEntity frame;
    private static double fx, fy, fz;
    private static int fdx, fdz, ldx, ldz;
    private static String facingName = "";
    private static boolean pendingFrame;
    private static double pendX, pendY, pendZ;
    // mining
    private static int mineTicks, aimIdx, aimMiss;
    private static String mineFail = "";
    // step data
    private static int elytraBefore, hitTries, aimOk, pearlBefore;
    private static long throwNow;
    private static PearlPlan plan;
    // shulker bullet defense
    private static ShulkerBulletEntity bullet;
    private static int bulletCd, defendTicks, bulletsStopped;
    private static boolean bulletLogged;
    private static double lastPx, lastPy, lastPz;

    private static final class PearlPlan {
        float yaw, pitch;
        double dist, cost;
        double landX, landY, landZ;
    }

    private static final class Impact {
        Vec3d free;
        BlockPos hit;
    }

    private FlightShipRoutine() {}

    // ------------------------------------------------------------------ state for other classes

    public static boolean isActive() {
        return phase == Phase.WAIT_KEY || phase == Phase.DELAY || phase == Phase.RUN;
    }

    public static boolean isVisible() {
        return phase != Phase.IDLE;
    }

    public static float progress() {
        if (phase == Phase.IDLE) return 0f;
        if (phase == Phase.DONE) return 1f;
        return Math.min(1f, stepIndex / (float) STEPS.length);
    }

    public static List<String> hudLines() {
        List<String> l = new ArrayList<>();
        String mode = FlightConfig.routineMode == 2 ? "FULL AUTO" : "ASSISTED";
        l.add("Ship routine: " + mode + (FlightConfig.routineDryRun ? "  (DRY RUN - nothing is done)" : ""));
        if (phase == Phase.DONE) {
            l.add("Finished. " + lastLine);
            return l;
        }
        Step s = STEPS[Math.min(stepIndex, STEPS.length - 1)];
        l.add("Step " + (stepIndex + 1) + "/" + STEPS.length + ": " + s.text);
        switch (phase) {
            case WAIT_KEY -> l.add("Press [" + FlightClient.stepKeyText() + "] to do this step");
            case DELAY -> l.add("Next step starts in a moment...");
            case RUN -> l.add("Working... " + (stepTicks / 20) + " s   (sneak = stop)");
            case ABORTED -> {
                l.add("STOPPED: " + abortReason);
                l.add("You have control. Fix it, then press [" + FlightClient.stepKeyText() + "] to retry this step");
            }
            default -> { }
        }
        if (!lastLine.isEmpty() && phase != Phase.ABORTED) l.add("Last: " + lastLine);
        if (facingName.length() > 0) l.add("Frame faces " + facingName + "  Y " + (int) fy);
        return l;
    }

    // ------------------------------------------------------------------ arming (called from FlightRecorder)

    public static void noteFrame(double x, double y, double z) {
        pendingFrame = true;
        pendX = x;
        pendY = y;
        pendZ = z;
    }

    public static void onRunnerStopped(String message) {
        boolean found = pendingFrame && message != null && message.startsWith("ELYTRA FOUND");
        pendingFrame = false;
        if (!found || FlightConfig.routineMode == 0) return;
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player == null || c.world == null) return;
        arm(c);
    }

    private static void arm(MinecraftClient c) {
        phase = Phase.IDLE;
        stepIndex = 0;
        lastLine = "";
        abortReason = "";
        facingName = "";
        dimension = c.world.getRegistryKey();
        frame = null;
        double best = 9.0;
        Box box = new Box(pendX - 3, pendY - 3, pendZ - 3, pendX + 3, pendY + 3, pendZ + 3);
        for (ItemFrameEntity fr : c.world.getEntitiesByClass(ItemFrameEntity.class, box, e -> !e.isRemoved())) {
            double d = sq(fr.getX() - pendX) + sq(fr.getY() - pendY) + sq(fr.getZ() - pendZ);
            if (d < best) {
                best = d;
                frame = fr;
            }
        }
        if (frame == null) {
            abort(c, "frame facing missing (no item frame found at the ship)");
            return;
        }
        Direction dir = frame.getHorizontalFacing();
        fdx = dir.getOffsetX();
        fdz = dir.getOffsetZ();
        if (fdx == 0 && fdz == 0) {
            abort(c, "frame facing missing (the frame does not face north/south/east/west)");
            return;
        }
        ldx = fdz;
        ldz = -fdx;
        fx = frame.getX();
        fy = frame.getY();
        fz = frame.getZ();
        facingName = dir.toString();
        ClientPlayerEntity p = c.player;
        double hd = Math.hypot(p.getX() - fx, p.getZ() - fz);
        FlightRecorder.routine("ROUTINE_START mode=" + FlightConfig.routineMode + " dry=" + FlightConfig.routineDryRun
                + " frame=" + fmt(fx) + "," + fmt(fy) + "," + fmt(fz) + " facing=" + facingName
                + " player=" + local(p.getX(), p.getY(), p.getZ()));
        if (p.getY() < fy + 3.0 || p.getY() > fy + 9.0 || hd > 16.0) {
            abort(c, "you are not on the ship roof (your Y " + fmt(p.getY()) + ", frame Y " + fmt(fy) + ")");
            return;
        }
        if (FlightConfig.routineMode == 2) {
            phase = Phase.DELAY;
            delayLeft = 50 + RND.nextInt(40);
        } else {
            phase = Phase.WAIT_KEY;
        }
        say(c, "Ship routine ready - " + (FlightConfig.routineMode == 2 ? "full auto starts soon" : "press the step key"));
    }

    // ------------------------------------------------------------------ keys and ticks

    public static void onStepKey(MinecraftClient c) {
        if (c.player == null || c.world == null || c.currentScreen != null) return;
        switch (phase) {
            case WAIT_KEY, DELAY -> beginStep(c, "key");
            case ABORTED -> {
                if (frame != null) beginStep(c, "retry");
            }
            default -> { }
        }
    }

    public static void cancel(MinecraftClient c, String text) {
        if (!isActive()) return;
        releaseKeys(c);
        phase = Phase.IDLE;
        FlightRecorder.routine("ROUTINE_END result=cancelled");
        if (text != null) say(c, text);
    }

    public static void tick(MinecraftClient c) {
        now++;
        if (alertLeft > 0) {
            alertLeft--;
            if (alertLeft % 10 == 0 && c.player != null) {
                c.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.ENTITY_PLAYER_LEVELUP,
                        alertLeft % 20 == 0 ? 1.0f : 1.4f));
            }
        }
        if (phase == Phase.IDLE) return;
        if (phase == Phase.DONE) {
            if (--doneHold <= 0) phase = Phase.IDLE;
            return;
        }
        ClientPlayerEntity p = c.player;
        if (p == null || c.world == null || c.world.getRegistryKey() != dimension) {
            phase = Phase.IDLE;
            releaseKeys(c);
            return;
        }
        if (phase == Phase.ABORTED) {
            if (FlightRunner.isRunning()) phase = Phase.IDLE;     // you started a flight yourself
            return;
        }
        if (c.currentScreen == null && c.options.sneakKey.isPressed()) {
            abort(c, "stopped by you (sneak)");
            return;
        }
        boolean takeoffRunning = phase == Phase.RUN && STEPS[stepIndex] == Step.TAKEOFF;
        if (FlightRunner.isRunning() && !takeoffRunning) {
            cancel(c, "Ship routine cancelled: a flight was started");
            return;
        }
        if (phase == Phase.DELAY) {
            if (--delayLeft <= 0) beginStep(c, "auto");
        } else if (phase == Phase.RUN) {
            run(c, p);
        }
    }

    // ------------------------------------------------------------------ step control

    private static void beginStep(MinecraftClient c, String trigger) {
        ClientPlayerEntity p = c.player;
        Step s = STEPS[stepIndex];
        stepStartNow = now;
        stepStartClock = FlightRecorder.clock();
        stepTicks = 0;
        airTicks = 0;
        levTicks = 0;
        elytraBefore = countElytra(p);
        hitTries = 0;
        plan = null;
        bullet = null;
        bulletCd = 0;
        defendTicks = 0;
        mineReset();
        go(0);
        releaseKeys(c);
        FlightRecorder.routine("STEP_BEGIN step=" + s + " t=" + stepStartClock + " trigger=" + trigger
                + " dry=" + FlightConfig.routineDryRun + " pos=" + local(p.getX(), p.getY(), p.getZ())
                + " hp=" + fmt(p.getHealth()));
        String why = precheck(c, p, s);
        if (why != null) {
            abort(c, why);
            return;
        }
        if (FlightConfig.routineDryRun) {
            dryRun(c, p, s);
            return;
        }
        why = positionCheck(p, s);
        if (why != null) {
            abort(c, why);
            return;
        }
        phase = Phase.RUN;
        say(c, "Ship routine: " + s.text);
    }

    private static void finishStep(MinecraftClient c, String result, String detail) {
        Step s = STEPS[stepIndex];
        releaseKeys(c);
        FlightRecorder.routine("STEP_END step=" + s + " start=" + stepStartClock + " end=" + FlightRecorder.clock()
                + " ticks=" + (now - stepStartNow) + " result=" + result + " " + detail);
        lastLine = "step " + (stepIndex + 1) + " " + s + " = " + result;
        stepIndex++;
        if (stepIndex >= STEPS.length) {
            phase = Phase.DONE;
            doneHold = 200;
            alertLeft = 30;
            FlightRecorder.routine("ROUTINE_END result=ok");
            say(c, "Ship routine finished");
            return;
        }
        if (FlightConfig.routineMode == 2) {
            phase = Phase.DELAY;
            delayLeft = 14 + RND.nextInt(28);
        } else {
            phase = Phase.WAIT_KEY;
            say(c, "Step done. Press [" + FlightClient.stepKeyText() + "]: " + STEPS[stepIndex].text);
        }
    }

    private static void abort(MinecraftClient c, String reason) {
        releaseKeys(c);
        abortReason = reason;
        Step s = STEPS[Math.min(stepIndex, STEPS.length - 1)];
        ClientPlayerEntity p = c == null ? null : c.player;
        FlightRecorder.routine("STEP_END step=" + s + " start=" + stepStartClock + " end=" + FlightRecorder.clock()
                + " result=ABORT reason=" + reason.replace(' ', '_')
                + (p != null && frame != null ? " pos=" + local(p.getX(), p.getY(), p.getZ()) : ""));
        phase = Phase.ABORTED;
        alertLeft = 100;
        if (p != null) {
            p.sendMessage(Text.literal("Ship routine STOPPED: " + reason + " - you have control. Fix it, then press the step key to retry."), false);
        }
    }

    private static void go(int newSub) {
        sub = newSub;
        subTick = 0;
        waitLeft = -1;
        walkBest = 1e9;
        stuckTicks = 0;
        aimOk = 0;
    }

    /** Human pause: returns true once, after a random number of ticks between min and max. */
    private static boolean waited(int min, int max) {
        if (waitLeft < 0) waitLeft = min + RND.nextInt(max - min + 1);
        if (waitLeft > 0) {
            waitLeft--;
            return false;
        }
        waitLeft = -1;
        return true;
    }

    // ------------------------------------------------------------------ checks before a step

    private static String precheck(MinecraftClient c, ClientPlayerEntity p, Step s) {
        if (frame == null || frame.isRemoved()) return "the item frame is gone";
        switch (s) {
            case EQUIP_PICKAXE -> {
                if (pickaxeSlot(p) < 0) return "no pickaxe in the hotbar";
                if (hotbarSlot(p, Items.ENDER_PEARL) < 0) return "no ender pearl in the hotbar";
                if (FlightConfig.pearlRescue && countItem(p, Items.ENDER_PEARL) < 2) {
                    return "you need 2 ender pearls: one for the exit and one for the void rescue";
                }
            }
            case DROP -> {
                BlockPos d = blockAt(DROP_BLOCK);
                BlockState st = c.world.getBlockState(d);
                if (st.isAir()) return "the drop block is already air at " + blockLocal(d);
                if (!isPurpur(st)) return "unexpected block " + blockId(c, d) + " at the drop block " + blockLocal(d);
            }
            case DIG_UNDER_SHULKER -> {
                BlockState st = c.world.getBlockState(blockAt(UNDER_BLOCK));
                if (!st.isAir() && !isPurpur(st)) return "unexpected block " + blockId(c, blockAt(UNDER_BLOCK)) + " under the shulker";
            }
            case TAKE_ELYTRA -> {
                if (!frame.getHeldItemStack().isOf(Items.ELYTRA)) return "the frame has no elytra";
            }
            case DIG_EXIT -> {
                for (double[] g : new double[][]{EXIT_UP, EXIT_LOW}) {
                    BlockState st = c.world.getBlockState(blockAt(g));
                    if (!st.isAir() && !isPurpur(st)) return "unexpected block " + blockId(c, blockAt(g)) + " in the exit tunnel";
                }
                if (!solid(c, blockAt(EXIT_FLOOR))) return "the tunnel has no floor block";
            }
            case PEARL_OUT -> {
                if (hotbarSlot(p, Items.ENDER_PEARL) < 0) return "no ender pearl in the hotbar";
            }
            case TAKEOFF -> {
                if (FlightRunner.isRunning()) return "a flight is already running";
                if (hotbarSlot(p, Items.FIREWORK_ROCKET) < 0) return "no firework rockets in the hotbar";
                if (FlightConfig.pearlRescue && hotbarSlot(p, Items.ENDER_PEARL) < 0) return "no ender pearl in the hotbar (pearl rescue is on)";
            }
        }
        return null;
    }

    private static String positionCheck(ClientPlayerEntity p, Step s) {
        float need = s == Step.DROP ? 10f : 8f;
        if (p.getHealth() < need) return "health " + fmt(p.getHealth()) + " is below " + (int) need;
        if (s == Step.DROP) {
            boolean inside = p.getY() < fy - 1.0;
            double hd = Math.hypot(p.getX() - fx, p.getZ() - fz);
            if (!inside && (p.getY() < fy + 3.0 || hd > 16.0)) return "you are not on the ship roof";
        }
        return null;
    }

    private static void dryRun(MinecraftClient c, ClientPlayerEntity p, Step s) {
        List<String> info = new ArrayList<>();
        switch (s) {
            case EQUIP_PICKAXE -> info.add("would hold hotbar slot " + (pickaxeSlot(p) + 1) + " (pickaxe)");
            case DROP -> {
                BlockPos d = blockAt(DROP_BLOCK);
                info.add("would walk to the drop block " + blockText(c, DROP_BLOCK));
                info.add("planned fall: " + fmt(plannedFall(c, d)) + " blocks (expected about 5)");
            }
            case DIG_UNDER_SHULKER -> info.add("would break " + blockText(c, UNDER_BLOCK));
            case TAKE_ELYTRA -> {
                info.add("would stand at a=" + fmt(SPOT_FRAME[0]) + " left=" + fmt(SPOT_FRAME[1]) + " and hit the frame (facing " + facingName + ")");
                info.add("elytra in inventory now: " + elytraBefore);
            }
            case DIG_EXIT -> {
                info.add("would break " + blockText(c, EXIT_UP));
                info.add("would break " + blockText(c, EXIT_LOW));
                info.add("tunnel floor " + blockText(c, EXIT_FLOOR));
            }
            case PEARL_OUT -> {
                PearlPlan pl = scanPearl(c, theoryEye());
                if (pl == null) {
                    abort(c, "no safe ground found for the pearl (about 40 to 70 blocks back-left and well below)");
                    return;
                }
                info.add("pearl: yaw " + fmt(pl.yaw) + " pitch " + fmt(pl.pitch) + " lands " + fmt(pl.dist) + " blocks away at Y " + fmt(pl.landY)
                        + " (" + local(pl.landX, pl.landY, pl.landZ) + ")");
            }
            case TAKEOFF -> info.add("would start the flight (existing take off logic from the ground)");
        }
        for (String line : info) {
            p.sendMessage(Text.literal("[dry run] " + s + ": " + line), false);
        }
        finishStep(c, "DRY", "info=" + String.join(" | ", info).replace(' ', '_'));
    }

    // ------------------------------------------------------------------ running a step

    private static void run(MinecraftClient c, ClientPlayerEntity p) {
        Step s = STEPS[stepIndex];
        if (c.currentScreen != null) {
            releaseKeys(c);
            return;
        }
        if (p.getHealth() < 8.0f) {
            abort(c, "health below 8 (" + fmt(p.getHealth()) + ")");
            return;
        }
        boolean lev = p.hasStatusEffect(StatusEffects.LEVITATION);
        boolean acting = s != Step.TAKEOFF && !(s == Step.PEARL_OUT && sub >= 4);
        if (acting && (p.isOnGround() || lev) && defend(c, p, lev)) {
            stepTicks++;                                  // defending also counts for the step timeout
            if (stepTicks > s.timeout) abort(c, "timeout in step " + s);
            return;
        }
        if (lev && acting) {
            releaseKeys(c);
            if (++levTicks > 400) abort(c, "levitation lasts too long");
            return;
        }
        boolean fallAllowed = (s == Step.DROP && sub >= 3) || (s == Step.PEARL_OUT && sub >= 4) || s == Step.TAKEOFF;
        if (!fallAllowed) {
            if (p.isOnGround()) airTicks = 0;
            else airTicks++;
            if (airTicks > 18) {
                abort(c, "falling");
                return;
            }
            if (p.getY() < fy - 4.0) {
                abort(c, "below the ship (Y " + fmt(p.getY()) + ")");
                return;
            }
        }
        stepTicks++;
        if (stepTicks > s.timeout) {
            abort(c, "timeout in step " + s);
            return;
        }
        subTick++;
        switch (s) {
            case EQUIP_PICKAXE -> stepEquip(c, p);
            case DROP -> stepDrop(c, p);
            case DIG_UNDER_SHULKER -> stepUnder(c, p);
            case TAKE_ELYTRA -> stepTake(c, p);
            case DIG_EXIT -> stepExit(c, p);
            case PEARL_OUT -> stepPearl(c, p);
            case TAKEOFF -> stepTakeoff(c, p);
        }
    }

    private static void stepEquip(MinecraftClient c, ClientPlayerEntity p) {
        int slot = pickaxeSlot(p);
        if (slot < 0) {
            abort(c, "no pickaxe in the hotbar");
            return;
        }
        if (sub == 0) {
            if (!waited(4, 11)) return;
            p.getInventory().setSelectedSlot(slot);
            go(1);
            return;
        }
        if (isPickaxe(p.getMainHandStack())) finishStep(c, "OK", "slot=" + (slot + 1));
        else if (subTick > 20) abort(c, "could not hold the pickaxe");
    }

    private static void stepDrop(MinecraftClient c, ClientPlayerEntity p) {
        BlockPos d = blockAt(DROP_BLOCK);
        double tx = d.getX() + 0.5, tz = d.getZ() + 0.5, standY = fy + DROP_BLOCK[2] + 1.0;
        switch (sub) {
            case 0 -> {
                if (p.getY() < fy - 1.0 && p.isOnGround()) {
                    finishStep(c, "OK", "already_inside_the_ship");
                    return;
                }
                int r = walkTo(c, p, tx, tz, 0.15, false);
                if (r == W_UNSAFE) abort(c, "unsafe: a drop of 4 blocks or more is ahead");
                else if (r == W_STUCK) abort(c, "stuck on the way to the drop block");
                else if (r == W_ARRIVED) {
                    if (Math.abs(p.getY() - standY) > 0.7) {
                        abort(c, "not standing on the drop block (Y " + fmt(p.getY()) + ", expected " + fmt(standY) + ")");
                    } else {
                        go(1);
                    }
                }
            }
            case 1 -> {
                if (!waited(4, 9)) return;
                String why = dropChecks(c, p, d);
                if (why != null) {
                    abort(c, why);
                    return;
                }
                mineReset();
                go(2);
            }
            case 2 -> {
                int m = mine(c, p, d);
                if (m == M_FAIL) abort(c, mineFail);
                else if (m == M_DONE) go(3);
            }
            default -> {
                // falling through the hole (planned 5 block fall, 2 hp)
                if (p.isOnGround() && subTick > 3) {
                    if (p.getY() <= fy - 1.7 && p.getY() >= fy - 3.4) {
                        finishStep(c, "OK", "landed=" + local(p.getX(), p.getY(), p.getZ()) + " hp=" + fmt(p.getHealth()));
                    } else if (p.getY() > fy + 1.0 && subTick > 6) {
                        walkTo(c, p, tx, tz, 0.08, true);        // not centred: nudge into the hole
                    } else if (subTick > 10) {
                        abort(c, "landed at an unexpected height (Y " + fmt(p.getY()) + ")");
                    }
                }
            }
        }
    }

    private static void stepUnder(MinecraftClient c, ClientPlayerEntity p) {
        BlockPos u = blockAt(UNDER_BLOCK);
        if (sub == 0) {
            if (c.world.getBlockState(u).isAir()) {
                finishStep(c, "OK", "block_already_air");
                return;
            }
            if (!waited(5, 12)) return;
            String why = standCheck(c, p);
            if (why != null) {
                abort(c, why);
                return;
            }
            mineReset();
            go(1);
            return;
        }
        int m = mine(c, p, u);
        if (m == M_FAIL) abort(c, mineFail);
        else if (m == M_DONE) finishStep(c, "OK", "broke=" + blockLocal(u));
    }

    private static void stepTake(MinecraftClient c, ClientPlayerEntity p) {
        if (frame.isRemoved()) {
            abort(c, "the item frame is gone");
            return;
        }
        switch (sub) {
            case 0 -> {
                int r = walkTo(c, p, wx(SPOT_FRAME[0], SPOT_FRAME[1]), wz(SPOT_FRAME[0], SPOT_FRAME[1]), 0.3, false);
                if (r == W_UNSAFE) abort(c, "unsafe: a drop of 4 blocks or more is ahead");
                else if (r == W_STUCK) abort(c, "stuck on the way to the frame");
                else if (r == W_ARRIVED) {
                    if (Math.abs(p.getY() - (fy + SPOT_FRAME[2])) > 0.7) abort(c, "not on the ledge in front of the frame");
                    else go(1);
                }
            }
            case 1 -> {
                String why = standCheck(c, p);
                if (why != null) {
                    abort(c, why);
                    return;
                }
                double err = look(p, new Vec3d(fx, fy, fz));
                boolean on = c.crosshairTarget instanceof EntityHitResult eh && eh.getEntity() == frame;
                if (err < 1.0 && on) {
                    if (waited(3, 8)) {
                        c.interactionManager.attackEntity(p, frame);
                        p.swingHand(Hand.MAIN_HAND);
                        hitTries++;
                        go(2);
                    }
                } else if (subTick > 100) {
                    abort(c, "cannot aim at the frame");
                }
            }
            default -> {
                int cnt = countElytra(p);
                if (cnt > elytraBefore) {
                    finishStep(c, "OK", "elytra=" + elytraBefore + "->" + cnt + " after_hit_ticks=" + subTick
                            + " stand=" + local(p.getX(), p.getY(), p.getZ()));
                    return;
                }
                if (subTick > 60) {
                    if (frame.getHeldItemStack().isOf(Items.ELYTRA) && hitTries < 3) go(1);
                    else abort(c, frame.getHeldItemStack().isOf(Items.ELYTRA) ? "the frame still has the elytra" : "the elytra was not picked up");
                }
            }
        }
    }

    private static void stepExit(MinecraftClient c, ClientPlayerEntity p) {
        BlockPos t1 = blockAt(EXIT_UP), t2 = blockAt(EXIT_LOW);
        switch (sub) {
            case 0 -> {
                int r = walkTo(c, p, wx(SPOT_DIG[0], SPOT_DIG[1]), wz(SPOT_DIG[0], SPOT_DIG[1]), 0.3, false);
                if (r == W_UNSAFE) abort(c, "unsafe: a drop of 4 blocks or more is ahead");
                else if (r == W_STUCK) abort(c, "stuck on the way to the tunnel");
                else if (r == W_ARRIVED) {
                    if (Math.abs(p.getY() - (fy + SPOT_DIG[2])) > 0.7) abort(c, "not at the tunnel spot (Y " + fmt(p.getY()) + ")");
                    else go(1);
                }
            }
            case 1, 3 -> {
                if (!waited(3, 9)) return;
                String why = standCheck(c, p);
                if (why != null) {
                    abort(c, why);
                    return;
                }
                mineReset();
                go(sub + 1);
            }
            case 2 -> {
                int m = c.world.getBlockState(t1).isAir() ? M_DONE : mine(c, p, t1);
                if (m == M_FAIL) abort(c, mineFail);
                else if (m == M_DONE) go(3);
            }
            default -> {
                int m = c.world.getBlockState(t2).isAir() ? M_DONE : mine(c, p, t2);
                if (m == M_FAIL) abort(c, mineFail);
                else if (m == M_DONE) {
                    if (!solid(c, blockAt(EXIT_FLOOR))) abort(c, "the tunnel has no floor block");
                    else finishStep(c, "OK", "broke=" + blockLocal(t1) + "," + blockLocal(t2));
                }
            }
        }
    }

    private static void stepPearl(MinecraftClient c, ClientPlayerEntity p) {
        int slot = hotbarSlot(p, Items.ENDER_PEARL);
        if (slot < 0 && sub < 4) {
            abort(c, "no ender pearl in the hotbar");
            return;
        }
        switch (sub) {
            case 0 -> {
                int r = walkTo(c, p, wx(SPOT_PEARL[0], SPOT_PEARL[1]), wz(SPOT_PEARL[0], SPOT_PEARL[1]), 0.3, false);
                if (r == W_UNSAFE) abort(c, "unsafe: a drop of 4 blocks or more is ahead");
                else if (r == W_STUCK) abort(c, "stuck on the way to the pearl spot");
                else if (r == W_ARRIVED) {
                    if (Math.abs(p.getY() - (fy + SPOT_PEARL[2])) > 0.7) abort(c, "not at the pearl spot (Y " + fmt(p.getY()) + ")");
                    else go(1);
                }
            }
            case 1 -> {
                if (!waited(5, 12)) return;
                String why = standCheck(c, p);
                if (why != null) {
                    abort(c, why);
                    return;
                }
                plan = scanPearl(c, new Vec3d(p.getX(), p.getEyeY() - 0.1, p.getZ()));
                if (plan == null) {
                    abort(c, "no safe ground found for the pearl (about 40 to 70 blocks back-left and well below)");
                    return;
                }
                FlightRecorder.routine("PEARL_PLAN yaw=" + fmt(plan.yaw) + " pitch=" + fmt(plan.pitch) + " dist=" + fmt(plan.dist)
                        + " land=" + local(plan.landX, plan.landY, plan.landZ) + " from=" + local(p.getX(), p.getY(), p.getZ()));
                go(2);
            }
            case 2 -> {
                if (subTick == 1) p.getInventory().setSelectedSlot(slot);
                if (waited(6, 12)) go(3);
            }
            case 3 -> {
                if (!p.getMainHandStack().isOf(Items.ENDER_PEARL)) {
                    p.getInventory().setSelectedSlot(slot);
                    return;
                }
                double err = lookAngles(p, plan.yaw, plan.pitch);
                aimOk = err < 0.3 ? aimOk + 1 : 0;
                if (aimOk >= 4 && p.isOnGround() && waited(2, 6)) {
                    pearlBefore = countItem(p, Items.ENDER_PEARL);
                    c.interactionManager.interactItem(p, Hand.MAIN_HAND);
                    throwNow = now;
                    lastPx = p.getX();
                    lastPy = p.getY();
                    lastPz = p.getZ();
                    FlightRecorder.routine("PEARL_THROW yaw=" + fmt(p.getYaw()) + " pitch=" + fmt(p.getPitch()) + " pos=" + local(p.getX(), p.getY(), p.getZ()));
                    go(4);
                }
            }
            case 4 -> {
                double jump = Math.sqrt(sq(p.getX() - lastPx) + sq(p.getY() - lastPy) + sq(p.getZ() - lastPz));
                if (jump > 6.0) {
                    FlightRecorder.routine("TELEPORT_SEEN after=" + (now - throwNow) + " from=" + local(lastPx, lastPy, lastPz)
                            + " to=" + local(p.getX(), p.getY(), p.getZ()) + " levitation=" + p.hasStatusEffect(StatusEffects.LEVITATION));
                    go(5);
                    return;
                }
                lastPx = p.getX();
                lastPy = p.getY();
                lastPz = p.getZ();
                if (now - throwNow == 15 && countItem(p, Items.ENDER_PEARL) >= pearlBefore) abort(c, "the pearl was not thrown");
                else if (now - throwNow > 160) abort(c, "the pearl did not teleport you");
            }
            default -> {
                if (p.isOnGround() && !p.hasStatusEffect(StatusEffects.LEVITATION) && subTick > 10) {
                    finishStep(c, "OK", "landed=" + local(p.getX(), p.getY(), p.getZ()) + " absY=" + fmt(p.getY()) + " hp=" + fmt(p.getHealth()));
                }
            }
        }
    }

    private static void stepTakeoff(MinecraftClient c, ClientPlayerEntity p) {
        if (sub == 0) {
            if (FlightRunner.isRunning()) {
                abort(c, "a flight is already running");
                return;
            }
            if (!p.isOnGround() || p.hasStatusEffect(StatusEffects.LEVITATION) || p.getVelocity().lengthSquared() > 0.01) return;
            if (!waited(10, 25)) return;
            FlightRunner.start(c);
            go(1);
            return;
        }
        if (FlightRunner.isRunning()) {
            if (p.isGliding()) finishStep(c, "OK", "glide_started pos=" + fmt(p.getY()));
        } else if (subTick > 5) {
            abort(c, "the flight did not start (read the message above)");
        }
    }

    // ------------------------------------------------------------------ shulker bullets

    /**
     * A shulker bullet that flies at you gives levitation (and about 4 damage). A bullet dies from one hit,
     * with any item in the hand (the held item is never changed here). This turns to the bullet, waits until it
     * is in reach (3 blocks) and the crosshair is exactly on it, then hits it. Nothing else is ever hit.
     * Returns true while it is busy with a bullet (the normal step waits).
     */
    private static boolean defend(MinecraftClient c, ClientPlayerEntity p, boolean lev) {
        if (bulletCd > 0) bulletCd--;
        if (bullet == null || bullet.isRemoved() || bullet.squaredDistanceTo(p) > 100.0) {
            bullet = null;
            bulletLogged = false;
            double nearest = 1e9;
            Box box = new Box(p.getX() - 8, p.getY() - 8, p.getZ() - 8, p.getX() + 8, p.getY() + 8, p.getZ() + 8);
            for (ShulkerBulletEntity b : c.world.getEntitiesByClass(ShulkerBulletEntity.class, box, e -> !e.isRemoved())) {
                double d = b.squaredDistanceTo(p);
                if (d < nearest) {
                    nearest = d;
                    bullet = b;
                }
            }
        }
        if (bullet == null) return false;
        double bx = bullet.getX(), by = bullet.getY() + 0.15, bz = bullet.getZ();
        double ex = p.getX(), ey = p.getEyeY(), ez = p.getZ();
        double dist = Math.sqrt(sq(bx - ex) + sq(by - ey) + sq(bz - ez));
        // ignore bullets that fly away from us
        Vec3d v = bullet.getVelocity();
        double vl = Math.sqrt(v.lengthSquared());
        if (vl > 0.01 && dist > 0.1) {
            double closing = (v.x * (ex - bx) + v.y * (ey - by) + v.z * (ez - bz)) / (vl * dist);
            if (closing < 0.2) {
                bullet = null;
                return false;
            }
        }
        if (!bulletLogged) {
            bulletLogged = true;
            FlightRecorder.routine("BULLET_SEEN dist=" + fmt(dist) + " levitation=" + lev + " hp=" + fmt(p.getHealth()));
        }
        if (++defendTicks > 160) {
            abort(c, "could not stop the shulker bullets (they keep coming)");
            return true;
        }
        releaseKeys(c);
        double err = lookAngles(p, (float) Math.toDegrees(Math.atan2(-(bx - ex), bz - ez)),
                (float) -Math.toDegrees(Math.atan2(by - ey, Math.max(Math.hypot(bx - ex, bz - ez), 1.0E-4))),
                28f + RND.nextFloat() * 12f);
        say(c, "Shulker bullet! Hitting it (" + fmt(dist) + " blocks)");
        boolean on = c.crosshairTarget instanceof EntityHitResult eh && eh.getEntity() == bullet;
        if (dist <= 2.9 && err < 5.0 && on && bulletCd == 0) {
            c.interactionManager.attackEntity(p, bullet);
            p.swingHand(Hand.MAIN_HAND);
            bulletCd = 5;
            FlightRecorder.routine("BULLET_HIT dist=" + fmt(dist) + " held=" + Registries.ITEM.getId(p.getMainHandStack().getItem()));
            if (bullet.isRemoved()) {
                bulletsStopped++;
                FlightRecorder.routine("BULLET_DESTROYED total=" + bulletsStopped);
                bullet = null;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ drop checks

    private static String dropChecks(MinecraftClient c, ClientPlayerEntity p, BlockPos d) {
        if (p.getHealth() < 10f) return "health " + fmt(p.getHealth()) + " is too low for the planned 5 block fall";
        BlockState st = c.world.getBlockState(d);
        if (!isPurpur(st)) return "unexpected block " + blockId(c, d) + " at the drop block";
        double fall = plannedFall(c, d);
        if (Double.isNaN(fall) || fall < 3.9 || fall > 6.3) {
            return "the planned fall is " + (Double.isNaN(fall) ? "unknown (no safe floor below)" : fmt(fall) + " blocks, expected about 5");
        }
        return standCheck(c, p);
    }

    private static double plannedFall(MinecraftClient c, BlockPos d) {
        for (int y = d.getY() - 1; y >= d.getY() - 9; y--) {
            if (solid(c, d.getX(), y, d.getZ())) {
                if (solid(c, d.getX(), y + 1, d.getZ()) || solid(c, d.getX(), y + 2, d.getZ())) return Double.NaN;
                return (d.getY() + 1.0) - (y + 1.0);
            }
        }
        return Double.NaN;
    }

    private static String standCheck(MinecraftClient c, ClientPlayerEntity p) {
        double top = groundTop(c, p.getX(), p.getY() - 0.01, p.getZ(), 3);
        if (Double.isNaN(top) || p.getY() - top > 3.0) return "no solid block within 3 blocks under your feet";
        return null;
    }

    // ------------------------------------------------------------------ mining

    private static void mineReset() {
        mineTicks = 0;
        aimIdx = 0;
        aimMiss = 0;
        mineFail = "";
    }

    /** Looks at the planned block and holds the attack key only while the crosshair is on exactly that block. */
    private static int mine(MinecraftClient c, ClientPlayerEntity p, BlockPos bp) {
        BlockState st = c.world.getBlockState(bp);
        if (st.isAir()) {
            c.options.attackKey.setPressed(false);
            FlightRecorder.routine("BROKE block=" + blockLocal(bp) + " ticks=" + mineTicks);
            return M_DONE;
        }
        c.options.attackKey.setPressed(false);
        if (!isPurpur(st)) {
            mineFail = "unexpected block " + blockId(c, bp) + " at " + blockLocal(bp) + " (only purpur is allowed)";
            return M_FAIL;
        }
        if (!isPickaxe(p.getMainHandStack())) {
            mineFail = "the pickaxe is not in your hand";
            return M_FAIL;
        }
        Vec3d center = new Vec3d(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5);
        double dist = new Vec3d(p.getX(), p.getEyeY(), p.getZ()).distanceTo(center);
        if (dist > 4.4) {
            mineFail = "the block " + blockLocal(bp) + " is out of reach (" + fmt(dist) + " blocks)";
            return M_FAIL;
        }
        if (++mineTicks > 200) {
            mineFail = "breaking the block " + blockLocal(bp) + " takes too long";
            return M_FAIL;
        }
        double[] o = AIM[aimIdx];
        double err = look(p, center.add(o[0], o[1], o[2]));
        boolean on = c.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK
                && bhr.getBlockPos().equals(bp);
        if (err < 1.0 && on) {
            c.options.attackKey.setPressed(true);
            aimMiss = 0;
        } else if (err < 1.0 && ++aimMiss > 6) {
            aimMiss = 0;
            aimIdx++;
            if (aimIdx >= AIM.length) {
                mineFail = "the block " + blockLocal(bp) + " cannot be seen from here";
                return M_FAIL;
            }
        }
        return M_WORKING;
    }

    // ------------------------------------------------------------------ looking and walking

    private static double look(ClientPlayerEntity p, Vec3d target) {
        double dx = target.x - p.getX(), dy = target.y - p.getEyeY(), dz = target.z - p.getZ();
        double h = Math.hypot(dx, dz);
        float wantYaw = h < 0.05 ? p.getYaw() : (float) Math.toDegrees(Math.atan2(-dx, dz));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(h, 1.0E-4)));
        return lookAngles(p, wantYaw, wantPitch);
    }

    /** Turns smoothly (not instantly) and returns the angle that is still missing, in degrees. */
    private static double lookAngles(ClientPlayerEntity p, float wantYaw, float wantPitch) {
        return lookAngles(p, wantYaw, wantPitch, TURN_BASE + RND.nextFloat() * TURN_RAND);
    }

    private static double lookAngles(ClientPlayerEntity p, float wantYaw, float wantPitch, float step) {
        float dyaw = MathHelper.wrapDegrees(wantYaw - p.getYaw());
        float dpitch = wantPitch - p.getPitch();
        p.setYaw(p.getYaw() + clampF(dyaw, -step, step));
        p.setPitch(MathHelper.clamp(p.getPitch() + clampF(dpitch, -step, step), -90f, 90f));
        return Math.max(Math.abs(MathHelper.wrapDegrees(wantYaw - p.getYaw())), Math.abs(wantPitch - p.getPitch()));
    }

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int walkTo(MinecraftClient c, ClientPlayerEntity p, double tx, double tz, double tol, boolean allowDrop) {
        double dx = tx - p.getX(), dz = tz - p.getZ();
        double dist = Math.hypot(dx, dz);
        if (dist < tol) {
            c.options.forwardKey.setPressed(false);
            c.options.jumpKey.setPressed(false);
            return W_ARRIVED;
        }
        if (!allowDrop && !pathSafe(c, p, tx, tz)) {
            c.options.forwardKey.setPressed(false);
            c.options.jumpKey.setPressed(false);
            return W_UNSAFE;
        }
        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float step = TURN_BASE + RND.nextFloat() * TURN_RAND;
        p.setYaw(p.getYaw() + clampF(MathHelper.wrapDegrees(wantYaw - p.getYaw()), -step, step));
        p.setPitch(p.getPitch() + clampF(18f - p.getPitch(), -4f, 4f));
        boolean aligned = Math.abs(MathHelper.wrapDegrees(wantYaw - p.getYaw())) < 18f;
        boolean press = aligned && (dist > 0.7 || now % 3 == 0);       // short taps when close
        c.options.forwardKey.setPressed(press);
        c.options.jumpKey.setPressed(press && p.isOnGround() && dist > 0.4 && stepAhead(c, p, dx / dist, dz / dist));
        if (dist < walkBest - 0.05) {
            walkBest = dist;
            stuckTicks = 0;
        } else {
            stuckTicks++;
        }
        return stuckTicks > 45 ? W_STUCK : W_MOVING;
    }

    /** A one block step up in front of us, with room above it. */
    private static boolean stepAhead(MinecraftClient c, ClientPlayerEntity p, double ux, double uz) {
        int bx = MathHelper.floor(p.getX() + ux * 0.7), bz = MathHelper.floor(p.getZ() + uz * 0.7);
        int by = MathHelper.floor(p.getY() + 0.05);
        return solid(c, bx, by, bz) && !solid(c, bx, by + 1, bz) && !solid(c, bx, by + 2, bz);
    }

    /** Looks up to 6 blocks ahead; every single step down must be smaller than 4 blocks and ground must exist. */
    private static boolean pathSafe(MinecraftClient c, ClientPlayerEntity p, double tx, double tz) {
        if (!p.isOnGround()) return true;
        double sx = p.getX(), sz = p.getZ();
        double top = groundTop(c, sx, p.getY() - 0.01, sz, 4);
        if (Double.isNaN(top)) return false;
        double dist = Math.hypot(tx - sx, tz - sz);
        if (dist < 0.01) return true;
        double ux = (tx - sx) / dist, uz = (tz - sz) / dist;
        double reach = Math.min(dist, 6.0);
        double prev = top;
        for (double s = 0.5; s <= reach + 0.01; s += 0.5) {
            double t2 = groundTop(c, sx + ux * s, prev + 1.3, sz + uz * s, 5);
            if (Double.isNaN(t2) || prev - t2 > MAX_WALK_DROP) return false;
            prev = t2;
        }
        return true;
    }

    /** Top of the highest solid block under (x, z) (centre and 4 corners of the player), scanning down from startY. NaN = none. */
    private static double groundTop(MinecraftClient c, double x, double startY, double z, int maxDown) {
        double best = Double.NaN;
        int y0 = MathHelper.floor(startY);
        for (int k = 0; k < 5; k++) {
            double sx = x + (k == 1 ? 0.3 : k == 2 ? -0.3 : 0.0);
            double sz = z + (k == 3 ? 0.3 : k == 4 ? -0.3 : 0.0);
            int bx = MathHelper.floor(sx), bz = MathHelper.floor(sz);
            for (int y = y0; y >= y0 - maxDown; y--) {
                if (solid(c, bx, y, bz)) {
                    double top = y + 1.0;
                    if (Double.isNaN(best) || top > best) best = top;
                    break;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ pearl planning

    private static Vec3d theoryEye() {
        return new Vec3d(wx(SPOT_PEARL[0], SPOT_PEARL[1]), fy + SPOT_PEARL[2] + 1.62 - 0.1, wz(SPOT_PEARL[0], SPOT_PEARL[1]));
    }

    /** Tries yaw/pitch combinations around "back-left 45 degrees" and simulates the real pearl flight over the loaded world. */
    private static PearlPlan scanPearl(MinecraftClient c, Vec3d start) {
        double dx = -fdx + ldx, dz = -fdz + ldz;
        float baseYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float[] yawOffs = {0f, -3f, 3f, -6f, 6f, -10f, 10f};
        PearlPlan best = null;
        for (float yo : yawOffs) {
            for (float pitch = 26f; pitch <= 62f; pitch += 2f) {
                float yaw = baseYaw + yo;
                Impact im = simulatePearl(c, start, yaw, pitch);
                if (im == null) continue;
                BlockPos hit = im.hit;
                if (im.free.y < hit.getY() + 0.5) continue;                       // it hits a wall, not the top of a block
                double landY = hit.getY() + 1.0;
                if (landY > fy - 20.0 || landY < 10.0) continue;                  // must be far below the ship, not in the void zone
                double hd = Math.hypot(im.free.x - start.x, im.free.z - start.z);
                if (hd < 32.0 || hd > 78.0) continue;
                if (solid(c, hit.getX(), hit.getY() + 1, hit.getZ()) || solid(c, hit.getX(), hit.getY() + 2, hit.getZ())) continue;
                if (groundPatch(c, hit) < 15) continue;                            // a wide flat patch, not an edge or a pillar
                double cost = Math.abs(pitch - 42f) * 0.6 + Math.abs(yo) * 0.8 + (hd < 40 ? (40 - hd) * 0.5 : hd > 70 ? (hd - 70) * 0.5 : 0);
                if (best == null || cost < best.cost) {
                    best = new PearlPlan();
                    best.yaw = yaw;
                    best.pitch = pitch;
                    best.dist = hd;
                    best.cost = cost;
                    best.landX = im.free.x;
                    best.landY = landY;
                    best.landZ = im.free.z;
                }
            }
            if (best != null) break;       // the smallest yaw change that works
        }
        return best;
    }

    private static int groundPatch(MinecraftClient c, BlockPos hit) {
        int n = 0;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                int bx = hit.getX() + x, bz = hit.getZ() + z;
                if ((solid(c, bx, hit.getY(), bz) || solid(c, bx, hit.getY() - 1, bz)) && !solid(c, bx, hit.getY() + 1, bz)) n++;
            }
        }
        return n;
    }

    /** Same physics as the game: speed 1.5, drag 0.99, gravity 0.03. Returns null when it enters unloaded chunks or the void. */
    private static Impact simulatePearl(MinecraftClient c, Vec3d start, float yaw, float pitch) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double vx = -Math.sin(yr) * Math.cos(pr) * 1.5;
        double vy = -Math.sin(pr) * 1.5;
        double vz = Math.cos(yr) * Math.cos(pr) * 1.5;
        double x = start.x, y = start.y, z = start.z;
        double lx = x, ly = y, lz = z;
        int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
        for (int tick = 0; tick < 300; tick++) {
            double nx = x + vx, ny = y + vy, nz = z + vz;
            for (int s = 1; s <= 12; s++) {
                double px = x + (nx - x) * s / 12.0, py = y + (ny - y) * s / 12.0, pz = z + (nz - z) * s / 12.0;
                int bx = MathHelper.floor(px), by = MathHelper.floor(py), bz = MathHelper.floor(pz);
                int cx = bx >> 4, cz = bz >> 4;
                if (cx != lastCx || cz != lastCz) {
                    if (c.world.getChunk(cx, cz, ChunkStatus.FULL, false) == null) return null;
                    lastCx = cx;
                    lastCz = cz;
                }
                if (solid(c, bx, by, bz)) {
                    Impact im = new Impact();
                    im.free = new Vec3d(lx, ly, lz);
                    im.hit = new BlockPos(bx, by, bz);
                    return im;
                }
                lx = px;
                ly = py;
                lz = pz;
            }
            x = nx;
            y = ny;
            z = nz;
            vx *= 0.99;
            vy = vy * 0.99 - 0.03;
            vz *= 0.99;
            if (y < c.world.getBottomY() + 4) return null;
        }
        return null;
    }

    // ------------------------------------------------------------------ local coordinates

    private static double wx(double a, double left) {
        return fx + a * fdx + left * ldx;
    }

    private static double wz(double a, double left) {
        return fz + a * fdz + left * ldz;
    }

    private static BlockPos blockAt(double[] g) {
        return new BlockPos(MathHelper.floor(wx(g[0], g[1])), MathHelper.floor(fy + g[2] + 0.01), MathHelper.floor(wz(g[0], g[1])));
    }

    private static double localA(double x, double z) {
        return (x - fx) * fdx + (z - fz) * fdz;
    }

    private static double localLeft(double x, double z) {
        return (x - fx) * ldx + (z - fz) * ldz;
    }

    private static String local(double x, double y, double z) {
        return "a=" + fmt(localA(x, z)) + ",left=" + fmt(localLeft(x, z)) + ",h=" + fmt(y - fy);
    }

    /** Same format as the teach log: block centre x/z, block bottom y. */
    private static String blockLocal(BlockPos bp) {
        return local(bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5);
    }

    private static String blockText(MinecraftClient c, double[] g) {
        BlockPos bp = blockAt(g);
        return "(a=" + fmt(g[0]) + ", left=" + fmt(g[1]) + ", h=" + fmt(g[2]) + ") = " + blockId(c, bp)
                + " at " + bp.getX() + " " + bp.getY() + " " + bp.getZ();
    }

    // ------------------------------------------------------------------ small helpers

    private static boolean solid(MinecraftClient c, BlockPos pos) {
        return solid(c, pos.getX(), pos.getY(), pos.getZ());
    }

    private static boolean solid(MinecraftClient c, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return !c.world.getBlockState(pos).getCollisionShape(c.world, pos).isEmpty();
    }

    private static boolean isPurpur(BlockState st) {
        return !st.isAir() && Registries.BLOCK.getId(st.getBlock()).getPath().startsWith("purpur");
    }

    private static String blockId(MinecraftClient c, BlockPos pos) {
        return Registries.BLOCK.getId(c.world.getBlockState(pos).getBlock()).toString();
    }

    private static boolean isPickaxe(ItemStack st) {
        return !st.isEmpty() && Registries.ITEM.getId(st.getItem()).getPath().endsWith("_pickaxe");
    }

    private static int pickaxeSlot(ClientPlayerEntity p) {
        int any = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getStack(i);
            if (!isPickaxe(st)) continue;
            if (Registries.ITEM.getId(st.getItem()).getPath().equals("netherite_pickaxe")) return i;
            if (any < 0) any = i;
        }
        return any;
    }

    private static int hotbarSlot(ClientPlayerEntity p, net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private static int countItem(ClientPlayerEntity p, net.minecraft.item.Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getStack(i);
            if (st.isOf(item)) n += st.getCount();
        }
        return n;
    }

    private static int countElytra(ClientPlayerEntity p) {
        return countItem(p, Items.ELYTRA);
    }

    private static void releaseKeys(MinecraftClient c) {
        if (c == null || c.options == null) return;
        c.options.forwardKey.setPressed(false);
        c.options.jumpKey.setPressed(false);
        c.options.attackKey.setPressed(false);
        c.options.useKey.setPressed(false);
    }

    private static void say(MinecraftClient c, String text) {
        if (c != null && c.player != null) c.player.sendMessage(Text.literal(text), true);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
