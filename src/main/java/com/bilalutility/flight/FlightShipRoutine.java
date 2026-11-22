package com.bilalutility.flight;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.sound.SoundEvents;
import net.minecraft.client.sound.PositionedSoundInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Assisted ship-looting routine. The player still walks between the recorded positions;
 * this class selects the correct items, mines the confirmed blocks, takes the frame item,
 * and aims/throws the pearl. Every dangerous action requires a fresh key press.
 */
public final class FlightShipRoutine {
    private enum Action { NONE, MINE, FRAME, WAIT_TELEPORT, WAIT_SAFE_GROUND }

    private static boolean active;
    private static boolean dryRunActive;
    private static Action action = Action.NONE;
    private static int step;
    private static int actionTicks;
    private static int baselineElytra;
    private static BlockPos targetBlock;
    private static ItemFrameEntity frame;
    private static double frameX, frameY, frameZ;
    private static Direction facing = Direction.NORTH;
    private static double startX, startY, startZ;
    private static String status = "";
    private static String currentStepName = "";
    private static int pearlSlot = -1;
    private static boolean pearlNeedsConfirm;

    private FlightShipRoutine() {}

    public static boolean isActive() { return active; }

    public static boolean start(MinecraftClient client, double x, double y, double z) {
        if (FlightConfig.shipRoutineMode == 0 || client == null || client.player == null || client.world == null) return false;
        frameX = x;
        frameY = y;
        frameZ = z;
        frame = findFrame(client);
        if (frame == null) {
            FlightRecorder.event("SHIP_ROUTINE_REFUSED reason=frame_facing_unavailable");
            message(client, "Ship routine cannot start: item frame and its facing were not found. Take the elytra manually.");
            return false;
        }
        facing = frame.getHorizontalFacing();
        if (facing.getAxis() != Direction.Axis.Z && facing.getAxis() != Direction.Axis.X) {
            message(client, "Ship routine cannot start: item frame is not facing horizontally.");
            return false;
        }
        active = true;
        dryRunActive = FlightConfig.shipRoutineDryRun;
        action = Action.NONE;
        step = 0;
        actionTicks = 0;
        targetBlock = null;
        currentStepName = "";
        baselineElytra = countElytra(client.player);
        status = dryRunActive
                ? "DRY RUN: press Ship step to preview each action"
                : "Press Ship step to equip your netherite pickaxe";
        pearlNeedsConfirm = false;
        FlightRecorder.event("SHIP_ROUTINE_START facing=" + facing + " frame=" + f(x) + "," + f(y) + "," + f(z)
                + " dryRun=" + dryRunActive);
        message(client, "Ship routine ready. Set a key for 'Ship step' in Controls.");
        return true;
    }

    /** One fresh key press starts exactly one action. It never skips a failed step. */
    public static void advance(MinecraftClient client) {
        if (!active || client == null || client.player == null || client.world == null || action != Action.NONE) return;
        if (dryRunActive) {
            String plan = plannedStep();
            FlightRecorder.routineStepStart("DRY_RUN_STEP_" + (step + 1), plan);
            FlightRecorder.routineStepEnd("DRY_RUN_STEP_" + (step + 1), "PREVIEW_ONLY", "no_game_action=true");
            FlightRecorder.event("SHIP_DRY_RUN step=" + step + " " + plan);
            status = "DRY RUN: " + plan;
            step++;
            if (step > 6) {
                active = false;
                status = "Dry run complete. No game actions were performed.";
                message(client, status);
            }
            return;
        }
        if (!basicSafety(client, false)) return;
        switch (step) {
            case 0 -> equipPickaxe(client);
            case 1 -> beginMine(client, true, 3.5, 4.5, 1.0, 1.5, "drop block");
            case 2 -> beginMine(client, false, 1.5, 1.5, 0.0, -2.5, "block under front shulker");
            case 3 -> beginFrame(client);
            case 4 -> beginMine(client, false, -0.5, -0.5, 1.0, 0.5, "first tunnel block");
            case 5 -> beginMine(client, false, -0.5, -0.5, 1.0, -0.5, "second tunnel block");
            case 6 -> beginPearl(client);
            default -> { }
        }
    }

    public static void cancel(MinecraftClient client) {
        if (active) fail(client, "Cancelled by flight toggle key");
    }

