package com.bilalutility.hud;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class HudConfigScreen extends BilalScreen {
    private static final String[] TABS = {"Display", "Layout", "Scoreboard", "Target"};
    private static int lastPage = 0;

    private int page;
    private int cell;

    public HudConfigScreen(Screen parent) {
        super("HUD settings", "Armor, counters and info next to the hotbar, enemy gear, server scoreboard", 0x9B6BFF, parent);
        this.page = lastPage;
    }

    private static String pct(int v) {
        return v + "%";
    }

    private static String px(int v) {
        return v + " px";
    }

    @Override
    protected void build() {
        lastPage = page;
        cell = 0;
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int y = contentTop();

        int tw = (inner - 3 * 3) / 4;
        for (int i = 0; i < TABS.length; i++) {
            int idx = i;
            ButtonWidget b = button(TABS[i], left + i * (tw + 3), y, tw, () -> {
                page = idx;
                clearAndInit();
            }, null);
            b.active = i != page;
        }
        y += 30;

        switch (page) {
            case 0 -> displayPage(y);
            case 1 -> layoutPage(y);
            case 2 -> scoreboardPage(y);
            default -> targetPage(y);
        }
    }

    // ---------- pages ----------

    private void displayPage(int y) {
        HudConfig.Data c = HudConfig.d;
        section("Switches", y);
        y += 12;
        grid(y, "HUD", () -> c.enabled, v -> c.enabled = v, "Turns the whole HUD on or off");
        grid(y, "Armor", () -> c.armor, v -> c.armor = v, "Armor durability next to the hotbar");
        cycleCell(y, "Armor", 2, () -> c.armorText, v -> c.armorText = v, v -> v == 0 ? "percent" : "number");
        grid(y, "Swap sides", () -> c.swapSides, v -> c.swapSides = v, "Armor on the right, counters on the left");
        grid(y, "Hide zero", () -> c.hideZero, v -> c.hideZero = v, "Hide counters you have none of (totems always show)");
        grid(y, "Armor warn", () -> c.armorWarn, v -> c.armorWarn = v, "Shows 'Armor low!' under 15 %");

        y += 22 * 2 + 4;
        cell = 0;
        section("Item counters", y);
        y += 12;
        grid(y, "Totems", () -> c.totems, v -> c.totems = v, null);
        grid(y, "Crystals", () -> c.crystals, v -> c.crystals = v, null);
        grid(y, "Pearls", () -> c.pearls, v -> c.pearls = v, null);
        grid(y, "Gapples", () -> c.gapples, v -> c.gapples = v, null);
        grid(y, "Glowstone", () -> c.glowstone, v -> c.glowstone = v, null);
        grid(y, "Anchors", () -> c.anchors, v -> c.anchors = v, null);
        grid(y, "Obsidian", () -> c.obsidian, v -> c.obsidian = v, null);
        grid(y, "XP", () -> c.xp, v -> c.xp = v, null);

        y += 22 * 3 + 4;
        cell = 0;
        section("Info lines", y);
        y += 12;
        grid(y, "Combat", () -> c.combat, v -> c.combat = v, "Combat timer read from the action bar");
        grid(y, "Ping", () -> c.ping, v -> c.ping = v, null);
        grid(y, "FPS", () -> c.fps, v -> c.fps = v, null);
        grid(y, "Coords", () -> c.coords, v -> c.coords = v, "XYZ and the way you face");
        grid(y, "Speed", () -> c.speed, v -> c.speed = v, "Horizontal speed in blocks per second");
    }

    private void layoutPage(int y) {
        HudConfig.Data c = HudConfig.d;
        section("Size and background", y);
        y += 12;
        stepper(y, "HUD size", () -> c.scale, v -> c.scale = v, 50, 200, 5, 25, HudConfigScreen::pct,
                HudConfig::save, "Size of armor, counters and info lines");
        stepper(y + 22, "Panel opacity", () -> c.panelOpacity, v -> c.panelOpacity = v, 0, 100, 5, 25,
                HudConfigScreen::pct, HudConfig::save, "Background of the enemy gear panel");

        y += 52;
        section("Move things (pixels, + is right / down)", y);
        y += 12;
        stepper(y, "Armor X", () -> c.armorX, v -> c.armorX = v, -300, 300, 1, 10, HudConfigScreen::px, HudConfig::save, null);
        stepper(y + 22, "Armor Y", () -> c.armorY, v -> c.armorY = v, -300, 300, 1, 10, HudConfigScreen::px, HudConfig::save, null);
        stepper(y + 44, "Counters X", () -> c.counterX, v -> c.counterX = v, -300, 300, 1, 10, HudConfigScreen::px, HudConfig::save, null);
        stepper(y + 66, "Counters Y", () -> c.counterY, v -> c.counterY = v, -300, 300, 1, 10, HudConfigScreen::px, HudConfig::save, null);
        stepper(y + 88, "Info lines X", () -> c.infoX, v -> c.infoX = v, -300, 300, 1, 10, HudConfigScreen::px, HudConfig::save, null);
        stepper(y + 110, "Info lines Y", () -> c.infoY, v -> c.infoY = v, -300, 300, 1, 10, HudConfigScreen::px, HudConfig::save, null);

        button("Reset layout", panelLeft() + 12, y + 136, panelW() - 24, () -> {
            c.scale = 100;
            c.panelOpacity = 53;
            c.armorX = c.armorY = c.counterX = c.counterY = c.infoX = c.infoY = 0;
            HudConfig.save();
            clearAndInit();
        }, "Puts size, opacity and all offsets back to default");
    }

    private void scoreboardPage(int y) {
        HudConfig.Data c = HudConfig.d;
        int left = panelLeft() + 12;
        int half = (panelW() - 24 - 4) / 2;

        section("Server scoreboard", y);
        y += 12;
        toggle(left, y, half, "Sidebar shown", () -> !c.sbHidden, v -> c.sbHidden = !v, HudConfig::save,
                "Turn the server's sidebar on or off");
        cycle(left + half + 4, y, half, "Style", 2, () -> c.sbMode, v -> c.sbMode = v,
                v -> v == 0 ? "server's own" : "custom panel", HudConfig::save,
                "Custom panel redraws the sidebar from its data, so size, background and numbers are yours to set");

        y += 30;
        section("Size and position", y);
        y += 12;
        stepper(y, "Size", () -> c.scoreboardScale, v -> c.scoreboardScale = v, 30, 150, 5, 10,
                HudConfigScreen::pct, HudConfig::save, "Size of the sidebar");
        stepper(y + 22, "Move X", () -> c.sbX, v -> c.sbX = v, -400, 400, 1, 10, HudConfigScreen::px, HudConfig::save, null);
        stepper(y + 44, "Move Y", () -> c.sbY, v -> c.sbY = v, -300, 300, 1, 10, HudConfigScreen::px, HudConfig::save, null);

        y += 74;
        section("Custom panel only", y);
        y += 12;
        stepper(y, "Background", () -> c.sbOpacity, v -> c.sbOpacity = v, 0, 100, 5, 25, HudConfigScreen::pct,
                HudConfig::save, "Background opacity of the custom panel");
        stepper(y + 22, "Max lines", () -> c.sbMaxLines, v -> c.sbMaxLines = v, 1, 15, 1, 5, v -> String.valueOf(v),
                HudConfig::save, "How many lines to show");
        toggle(left, y + 46, half, "Numbers", () -> c.sbNumbers, v -> c.sbNumbers = v, HudConfig::save,
                "Show the score number on every line");
        toggle(left + half + 4, y + 46, half, "Shadow", () -> c.sbShadow, v -> c.sbShadow = v, HudConfig::save, null);
    }

    private void targetPage(int y) {
        HudConfig.Data c = HudConfig.d;
        int left = panelLeft() + 12;
        int half = (panelW() - 24 - 4) / 2;

        section("Enemy gear panel", y);
        y += 12;
        toggle(left, y, half, "Target gear", () -> c.targetGear, v -> c.targetGear = v, HudConfig::save,
                "Armor and both hands of a nearby player");
        cycle(left + half + 4, y, half, "Pick", 2, () -> c.targetCrosshairOnly ? 1 : 0,
                v -> c.targetCrosshairOnly = v == 1, v -> v == 1 ? "crosshair" : "nearest", HudConfig::save, null);
        cycle(left, y + 22, half, "Place", 3, () -> c.targetPos, v -> c.targetPos = v,
                v -> v == 0 ? "right" : v == 1 ? "left" : "center", HudConfig::save, null);

        y += 52;
        section("Size and position", y);
        y += 12;
        stepper(y, "Range", () -> c.targetRange, v -> c.targetRange = v, 2, 64, 1, 5, v -> v + " blocks",
                HudConfig::save, "How far away a player can be and still show up");
        stepper(y + 22, "Size", () -> c.targetScale, v -> c.targetScale = v, 50, 200, 5, 25, HudConfigScreen::pct,
                HudConfig::save, null);
        stepper(y + 44, "Move X", () -> c.targetX, v -> c.targetX = v, -400, 400, 1, 10, HudConfigScreen::px, HudConfig::save, null);
        stepper(y + 66, "Move Y", () -> c.targetY, v -> c.targetY = v, -300, 300, 1, 10, HudConfigScreen::px, HudConfig::save, null);
        stepper(y + 88, "Opacity", () -> c.panelOpacity, v -> c.panelOpacity = v, 0, 100, 5, 25, HudConfigScreen::pct,
                HudConfig::save, "Background of the panel");
    }

    // ---------- grid helpers (3 columns) ----------

    private int cellX() {
        int inner = panelW() - 24;
        int w = (inner - 8) / 3;
        return panelLeft() + 12 + (cell % 3) * (w + 4);
    }

    private int cellW() {
        return (panelW() - 24 - 8) / 3;
    }

    private void grid(int y, String name, BooleanSupplier get, Consumer<Boolean> set, String tip) {
        toggle(cellX(), y + (cell / 3) * 22, cellW(), name, get, set, HudConfig::save, tip);
        cell++;
    }

    private void cycleCell(int y, String name, int count, java.util.function.IntSupplier get,
                           java.util.function.IntConsumer set, java.util.function.IntFunction<String> names) {
        cycle(cellX(), y + (cell / 3) * 22, cellW(), name, count, get, set, names, HudConfig::save, null);
        cell++;
    }

    @Override
    protected void saveAndLeave() {
        HudConfig.save();
    }
}
