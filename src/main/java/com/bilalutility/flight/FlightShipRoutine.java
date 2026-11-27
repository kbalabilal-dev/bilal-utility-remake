package com.bilalutility.flight;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
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
 * Ship routine: after "ELYTRA FOUND" it lands, takes the Elytra, stores extras, leaves and flies again.
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
        STORE_ELYTRA("Store extra Elytras in the Ender Chest", 700),
        DIG_EXIT("Dig the exit tunnel", 700),
        PEARL_OUT("Leave the ship: glide out (pearl = backup)", 900),
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
    // Dig stance = CENTRE of the chest top (chest block: a 0..1, left 0.5..1.5, top 0.875 high). The old stance
    // (0.8, 1.2) put the player's box flush against the ship's side wall at left=1.5.
    private static final double[] SPOT_DIG = {0.5, 1.0, -0.625};
    // Pearl stance = CENTRE of the one-block tunnel floor (a -1..0, left 0.5..1.5, floor top h=-0.5). The old stance
    // (-0.8, 1.3) hung 0.1 over both edges of that block.
    private static final double[] SPOT_PEARL = {-0.5, 1.0, -0.5};
    // the chest block (bottom edge h=-1.5) between the frame ledge and the tunnel
    private static final double[] CHEST_BLOCK = {0.5, 1.0, -1.5};
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
    // One temporary side-step waypoint is used to recover from ordinary walls without
    // weakening the ground/drop safety check.
    private static int navigationRetries;
    private static boolean navigationRetryResume;
    private static final int MAX_NAV_RETRIES = 3;
    private static double navLastX = Double.NaN, navLastY, navLastZ, navLastBest = 1e9;
    private static boolean chestClimbActive, chestClimbJumpIssued;
    private static double chestClimbX, chestClimbZ, chestClimbTop;
    private static int chestClimbTicks, chestClimbGroundTicks;
    private static String abortReason = "", lastLine = "";
    private static RegistryKey<World> dimension;
    private static ItemFrameEntity frame;
    // A shulker is handled only when it is inside the ship, close to the frame, and can see the player.
    private static ShulkerEntity frameThreat;
    private static int frameThreatAttackCd;
    // v2.0.8: shulker that sits in/next to the way out (DIG_EXIT) and blocks the jump or the digging
    private static ShulkerEntity exitShulker;
    private static int exitShulkerCd, exitShulkerTicks;
    private static boolean exitShulkerGiveUp;
    private static double fx, fy, fz;
    private static double frameApproachA;
    private static int fdx, fdz, ldx, ldz;
    private static String facingName = "";
    private static double pendX, pendY, pendZ;
    private static int elytraAtArm;
    // mining
    private static int mineTicks, aimIdx, aimMiss;
    private static String mineFail = "";
    private static int reachWalk;          // v2.0.8: ticks spent walking back to a block after a knockback
    // step data
    private static int elytraBefore, hitTries, aimOk, pearlBefore;
    private static int storageSourceSlot = -1, storageDestSlot = -1;
    private static long throwNow;
    private static PearlPlan plan;
    // shulker bullet defense
    private static ShulkerBulletEntity bullet;
    private static int bulletCd, defendTicks, bulletsStopped;
    private static boolean bulletLogged;
    private static long bulletHitNow;
    // glide exit (used when no pearl ground exists)
    private static boolean glideAsk, glideConfirmed;
    // v2.0.9: glide is the first way out. pearlOnly = gliding failed (or is switched off), so the pearl is used.
    private static boolean pearlOnly;
    private static int pingPause;
    private static boolean bulletGiveUp;     // Full Auto: bullets keep coming -> stop fighting them, carry on with the step
    private static int healRetries;
    private static int emergencyThrows, lastEmergencyTick = -1000;
    private static int glideAir;
    private static double[] glideGround;          // ground the flight falls back on if something goes wrong (x, y, z)
    private static final int GLIDE_EXTRA_ROCKETS = 1;   // margin: the player moves a little before the flight checks its rockets
    // time-sliced pearl scan (about 10 ms per tick instead of one long freeze)
    private static boolean scanActive;
    private static int scG, scSgnIdx, scPitchIdx, scSlices;
    private static long scNanos;
    private static PearlPlan scBest;
    private static Vec3d scStart;
    private static float scBaseYaw;
    private static final int PITCH_COUNT = 19;          // pitch -8 ... 64 in steps of 4
    private static final long SCAN_SLICE_NANOS = 10_000_000L;
    // pearl scan statistics (for the log and the stop message)
    private static int scTried, scWall, scVoid, scRange, scHigh, scSpace, scPatch;
    // Automatic emergency healing shared by the flight controller and ship routine.
    private static final float AUTO_APPLE_HEALTH = 12.0f;
    private static final int AUTO_APPLE_HOTBAR_SLOT = 8;
    private static boolean appleBusy, appleStarted, appleMoved, appleWarned;
    private static int appleSourceSlot = -1, appleOriginalSelected = -1, appleUseTicks, appleCooldown;
    private static long appleLastWorldTime = Long.MIN_VALUE;
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
            case WAIT_KEY -> {
                if (glideAsk) {
                    l.add("No safe pearl ground here.");
                    l.add("Press [" + FlightClient.stepKeyText() + "] to GLIDE out (dangerous), or sneak to stop");
                } else {
                    l.add("Press [" + FlightClient.stepKeyText() + "] to do this step");
                }
            }
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

    /** Called by FlightRunner.found() when an elytra frame is confirmed and the flight has stopped. */
    public static void onShipFound(MinecraftClient c, double x, double y, double z) {
        pendX = x;
        pendY = y;
        pendZ = z;
        if (FlightConfig.routineMode == 0) return;
        if (c == null || c.player == null || c.world == null) return;
        arm(c);
    }

    private static void arm(MinecraftClient c) {
        phase = Phase.IDLE;
        stepIndex = 0;
        navigationRetries = 0;
        navigationRetryResume = false;
        chestClimbActive = false;
        chestClimbJumpIssued = false;
        chestClimbTicks = 0;
        chestClimbGroundTicks = 0;
        glideAsk = false;
        glideConfirmed = false;
        glideGround = null;
        pearlOnly = false;
        emergencyThrows = 0;
        healRetries = 0;
        bulletGiveUp = false;
        scanActive = false;
        lastLine = "";
        abortReason = "";
        facingName = "";
        dimension = c.world.getRegistryKey();
        frame = null;
        frameThreat = null;
        frameThreatAttackCd = 0;
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
        elytraAtArm = countElytra(p);
        double hd = Math.hypot(p.getX() - fx, p.getZ() - fz);
        boolean lastShip = !FlightRunner.hasQueuePoint();
        FlightRecorder.routine("ROUTINE_START mode=" + FlightConfig.routineMode + " dry=" + FlightConfig.routineDryRun
                + " frame=" + fmt(fx) + "," + fmt(fy) + "," + fmt(fz) + " facing=" + facingName
                + " player=" + local(p.getX(), p.getY(), p.getZ()) + " elytra=" + elytraAtArm
                + " rockets=" + FlightRunner.rocketCount(p) + " lastShip=" + lastShip);
        if (!FlightRecorder.isOpen()) {
            p.sendMessage(Text.literal("Ship routine WARNING: no log file is open (it could not be created). "
                    + "Nothing of this routine will be recorded."), false);
        }
        if (lastShip) {
            p.sendMessage(Text.literal("Ship routine: this was the LAST point of the queue - the routine stops after you have the elytra."), false);
        }
        if (p.getY() < fy + 3.0 || p.getY() > fy + 9.0 || hd > 16.0) {
            abort(c, "you are not on the ship roof (your Y " + fmt(p.getY()) + ", frame Y " + fmt(fy) + ")");
            return;
        }
        if (FlightConfig.routineMode == 2) {
            phase = Phase.DELAY;
            delayLeft = 15 + RND.nextInt(15) + PingGuard.extraTicks();
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
        // the flight is ours in two places: the TAKEOFF step, and the last sub-step of a glide exit (PEARL_OUT sub 8)
        Step cur = STEPS[Math.min(stepIndex, STEPS.length - 1)];
        boolean takeoffRunning = phase == Phase.RUN && (cur == Step.TAKEOFF || (cur == Step.PEARL_OUT && sub >= 8));
        if (FlightRunner.isRunning() && !takeoffRunning) {
            cancel(c, "Ship routine cancelled: a flight was started");
            return;
        }
        if (phase == Phase.DELAY) {
            if (--delayLeft <= 0) {
                if (afkRestart) {
                    afkRestart = false;
                    beginStep(c, "afk_restart");
                } else if (navigationRetryResume) {
                    navigationRetryResume = false;
                    stepTicks = 0;
                    stepStartNow = now;
                    stepStartClock = FlightRecorder.clock();
                    subTick = 0;
                    phase = Phase.RUN;
                    if (c.player != null) c.player.sendMessage(Text.literal("Ship routine: retrying the current route"), true);
                } else {
                    beginStep(c, "auto");
                }
            }
        } else if (phase == Phase.RUN) {
            run(c, p);
        }
    }

    // ------------------------------------------------------------------ step control

    private static void beginStep(MinecraftClient c, String trigger) {
        navigationRetryResume = false;
        navigationRetries = 0;
        navLastX = Double.NaN;
        navLastBest = 1e9;
        routePhase = "";
        ClientPlayerEntity p = c.player;
        Step s = STEPS[stepIndex];
        if (glideAsk) {
            glideConfirmed = true;
            glideAsk = false;
        }
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
        exitShulker = null;
        exitShulkerCd = 0;
        exitShulkerTicks = 0;
        exitShulkerGiveUp = false;
        mineReset();
        scanActive = false;
        pearlOnly = false;
        emergencyThrows = 0;
        bulletGiveUp = false;
        pingPause = 0;
        glideAir = 0;
        go(0);
        releaseKeys(c);
        FlightRecorder.routine("STEP_BEGIN step=" + s + " t=" + stepStartClock + " trigger=" + trigger
                + " dry=" + FlightConfig.routineDryRun + " pos=" + local(p.getX(), p.getY(), p.getZ())
                + " hp=" + fmt(p.getHealth()));
        // retry after a timeout: the elytra may already be in the inventory (the frame is empty or gone)
        if (s == Step.TAKE_ELYTRA && countElytra(p) > elytraAtArm
                && (frame == null || frame.isRemoved() || !frame.getHeldItemStack().isOf(Items.ELYTRA))) {
            finishStep(c, "OK", "already_taken elytra=" + elytraAtArm + "->" + countElytra(p));
            return;
        }
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
        glideAsk = false;
        glideConfirmed = false;
        navigationRetries = 0;
        navLastX = Double.NaN;
        navLastBest = 1e9;
        navigationRetryResume = false;
        stepRestarts = 0;
        restartIndex = -1;
        chestClimbActive = false;
        chestClimbJumpIssued = false;
        chestClimbTicks = 0;
        chestClimbGroundTicks = 0;
        lastLine = "step " + (stepIndex + 1) + " " + s + " = " + result;
        stepIndex++;
        if (s == Step.STORE_ELYTRA && !FlightRunner.hasQueuePoint()) {
            // last ship of the queue: store the extra first, then stop without a tunnel or glide
            FlightRecorder.routine("ROUTINE_END result=queue_finished (last ship: no pearl, no glide)");
            stepIndex = STEPS.length;
            phase = Phase.DONE;
            doneHold = 200;
            alertLeft = 30;
            lastLine = "queue finished - you have the elytra";
            say(c, "Ship routine finished: queue finished");
            if (c.player != null) {
                c.player.sendMessage(Text.literal("Ship routine: queue finished - there is no point left to fly to. "
                        + "You have the elytra; the routine did not pearl or glide."), false);
            }
            return;
        }
        if (stepIndex >= STEPS.length) {
            phase = Phase.DONE;
            doneHold = 200;
            alertLeft = 30;
            FlightRecorder.routine("ROUTINE_END result=ok");
            say(c, "Ship routine finished");
            return;
        }
        if (s == Step.TAKE_ELYTRA) {
            // Storage is automatic even in Assisted mode; the user should not need
            // another key press between picking up the Elytra and protecting it.
            phase = Phase.DELAY;
            delayLeft = 2 + RND.nextInt(4) + PingGuard.extraTicks();
            say(c, "Elytra taken. Opening the Ender Chest...");
            return;
        }
        if (FlightConfig.routineMode == 2) {
            phase = Phase.DELAY;
            delayLeft = 4 + RND.nextInt(8) + PingGuard.extraTicks();
        } else {
            phase = Phase.WAIT_KEY;
            say(c, "Step done. Press [" + FlightClient.stepKeyText() + "]: " + STEPS[stepIndex].text);
        }
    }

    /**
     * Full Auto navigation watchdog shared by every movement sub-step. It does NOT repeat the same attempt forever:
     * a retry is only allowed when the last attempt made progress (the player moved at least 0.25 blocks, or got at
     * least 0.15 closer to the target), at most MAX_NAV_RETRIES times per step. Every retry re-plans: the route state is
     * cleared, so the next attempt starts from where the player really is (ledge, chest top or tunnel floor).
     * When there is no progress it gives up (returns false): the caller stops with a clear reason and a diagnostic dump.
     */
    private static boolean retryNavigation(MinecraftClient c, String reason) {
        if (FlightConfig.routineMode != 2) return false;
        ClientPlayerEntity p = c == null ? null : c.player;
        if (p == null) return false;
        Step s = STEPS[Math.min(stepIndex, STEPS.length - 1)];
        boolean navigationSub = switch (s) {
            case DROP -> sub == 0 || sub == 3;
            case TAKE_ELYTRA -> sub == 0 || sub == 1 || sub == 11 || sub == 12;
            case DIG_EXIT -> sub == 0;
            case PEARL_OUT -> sub == 0 || sub == 7;
            default -> false;
        };
        if (!navigationSub) return false;

        double moved = Double.isNaN(navLastX) ? 99.0
                : Math.sqrt(sq(p.getX() - navLastX) + sq(p.getY() - navLastY) + sq(p.getZ() - navLastZ));
        boolean closer = walkBest < navLastBest - 0.15;
        boolean progress = navigationRetries == 0 || moved >= 0.25 || closer;
        if (!progress || navigationRetries >= MAX_NAV_RETRIES) {
            FlightRecorder.routine("NAV_GIVE_UP step=" + s + " sub=" + sub + " retries=" + navigationRetries
                    + " moved=" + f2(moved) + " reason=" + reason.replace(' ', '_')
                    + (progress ? "_retry_limit" : "_no_progress"));
            diag(c, p, "NAV_GIVE_UP");
            return false;
        }
        navLastX = p.getX();
        navLastY = p.getY();
        navLastZ = p.getZ();
        navLastBest = Math.min(navLastBest, walkBest);

        releaseKeys(c);
        navigationRetries++;
        stepTicks = 0;
        stepStartNow = now;
        stepStartClock = FlightRecorder.clock();
        subTick = 0;
        airTicks = 0;
        walkBest = 1e9;
        stuckTicks = 0;
        navigationRetryResume = true;
        chestClimbActive = false;
        chestClimbJumpIssued = false;
        chestClimbTicks = 0;
        chestClimbGroundTicks = 0;
        routePhase = "";
        phase = Phase.DELAY;
        delayLeft = 10 + navigationRetries * 5;
        FlightRecorder.routine("NAV_REPLAN step=" + s + " sub=" + sub + " retry=" + navigationRetries + "/" + MAX_NAV_RETRIES
                + " moved=" + f2(moved) + " loc=" + locName(locate(c, p)) + " delay=" + delayLeft
                + " reason=" + reason.replace(' ', '_'));
        return true;
    }

    private static final int MAX_STEP_RESTARTS = 5;
    private static int stepRestarts, restartIndex = -1, healthWait;
    private static boolean afkRestart;

    /** Reasons a restart cannot fix: a deliberate stop, a missing item, or a wrong place / danger. */
    private static boolean unrecoverable(String reason) {
        String r = reason.toLowerCase(Locale.ROOT);
        return r.contains("sneak") || r.contains("frame facing missing") || r.contains("not on the ship roof")
                || r.contains("falling") || r.contains("below the ship") || r.contains("flight did not start")
                || r.contains("flight is already running") || r.contains("no pickaxe") || r.contains("no ender pearl")
                || r.contains("no sword") || r.contains("no safe ground for the pearl")
                || r.contains("take control") || r.contains("glide failed");
    }

    private static void abort(MinecraftClient c, String reason) {
        releaseKeys(c);
        abortReason = reason;
        glideAsk = false;
        glideConfirmed = false;          // a retry asks again (assisted) or decides again (full auto)
        scanActive = false;
        Step s = STEPS[Math.min(stepIndex, STEPS.length - 1)];
        ClientPlayerEntity p = c == null ? null : c.player;
        FlightRecorder.routine("STEP_END step=" + s + " start=" + stepStartClock + " end=" + FlightRecorder.clock()
                + " result=ABORT reason=" + reason.replace(' ', '_')
                + (p != null && frame != null ? " pos=" + local(p.getX(), p.getY(), p.getZ()) : ""));
        if (FlightConfig.routineMode == 2 && p != null) {
            String lr = reason.toLowerCase(Locale.ROOT);
            // v2.0.9: low health: wait for the golden apple / regeneration, do not give control away (about 40 s)
            if (lr.startsWith("health ") && lr.contains("below") && healRetries < 24) {
                healRetries++;
                FlightRecorder.routine("HEAL_WAIT step=" + s + " try=" + healRetries + " hp=" + fmt(p.getHealth()));
                p.sendMessage(Text.literal("Ship routine: health is low - waiting to heal (" + healRetries + ")"), true);
                afkRestart = true;
                phase = Phase.DELAY;
                delayLeft = 40;
                return;
            }
            // v2.0.9: the pearl teleport came late: you are already on the ground far from the ship -> go on to the take off
            if (s == Step.PEARL_OUT && lr.contains("below the ship") && p.isOnGround() && !FlightRunner.isRunning()) {
                FlightRecorder.routine("PEARL_LATE_TELEPORT_ACCEPTED pos=" + local(p.getX(), p.getY(), p.getZ()));
                stepIndex = STEPS.length - 1;
                afkRestart = false;
                phase = Phase.DELAY;
                delayLeft = 20;
                return;
            }
        }
        // Full Auto (AFK): restart the same step by itself, a few times, before giving up for good
        if (FlightConfig.routineMode == 2 && p != null && !unrecoverable(reason)) {
            if (restartIndex != stepIndex) {
                restartIndex = stepIndex;
                stepRestarts = 0;
            }
            if (stepRestarts < MAX_STEP_RESTARTS) {
                stepRestarts++;
                FlightRecorder.routine("AFK_RESTART step=" + s + " attempt=" + stepRestarts + "/" + MAX_STEP_RESTARTS
                        + " reason=" + reason.replace(' ', '_'));
                p.sendMessage(Text.literal("Ship routine: " + reason + " - restarting this step by itself ("
                        + stepRestarts + "/" + MAX_STEP_RESTARTS + ")"), false);
                afkRestart = true;
                phase = Phase.DELAY;
                delayLeft = 60;
                return;
            }
        }
        phase = Phase.ABORTED;
        alertLeft = 100;
        if (p != null) {
            p.sendMessage(Text.literal("Ship routine STOPPED: " + reason + " - you have control. Fix it, then press the step key to retry."), false);
        }
    }

    private static void go(int newSub) {
        chestClimbActive = false;
        chestClimbJumpIssued = false;
        chestClimbTicks = 0;
        chestClimbGroundTicks = 0;
        sub = newSub;
        subTick = 0;
        waitLeft = -1;
        walkBest = 1e9;
        stuckTicks = 0;
        aimOk = 0;
    }

    /** Human pause: returns true once, after a random number of ticks between min and max. */
    private static boolean waited(int min, int max) {
        if (waitLeft < 0) waitLeft = min + RND.nextInt(max - min + 1) + PingGuard.extraTicks();
        if (waitLeft > 0) {
            waitLeft--;
            return false;
        }
        waitLeft = -1;
        return true;
    }

    // ------------------------------------------------------------------ checks before a step

    private static String precheck(MinecraftClient c, ClientPlayerEntity p, Step s) {
        // v2.0.10: take the pickaxe / pearls from the inventory into the hotbar when they are missing
        if (!FlightConfig.routineDryRun && s != Step.STORE_ELYTRA) {
            if (s.ordinal() <= Step.DIG_EXIT.ordinal() && pickaxeSlot(p) < 0) refillHotbar(c, p, true);
            if (hotbarSlot(p, Items.ENDER_PEARL) < 0) refillHotbar(c, p, false);
        }
        // v2.0.12: a pearl costs about 5 hp and the drop before it costs more: leave the ship with enough health
        if (s == Step.PEARL_OUT && !FlightConfig.routineDryRun && p.getHealth() < 13.0f) {
            return "health " + fmt(p.getHealth()) + " is below 13 before leaving the ship (a pearl costs 5)";
        }
        // v2.0.10: the frame entity is only needed until the elytra is taken. After that the saved ship position is used.
        if (s.ordinal() <= Step.TAKE_ELYTRA.ordinal() && (frame == null || frame.isRemoved())) return "the item frame is gone";
        switch (s) {
            case EQUIP_PICKAXE -> {
                if (pickaxeSlot(p) < 0) return "no pickaxe in the hotbar";
                if (hotbarSlot(p, Items.ENDER_PEARL) < 0) return "no ender pearl in the hotbar";
                if (FlightConfig.pearlRescue && countItem(p, Items.ENDER_PEARL) < 2) {
                    return "you need 2 ender pearls: one for the exit and one for the void rescue";
                }
            }
            case DROP -> {
                if (p.getY() < fy - 1.0) return null;          // already inside the ship (retry): nothing to check
                BlockPos d = blockAt(DROP_BLOCK);
                BlockState st = c.world.getBlockState(d);
                if (st.isAir()) {
                    double fall = plannedFall(c, d);
                    double near = Math.hypot(p.getX() - (d.getX() + 0.5), p.getZ() - (d.getZ() + 0.5));
                    if (near > 1.25 || Double.isNaN(fall) || fall < 3.9 || fall > 6.3)
                        return "the drop block is already air, but the hole is not safely reachable";
                } else if (!isPurpur(st)) {
                    return "unexpected block " + blockId(c, d) + " at the drop block " + blockLocal(d);
                }
            }
            case DIG_UNDER_SHULKER -> {
                BlockState st = c.world.getBlockState(blockAt(UNDER_BLOCK));
                if (!st.isAir() && !isPurpur(st)) return "unexpected block " + blockId(c, blockAt(UNDER_BLOCK)) + " under the shulker";
            }
            case TAKE_ELYTRA -> {
                if (!frame.getHeldItemStack().isOf(Items.ELYTRA)) return "the frame has no elytra";
            }
            case STORE_ELYTRA -> { }
            case DIG_EXIT -> {
                for (double[] g : new double[][]{EXIT_UP, EXIT_LOW}) {
                    BlockState st = c.world.getBlockState(blockAt(g));
                    if (!st.isAir() && !isPurpur(st)) return "unexpected block " + blockId(c, blockAt(g)) + " in the exit tunnel";
                }
                if (!solid(c, blockAt(EXIT_FLOOR))) return "the tunnel has no floor block";
                String chest = chestProblem(c);
                if (chest != null) return chest;
            }
            case PEARL_OUT -> {
                if (hotbarSlot(p, Items.ENDER_PEARL) < 0) return "no ender pearl in the hotbar";
                // never walk through a block that is still there: both planned exit blocks must be gone
                String tunnel = tunnelProblem(c);
                if (tunnel != null) return tunnel;
                String chest = chestProblem(c);
                if (chest != null) return chest;
                // never leave the ship if the flight could not start afterwards
                String refusal = FlightRunner.startRefusal(c, 0);
                if (refusal != null) return "the flight could not start after leaving: " + refusal;
            }
            case TAKEOFF -> {
                if (FlightRunner.isRunning()) return "a flight is already running";
                String refusal = FlightRunner.startRefusal(c, 0);
                if (refusal != null) return "the flight cannot start: " + refusal;
                if (hotbarSlot(p, Items.FIREWORK_ROCKET) < 0) return "no firework rockets in the hotbar";
                if (FlightConfig.pearlRescue && hotbarSlot(p, Items.ENDER_PEARL) < 0) return "no ender pearl in the hotbar (pearl rescue is on)";
            }
        }
        return null;
    }

    private static String positionCheck(ClientPlayerEntity p, Step s) {
        float need = s == Step.DROP ? 10f : 8f;
        if (s != Step.STORE_ELYTRA && p.getHealth() < need) return "health " + fmt(p.getHealth()) + " is below " + (int) need;
        if (s == Step.DROP) {
            boolean inside = p.getY() < fy - 1.0;
            double hd = Math.hypot(p.getX() - fx, p.getZ() - fz);
            if (!inside && (p.getY() < fy + 1.5 || p.getY() > fy + 9.0 || hd > 16.0)) {
                return "you are not on the ship roof or the drop ledge (Y " + fmt(p.getY()) + ", frame Y " + fmt(fy) + ")";
            }
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
            case STORE_ELYTRA -> info.add("would open /enderchest and store extra unenchanted Elytras; keep all enchanted Elytras and one plain backup");
            case DIG_EXIT -> {
                info.add("would break " + blockText(c, EXIT_UP));
                info.add("would break " + blockText(c, EXIT_LOW));
                info.add("tunnel floor " + blockText(c, EXIT_FLOOR));
            }
            case PEARL_OUT -> {
                String gw = FlightConfig.routineGlideExit ? glideChecks(c, p, true) : "glide exit is switched off in the settings";
                if (gw == null) {
                    info.add("would GLIDE out of the tunnel (elytra, then the normal flight)");
                    if (glideGround != null) {
                        info.add("backup ground if the elytra does not open (pearl): x " + fmt(glideGround[0]) + " y " + fmt(glideGround[1]) + " z " + fmt(glideGround[2]));
                    }
                } else {
                    info.add("cannot glide: " + gw + " - would use the PEARL");
                }
                long t0 = System.nanoTime();
                PearlPlan pl = scanPearl(c, theoryEye());
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                FlightRecorder.routine("PEARL_SCAN ms=" + ms + " tried=" + scTried + " found=" + (pl != null) + " mode=dry_run_one_tick");
                if (pl == null) {
                    info.add("pearl from the tunnel: no safe ground (" + scanSummary() + ")");
                    if (gw != null) {
                        abort(c, "cannot glide (" + gw + ") and no safe pearl ground (" + scanSummary() + ")");
                        return;
                    }
                } else {
                    info.add("pearl from the tunnel: yaw " + fmt(pl.yaw) + " pitch " + fmt(pl.pitch) + " lands " + fmt(pl.dist) + " blocks away at Y " + fmt(pl.landY)
                            + " (" + local(pl.landX, pl.landY, pl.landZ) + ")");
                }
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
        if (tickAutoGoldenApple(c)) return;
        Step s = STEPS[stepIndex];
        if (c.currentScreen != null && s != Step.STORE_ELYTRA) {
            releaseKeys(c);
            return;
        }
        boolean flightHandOver = s == Step.PEARL_OUT && sub >= 8;      // the flight owns the keys now
        if (p.getHealth() >= 8.0f) healthWait = 0;
        if (p.getHealth() < 8.0f && !flightHandOver && s != Step.STORE_ELYTRA) {
            // v2.0.12: never stand still while bullets hit us
            boolean actingNow = s != Step.TAKEOFF && !(s == Step.PEARL_OUT && sub >= 4);
            if (actingNow && (p.isOnGround() || p.hasStatusEffect(StatusEffects.LEVITATION))
                    && defend(c, p, p.hasStatusEffect(StatusEffects.LEVITATION))) {
                return;
            }
            // Full Auto: stand still and let the golden apple / regeneration work (up to 60 s) instead of stopping
            if (FlightConfig.routineMode == 2 && ++healthWait <= 1200) {
                releaseKeys(c);
                return;
            }
            abort(c, "health below 8 (" + fmt(p.getHealth()) + ")");
            return;
        }
        // v2.0.14: very high ping: wait before risky server side actions (not a stop, at most about 60 s per step)
        if (PingGuard.spike() && pingPause < 1200 && !flightHandOver && riskyForPing(s, sub)) {
            if (pingPause++ % 100 == 0) {
                FlightRecorder.routine("PING_WAIT step=" + s + " sub=" + sub + " ping=" + PingGuard.ms());
                p.sendMessage(Text.literal("Ship routine: ping is " + PingGuard.ms() + " ms - waiting for it to drop"), true);
            }
            releaseKeys(c);
            return;
        }
        boolean lev = p.hasStatusEffect(StatusEffects.LEVITATION);
        boolean acting = s != Step.TAKEOFF && !(s == Step.PEARL_OUT && sub >= 4);
        if (s == Step.DIG_EXIT && (p.isOnGround() || lev) && fightExitShulker(c, p)) {
            stepTicks++;
            if (stepTicks > s.timeout) abort(c, "timeout in step " + s);
            return;
        }
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
            if (airTicks > 18 && !chestClimbActive) {
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
            case STORE_ELYTRA -> stepStoreElytra(c, p);
            case DIG_EXIT -> stepExit(c, p);
            case PEARL_OUT -> stepPearl(c, p);
            case TAKEOFF -> stepTakeoff(c, p);
        }
    }

    private static void stepEquip(MinecraftClient c, ClientPlayerEntity p) {
        int slot = pickaxeSlot(p);
        if (slot < 0) {
            if (refillHotbar(c, p, true)) return;          // v2.0.10: try again next tick
            abort(c, "no pickaxe in the hotbar or inventory");
            return;
        }
        if (sub == 0) {
            if (!waited(2, 4)) return;
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
                if (c.world.getBlockState(d).isAir()) {
                    double near = Math.hypot(p.getX() - tx, p.getZ() - tz);
                    if (near <= 1.25 && Math.abs(p.getY() - standY) <= 0.8) {
                        go(3); // the hole was opened manually; use the same verified fall path
                        return;
                    }
                    abort(c, "the drop hole is already open, but you are not safely beside it");
                    return;
                }
                int r = walkTo(c, p, tx, tz, 0.40, false);
                if (r == W_UNSAFE) {
                    if (!retryNavigation(c, "unsafe route to the drop block")) abort(c, "unsafe path: a wall or a drop blocks the way");
                } else if (r == W_STUCK) {
                    if (!retryNavigation(c, "stuck on the way to the drop block")) abort(c, "stuck on the way to the drop block");
                }
                else if (r == W_ARRIVED) {
                    if (Math.abs(p.getY() - standY) > 0.7) {
                        abort(c, "not standing on the drop block (Y " + fmt(p.getY()) + ", expected " + fmt(standY) + ")");
                    } else {
                        go(1);
                    }
                }
            }
            case 1 -> {
                if (!waited(2, 4)) return;
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
                        int r = walkTo(c, p, tx, tz, 0.08, true); // not centred: nudge precisely into the hole
                        if (r == W_UNSAFE) {
                            if (!retryNavigation(c, "blocked while centring over the drop hole"))
                                abort(c, "blocked while centring over the drop hole");
                        } else if (r == W_STUCK) {
                            if (!retryNavigation(c, "stuck while centring over the drop hole"))
                                abort(c, "stuck while centring over the drop hole");
                        }
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
            if (!waited(2, 5)) return;
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
            if (countElytra(p) > elytraAtArm) finishStep(c, "OK", "frame_gone_elytra=" + elytraAtArm + "->" + countElytra(p));
            else abort(c, "the item frame is gone");
            return;
        }

        // Do not attack every nearby shulker. Only intervene when it is inside the ship,
        // close to the frame, and has line of sight to the player.
        if (sub < 10) {
            ShulkerEntity threat = findFrameThreat(c, p);
            if (threat != null) {
                int weapon = swordSlot(p);
                if (weapon < 0) weapon = pickaxeSlot(p);
                if (weapon < 0) {
                    abort(c, "a shulker is blocking the frame, but no sword or pickaxe is in the hotbar");
                    return;
                }
                frameThreat = threat;
                p.getInventory().setSelectedSlot(weapon);
                frameThreatAttackCd = 0;
                FlightRecorder.routine("FRAME_SHULKER_BLOCKER pos=" + local(threat.getX(), threat.getY(), threat.getZ())
                        + " weapon=" + Registries.ITEM.getId(p.getInventory().getStack(weapon).getItem()));
                go(10);
                return;
            }
        }

        switch (sub) {
            case 0 -> {
                // First line up sideways with the frame's safe ledge. Keeping the
                // current forward coordinate avoids cutting diagonally across its step.
                if (subTick == 1) frameApproachA = localA(p.getX(), p.getZ());
                int r = walkTo(c, p, wx(frameApproachA, SPOT_FRAME[1]), wz(frameApproachA, SPOT_FRAME[1]), 0.42, false);
                if (r == W_UNSAFE) {
                    if (!retryNavigation(c, "unsafe route lining up beside the frame")) abort(c, "unsafe path: a wall or a drop blocks the way");
                } else if (r == W_STUCK) {
                    if (!retryNavigation(c, "stuck lining up beside the frame")) abort(c, "stuck while lining up beside the frame");
                }
                else if (r == W_ARRIVED) go(1);
            }
            case 1 -> {
                // Now approach straight along the frame axis at the lined-up left offset.
                int r = walkTo(c, p, wx(SPOT_FRAME[0], SPOT_FRAME[1]), wz(SPOT_FRAME[0], SPOT_FRAME[1]), 0.42, false);
                if (r == W_UNSAFE) {
                    if (!retryNavigation(c, "unsafe straight approach to the frame")) abort(c, "unsafe path: a wall or a drop blocks the way");
                } else if (r == W_STUCK) {
                    if (!retryNavigation(c, "stuck on straight approach to the frame")) abort(c, "stuck on the straight approach to the frame");
                }
                else if (r == W_ARRIVED) {
                    if (Math.abs(p.getY() - (fy + SPOT_FRAME[2])) > 0.7) abort(c, "not on the ledge in front of the frame");
                    else go(2);
                }
            }
            case 2 -> {
                String why = standCheck(c, p);
                if (why != null) {
                    abort(c, why);
                    return;
                }
                double err = look(p, new Vec3d(fx, fy, fz));
                boolean on = c.crosshairTarget instanceof EntityHitResult eh && eh.getEntity() == frame;
                if (err < 1.0 && on) {
                    if (waited(2, 3)) {
                        c.interactionManager.attackEntity(p, frame);
                        p.swingHand(Hand.MAIN_HAND);
                        hitTries++;
                        go(3);
                    }
                } else if (subTick > 100) {
                    abort(c, "cannot aim at the frame");
                }
            }
            case 3 -> {
                int cnt = countElytra(p);
                if (cnt > elytraBefore) {
                    finishStep(c, "OK", "elytra=" + elytraBefore + "->" + cnt + " after_hit_ticks=" + subTick
                            + " stand=" + local(p.getX(), p.getY(), p.getZ()));
                    return;
                }
                if (subTick > 60) {
                    if (frame.getHeldItemStack().isOf(Items.ELYTRA) && hitTries < 3) go(2);
                    else abort(c, frame.getHeldItemStack().isOf(Items.ELYTRA) ? "the frame still has the elytra" : "the elytra was not picked up");
                }
            }
            case 10 -> handleFrameShulker(c, p);
            case 11 -> {
                // Return sideways to the frame's left offset before moving forward.
                if (subTick == 1) frameApproachA = localA(p.getX(), p.getZ());
                int r = walkTo(c, p, wx(frameApproachA, SPOT_FRAME[1]), wz(frameApproachA, SPOT_FRAME[1]), 0.42, false);
                if (r == W_UNSAFE) {
                    if (!retryNavigation(c, "unsafe route lining up after shulker")) abort(c, "unsafe wall or drop while lining up with the item frame");
                } else if (r == W_STUCK) {
                    if (!retryNavigation(c, "stuck lining up after shulker")) abort(c, "stuck while lining up with the item frame after the shulker");
                }
                else if (r == W_ARRIVED) go(12);
            }
            case 12 -> {
                // Finish the return with a straight approach along the frame axis.
                int r = walkTo(c, p, wx(SPOT_FRAME[0], SPOT_FRAME[1]), wz(SPOT_FRAME[0], SPOT_FRAME[1]), 0.42, false);
                if (r == W_UNSAFE) {
                    if (!retryNavigation(c, "unsafe straight return to frame")) abort(c, "unsafe wall or drop on the straight return to the item frame");
                } else if (r == W_STUCK) {
                    if (!retryNavigation(c, "stuck on straight return to frame")) abort(c, "stuck on the straight return to the item frame");
                }
                else if (r == W_ARRIVED) {
                    int pick = pickaxeSlot(p);
                    if (pick >= 0) p.getInventory().setSelectedSlot(pick);
                    if (Math.abs(p.getY() - (fy + SPOT_FRAME[2])) > 0.7) abort(c, "returned near the frame but not to its safe ledge");
                    else {
                        FlightRecorder.routine("FRAME_SHULKER_CLEARED return=" + local(p.getX(), p.getY(), p.getZ()));
                        go(2);
                    }
                }
            }
            default -> { }
        }
    }

    private static ShulkerEntity findFrameThreat(MinecraftClient c, ClientPlayerEntity p) {
        Box area = new Box(fx - 5.0, fy - 4.0, fz - 5.0, fx + 5.0, fy + 3.0, fz + 5.0);
        ShulkerEntity best = null;
        double bestDist = 2.4 * 2.4;
        for (ShulkerEntity shulker : c.world.getEntitiesByClass(ShulkerEntity.class, area,
                e -> !e.isRemoved() && e.isAlive())) {
            double a = localA(shulker.getX(), shulker.getZ());
            double left = localLeft(shulker.getX(), shulker.getZ());
            double h = shulker.getY() - fy;
            // The ship interior is on the positive-a side of the frame. This rejects
            // shulkers that teleport outside/behind the ship near the frame.
            if (a < 0.8 || a > 4.8 || Math.abs(left) > 2.6 || h < -3.6 || h > 1.8) continue;
            double fd = shulker.squaredDistanceTo(fx, fy, fz);
            if (fd > bestDist || !shulker.canSee(p)) continue;
            if (shulker.squaredDistanceTo(p) > 3.4 * 3.4) continue;
            best = shulker;
            bestDist = fd;
        }
        return best;
    }

    private static void handleFrameShulker(MinecraftClient c, ClientPlayerEntity p) {
        if (frameThreat == null || frameThreat.isRemoved() || !frameThreat.isAlive()) {
            frameThreat = null;
            p.getInventory().setSelectedSlot(Math.max(0, pickaxeSlot(p)));
            go(11);
            return;
        }
        double a = localA(frameThreat.getX(), frameThreat.getZ());
        double left = localLeft(frameThreat.getX(), frameThreat.getZ());
        double h = frameThreat.getY() - fy;
        // Never chase a shulker that teleported outside the ship or lost sight of the player.
        if (a < 0.8 || a > 4.8 || Math.abs(left) > 2.6 || h < -3.6 || h > 1.8 || !frameThreat.canSee(p)) {
            FlightRecorder.routine("FRAME_SHULKER_IGNORE moved_or_not_visible pos="
                    + local(frameThreat.getX(), frameThreat.getY(), frameThreat.getZ()));
            frameThreat = null;
            int pick = pickaxeSlot(p);
            if (pick >= 0) p.getInventory().setSelectedSlot(pick);
            go(11);
            return;
        }
        if (!isSword(p.getMainHandStack()) && !isPickaxe(p.getMainHandStack())) {
            int weapon = swordSlot(p);
            if (weapon < 0) weapon = pickaxeSlot(p);
            if (weapon >= 0) p.getInventory().setSelectedSlot(weapon);
        }
        Vec3d target = new Vec3d(frameThreat.getX(), frameThreat.getY() + 0.5, frameThreat.getZ());
        double err = look(p, target);
        boolean on = c.crosshairTarget instanceof EntityHitResult eh && eh.getEntity() == frameThreat;
        double dist = Math.sqrt(frameThreat.squaredDistanceTo(p));
        if (frameThreatAttackCd > 0) frameThreatAttackCd--;
        if (dist <= 3.4 && err < 4.0 && on && frameThreatAttackCd == 0) {
            c.interactionManager.attackEntity(p, frameThreat);
            p.swingHand(Hand.MAIN_HAND);
            frameThreatAttackCd = 10 + RND.nextInt(5);
            FlightRecorder.routine("FRAME_SHULKER_HIT dist=" + fmt(dist));
        }
        if (subTick > 120) {
            abort(c, "the shulker is still blocking the item frame; stopping safely");
        }
    }

    private static void stepStoreElytra(MinecraftClient c, ClientPlayerEntity p) {
        if (sub == 0) {
            storageSourceSlot = -1;
            storageDestSlot = -1;
            p.networkHandler.sendChatCommand("enderchest");
            go(1);
            FlightRecorder.routine("ENDER_CHEST_OPEN_REQUEST plainElytra=" + countPlainElytra(p));
            return;
        }

        if (sub == 1) {
            if (p.currentScreenHandler == p.playerScreenHandler) {
                if (subTick > 100 + 10 * PingGuard.extraTicks()) {
                    finishStep(c, "OK", "enderchest_unavailable; Elytras kept in inventory");
                }
                return;
            }
            net.minecraft.screen.ScreenHandler handler = p.currentScreenHandler;
            int totalSlots = handler.getStacks().size();
            int containerSlots = Math.max(0, totalSlots - 36);
            storageSourceSlot = extraPlainElytraPlayerSlot(p);
            if (storageSourceSlot < 0) {
                p.closeHandledScreen();
                finishStep(c, "OK", "no_extra_plain_elytra; enchanted Elytras and one backup kept");
                return;
            }
            for (int i = 0; i < containerSlots; i++) {
                if (handler.getSlot(i).getStack().isEmpty()) {
                    storageDestSlot = i;
                    break;
                }
            }
            if (storageDestSlot < 0) {
                p.closeHandledScreen();
                finishStep(c, "OK", "enderchest_full; extra Elytra kept in inventory");
                return;
            }
            int containerPlayerSlot = toContainerPlayerSlot(storageSourceSlot, containerSlots);
            if (containerPlayerSlot < 0 || containerPlayerSlot >= totalSlots) {
                p.closeHandledScreen();
                finishStep(c, "OK", "could_not_map_inventory_slot; Elytra kept in inventory");
                return;
            }
            c.interactionManager.clickSlot(handler.syncId, containerPlayerSlot, 0, SlotActionType.PICKUP, p);
            c.interactionManager.clickSlot(handler.syncId, storageDestSlot, 0, SlotActionType.PICKUP, p);
            go(2);
            return;
        }

        if (sub == 2) {
            net.minecraft.screen.ScreenHandler handler = p.currentScreenHandler;
            if (handler == p.playerScreenHandler) {
                finishStep(c, "OK", "enderchest_closed_after_transfer");
                return;
            }
            if (handler.getCursorStack().isEmpty() && subTick >= 3 + PingGuard.extraTicks()) {
                // Keep going until only the protected plain backup remains, or the chest is full.
                int nextSource = extraPlainElytraPlayerSlot(p);
                int totalSlots = handler.getStacks().size();
                int containerSlots = Math.max(0, totalSlots - 36);
                int nextDest = -1;
                if (nextSource >= 0) {
                    for (int i = 0; i < containerSlots; i++) {
                        if (handler.getSlot(i).getStack().isEmpty()) {
                            nextDest = i;
                            break;
                        }
                    }
                }
                if (nextSource >= 0 && nextDest >= 0) {
                    int nextContainerPlayerSlot = toContainerPlayerSlot(nextSource, containerSlots);
                    if (nextContainerPlayerSlot >= 0 && nextContainerPlayerSlot < totalSlots) {
                        storageSourceSlot = nextSource;
                        storageDestSlot = nextDest;
                        c.interactionManager.clickSlot(handler.syncId, nextContainerPlayerSlot, 0, SlotActionType.PICKUP, p);
                        c.interactionManager.clickSlot(handler.syncId, nextDest, 0, SlotActionType.PICKUP, p);
                        subTick = 0;
                        return;
                    }
                }
                p.closeHandledScreen();
                FlightRecorder.routine("ENDER_CHEST_STORED through source=" + storageSourceSlot + " dest=" + storageDestSlot);
                finishStep(c, "OK", nextSource >= 0 ? "enderchest_full; remaining_extras_kept_in_inventory"
                        : "stored_extras; enchanted_elytras_and_one_plain_backup_kept");
            } else if (!handler.getCursorStack().isEmpty() && subTick > 25 + 3 * PingGuard.extraTicks()) {
                // Closing returns any cursor item to the player's inventory; never drop it.
                p.closeHandledScreen();
                finishStep(c, "OK", "transfer_not_confirmed; Elytra returned to inventory");
            }
        }
    }

    /** PlayerScreenHandler slot index of an extra plain Elytra; enchanted items are never candidates. */
    private static int extraPlainElytraPlayerSlot(ClientPlayerEntity p) {
        int plain = countPlainElytra(p);
        if (plain <= 1) return -1; // keep one plain backup; all enchanted Elytras are protected
        for (int inv = 0; inv < 36; inv++) {
            ItemStack stack = p.getInventory().getStack(inv);
            if (stack.isOf(Items.ELYTRA) && !stack.hasEnchantments()) {
                return inv < 9 ? 36 + inv : inv;
            }
        }
        return -1;
    }

    private static int countPlainElytra(ClientPlayerEntity p) {
        int count = 0;
        ItemStack worn = p.getEquippedStack(EquipmentSlot.CHEST);
        if (worn.isOf(Items.ELYTRA) && !worn.hasEnchantments()) count++;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = p.getInventory().getStack(i);
            if (stack.isOf(Items.ELYTRA) && !stack.hasEnchantments()) count += stack.getCount();
        }
        return count;
    }

    /** Maps a PlayerScreenHandler slot (9..44) to the same inventory slot in a container handler. */
    private static int toContainerPlayerSlot(int playerSlot, int containerSlots) {
        if (playerSlot >= 9 && playerSlot <= 35) return containerSlots + playerSlot - 9;
        if (playerSlot >= 36 && playerSlot <= 44) return containerSlots + 27 + playerSlot - 36;
        return -1;
    }

    private static void stepExit(MinecraftClient c, ClientPlayerEntity p) {
        BlockPos t1 = blockAt(EXIT_UP), t2 = blockAt(EXIT_LOW);
        switch (sub) {
            case 0 -> {
                // ledge -> (approach, one jump, verified landing) -> chest top -> centre of the chest
                if (subTick == 1) routeBegin(c, p);
                int r = walkTo(c, p, wx(SPOT_DIG[0], SPOT_DIG[1]), wz(SPOT_DIG[0], SPOT_DIG[1]), 0.20, false);
                if (r == W_UNSAFE) {
                    if (!retryNavigation(c, "unsafe path: wall or drop")) abort(c, "unsafe path to the chest top (see the DIAG lines in the log)");
                } else if (r == W_STUCK) {
                    if (!retryNavigation(c, "stuck on the way to the chest")) abort(c, "stuck on the way to the chest top");
                }
                else if (r == W_ARRIVED) {
                    int loc = locate(c, p);
                    if (loc != L_CHEST) {
                        diag(c, p, "DIG_STANCE_REJECTED");
                        abort(c, "not standing on the chest top (" + locName(loc) + ", " + local(p.getX(), p.getY(), p.getZ()) + ")");
                    } else {
                        routeNote(p, "ARRIVED_DIG_STANCE");
                        go(1);
                    }
                }
            }
            case 1, 3 -> {
                if (!waited(2, 4)) return;
                if (locate(c, p) != L_CHEST) {
                    diag(c, p, "LEFT_DIG_STANCE");
                    abort(c, "you are no longer on the chest top (" + local(p.getX(), p.getY(), p.getZ()) + ")");
                    return;
                }
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
            if (refillHotbar(c, p, false)) return;         // v2.0.10: try again next tick
            abort(c, "no ender pearl in the hotbar or inventory");
            return;
        }
        switch (sub) {
            case 0 -> {
                // Two legs: ledge -> chest centre (the explicit chest climb), then chest centre -> tunnel centre.
                if (subTick == 1) routeBegin(c, p);
                int loc = locate(c, p);
                double[] goal = (loc == L_LEDGE || loc == L_UNKNOWN) ? SPOT_DIG : SPOT_PEARL;
                int r = walkTo(c, p, wx(goal[0], goal[1]), wz(goal[0], goal[1]), 0.20, false);
                if (r == W_UNSAFE) {
                    if (!retryNavigation(c, "unsafe route to pearl spot") && !glideFromHere(c, p, "unsafe route to the pearl spot")) abort(c, "unsafe path to the pearl spot (see the DIAG lines in the log)");
                } else if (r == W_STUCK) {
                    if (!retryNavigation(c, "stuck on the way to pearl spot") && !glideFromHere(c, p, "stuck on the way to the pearl spot")) abort(c, "stuck on the way to the pearl spot");
                }
                else if (r == W_ARRIVED) {
                    int at = locate(c, p);
                    if (at == L_TUNNEL && goal == SPOT_PEARL) {
                        routeNote(p, "ARRIVED_PEARL_STANCE");
                        go(1);
                    } else if (at == L_CHEST && goal == SPOT_DIG) {
                        routeNote(p, "ON_CHEST_CENTRED");     // next tick: second leg into the tunnel
                    } else {
                        diag(c, p, "PEARL_STANCE_REJECTED");
                        abort(c, "not at the pearl stance (" + locName(at) + ", " + local(p.getX(), p.getY(), p.getZ()) + ")");
                    }
                }
            }
            case 1 -> {
                // v2.0.9: GLIDE FIRST. The pearl is only the backup when gliding is not possible.
                if (!pearlOnly && FlightConfig.routineGlideExit) {
                    if (!waited(2, 5)) return;
                    String gw = glideChecks(c, p, true);
                    if (gw == null) {
                        FlightRecorder.routine("GLIDE_EXIT_START pos=" + local(p.getX(), p.getY(), p.getZ()) + " hp=" + fmt(p.getHealth())
                                + " fallback=" + fmt(glideGround[0]) + "," + fmt(glideGround[1]) + "," + fmt(glideGround[2]));
                        go(6);
                        return;
                    }
                    pearlOnly = true;
                    FlightRecorder.routine("GLIDE_NOT_POSSIBLE reason=" + gw.replace(' ', '_') + " -> pearl backup");
                    p.sendMessage(Text.literal("Ship routine: cannot glide (" + gw + ") - using the pearl instead"), false);
                    subTick = 0;
                }
                if (!scanActive) {
                    if (!waited(2, 5)) return;
                    String why = standCheck(c, p);
                    if (why != null) {
                        abort(c, why);
                        return;
                    }
                    scanBegin(new Vec3d(p.getX(), p.getEyeY() - 0.1, p.getZ()));
                }
                if (!scanStep(c, SCAN_SLICE_NANOS)) return;          // not finished: continues next tick
                plan = scBest;
                FlightRecorder.routine("PEARL_SCAN ms=" + (scNanos / 1_000_000L) + " ticks=" + scSlices + " tried=" + scTried
                        + " found=" + (plan != null));
                if (plan == null) {
                    String sum = scanSummary();
                    FlightRecorder.routine("PEARL_SCAN_FAILED " + sum.replace(' ', '_'));
                    String gw = FlightConfig.routineGlideExit ? glideChecks(c, p, false) : "glide exit is switched off";
                    abort(c, "no safe ground for the pearl (" + sum + ") and no glide: " + (gw == null ? "glide was not used" : gw));
                    return;
                }
                FlightRecorder.routine("PEARL_PLAN yaw=" + fmt(plan.yaw) + " pitch=" + fmt(plan.pitch) + " dist=" + fmt(plan.dist)
                        + " land=" + local(plan.landX, plan.landY, plan.landZ) + " from=" + local(p.getX(), p.getY(), p.getZ()));
                go(2);
            }
            case 2 -> {
                if (subTick == 1) p.getInventory().setSelectedSlot(slot);
                if (waited(3, 6)) go(3);
            }
            case 3 -> {
                if (!p.getMainHandStack().isOf(Items.ENDER_PEARL)) {
                    p.getInventory().setSelectedSlot(slot);
                    return;
                }
                double err = lookAngles(p, plan.yaw, plan.pitch);
                aimOk = err < 0.3 ? aimOk + 1 : 0;
                if (aimOk >= 4 && p.isOnGround() && waited(2, 3)) {
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
                if (now - throwNow == 15 + 2 * PingGuard.extraTicks() && countItem(p, Items.ENDER_PEARL) >= pearlBefore) abort(c, "the pearl was not thrown");
                else if (now - throwNow > 160 + 3 * PingGuard.extraTicks() && (now - throwNow > 600 || !ownPearlFlying(c, p))) abort(c, "the pearl did not teleport you");
            }
            case 6 -> {
                // put the elytra on (the runner may have put a chestplate on after landing)
                if (p.hasStatusEffect(StatusEffects.LEVITATION)) return;
                if (p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
                    if (waited(3, 7)) go(7);
                } else {
                    if (subTick % 10 == 1) swapElytraIntoChest(c);
                    if (subTick > 80) abort(c, "could not put the elytra on");
                }
            }
            case 7 -> {
                // walk straight out of the tunnel (backwards, away from the wall) and fall
                if (!p.isOnGround()) {
                    c.options.forwardKey.setPressed(true);
                    if (++glideAir >= 3) {
                        releaseKeys(c);
                        FlightRecorder.routine("GLIDE_EXIT_JUMPED pos=" + local(p.getX(), p.getY(), p.getZ()));
                        if (glideGround != null) FlightRunner.setFallbackIsland(glideGround[0], glideGround[1], glideGround[2]);
                        FlightRunner.startFromShipRoutine(c);
                        releaseKeys(c);
                        go(8);
                    }
                    return;
                }
                glideAir = 0;
                // last look before the point of no return (still standing on the tunnel floor): can the flight really start?
                String gw = glideChecks(c, p, false);
                if (gw != null) {
                    // v2.0.9: still standing in the tunnel, so the pearl is still possible: go back to the pearl stance
                    pearlOnly = true;
                    releaseKeys(c);
                    FlightRecorder.routine("GLIDE_CANCELLED_IN_TUNNEL reason=" + gw.replace(' ', '_') + " -> pearl backup");
                    p.sendMessage(Text.literal("Ship routine: glide not possible any more (" + gw + ") - using the pearl instead"), false);
                    go(0);
                    return;
                }
                int r = walkTo(c, p, wx(-4.0, SPOT_PEARL[1]), wz(-4.0, SPOT_PEARL[1]), 0.20, true);
                if (r == W_UNSAFE) {
                    if (!retryNavigation(c, "blocked while stepping out for glide") && !walkOff(c, p)) abort(c, "blocked while stepping out of the tunnel");
                } else if (r == W_STUCK) {
                    if (!retryNavigation(c, "stuck while stepping out for glide") && !walkOff(c, p)) abort(c, "stuck while stepping out of the tunnel");
                }
                if (subTick > 120) {
                    if (!retryNavigation(c, "glide exit route timed out") && !walkOff(c, p)) abort(c, "could not step out of the tunnel");
                }
            }
            case 8 -> {
                // v2.0.9: the glide is the plan, the pearl is the backup when the elytra does not open
                if (FlightRunner.isRunning() && p.isGliding()) {
                    FlightRecorder.routine("STEP_END step=PEARL_OUT start=" + stepStartClock + " end=" + FlightRecorder.clock()
                            + " ticks=" + (now - stepStartNow) + " result=GLIDE pos=" + local(p.getX(), p.getY(), p.getZ())
                            + " pearlBackups=" + emergencyThrows);
                    stepIndex = STEPS.length - 1;          // the flight is running: the routine is finished
                    finishStep(c, "OK", "glide_exit");
                    return;
                }
                if (p.isOnGround()) {
                    if (emergencyThrows > 0 && !FlightRunner.isRunning()) {
                        abort(c, "the glide failed and the pearl backup put you on the ground - take control");
                    } else if (!FlightRunner.isRunning() && subTick > 5) {
                        abort(c, "the flight did not start (read the message above) - you are on the ground");
                    }
                    return;
                }
                // in the air and not gliding
                boolean runnerGone = !FlightRunner.isRunning() && subTick > 2;
                boolean notOpen = FlightRunner.isRunning() && subTick >= 21;
                if (runnerGone || notOpen) {
                    if (emergencyThrows == 0 || now - lastEmergencyTick > 40) {
                        if (emergencyThrows < 3 && FlightRunner.emergencyPearl(c)) {
                            emergencyThrows++;
                            lastEmergencyTick = (int) now;
                            FlightRecorder.routine("GLIDE_FAILED_PEARL_BACKUP try=" + emergencyThrows + " pos="
                                    + local(p.getX(), p.getY(), p.getZ()) + " vy=" + fmt(p.getVelocity().y)
                                    + " runner=" + FlightRunner.isRunning());
                            p.sendMessage(Text.literal("Ship routine: the elytra did not open - pearl backup thrown (" + emergencyThrows + ")"), false);
                        } else if (emergencyThrows >= 3 || subTick % 20 == 1) {
                            alertLeft = 100;
                            p.sendMessage(Text.literal("Ship routine WARNING: not gliding and the pearl backup is not possible - "
                                    + "you are falling! Press jump, or sneak to take control."), false);
                            if (runnerGone && emergencyThrows >= 3) abort(c, "the glide failed and the pearl backup did not work - you are in the air, take control!");
                        }
                    }
                }
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
            if (!waited(4, 10)) return;
            FlightRunner.startFromShipRoutine(c);
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
     * v2.0.8: a shulker that teleported next to / into the way out (above the chest, in the exit tunnel) blocks the
     * jump onto the chest and keeps shooting. This kills it with the sword (or the pickaxe) while it is in reach (3 blocks).
     * It never chases: a shulker out of reach is left alone. Returns true while it is busy fighting.
     */
    private static boolean fightExitShulker(MinecraftClient c, ClientPlayerEntity p) {
        if (exitShulkerGiveUp) return false;
        if (exitShulkerCd > 0) exitShulkerCd--;
        if (exitShulker != null && (exitShulker.isRemoved() || !exitShulker.isAlive())) {
            FlightRecorder.routine("EXIT_SHULKER_KILLED ticks=" + exitShulkerTicks);
            exitShulker = null;
            exitShulkerTicks = 0;
            defendTicks = 0;                       // the bullet budget starts again once the source is dead
            int pick = pickaxeSlot(p);
            if (pick >= 0) p.getInventory().setSelectedSlot(pick);
            return false;
        }
        double ex = p.getX(), ey = p.getEyeY(), ez = p.getZ();
        if (exitShulker == null) {
            double best = 3.0 * 3.0;
            Box box = new Box(ex - 3.5, ey - 3.5, ez - 3.5, ex + 3.5, ey + 3.5, ez + 3.5);
            for (ShulkerEntity sh : c.world.getEntitiesByClass(ShulkerEntity.class, box, e -> !e.isRemoved() && e.isAlive())) {
                double d = sq(sh.getX() - ex) + sq(sh.getY() + 0.5 - ey) + sq(sh.getZ() - ez);
                if (d < best) {
                    best = d;
                    exitShulker = sh;
                }
            }
            if (exitShulker == null) return false;
            FlightRecorder.routine("EXIT_SHULKER_FOUND pos=" + local(exitShulker.getX(), exitShulker.getY(), exitShulker.getZ())
                    + " dist=" + fmt(Math.sqrt(best)));
        }
        double dist = Math.sqrt(sq(exitShulker.getX() - ex) + sq(exitShulker.getY() + 0.5 - ey) + sq(exitShulker.getZ() - ez));
        if (dist > 3.6) {                           // it moved away: stop, do not chase it
            FlightRecorder.routine("EXIT_SHULKER_OUT_OF_REACH dist=" + fmt(dist));
            exitShulker = null;
            return false;
        }
        if (++exitShulkerTicks > 200) {
            FlightRecorder.routine("EXIT_SHULKER_GIVE_UP could not hit it in 200 ticks");
            exitShulkerGiveUp = true;
            exitShulker = null;
            int pick = pickaxeSlot(p);
            if (pick >= 0) p.getInventory().setSelectedSlot(pick);
            return false;
        }
        releaseKeys(c);
        if (!isSword(p.getMainHandStack())) {
            int weapon = swordSlot(p);
            if (weapon < 0) weapon = pickaxeSlot(p);
            if (weapon >= 0) p.getInventory().setSelectedSlot(weapon);
        }
        Vec3d aim = new Vec3d(exitShulker.getX(), exitShulker.getY() + 0.5, exitShulker.getZ());
        double err = look(p, aim);
        say(c, "Shulker blocks the way out! Attacking it");
        boolean on = c.crosshairTarget instanceof EntityHitResult eh && eh.getEntity() == exitShulker;
        if (dist <= 3.0 && err < 4.0 && on && exitShulkerCd == 0) {
            c.interactionManager.attackEntity(p, exitShulker);
            p.swingHand(Hand.MAIN_HAND);
            exitShulkerCd = 10 + RND.nextInt(5);
            FlightRecorder.routine("EXIT_SHULKER_HIT dist=" + fmt(dist) + " hp=" + fmt(exitShulker.getHealth())
                    + " held=" + Registries.ITEM.getId(p.getMainHandStack().getItem()));
        }
        return true;
    }

    /**
     * A shulker bullet that flies at you gives levitation (and about 4 damage). A bullet dies from one hit,
     * with any item in the hand (the held item is never changed here). This turns to the bullet, waits until it
     * is in reach (3 blocks) and the crosshair is exactly on it, then hits it. Nothing else is ever hit.
     * Returns true while it is busy with a bullet (the normal step waits).
     */
    private static boolean defend(MinecraftClient c, ClientPlayerEntity p, boolean lev) {
        if (bulletCd > 0) bulletCd--;
        if (bulletGiveUp) {
            if (p.getHealth() < 9.0f) {          // v2.0.12: hurt again: fight the bullets again instead of ignoring them
                bulletGiveUp = false;
                defendTicks = 0;
                FlightRecorder.routine("BULLETS_FIGHT_AGAIN hp=" + fmt(p.getHealth()));
            } else {
                return false;
            }
        }
        if (bullet != null && bullet.isRemoved()) {
            if (bulletHitNow > 0) {
                bulletsStopped++;
                FlightRecorder.routine("BULLET_DESTROYED after=" + (now - bulletHitNow) + " total=" + bulletsStopped);
            }
            bullet = null;
            bulletHitNow = 0;
            bulletLogged = false;
        }
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
            if (FlightConfig.routineMode == 2) {
                // v2.0.9: do not give control away: stop fighting bullets and carry on with the step
                bulletGiveUp = true;
                bullet = null;
                bulletLogged = false;
                FlightRecorder.routine("BULLETS_IGNORED total=" + bulletsStopped + " -> carrying on");
                return false;
            }
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
            bulletHitNow = now;
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
        if (Double.isNaN(top) || p.getY() - top > 3.0) {
            diag(c, p, "STAND_CHECK_FAILED");
            return "no solid block within 3 blocks under your feet";
        }
        return null;
    }

    // ------------------------------------------------------------------ mining

    private static void mineReset() {
        mineTicks = 0;
        aimIdx = 0;
        aimMiss = 0;
        mineFail = "";
        reachWalk = 0;
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
        if (dist > 4.0 && (reachWalk > 0 || dist > 4.4)) {
            // v2.0.8: knocked back (shulker hit): walk back towards the block instead of giving up
            double hd = Math.hypot(center.x - p.getX(), center.z - p.getZ());
            if (hd > 9.0 || ++reachWalk > 160) {
                c.options.forwardKey.setPressed(false);
                c.options.jumpKey.setPressed(false);
                mineFail = "the block " + blockLocal(bp) + " is out of reach (" + fmt(dist) + " blocks)";
                return M_FAIL;
            }
            float yaw = (float) Math.toDegrees(Math.atan2(-(center.x - p.getX()), center.z - p.getZ()));
            lookAngles(p, yaw, 15f);
            double ux = -Math.sin(Math.toRadians(yaw)), uz = Math.cos(Math.toRadians(yaw));
            boolean floorAhead = c.world.getBlockCollisions(p, p.getBoundingBox().offset(ux * 0.7, 0, uz * 0.7).stretch(0, -2.5, 0)).iterator().hasNext();
            if (!floorAhead && hd > 0.8) {           // never walk off an edge
                c.options.forwardKey.setPressed(false);
                mineFail = "the block " + blockLocal(bp) + " is out of reach and the way back is not safe";
                return M_FAIL;
            }
            c.options.forwardKey.setPressed(hd > 0.3);
            c.options.jumpKey.setPressed(p.horizontalCollision && p.isOnGround());
            if (reachWalk == 1) FlightRecorder.routine("REACH_WALK_BACK block=" + blockLocal(bp) + " dist=" + fmt(dist));
            return M_WORKING;
        }
        if (reachWalk > 0) {
            FlightRecorder.routine("REACH_WALK_DONE ticks=" + reachWalk + " dist=" + fmt(dist));
            reachWalk = 0;
            c.options.forwardKey.setPressed(false);
            c.options.jumpKey.setPressed(false);
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

    private static final int P_CLEAR = 0, P_STEP = 1, P_JUMP = 2, P_WALL = 3;
    private static final double STEP_HEIGHT = 0.6, JUMP_HEIGHT = 1.25, SUPPORT_INSET = 0.25;
    // details of the last probeAhead() call (used for the chest climb and for the log)
    private static double probeNeed, probeTop;
    private static String probeBlock = "";
    private static BlockPos probePos;

    private static int walkTo(MinecraftClient c, ClientPlayerEntity p, double tx, double tz, double tol, boolean allowDrop) {
        if (chestClimbActive) return continueChestClimb(c, p);
        double dx = tx - p.getX(), dz = tz - p.getZ();
        double dist = Math.hypot(dx, dz);
        // The player's box is 0.6 wide, so the route does not need to hit the exact point.
        // Callers that need a precise stance (chest centre, tunnel centre) pass 0.20.
        double arriveTol = allowDrop ? Math.max(tol, 0.08) : Math.max(tol, 0.15);
        if (dist < arriveTol) {
            c.options.forwardKey.setPressed(false);
            c.options.jumpKey.setPressed(false);
            return p.isOnGround() ? W_ARRIVED : W_MOVING;
        }

        // Keep steering deterministic and yaw normalized; no random turn jitter.
        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float currentYaw = MathHelper.wrapDegrees(p.getYaw());
        float yawDelta = MathHelper.wrapDegrees(wantYaw - currentYaw);
        p.setYaw(MathHelper.wrapDegrees(currentYaw + clampF(yawDelta, -18.0f, 18.0f)));
        p.setPitch(p.getPitch() + clampF(18f - p.getPitch(), -4f, 4f));
        boolean aligned = Math.abs(MathHelper.wrapDegrees(wantYaw - p.getYaw())) < 14f;
        boolean press = aligned && (dist > 0.55 || now % 2 == 0);

        double ux = dx / dist, uz = dz / dist;
        // Look only as far as the player will really move: never past the target. (The old probe looked
        // 0.75 blocks ahead even when the target was 0.5 away, and so "saw" the ship wall behind the target.)
        int kind = press ? probeAhead(c, p, ux, uz, Math.min(0.75, dist + 0.05)) : P_CLEAR;

        // A chest that is higher than the feet but can be jumped on: explicit climb
        // (approach, ONE jump, steer in the air, confirmed landing on top).
        if (kind == P_JUMP && probePos != null && probeBlock.contains("chest") && p.isOnGround()) {
            chestClimbActive = true;
            chestClimbJumpIssued = false;
            chestClimbX = probePos.getX() + 0.5;
            chestClimbZ = probePos.getZ() + 0.5;
            chestClimbTop = probeTop;
            chestClimbTicks = 0;
            chestClimbGroundTicks = 0;
            routeNote(p, "CLIMB_START");
            FlightRecorder.routine("CHEST_STEP_START block=" + probeBlock + " top=" + f2(chestClimbTop)
                    + " pos=" + local(p.getX(), p.getY(), p.getZ()));
            return continueChestClimb(c, p);
        }

        // Void / drop protection for the next ~1.2 blocks. It also runs while standing ON the chest.
        if (!allowDrop && !pathSafeAhead(c, p, ux, uz, Math.min(1.2, dist))) {
            c.options.forwardKey.setPressed(false);
            c.options.jumpKey.setPressed(false);
            FlightRecorder.routine("WALK_UNSAFE_AHEAD pos=" + local(p.getX(), p.getY(), p.getZ())
                    + " target=" + local(tx, p.getY(), tz) + " distance=" + fmt(dist));
            diag(c, p, "WALK_UNSAFE_AHEAD");
            return W_UNSAFE;
        }

        if (kind == P_WALL) {
            c.options.forwardKey.setPressed(false);
            c.options.jumpKey.setPressed(false);
            FlightRecorder.routine("WALK_BLOCKED pos=" + local(p.getX(), p.getY(), p.getZ())
                    + " target=" + local(tx, p.getY(), tz) + " distance=" + fmt(dist)
                    + " blocker=" + probeBlock + " needs_raise=" + f2(probeNeed));
            diag(c, p, "WALK_BLOCKED");
            return W_UNSAFE;
        }

        c.options.forwardKey.setPressed(press);
        // A step up to 0.6 is climbed by the game itself (no jump). Only a real 1-block step needs a jump.
        c.options.jumpKey.setPressed(press && kind == P_JUMP && p.isOnGround() && dist > 0.42);

        if (dist < walkBest - 0.04) {
            walkBest = dist;
            stuckTicks = 0;
        } else {
            stuckTicks++;
        }
        if (stuckTicks > 80) {
            FlightRecorder.routine("WALK_STUCK pos=" + local(p.getX(), p.getY(), p.getZ())
                    + " target=" + local(tx, p.getY(), tz) + " distance=" + fmt(dist));
            diag(c, p, "WALK_STUCK");
            return W_STUCK;
        }
        return W_MOVING;
    }

    /**
     * Looks at the space the player's body will occupy 0.25 / 0.5 / 0.75 blocks ahead (never beyond reach).
     * P_CLEAR = free, P_STEP = a ledge of up to 0.6 that the game climbs by itself, P_JUMP = a step of up to 1.25
     * that needs one jump (and has room above it), P_WALL = anything else. Details are left in probeBlock/probePos.
     */
    private static int probeAhead(MinecraftClient c, ClientPlayerEntity p, double ux, double uz, double reach) {
        int worst = P_CLEAR;
        double wNeed = 0.0, wTop = 0.0;
        String wBlock = "";
        BlockPos wPos = null;
        Box body = p.getBoundingBox();
        for (double d = 0.25; ; d += 0.25) {
            double use = Math.min(d, reach);
            int k = probeAt(c, p, body, ux * use, uz * use);
            if (k > worst || (k == worst && k != P_CLEAR && probeNeed > wNeed)) {
                worst = k;
                wNeed = probeNeed;
                wTop = probeTop;
                wBlock = probeBlock;
                wPos = probePos;
            }
            if (d >= reach) break;
        }
        probeNeed = wNeed;
        probeTop = wTop;
        probeBlock = wBlock;
        probePos = wPos;
        return worst;
    }

    private static int probeAt(MinecraftClient c, ClientPlayerEntity p, Box body, double ox, double oz) {
        probeNeed = 0.0;
        probeTop = 0.0;
        probeBlock = "";
        probePos = null;
        // 0.02 above the feet: the floor the player stands on does not count as an obstacle
        double top = topCollision(c, body.offset(ox, 0.02, oz));
        if (Double.isNaN(top)) return P_CLEAR;
        BlockPos pos = probePos;
        String id = probeBlock;
        double need = top - p.getY();
        double raise = need + 0.03;
        boolean liftedFree = need <= JUMP_HEIGHT && Double.isNaN(topCollision(c, body.offset(ox, raise, oz)));
        probePos = pos;
        probeBlock = id;
        probeTop = top;
        probeNeed = need;
        if (!liftedFree) return P_WALL;
        return need <= STEP_HEIGHT ? P_STEP : P_JUMP;
    }

    /** Highest collision surface that overlaps the box (world Y), NaN when nothing overlaps. Fills probePos / probeBlock. */
    private static double topCollision(MinecraftClient c, Box box) {
        double best = Double.NaN;
        probePos = null;
        probeBlock = "";
        int x0 = MathHelper.floor(box.minX), x1 = MathHelper.floor(box.maxX);
        int y0 = MathHelper.floor(box.minY), y1 = MathHelper.floor(box.maxY);
        int z0 = MathHelper.floor(box.minZ), z1 = MathHelper.floor(box.maxZ);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    pos.set(x, y, z);
                    BlockState st = c.world.getBlockState(pos);
                    if (st.isAir()) continue;
                    var shape = st.getCollisionShape(c.world, pos);
                    if (shape.isEmpty()) continue;
                    for (Box part : shape.getBoundingBoxes()) {
                        Box w = part.offset(x, y, z);
                        if (!w.intersects(box)) continue;
                        if (Double.isNaN(best) || w.maxY > best) {
                            best = w.maxY;
                            probePos = pos.toImmutable();
                            probeBlock = Registries.BLOCK.getId(st.getBlock()) + "@" + x + "," + y + "," + z;
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * A small explicit climb state: face the chest, issue one jump pulse, steer over
     * its collision footprint while airborne, then require a real grounded landing
     * at the chest collision top before the exit route continues.
     */
    private static int continueChestClimb(MinecraftClient c, ClientPlayerEntity p) {
        chestClimbTicks++;
        double dx = chestClimbX - p.getX(), dz = chestClimbZ - p.getZ();
        double dist = Math.hypot(dx, dz);
        if (p.getY() < chestClimbTop - 1.8) {
            releaseKeys(c);
            chestClimbActive = false;
            chestClimbJumpIssued = false;
            FlightRecorder.routine("CHEST_STEP_FAILED reason=player_fell_below_top pos="
                    + local(p.getX(), p.getY(), p.getZ()));
            return W_UNSAFE;
        }

        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float currentYaw = MathHelper.wrapDegrees(p.getYaw());
        float yawDelta = MathHelper.wrapDegrees(wantYaw - currentYaw);
        p.setYaw(MathHelper.wrapDegrees(currentYaw + clampF(yawDelta, -18.0f, 18.0f)));
        boolean aligned = Math.abs(yawDelta) < 16.0f;

        if (p.isOnGround()) {
            if (p.getY() >= chestClimbTop - 0.15 && dist <= 0.78) {
                int landedTicks = chestClimbTicks;
                releaseKeys(c);
                chestClimbActive = false;
                chestClimbJumpIssued = false;
                chestClimbTicks = 0;
                chestClimbGroundTicks = 0;
                walkBest = 1e9;
                stuckTicks = 0;
                FlightRecorder.routine("CHEST_STEP_LANDED pos=" + local(p.getX(), p.getY(), p.getZ())
                        + " chestTop=" + f2(chestClimbTop) + " ticks=" + landedTicks
                        + " loc=" + locName(locate(c, p)));
                routeNote(p, "CHEST_LANDED");
                return W_MOVING;
            }

            // Never hold jump continuously: a held jump can bounce the player off
            // the chest after landing. Wait until facing it, then send exactly one pulse.
            if (!chestClimbJumpIssued) {
                c.options.forwardKey.setPressed(aligned && dist > 0.42);
                c.options.jumpKey.setPressed(aligned);
                if (aligned) {
                    chestClimbJumpIssued = true;
                    routeNote(p, "CLIMB_JUMP");
                }
            } else {
                c.options.forwardKey.setPressed(false);
                c.options.jumpKey.setPressed(false);
                chestClimbGroundTicks++;
                if (chestClimbGroundTicks > 2) {
                    releaseKeys(c);
                    chestClimbActive = false;
                    chestClimbJumpIssued = false;
                    FlightRecorder.routine("CHEST_STEP_FAILED reason=jump_did_not_start ticks=" + chestClimbTicks
                            + " pos=" + local(p.getX(), p.getY(), p.getZ()));
                    diag(c, p, "CHEST_JUMP_NOT_STARTED");
                    return W_STUCK;
                }
            }
        } else {
            // Keep moving toward the chest centre, but stop horizontal input once
            // the player's centre is over the footprint so momentum cannot carry it off.
            c.options.forwardKey.setPressed(aligned && dist > 0.48);
            c.options.jumpKey.setPressed(false);
        }

        if (chestClimbTicks > 40) {
            releaseKeys(c);
            chestClimbActive = false;
            chestClimbJumpIssued = false;
            FlightRecorder.routine("CHEST_STEP_FAILED reason=jump_timed_out ticks=" + chestClimbTicks
                    + " pos=" + local(p.getX(), p.getY(), p.getZ()));
            diag(c, p, "CHEST_JUMP_TIMED_OUT");
            return W_STUCK;
        }
        return W_MOVING;
    }

    /** Drop guard for the next 1.2 blocks only; distant route geometry is not a detour trigger. */
    private static boolean pathSafeAhead(MinecraftClient c, ClientPlayerEntity p,
                                         double ux, double uz, double reach) {
        if (!p.isOnGround()) return true;
        double previousTop = groundTop(c, p.getX(), p.getY() - 0.01, p.getZ(), 5);
        if (Double.isNaN(previousTop)) return false;
        for (double along = 0.30; along <= reach + 0.01; along += 0.30) {
            double x = p.getX() + ux * Math.min(along, reach);
            double z = p.getZ() + uz * Math.min(along, reach);
            double top = groundTop(c, x, previousTop + 1.05, z, 6);
            if (Double.isNaN(top) || previousTop - top > MAX_WALK_DROP || top - previousTop > 1.05) return false;
            previousTop = top;
        }
        return true;
    }

    /** Highest collision surface at ONE point (x, z) at or below startY, searching maxDown blocks down. NaN when none. */
    private static double surfaceAt(MinecraftClient c, double sx, double sz, double startY, int maxDown) {
        int y0 = MathHelper.floor(startY);
        int bx = MathHelper.floor(sx), bz = MathHelper.floor(sz);
        double localX = sx - bx, localZ = sz - bz;
        for (int y = y0; y >= y0 - maxDown; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            var shape = c.world.getBlockState(pos).getCollisionShape(c.world, pos);
            if (shape.isEmpty()) continue;
            double best = Double.NaN;
            for (Box part : shape.getBoundingBoxes()) {
                if (localX + 1.0E-4 < part.minX || localX - 1.0E-4 > part.maxX
                        || localZ + 1.0E-4 < part.minZ || localZ - 1.0E-4 > part.maxZ) continue;
                double top = y + part.maxY;
                if (Double.isNaN(best) || top > best) best = top;
            }
            if (!Double.isNaN(best)) return best;
        }
        return Double.NaN;
    }

    /**
     * Support under the player. The CENTRE must stand over a real collision surface (never accepted otherwise),
     * and at least 2 of 4 sample points 0.25 away from the centre must have support too. The old test used
     * points exactly 0.3 away (the very edge of the 0.6 wide box) and needed all of them: on the one-block tunnel
     * floor one of them always fell on the neighbouring block's edge and the player was called "unsupported".
     */
    private static double groundTop(MinecraftClient c, double x, double startY, double z, int maxDown) {
        double centerTop = surfaceAt(c, x, z, startY, maxDown);
        if (Double.isNaN(centerTop)) return Double.NaN;
        int supported = 0;
        for (int k = 0; k < 4; k++) {
            double sx = x + (k == 0 ? SUPPORT_INSET : k == 1 ? -SUPPORT_INSET : 0.0);
            double sz = z + (k == 2 ? SUPPORT_INSET : k == 3 ? -SUPPORT_INSET : 0.0);
            double t = surfaceAt(c, sx, sz, startY, maxDown);
            if (!Double.isNaN(t) && centerTop - t <= MAX_WALK_DROP) supported++;
        }
        return supported >= 2 ? centerTop : Double.NaN;
    }

    // ------------------------------------------------------------------ ship route: where is the player?

    private static final int L_AIR = 0, L_LEDGE = 1, L_CHEST = 2, L_TUNNEL = 3, L_UNKNOWN = 4;
    private static String routePhase = "";

    private static String locName(int loc) {
        return switch (loc) {
            case L_AIR -> "AIR";
            case L_LEDGE -> "LEDGE";
            case L_CHEST -> "CHEST_TOP";
            case L_TUNNEL -> "TUNNEL_FLOOR";
            default -> "UNKNOWN";
        };
    }

    /** True when the player is really supported by this block's collision top (the block under the feet). */
    private static boolean standingOn(MinecraftClient c, ClientPlayerEntity p, BlockPos pos) {
        if (!p.isOnGround()) return false;
        var shape = c.world.getBlockState(pos).getCollisionShape(c.world, pos);
        if (shape.isEmpty()) return false;
        Box feet = p.getBoundingBox().offset(0, -0.03, 0);
        for (Box part : shape.getBoundingBoxes()) {
            Box w = part.offset(pos.getX(), pos.getY(), pos.getZ());
            if (w.intersects(feet) && Math.abs(w.maxY - p.getY()) < 0.06) return true;
        }
        return false;
    }

    /** Ledge in front of the frame, chest top, or tunnel floor: decided from the real supporting block. */
    private static int locate(MinecraftClient c, ClientPlayerEntity p) {
        if (!p.isOnGround()) return L_AIR;
        double a = localA(p.getX(), p.getZ()), left = localLeft(p.getX(), p.getZ()), h = p.getY() - fy;
        boolean onChest = standingOn(c, p, blockAt(CHEST_BLOCK));
        boolean onFloor = standingOn(c, p, blockAt(EXIT_FLOOR));
        if (onChest && onFloor) return a >= 0.0 ? L_CHEST : L_TUNNEL;
        if (onChest) return L_CHEST;
        if (onFloor) return L_TUNNEL;
        if (Math.abs(h + 1.5) < 0.3 && a > -0.3 && a < 2.4 && left > -1.7 && left < 0.7) return L_LEDGE;
        return L_UNKNOWN;
    }

    private static void routeNote(ClientPlayerEntity p, String phase) {
        if (phase.equals(routePhase)) return;
        routePhase = phase;
        FlightRecorder.routine("ROUTE " + phase + " step=" + STEPS[Math.min(stepIndex, STEPS.length - 1)]
                + " sub=" + sub + " pos=" + local(p.getX(), p.getY(), p.getZ()));
    }

    private static void routeBegin(MinecraftClient c, ClientPlayerEntity p) {
        routePhase = "";
        routeNote(p, "BEGIN_" + locName(locate(c, p)));
        dumpGeometry(c);
        diag(c, p, "ROUTE_BEGIN");
    }

    private static String tunnelProblem(MinecraftClient c) {
        for (double[] g : new double[][]{EXIT_UP, EXIT_LOW}) {
            BlockPos bp = blockAt(g);
            if (solid(c, bp)) return "an exit block is still there: " + blockId(c, bp) + " at " + blockLocal(bp);
        }
        if (!solid(c, blockAt(EXIT_FLOOR))) return "the tunnel has no floor block";
        return null;
    }

    private static String chestProblem(MinecraftClient c) {
        BlockPos bp = blockAt(CHEST_BLOCK);
        String id = blockId(c, bp);
        double top = collisionTop(c.world.getBlockState(bp), c, bp);
        if (!id.contains("chest") || top < 0.5 || top > 1.1) {
            return "expected a chest at " + blockLocal(bp) + " but found " + id + " (collision height " + f2(top) + ")";
        }
        return null;
    }

    // ------------------------------------------------------------------ diagnostics

    private static String f2(double v) {
        return Double.isNaN(v) ? "none" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String supportText(MinecraftClient c, ClientPlayerEntity p) {
        double y = p.getY() - 0.01;
        return "c=" + f2(surfaceAt(c, p.getX(), p.getZ(), y, 3))
                + " +x=" + f2(surfaceAt(c, p.getX() + SUPPORT_INSET, p.getZ(), y, 3))
                + " -x=" + f2(surfaceAt(c, p.getX() - SUPPORT_INSET, p.getZ(), y, 3))
                + " +z=" + f2(surfaceAt(c, p.getX(), p.getZ() + SUPPORT_INSET, y, 3))
                + " -z=" + f2(surfaceAt(c, p.getX(), p.getZ() - SUPPORT_INSET, y, 3));
    }

    /** Player box, local position, support samples and the blocks around the feet (id, local position, collision top). */
    private static void diag(MinecraftClient c, ClientPlayerEntity p, String tag) {
        if (c == null || c.world == null || p == null || frame == null) return;
        Box b = p.getBoundingBox();
        FlightRecorder.routine("DIAG " + tag + " pos=" + local(p.getX(), p.getY(), p.getZ())
                + " world=" + f2(p.getX()) + "," + f2(p.getY()) + "," + f2(p.getZ())
                + " gnd=" + p.isOnGround() + " loc=" + locName(locate(c, p))
                + " box=" + f2(b.minX) + "," + f2(b.minY) + "," + f2(b.minZ) + ".." + f2(b.maxX) + "," + f2(b.maxY) + "," + f2(b.maxZ)
                + " support[" + supportText(c, p) + "]");
        StringBuilder sb = new StringBuilder("DIAG_BLOCKS");
        int bx = MathHelper.floor(p.getX()), by = MathHelper.floor(p.getY()), bz = MathHelper.floor(p.getZ());
        int n = 0;
        for (int dy = -1; dy <= 2 && n < 16; dy++) {
            for (int dx = -1; dx <= 1 && n < 16; dx++) {
                for (int dz = -1; dz <= 1 && n < 16; dz++) {
                    BlockPos pos = new BlockPos(bx + dx, by + dy, bz + dz);
                    BlockState st = c.world.getBlockState(pos);
                    if (st.isAir()) continue;
                    var shape = st.getCollisionShape(c.world, pos);
                    sb.append(' ').append(blockId(c, pos).replace("minecraft:", "")).append('@')
                            .append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ())
                            .append("[").append(blockLocal(pos)).append("]top=")
                            .append(shape.isEmpty() ? "none" : f2(pos.getY() + shape.getMax(Direction.Axis.Y)));
                    n++;
                }
            }
        }
        FlightRecorder.routine(sb.toString());
    }

    /** The blocks the exit route depends on, with real ids and collision tops (the MAP in the log has no ids). */
    private static void dumpGeometry(MinecraftClient c) {
        String[] names = {"CHEST", "WALL_BESIDE_CHEST_LOW", "WALL_BESIDE_CHEST_UP", "EXIT_FLOOR", "EXIT_LOW", "EXIT_UP",
                "TUNNEL_CEILING", "OUTSIDE_LOW", "OUTSIDE_UP", "TUNNEL_SIDE_LOW"};
        double[][] gs = {CHEST_BLOCK, {0.5, 2.0, -0.5}, {0.5, 2.0, 0.5}, EXIT_FLOOR, EXIT_LOW, EXIT_UP,
                {-0.5, 1.0, 1.5}, {-1.5, 1.0, -0.5}, {-1.5, 1.0, 0.5}, {-0.5, 2.0, -0.5}};
        StringBuilder sb = new StringBuilder("GEOMETRY");
        for (int i = 0; i < names.length; i++) {
            BlockPos bp = blockAt(gs[i]);
            BlockState st = c.world.getBlockState(bp);
            sb.append(' ').append(names[i]).append('=').append(blockId(c, bp).replace("minecraft:", ""))
                    .append('@').append(bp.getX()).append(',').append(bp.getY()).append(',').append(bp.getZ())
                    .append("top=").append(st.isAir() ? "air" : f2(bp.getY() + collisionTop(st, c, bp)));
        }
        FlightRecorder.routine(sb.toString());
    }

    /** Highest vertical collision surface for a block (0..1); used for chest step height. */
    private static double collisionTop(BlockState state, MinecraftClient c, BlockPos pos) {
        var shape = state.getCollisionShape(c.world, pos);
        return shape.isEmpty() ? 0.0 : shape.getMax(Direction.Axis.Y);
    }

    // ------------------------------------------------------------------ pearl planning

    private static Vec3d theoryEye() {
        return new Vec3d(wx(SPOT_PEARL[0], SPOT_PEARL[1]), fy + SPOT_PEARL[2] + 1.62 - 0.1, wz(SPOT_PEARL[0], SPOT_PEARL[1]));
    }

    private static String scanSummary() {
        return "tried " + scTried + " throws: wall " + scWall + ", void/unloaded " + scVoid + ", distance " + scRange
                + ", height " + scHigh + ", no space " + scSpace + ", narrow ground " + scPatch;
    }

    /** One call = the whole scan in one go (dry run). The real run uses scanBegin + scanStep over several ticks. */
    private static PearlPlan scanPearl(MinecraftClient c, Vec3d start) {
        scanBegin(start);
        while (!scanStep(c, Long.MAX_VALUE)) {
            // Long.MAX_VALUE never runs out: one pass finishes it
        }
        return scBest;
    }

    private static void scanBegin(Vec3d start) {
        double dx = -fdx + ldx, dz = -fdz + ldz;
        scBaseYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        scStart = start;
        scTried = scWall = scVoid = scRange = scHigh = scSpace = scPatch = 0;
        scBest = null;
        scG = 0;
        scSgnIdx = 0;
        scPitchIdx = 0;
        scNanos = 0;
        scSlices = 0;
        scanActive = true;
    }

    /**
     * Simulates the real pearl flight for many directions (back-left first, then all around) and pitches.
     * The ship's own blocks block the directions that are not open. Works for about budgetNanos, then returns false
     * (call again next tick). Returns true when finished: the result is in scBest (null = nothing safe found).
     */
    private static boolean scanStep(MinecraftClient c, long budgetNanos) {
        long t0 = System.nanoTime();
        scSlices++;
        while (scG <= 15) {
            int nSgn = scG == 0 ? 1 : 2;
            while (scSgnIdx < nSgn) {
                float sgn = scG == 0 ? 1f : (scSgnIdx == 0 ? -1f : 1f);
                while (scPitchIdx < PITCH_COUNT) {
                    float pitch = -8f + scPitchIdx * 4f;
                    scanOne(c, scG * 12f * sgn, pitch);
                    scPitchIdx++;
                    if (System.nanoTime() - t0 > budgetNanos) {
                        scNanos += System.nanoTime() - t0;
                        return false;
                    }
                }
                scSgnIdx++;
                scPitchIdx = 0;
            }
            if (scBest != null) break;          // the direction closest to back-left that works
            scG++;
            scSgnIdx = 0;
            scPitchIdx = 0;
        }
        scNanos += System.nanoTime() - t0;
        scanActive = false;
        return true;
    }

    /** One simulated throw: rates it and keeps it when it is the cheapest safe one so far. */
    private static void scanOne(MinecraftClient c, float yo, float pitch) {
        float yaw = scBaseYaw + yo;
        scTried++;
        Impact im = simulatePearl(c, scStart, yaw, pitch);
        if (im == null) {
            scVoid++;
            return;
        }
        BlockPos hit = im.hit;
        if (im.free.y < hit.getY() + 0.5) {
            scWall++;
            return;
        }
        double landY = hit.getY() + 1.0;
        if (landY > fy - 20.0 || landY < 10.0) {
            scHigh++;
            return;
        }
        double hd = Math.hypot(im.free.x - scStart.x, im.free.z - scStart.z);
        if (hd < 32.0 || hd > 115.0) {
            scRange++;
            return;
        }
        if (solid(c, hit.getX(), hit.getY() + 1, hit.getZ()) || solid(c, hit.getX(), hit.getY() + 2, hit.getZ())) {
            scSpace++;
            return;
        }
        if (groundPatch(c, hit) < 15) {
            scPatch++;
            return;
        }
        double cost = Math.abs(yo) * 0.15 + Math.abs(pitch - 42f) * 0.25
                + (hd < 40 ? (40 - hd) * 0.5 : hd > 80 ? (hd - 80) * 0.3 : 0);
        if (scBest == null || cost < scBest.cost) {
            PearlPlan pl = new PearlPlan();
            pl.yaw = yaw;
            pl.pitch = pitch;
            pl.dist = hd;
            pl.cost = cost;
            pl.landX = im.free.x;
            pl.landY = landY;
            pl.landZ = im.free.z;
            scBest = pl;
        }
    }

    // ------------------------------------------------------------------ glide exit

    private static boolean riskyForPing(Step s, int sub) {
        return (s == Step.STORE_ELYTRA && sub == 0) || s == Step.TAKE_ELYTRA || s == Step.DIG_UNDER_SHULKER
                || s == Step.EQUIP_PICKAXE || (s == Step.PEARL_OUT && sub <= 3);
    }

    /** v2.0.12: true while a pearl thrown by us is still flying (the server can be slow: do not give up too early). */
    private static boolean ownPearlFlying(MinecraftClient c, ClientPlayerEntity p) {
        for (net.minecraft.entity.Entity e : c.world.getEntities()) {
            if (e.getType() == net.minecraft.entity.EntityType.ENDER_PEARL && !e.isRemoved()
                    && e instanceof net.minecraft.entity.projectile.ProjectileEntity pe && pe.getOwner() == p) return true;
        }
        return false;
    }

    /** v2.0.10: move a pickaxe (or an ender pearl stack) from the inventory into the hotbar. Returns true when it clicked. */
    private static boolean refillHotbar(MinecraftClient c, ClientPlayerEntity p, boolean pickaxe) {
        if (c == null || p == null || c.interactionManager == null) return false;
        PlayerScreenHandler h = p.playerScreenHandler;
        int src = -1;
        for (int sl = 9; sl <= 35 && src < 0; sl++) {
            ItemStack st = h.getSlot(sl).getStack();
            if (pickaxe ? isPickaxe(st) : st.isOf(Items.ENDER_PEARL)) src = sl;
        }
        if (src < 0) return false;
        int dest = -1;
        for (int i = 0; i < 9 && dest < 0; i++) {
            if (p.getInventory().getStack(i).isEmpty()) dest = i;
        }
        for (int i = 7; i >= 0 && dest < 0; i--) {           // slot 8 stays free for the auto apple
            ItemStack st = p.getInventory().getStack(i);
            String id = Registries.ITEM.getId(st.getItem()).getPath();
            if (st.isOf(Items.FIREWORK_ROCKET) || st.isOf(Items.ENDER_PEARL) || isPickaxe(st) || st.isOf(Items.ELYTRA)
                    || id.contains("sword") || id.contains("apple") || id.contains("totem")) continue;
            dest = i;
        }
        if (dest < 0) return false;
        c.interactionManager.clickSlot(h.syncId, src, dest, SlotActionType.SWAP, p);
        FlightRecorder.routine("REFILL_HOTBAR item=" + (pickaxe ? "pickaxe" : "ender_pearl") + " from=" + src + " to=" + dest);
        return true;
    }

    /** v2.0.9: not in the tunnel (roof, air after levitation): glide from where we stand instead of giving control away. */
    private static boolean glideFromHere(MinecraftClient c, ClientPlayerEntity p, String why) {
        if (!FlightConfig.routineGlideExit) return false;
        String gw = glideChecks(c, p, true);
        if (gw != null) {
            FlightRecorder.routine("GLIDE_FROM_HERE_REFUSED why=" + why.replace(' ', '_') + " reason=" + gw.replace(' ', '_'));
            return false;
        }
        FlightRecorder.routine("GLIDE_FROM_HERE why=" + why.replace(' ', '_') + " pos=" + local(p.getX(), p.getY(), p.getZ()));
        p.sendMessage(Text.literal("Ship routine: " + why + " - gliding out from here"), false);
        pearlOnly = false;
        navigationRetries = 0;
        go(6);
        return true;
    }

    /** v2.0.9: walk/jump straight off the ship towards the tunnel side so the elytra can open (not used inside the tunnel). */
    private static boolean walkOff(MinecraftClient c, ClientPlayerEntity p) {
        if (locate(c, p) == L_TUNNEL) return false;
        double dx = wx(-4.0, SPOT_PEARL[1]) - p.getX(), dz = wz(-4.0, SPOT_PEARL[1]) - p.getZ();
        lookAngles(p, (float) Math.toDegrees(Math.atan2(-dx, dz)), 8f);
        c.options.forwardKey.setPressed(true);
        c.options.sprintKey.setPressed(true);
        c.options.jumpKey.setPressed(p.isOnGround());
        if (subTick % 40 == 1) FlightRecorder.routine("WALK_OFF pos=" + local(p.getX(), p.getY(), p.getZ()));
        return true;
    }

    /** The dangerous fallback: jump out of the tunnel with the elytra and let the normal flight take over. */
    private static String glideChecks(MinecraftClient c, ClientPlayerEntity p, boolean refreshGround) {
        if (!FlightConfig.routineGlideExit) return "glide exit is switched off in the settings";
        if (p.getHealth() < 14f) return "health " + fmt(p.getHealth()) + " is too low for a glide exit (need 14)";
        if (p.hasStatusEffect(StatusEffects.LEVITATION)) return "levitation is active";
        if (countItem(p, Items.FIREWORK_ROCKET) < 6 || hotbarSlot(p, Items.FIREWORK_ROCKET) < 0) {
            return "you need at least 6 firework rockets, with some in the hotbar";
        }
        if (hotbarSlot(p, Items.ENDER_PEARL) < 0) refillHotbar(c, p, false);
        if (hotbarSlot(p, Items.ENDER_PEARL) < 0) return "no ender pearl in the hotbar for the void rescue";
        ItemStack worn = p.getEquippedStack(EquipmentSlot.CHEST);
        if (!worn.isOf(Items.ELYTRA) && countElytra(p) == 0) return "no elytra to glide with";
        if (worn.isOf(Items.ELYTRA) && worn.getMaxDamage() - worn.getDamage() < 40) return "the worn elytra is almost broken";
        // the flight must really start: a point must be left and the rockets must be enough for the first leg
        String refusal = FlightRunner.startRefusal(c, GLIDE_EXTRA_ROCKETS);
        if (refusal != null) return "the flight would not start after the jump: " + refusal;
        // and there must be known ground to fall back on if anything goes wrong in the air
        if (refreshGround || glideGround == null) glideGround = findFallbackGround(c);
        if (glideGround == null) return "no ground found in the loaded chunks to fall back on (come back when more chunks are loaded)";
        return null;
    }

    /**
     * Ground below the ship that the flight can land on in an emergency. Looks in rings around the tunnel exit,
     * at least 30 blocks away, at least 20 blocks below the frame, and wants a flat patch with air above it.
     * Returns {x, y, z} (y = top of the ground) or null.
     */
    private static double[] findFallbackGround(MinecraftClient c) {
        double x0 = wx(SPOT_PEARL[0], SPOT_PEARL[1]), z0 = wz(SPOT_PEARL[0], SPOT_PEARL[1]);
        int topStart = MathHelper.floor(fy) - 12;
        int bottom = Math.max(c.world.getBottomY(), MathHelper.floor(fy) - 110);
        for (int r = 30; r <= 110; r += 6) {
            double[] best = null;
            int bestPatch = 0;
            for (int k = 0; k < 24; k++) {
                double ang = Math.toRadians(k * 15.0);
                int bx = MathHelper.floor(x0 + Math.cos(ang) * r), bz = MathHelper.floor(z0 + Math.sin(ang) * r);
                int top = -1;
                for (int y = topStart; y >= bottom; y--) {
                    if (solid(c, bx, y, bz)) {
                        top = y + 1;
                        break;
                    }
                }
                if (top < 10 || top > fy - 20.0) continue;
                int patch = groundPatch(c, new BlockPos(bx, top - 1, bz));
                if (patch < 15) continue;
                if (best == null || patch > bestPatch) {
                    best = new double[]{bx + 0.5, top, bz + 0.5};
                    bestPatch = patch;
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** Same clicks as the flight code uses: pick up the elytra, put it on the chest slot, give back what comes out. */
    private static void swapElytraIntoChest(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        PlayerScreenHandler h = p.playerScreenHandler;
        int src = -1;
        for (int sl = 9; sl <= 44; sl++) {
            if (h.getSlot(sl).getStack().isOf(Items.ELYTRA)) {
                src = sl;
                break;
            }
        }
        if (src < 0) return;
        c.interactionManager.clickSlot(h.syncId, src, 0, SlotActionType.PICKUP, p);
        c.interactionManager.clickSlot(h.syncId, 6, 0, SlotActionType.PICKUP, p);      // 6 = chest armor slot
        if (!h.getCursorStack().isEmpty()) {
            c.interactionManager.clickSlot(h.syncId, src, 0, SlotActionType.PICKUP, p);
        }
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
        for (int tick = 0; tick < 220; tick++) {
            double nx = x + vx, ny = y + vy, nz = z + vz;
            for (int s = 1; s <= 8; s++) {
                double px = x + (nx - x) * s / 8.0, py = y + (ny - y) * s / 8.0, pz = z + (nz - z) * s / 8.0;
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

    // ------------------------------------------------------------------ automatic survival

    /**
     * Eats a golden apple at or below six hearts. Searches the entire player inventory,
     * temporarily swaps an apple into hotbar slot 9 when needed, then restores the slot.
     * Returns true while consuming so movement/mining does not fight the use action.
     */
    public static boolean tickAutoGoldenApple(MinecraftClient c) {
        if (c == null || c.player == null || c.world == null || c.interactionManager == null) return false;
        ClientPlayerEntity p = c.player;
        long worldTime = c.world.getTime();
        if (appleLastWorldTime == worldTime) return appleBusy;
        appleLastWorldTime = worldTime;

        if (appleCooldown > 0) appleCooldown--;
        if (!appleBusy && p.getHealth() > AUTO_APPLE_HEALTH) {
            appleWarned = false;
            return false;
        }
        if (c.currentScreen != null) return appleBusy;

        if (appleBusy) {
            appleUseTicks++;
            c.options.useKey.setPressed(true);
            if (p.isUsingItem() && isGoldenApple(p.getActiveItem())) {
                appleStarted = true;
                return true;
            }
            if (appleStarted) {
                finishAutoApple(c, p);
                appleCooldown = 40;
                return false;
            }
            if (appleUseTicks > 12) {
                finishAutoApple(c, p);
                return false;
            }
            return true;
        }
        if (p.getHealth() > AUTO_APPLE_HEALTH || appleCooldown > 0 || p.isUsingItem()) return false;

        int appleSlot = goldenAppleHotbarSlot(p);
        appleOriginalSelected = p.getInventory().getSelectedSlot();
        appleMoved = false;
        appleSourceSlot = -1;
        if (appleSlot < 0) {
            for (int i = 9; i < 36; i++) {
                if (isGoldenApple(p.getInventory().getStack(i))) {
                    appleSourceSlot = i;
                    appleSlot = AUTO_APPLE_HOTBAR_SLOT;
                    PlayerScreenHandler h = p.playerScreenHandler;
                    c.interactionManager.clickSlot(h.syncId, appleSourceSlot, appleSlot, SlotActionType.SWAP, p);
                    appleMoved = true;
                    break;
                }
            }
        }
        if (appleSlot < 0) {
            if (!appleWarned) {
                say(c, "Auto-heal: health is low, but no golden apple is in your inventory");
                FlightRecorder.routine("AUTO_APPLE_MISSING hp=" + fmt(p.getHealth()));
                appleWarned = true;
            }
            return false;
        }

        p.getInventory().setSelectedSlot(appleSlot);
        appleBusy = true;
        appleStarted = false;
        appleUseTicks = 0;
        c.options.useKey.setPressed(true);
        FlightRecorder.routine("AUTO_APPLE_START hp=" + fmt(p.getHealth()) + " slot=" + (appleSlot + 1)
                + " movedFromInventory=" + appleMoved);
        return true;
    }

    private static boolean isGoldenApple(ItemStack stack) {
        return !stack.isEmpty() && (stack.isOf(Items.GOLDEN_APPLE) || stack.isOf(Items.ENCHANTED_GOLDEN_APPLE));
    }

    private static int goldenAppleHotbarSlot(ClientPlayerEntity p) {
        for (int i = 0; i < 9; i++) {
            if (isGoldenApple(p.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private static void finishAutoApple(MinecraftClient c, ClientPlayerEntity p) {
        c.options.useKey.setPressed(false);
        if (appleMoved && appleSourceSlot >= 9 && appleSourceSlot < 36) {
            PlayerScreenHandler h = p.playerScreenHandler;
            c.interactionManager.clickSlot(h.syncId, appleSourceSlot, AUTO_APPLE_HOTBAR_SLOT, SlotActionType.SWAP, p);
        }
        if (appleOriginalSelected >= 0 && appleOriginalSelected < 9) {
            p.getInventory().setSelectedSlot(appleOriginalSelected);
        }
        FlightRecorder.routine("AUTO_APPLE_END hp=" + fmt(p.getHealth()) + " consumed=" + appleStarted);
        appleBusy = false;
        appleStarted = false;
        appleMoved = false;
        appleSourceSlot = -1;
        appleOriginalSelected = -1;
        appleUseTicks = 0;
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

    private static boolean isSword(ItemStack st) {
        return !st.isEmpty() && Registries.ITEM.getId(st.getItem()).getPath().endsWith("_sword");
    }

    private static int swordSlot(ClientPlayerEntity p) {
        int any = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getStack(i);
            if (!isSword(st)) continue;
            if (Registries.ITEM.getId(st.getItem()).getPath().equals("netherite_sword")) return i;
            if (any < 0) any = i;
        }
        return any;
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