    public static void tick(MinecraftClient client) {
        if (!active) return;
        if (client == null || client.player == null || client.world == null) {
            active = false;
            action = Action.NONE;
            return;
        }
        if (client.options.sneakKey.isPressed()) {
            fail(client, "Cancelled by sneak key");
            return;
        }
        FlightRunner.routineVoidSafety(client, action != Action.WAIT_TELEPORT && action != Action.WAIT_SAFE_GROUND);
        if (!active || !basicSafety(client, action == Action.MINE && step == 1)) return;
        if (action == Action.NONE) return;
        actionTicks++;
        if (actionTicks > actionTimeout()) {
            fail(client, "Timed out during " + status);
            return;
        }
        switch (action) {
            case MINE -> tickMine(client);
            case FRAME -> tickFrame(client);
            case WAIT_TELEPORT -> tickTeleport(client);
            case WAIT_SAFE_GROUND -> tickSafeGround(client);
            default -> { }
        }
    }

    private static int actionTimeout() {
        return switch (action) {
            case MINE -> 180;
            case FRAME -> 220;
            case WAIT_TELEPORT -> 180;
            case WAIT_SAFE_GROUND -> 300;
            default -> 0;
        };
    }

    private static void equipPickaxe(MinecraftClient client) {
        int slot = hotbarSlot(client.player, Items.NETHERITE_PICKAXE);
        if (slot < 0) {
            fail(client, "Netherite pickaxe not found in hotbar. Put it in the hotbar and retry.");
            return;
        }
        FlightRecorder.routineStepStart("EQUIP_PICKAXE", "slot=" + slot + " held=minecraft:netherite_pickaxe");
        client.player.getInventory().setSelectedSlot(slot);
        FlightRecorder.routineStepEnd("EQUIP_PICKAXE", "OK", "slot=" + slot);
        completeStep(client, "Netherite pickaxe selected. Move onto the drop block, then press Ship step again.");
    }

    private static void beginMine(MinecraftClient client, boolean drop, double aMin, double aMax,
                                  double left, double h, String name) {
        ClientPlayerEntity p = client.player;
        if (!p.getMainHandStack().isOf(Items.NETHERITE_PICKAXE)) {
            fail(client, "Netherite pickaxe is not selected");
            return;
        }
        if (!p.isOnGround() || Double.isNaN(groundBelow(client, p, 3))) {
            fail(client, "Stand on solid ground before mining this block");
            return;
        }
        targetBlock = null;
        double closestToPlayer = Double.MAX_VALUE;
        for (double a = aMin; a <= aMax + 0.01; a += 1.0) {
            BlockPos candidate = localBlock(a, left, h);
            if (!isPurpur(client, candidate)) continue;
            double d = Math.hypot(candidate.getX() + 0.5 - p.getX(), candidate.getZ() + 0.5 - p.getZ());
            if (d < closestToPlayer) {
                closestToPlayer = d;
                targetBlock = candidate;
            }
        }
        if (targetBlock == null) {
            fail(client, "Expected purpur block not found for " + name + ". Nothing was mined.");
            return;
        }
        double distance = p.getEyePos().distanceTo(Vec3d.ofCenter(targetBlock));
        if (distance > 4.5) {
            status = "Move closer to " + name + " (" + f(distance) + " blocks), then press Ship step";
            message(client, status);
            return;
        }
        if (drop) {
            if (Math.abs(p.getY() - (frameY + 2.5)) > 2.0 || !hasLandingSupport(client, targetBlock, 6)) {
                fail(client, "Planned drop is not in the recorded safe position or has no confirmed landing block");
                return;
            }
        }
        if (!drop && Double.isNaN(groundBelow(client, p, 3))) {
            fail(client, "No solid support under you; refusing to mine");
            return;
        }
        action = Action.MINE;
        actionTicks = 0;
        status = "Mining " + name;
        currentStepName = name.replace(' ', '_').toUpperCase(Locale.ROOT);
        lookAt(p, Vec3d.ofCenter(targetBlock));
        FlightRecorder.routineStepStart(currentStepName, "block=" + blockId(client, targetBlock)
                + " local=" + localDescription(targetBlock) + " pos=" + targetBlock.toString());
    }

