package com.bilalutility.flight;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;

/** Waypoint queue, flight settings and safety settings on one scrolling page. */
public class FlightScreen extends BilalScreen {
    private static final String[] ROUTINE_NAMES = {"Off", "Assisted", "Full auto"};
    private static final String[] HUD_NAMES = {"off", "top center", "top left", "top right"};
    private static String draftX = "";
    private static String draftZ = "";
    private static String pasteInfo = "";

    public FlightScreen(Screen parent) {
        super("Auto Elytra", "Flies to your points, nearest first", 0x66CCFF, parent);
    }

    private static String startLabel() {
        if (FlightRunner.isPausedAfterLandingFailure()) return "Resume flight";
        if (FlightRunner.isStorageCheckPending()) return "Checking Ender Chest...";
        if (FlightRunner.isStorageConfirmationPending()) return "Start anyway (Ender Chest low)";
        return FlightRunner.isRunning() ? "Stop flight" : "Start flight";
    }

    @Override
    protected void build() {
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int half = (inner - 4) / 2;
        int y = contentTop();

        // ---- run (stays at the top) ----
        ButtonWidget startBtn = button(startLabel(), left, y, inner, () -> {
            if (FlightRunner.isPausedAfterLandingFailure()) {
                client.setScreen(null);
                FlightRunner.resumeAfterLandingFailure(client);
            } else if (FlightRunner.isRunning()) {
                FlightRunner.stop(client, "Auto Elytra stopped");
                clearAndInit();
            } else {
                client.setScreen(null);
                FlightRunner.start(client);
            }
        }, "Checks Ender Chest space first. If it is short, press Start again to continue safely.");
        startBtn.active = client != null && client.world != null;

        // ---- add a point ----
        y += 30;
        section("Add a point  (X and Z)", y);
        y += 14;
        TextFieldWidget fx = new TextFieldWidget(textRenderer, left, y, half, 20, Text.literal("X"));
        fx.setMaxLength(9);
        fx.setText(draftX);
        fx.setChangedListener(s -> draftX = s);
        addDrawableChild(fx);
        TextFieldWidget fz = new TextFieldWidget(textRenderer, left + half + 4, y, half, 20, Text.literal("Z"));
        fz.setMaxLength(9);
        fz.setText(draftZ);
        fz.setChangedListener(s -> draftZ = s);
        addDrawableChild(fz);
        y += 24;
        button("Add point", left, y, half, this::addPoint, "Adds X / Z to the queue");
        button("My position", left + half + 4, y, half, () -> {
            if (client.player != null) {
                draftX = String.valueOf((int) Math.floor(client.player.getX()));
                draftZ = String.valueOf((int) Math.floor(client.player.getZ()));
                clearAndInit();
            }
        }, "Fills X and Z with where you stand");
        y += 24;
        button("Paste points (clipboard)", left, y, inner, this::pasteFromClipboard,
                "Reads your clipboard and adds every point in it. One point per line. Works with: 25773, -72911  or  X: 25773 Z: -72911  or  x, y, z. Points already in the queue are skipped");
        if (!pasteInfo.isEmpty()) {
            label(pasteInfo, left, y + 26, 0xFF9A9AA8);
            y += 14;
        }

        // ---- queue ----
        y += 34;
        section("Queue (" + FlightConfig.waypoints.size() + ")", y);
        y += 14;
        for (int i = 0; i < FlightConfig.waypoints.size(); i++) {
            FlightConfig.Waypoint w = FlightConfig.waypoints.get(i);
            int index = i;
            String result = w.result == null ? "" : w.result;
            String res = result.isEmpty() ? "" : "   [" + result + "]";
            label((i + 1) + ".  " + w.x + ", " + w.z + res, left, y + 6, 0xFFFFFFFF);
            if ("looted".equals(result)) {
                buttonSized("Reactivate", left + inner - 138, y, 74, 20, () -> {
                    if (index < FlightConfig.waypoints.size()) FlightConfig.waypoints.get(index).result = "";
                    FlightConfig.save();
                    clearAndInit();
                }, "Clears only this point's looted result so it can be flown again");
            }
            buttonSized("Remove", left + inner - 62, y, 62, 20, () -> {
                if (index < FlightConfig.waypoints.size()) FlightConfig.waypoints.remove(index);
                FlightConfig.save();
                clearAndInit();
            }, null);
            y += 22;
        }
        if (FlightConfig.waypoints.isEmpty()) {
            label("No points yet", left, y + 6, 0xFF9A9AA8);
            y += 22;
        } else {
            button("Clear all", left, y, inner, () -> {
                FlightConfig.waypoints.clear();
                FlightConfig.save();
                clearAndInit();
            }, null);
            y += 22;
        }

        // ---- flight ----
        y += 12;
        section("Flight", y);
        y += 14;
        stepper(y, "Altitude", () -> FlightConfig.cruiseY, v -> FlightConfig.cruiseY = v, 60, 300, 5, 20,
                v -> "Y " + v, FlightConfig::save, "Higher = more time to recover. The End has no ceiling problem.");
        y += 22;
        stepper(y, "Stop time", () -> FlightConfig.pauseSeconds, v -> FlightConfig.pauseSeconds = v, 0, 120, 1, 10,
                v -> v + " s", FlightConfig::save, "How long it waits after landing at a point");
        y += 22;
        stepper(y, "Spare rockets", () -> FlightConfig.reserveRockets, v -> FlightConfig.reserveRockets = v, 0, 40, 1, 5,
                v -> String.valueOf(v), FlightConfig::save, "Below this reserve it lands on the nearest island");

        y += 24;
        cycle(left, y, inner, "Landing", 2, () -> FlightConfig.landWithChestplate ? 1 : 0,
                v -> FlightConfig.landWithChestplate = v == 1,
                v -> v == 1 ? "wear chestplate" : "keep elytra on", FlightConfig::save,
                "wear chestplate = swaps to a chestplate from your inventory at every stop and back to the elytra before taking off");

        y += 24;
        toggle(left, y, inner, "Ship landing: chestplate drop", () -> FlightConfig.shipLandingChestplateDrop,
                v -> FlightConfig.shipLandingChestplateDrop = v, FlightConfig::save,
                "On confirmed ships, safely swaps to your inventory chestplate above solid roof and drops onto it; otherwise uses normal landing");

        // ---- elytra hunting ----
        y += 34;
        section("Elytra hunting", y);
        y += 14;
        toggle(left, y, half, "Hunt elytra", () -> FlightConfig.hunt, v -> FlightConfig.hunt = v,
                FlightConfig::save, "Looks for the elytra item frame. When it finds one it lands on the ship, plays an alert and stops, so you take the elytra yourself");
        toggle(left + half + 4, y, half, "Frame scouting", () -> FlightConfig.scout, v -> FlightConfig.scout = v,
                FlightConfig::save, "Searches for an elytra frame while flying. Shulker checking is controlled separately below");
        y += 24;
        stepper(y, "Search radius", () -> FlightConfig.searchRadius, v -> FlightConfig.searchRadius = v, 30, 200, 5, 20,
                v -> v + " m", FlightConfig::save, "How far around a point it looks for shulkers and item frames. Chunkbase points are often far from the ship");
        y += 22;
        toggle(left, y, inner, "Use shulkers", () -> FlightConfig.useShulkers, v -> FlightConfig.useShulkers = v,
                FlightConfig::save, "Three shulkers in the ship's known shape means untouched. Two or a wrong shape means looted. No visible shulkers means unknown, not empty");
        y += 24;
        stepper(y, "Landing radius", () -> FlightConfig.landRadius, v -> FlightConfig.landRadius = v, 10, 100, 5, 10,
                v -> v + " m", FlightConfig::save, "Landing this close to a point counts as arrived (when a frame is seen it lands within 12 blocks of it)");
        y += 24;
        toggle(left, y, inner, "Debug mode", () -> FlightConfig.debugMode, v -> FlightConfig.debugMode = v,
                FlightConfig::save, "Prints destination, shulker/frame checks, flight decisions, landing phases and retry reasons in chat");
        y += 24;
        toggle(left, y, inner, "Record flights", () -> FlightConfig.recordFlights, v -> FlightConfig.recordFlights = v,
                FlightConfig::save, "Writes a log to .minecraft/bilal_flight_logs and, after an elytra is found, records how you walk, hit the frame and take off (teach mode). Send the files to improve the mod");
        y += 24;
        button("Reset results", left, y, inner, () -> {
            for (FlightConfig.Waypoint w : FlightConfig.waypoints) w.result = "";
            FlightConfig.save();
            clearAndInit();
        }, "Clears the elytra / empty marks so every point is flown again");

        // ---- ship routine ----
        y += 34;
        section("Ship routine (land - take elytra - leave - fly)", y);
        y += 14;
        cycle(left, y, inner, "Mode", 3, () -> FlightConfig.routineMode, v -> FlightConfig.routineMode = v,
                v -> ROUTINE_NAMES[v], FlightConfig::save,
                "Off = you do the ship by hand. Assisted = one key press per step. Full auto = all steps without key presses. Starts after ELYTRA FOUND");
        y += 24;
        toggle(left, y, inner, "Dry run (only check and print)", () -> FlightConfig.routineDryRun,
                v -> FlightConfig.routineDryRun = v, FlightConfig::save,
                "ON: the mod checks blocks, items and the pearl target and prints the plan, but does not move, mine, hit or throw anything. Turn it off for a real run");
        y += 24;
        toggle(left, y, inner, "Leave by gliding (pearl = backup)", () -> FlightConfig.routineGlideExit,
                v -> FlightConfig.routineGlideExit = v, FlightConfig::save,
                "ON: the mod puts the elytra on, steps out of the tunnel and flies away. If gliding is not possible, or the elytra does not open, it uses the ender pearl as backup. OFF: pearl only. Needs 6 rockets, a pearl, health 14");
        y += 24;
        label("Step key: " + FlightClient.stepKeyText() + "   (set it in Controls)", left, y + 6, 0xFFFFFFFF);
        y += 12;

        // ---- safety ----
        y += 34;
        section("Void safety", y);
        y += 14;
        toggle(left, y, half, "Pearl rescue", () -> FlightConfig.pearlRescue, v -> FlightConfig.pearlRescue = v,
                FlightConfig::save, "Throws an ender pearl at the nearest island when you fall low");
        toggle(left + half + 4, y, half, "Teamhome", () -> FlightConfig.teamhome, v -> FlightConfig.teamhome = v,
                FlightConfig::save, "Sends the command when you fall under the Y below (End only)");
        y += 24;
        stepper(y, "Pearl below", () -> FlightConfig.pearlBelowY, v -> FlightConfig.pearlBelowY = v, -60, 120, 5, 20,
                v -> "Y " + v, FlightConfig::save, null);
        y += 22;
        stepper(y, "Teamhome below", () -> FlightConfig.teamhomeY, v -> FlightConfig.teamhomeY = v, -64, 40, 1, 10,
                v -> "Y " + v, FlightConfig::save, null);
        y += 26;
        label("Teamhome command (without the /)", left, y, 0xFFFFFFFF);
        y += 12;
        TextFieldWidget cmd = new TextFieldWidget(textRenderer, left, y, inner, 20, Text.literal("Command"));
        cmd.setMaxLength(40);
        cmd.setText(FlightConfig.teamhomeCommand);
        cmd.setChangedListener(s -> {
            String c = FlightConfig.cleanCommand(s);
            FlightConfig.teamhomeCommand = c.isEmpty() ? "teamhome" : c;
            FlightConfig.save();
        });
        addDrawableChild(cmd);

        // ---- progress box ----
        y += 34;
        section("Progress box", y);
        y += 14;
        cycle(left, y, inner, "Position", 4, () -> FlightConfig.hudPos, v -> FlightConfig.hudPos = v,
                v -> HUD_NAMES[v], FlightConfig::save, "Where the box shows while flying");
        y += 24;
        stepper(y, "Size", () -> FlightConfig.hudScale, v -> FlightConfig.hudScale = v, 50, 200, 5, 25,
                v -> v + "%", FlightConfig::save, null);
    }

    private void addPoint() {
        List<int[]> points = FlightConfig.parsePoints(draftX.trim() + ", " + draftZ.trim());
        if (points.size() != 1) {
            if (client.player != null) client.player.sendMessage(Text.literal("Enter X and Z as numbers"), true);
            return;
        }
        FlightConfig.addPoints(points);
        FlightConfig.save();
        draftX = "";
        draftZ = "";
        clearAndInit();
    }

    private void pasteFromClipboard() {
        String text = client.keyboard.getClipboard();
        List<int[]> points = FlightConfig.parsePoints(text);
        if (points.isEmpty()) {
            pasteInfo = "No coordinates found in the clipboard";
        } else {
            int[] r = FlightConfig.addPoints(points);
            FlightConfig.save();
            pasteInfo = "Added " + r[0] + " point" + (r[0] == 1 ? "" : "s")
                    + (r[1] > 0 ? ", " + r[1] + " already in the queue" : "")
                    + (r[2] > 0 ? ", " + r[2] + " not added (max " + FlightConfig.MAX_POINTS + ")" : "");
        }
        clearAndInit();
    }

    @Override
    protected void saveAndLeave() {
        FlightConfig.save();
    }
}