    private static void tickMine(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (targetBlock == null) { fail(client, "Mining target disappeared"); return; }
        if (client.world.getBlockState(targetBlock).isAir()) {
            client.options.attackKey.setPressed(false);
            FlightRecorder.routineStepEnd(currentStepName, "BROKE",
                    "block=" + targetBlock.toString() + " local=" + localDescription(targetBlock));
            action = Action.NONE;
            targetBlock = null;
            currentStepName = "";
            step++;
            status = nextInstruction();
            message(client, status);
            return;
        }
        if (!p.isOnGround()) {
            client.options.attackKey.setPressed(false);
            fail(client, "Player left the supported mining position before the block broke");
            return;
        }
        if (!isPurpur(client, targetBlock)) {
            fail(client, "Block changed to an unexpected type: " + blockId(client, targetBlock));
            return;
        }
        if (p.getEyePos().distanceTo(Vec3d.ofCenter(targetBlock)) > 4.5) {
            client.options.attackKey.setPressed(false);
            fail(client, "Moved out of mining reach");
            return;
        }
        lookAt(p, Vec3d.ofCenter(targetBlock));
        if (client.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit
                && hit.getBlockPos().equals(targetBlock)) {
            client.options.attackKey.setPressed(true);
        } else {
            client.options.attackKey.setPressed(false);
        }
    }

    private static void beginFrame(MinecraftClient client) {
        if (client.player.getMainHandStack().isEmpty()) {
            fail(client, "Hold a safe item before hitting the frame");
            return;
        }
        frame = findFrame(client);
        if (frame == null) {
            fail(client, "Item frame not found near the recorded ship position");
            return;
        }
        if (frame.getHeldItemStack().isEmpty()) {
            fail(client, "The frame is already empty; elytra count must be checked manually");
            return;
        }
        double distance = client.player.getEyePos().distanceTo(new Vec3d(frame.getX(), frame.getY(), frame.getZ()));
        if (distance > 4.5) {
            status = "Move closer to the item frame (" + f(distance) + " blocks), then press Ship step";
            message(client, status);
            return;
        }
        baselineElytra = countElytra(client.player);
        action = Action.FRAME;
        actionTicks = 0;
        status = "Taking elytra from item frame";
        FlightRecorder.routineStepStart("TAKE_ELYTRA", "facing=" + facing.asString() + " elytraBefore=" + baselineElytra
                + " frameLocal=0,0,0");
    }

    private static void tickFrame(MinecraftClient client) {
        if (!client.player.isOnGround()) {
            client.options.attackKey.setPressed(false);
            fail(client, "Player left the supported frame position");
            return;
        }
        frame = findFrame(client);
        if (frame == null) { fail(client, "Item frame disappeared before elytra was collected"); return; }
        int now = countElytra(client.player);
        if (now >= baselineElytra + 1 && frame.getHeldItemStack().isEmpty()) {
            client.options.attackKey.setPressed(false);
            FlightRecorder.routineStepEnd("TAKE_ELYTRA", "OK", "elytra=" + now + " frameLocal=0,0,0");
            action = Action.NONE;
            step++;
            status = nextInstruction();
            message(client, status);
            return;
        }
        if (frame.getHeldItemStack().isEmpty() && now < baselineElytra + 1) {
            client.options.attackKey.setPressed(false);
            return; // inventory sync can take several ticks after the frame empties
        }
        lookAt(client.player, new Vec3d(frame.getX(), frame.getY(), frame.getZ()));
        if (client.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() == frame) {
            client.options.attackKey.setPressed(true);
        } else {
            client.options.attackKey.setPressed(false);
        }
    }

    private static void beginPearl(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p.hasStatusEffect(StatusEffects.LEVITATION)) {
            status = "Levitation active: wait for it to end, then press Ship step";
            message(client, status);
            return;
        }
        if (!p.isOnGround() || Double.isNaN(groundBelow(client, p, 3))) {
            fail(client, "Stand on confirmed solid ground at the tunnel mouth before throwing the pearl");
            return;
        }
        if (p.getVelocity().horizontalLength() > 0.15
                || client.options.forwardKey.isPressed() || client.options.backKey.isPressed()
                || client.options.leftKey.isPressed() || client.options.rightKey.isPressed()
                || client.options.jumpKey.isPressed() || client.options.sprintKey.isPressed()) {
            status = "Stop moving and stand still before the pearl throw, then press Ship step";
            message(client, status);
            return;
        }
        pearlSlot = hotbarSlot(p, Items.ENDER_PEARL);
        if (pearlSlot < 0) {
            fail(client, "Ender pearl not found in hotbar");
            return;
        }
        Vec3d destination = findPearlTarget(client);
        if (destination == null) {
            fail(client, "No loaded safe island ground found in the back-left target area; pearl was not thrown");
            return;
        }
        // Shulkers are reported for diagnostics. If one applies levitation after the throw,
        // the routine waits for teleport and for the effect to end before doing anything else.
        int nearby = client.world.getEntitiesByClass(ShulkerEntity.class,
                client.player.getBoundingBox().expand(8), e -> !e.isRemoved()).size();
        if (nearby > 0 && !pearlNeedsConfirm) {
            pearlNeedsConfirm = true;
            status = "Shulker nearby: check your health and levitation, then press Ship step again to confirm pearl";
            message(client, status);
            FlightRecorder.event("SHIP_PEARL_CONFIRM_REQUIRED nearbyShulkers=" + nearby);
            return;
        }
        pearlNeedsConfirm = false;
        double pdx = destination.x - frameX, pdz = destination.z - frameZ;
        double pfx = facing.getOffsetX(), pfz = facing.getOffsetZ();
        double pla = pdx * pfx + pdz * pfz;
        double pll = pdx * pfz - pdz * pfx;
        FlightRecorder.event("SHIP_PEARL_TARGET x=" + f(destination.x) + " y=" + f(destination.y)
                + " z=" + f(destination.z) + " localA=" + f(pla) + " localLeft=" + f(pll)
                + " localH=" + f(destination.y - frameY) + " nearbyShulkers=" + nearby);
        p.getInventory().setSelectedSlot(pearlSlot);
        Vec3d from = p.getEyePos();
        double dx = destination.x - from.x;
        double dz = destination.z - from.z;
        double horizontal = Math.hypot(dx, dz);
        double drop = Math.max(1, from.y - destination.y);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Ender-pearl flight adds a long downward arc after launch. The three measured
        // standing throws were about 13 degrees shallower than the direct ground angle.
        float pitch = (float) MathHelper.clamp(Math.toDegrees(Math.atan2(drop, horizontal)) - 13.5, 20.0, 50.0);
        p.setYaw(yaw);
        p.setPitch(pitch);
        startX = p.getX(); startY = p.getY(); startZ = p.getZ();
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
        action = Action.WAIT_TELEPORT;
        actionTicks = 0;
        status = "Pearl thrown; waiting for teleport";
        FlightRecorder.routineStepStart("PEARL_THROW", "yaw=" + f(yaw) + " pitch=" + f(pitch)
                + " targetLocal=" + f(pla) + "," + f(destination.y - frameY) + "," + f(pll));
    }

    private static void tickTeleport(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        double moved = Math.sqrt(sq(p.getX() - startX) + sq(p.getY() - startY) + sq(p.getZ() - startZ));
        if (moved > 6) {
            FlightRecorder.routineStepEnd("PEARL_THROW", "TELEPORTED", "moved=" + f(moved)
                    + " levitation=" + p.hasStatusEffect(StatusEffects.LEVITATION));
            action = Action.WAIT_SAFE_GROUND;
            actionTicks = 0;
            FlightRecorder.routineStepStart("PEARL_LANDING", "teleport_received=true levitation="
                    + p.hasStatusEffect(StatusEffects.LEVITATION));
            status = p.hasStatusEffect(StatusEffects.LEVITATION)
                    ? "Teleported; levitation active, waiting safely" : "Teleported; waiting for solid ground";
        }
    }

    private static void tickSafeGround(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p.hasStatusEffect(StatusEffects.LEVITATION)) {
            status = "Levitation active: no mining or item use; waiting";
            return;
        }
        if (!p.isOnGround() || Double.isNaN(groundBelow(client, p, 3))) {
            status = "Waiting for solid ground under the player";
            return;
        }
        if (p.getHealth() < 8.0f) { fail(client, "Health fell below 8 after the pearl; staying on the ground"); return; }
        FlightRecorder.routineStepEnd("PEARL_LANDING", "SAFE_GROUND", "playerLocal=" + relPlayer(p)
                + " groundY=" + f(groundBelow(client, p, 3)));
        active = false;
        action = Action.NONE;
        releaseAttack(client);
        status = "Safe ground confirmed. Resuming the waypoint queue.";
        message(client, status);
        FlightRunner.start(client);
    }

    private static Vec3d findPearlTarget(MinecraftClient client) {
        Vec3d best = null;
        double bestScore = Double.MAX_VALUE;
        for (double a = -18; a >= -72; a -= 3) {
            for (double left = 18; left <= 72; left += 3) {
                double[] world = localWorld(a, left);
                int x = (int) Math.floor(frameX + world[0]);
                int z = (int) Math.floor(frameZ + world[1]);
                if (client.world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
                int top = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                if (top <= client.world.getBottomY() || top > frameY - 30 || top < frameY - 80) continue;
                BlockPos ground = new BlockPos(x, top - 1, z);
                if (client.world.getBlockState(ground).getCollisionShape(client.world, ground).isEmpty()
                        || !client.world.getFluidState(ground).isEmpty()) continue;
                double horizontal = Math.hypot(x + 0.5 - client.player.getX(), z + 0.5 - client.player.getZ());
                double drop = client.player.getEyeY() - top;
                if (horizontal < 30 || horizontal > 85 || drop < 25 || drop > 85) continue;
                double score = Math.abs(horizontal - 48) + Math.abs(drop - 55) * 0.65 + Math.abs(a - left) * 0.12;
                if (score < bestScore) {
                    bestScore = score;
                    best = new Vec3d(x + 0.5, top + 0.1, z + 0.5);
                }
            }
        }
        return best;
    }

    private static BlockPos localBlock(double a, double left, double h) {
        double[] world = localWorld(a, left);
        return new BlockPos((int) Math.floor(frameX + world[0]), (int) Math.floor(frameY + h),
                (int) Math.floor(frameZ + world[1]));
    }

    private static double[] localWorld(double a, double left) {
        double fx = facing.getOffsetX();
        double fz = facing.getOffsetZ();
        double lx = fz;
        double lz = -fx;
        return new double[]{a * fx + left * lx, a * fz + left * lz};
    }

    private static boolean basicSafety(MinecraftClient client, boolean plannedDrop) {
        ClientPlayerEntity p = client.player;
        if (p == null) return false;
        if (p.getHealth() < 8.0f && action != Action.WAIT_TELEPORT && action != Action.WAIT_SAFE_GROUND) {
            fail(client, "Health below 8 - routine stopped"); return false;
        }
        if (p.hasStatusEffect(StatusEffects.LEVITATION) && action != Action.WAIT_TELEPORT && action != Action.WAIT_SAFE_GROUND) {
            releaseAttack(client);
            status = "Levitation active: routine is waiting; no mining or pearl use";
            return false;
        }
        if (action == Action.NONE && !plannedDrop && step > 0 && step < 6 && !p.isOnGround()) {
            status = "Land on solid ground before starting the next step";
            return false;
        }
        return true;
    }

    private static double groundBelow(MinecraftClient client, ClientPlayerEntity p, int maxDown) {
        int x = MathHelper.floor(p.getX());
        int z = MathHelper.floor(p.getZ());
        int y = MathHelper.floor(p.getY() - 0.01);
        for (int yy = y; yy >= Math.max(client.world.getBottomY(), y - maxDown); yy--) {
            BlockPos pos = new BlockPos(x, yy, z);
            if (!client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty()) return yy + 1;
        }
        return Double.NaN;
    }

    private static boolean hasLandingSupport(MinecraftClient client, BlockPos from, int maxDown) {
        for (int y = from.getY() - 1; y >= Math.max(client.world.getBottomY(), from.getY() - maxDown); y--) {
            BlockPos pos = new BlockPos(from.getX(), y, from.getZ());
            if (!client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty()) return true;
        }
        return false;
    }

    private static boolean isPurpur(MinecraftClient client, BlockPos pos) {
        if (client.world.getBlockState(pos).isAir()) return false;
        String id = Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).getPath();
        return id.equals("purpur_block") || id.equals("purpur_pillar") || id.equals("purpur_stairs") || id.equals("purpur_slab");
    }

    private static String blockId(MinecraftClient client, BlockPos pos) {
        return Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).toString();
    }

    private static ItemFrameEntity findFrame(MinecraftClient client) {
        ItemFrameEntity best = null;
        double distance = Double.MAX_VALUE;
        for (ItemFrameEntity candidate : client.world.getEntitiesByClass(ItemFrameEntity.class,
                new net.minecraft.util.math.Box(frameX - 3, frameY - 3, frameZ - 3,
                        frameX + 3, frameY + 3, frameZ + 3), e -> !e.isRemoved())) {
            double d = new Vec3d(candidate.getX(), candidate.getY(), candidate.getZ()).squaredDistanceTo(new Vec3d(frameX, frameY, frameZ));
            if (d < distance) { best = candidate; distance = d; }
        }
        return best;
    }

    private static int hotbarSlot(ClientPlayerEntity p, Item item) {
        for (int i = 0; i < 9; i++) if (p.getInventory().getStack(i).isOf(item)) return i;
        return -1;
    }

    private static int countElytra(ClientPlayerEntity p) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getStack(i).isOf(Items.ELYTRA)) n += p.getInventory().getStack(i).getCount();
        return n;
    }

    private static void lookAt(ClientPlayerEntity p, Vec3d target) {
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        p.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setPitch((float) Math.toDegrees(-Math.atan2(dy, horizontal)));
    }

    private static void completeStep(MinecraftClient client, String next) {
        step++;
        status = next;
        message(client, next);
    }

    private static String nextInstruction() {
        return switch (step) {
            case 2 -> "Move to the block under the front shulker, then press Ship step";
            case 3 -> "Move in front of the item frame, then press Ship step";
            case 4 -> "Move to the first tunnel block, then press Ship step";
            case 5 -> "Move to the second tunnel block, then press Ship step";
            case 6 -> "Move to the tunnel mouth on solid ground, then press Ship step to throw the pearl";
            default -> "Step complete";
        };
    }

    private static String plannedStep() {
        return switch (step) {
            case 0 -> "select netherite pickaxe by item";
            case 1 -> "break purpur drop block at forward 3.5-4.5, left 1, h +1.5";
            case 2 -> "break purpur block at forward 1.5, left 0, h -2.5";
            case 3 -> "hit item frame and wait for elytra count +1";
            case 4 -> "break purpur at forward -0.5, left 1, h +0.5";
            case 5 -> "break purpur at forward -0.5, left 1, h -0.5";
            case 6 -> "find loaded back-left ground and throw ender pearl";
            default -> "finish";
        };
    }

    private static String relPlayer(ClientPlayerEntity p) {
        double dx = p.getX() - frameX, dz = p.getZ() - frameZ;
        double fx = facing.getOffsetX(), fz = facing.getOffsetZ();
        return "a=" + f(dx * fx + dz * fz) + ",h=" + f(p.getY() - frameY)
                + ",left=" + f(dx * fz - dz * fx);
    }

    private static String localDescription(BlockPos pos) {
        double dx = pos.getX() + 0.5 - frameX;
        double dz = pos.getZ() + 0.5 - frameZ;
        double fx = facing.getOffsetX(), fz = facing.getOffsetZ();
        double leftX = fz, leftZ = -fx;
        return "a=" + f(dx * fx + dz * fz) + ",left=" + f(dx * leftX + dz * leftZ)
                + ",h=" + f(pos.getY() - frameY);
    }

    private static void releaseAttack(MinecraftClient client) {
        if (client != null && client.options != null) client.options.attackKey.setPressed(false);
    }

    private static void fail(MinecraftClient client, String reason) {
        if (!active) return;
        releaseAttack(client);
        FlightRecorder.routineStepEnd("STEP_" + (step + 1), "FAILED", "reason=" + reason.replace(' ', '_'));
        FlightRecorder.event("SHIP_ROUTINE_FAIL step=" + step + " reason=" + reason);
        active = false;
        action = Action.NONE;
        targetBlock = null;
        currentStepName = "";
        status = "STOPPED: " + reason;
        if (client != null && client.player != null) {
            client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f));
            message(client, status + ". Control returned to you; no more actions will run.");
        }
    }

    private static void message(MinecraftClient client, String text) {
        if (client != null && client.player != null) client.player.sendMessage(net.minecraft.text.Text.literal(text), true);
    }

    private static double sq(double n) { return n * n; }
    private static String f(double n) { return String.format(Locale.ROOT, "%.1f", n); }

    public static List<String> hudLines() {
        List<String> lines = new ArrayList<>();
        if (!active) return lines;
        lines.add("Ship routine: " + (dryRunActive ? "DRY RUN" : "ASSISTED"));
        lines.add("Step " + Math.min(step + 1, 7) + "/7: " + plannedStep());
        lines.add("Facing " + facing.asString() + " | " + status);
        lines.add("Press the Ship step key for the next action");
        return lines;
    }
}
