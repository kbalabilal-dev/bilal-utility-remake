package com.bilalutility.regear;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** One simple page: run it, say how many of each item to buy, and (optionally) open the shop setup. */
public class RegearConfigScreen extends BilalScreen {
    private ButtonWidget startBtn, pauseBtn;

    public RegearConfigScreen(Screen parent) {
        super("Auto Regear", "Buys your items from the server shop", 0x55FF55, parent);
    }

    private static String secs(int ticks) {
        return String.format(Locale.ROOT, "%.2f s", ticks / 20.0);
    }

    private static String startLabel() {
        return RegearRunner.isRunning() ? "Stop" : "Start regear";
    }

    private static String pauseLabel() {
        return RegearRunner.isPaused() ? "Resume" : "Pause";
    }

    @Override
    protected void build() {
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int half = (inner - 4) / 2;
        int y = contentTop();

        // ---- run ----
        startBtn = button(startLabel(), left, y, half, () -> {
            if (RegearRunner.isRunning()) {
                RegearRunner.cancel(client);
                startBtn.setMessage(Text.literal(startLabel()));
                pauseBtn.active = false;
            } else {
                client.setScreen(null);
                RegearRunner.start(client);
            }
        }, null);
        startBtn.active = client != null && client.world != null;
        pauseBtn = button(pauseLabel(), left + half + 4, y, half, () -> {
            RegearRunner.togglePause(client);
            pauseBtn.setMessage(Text.literal(pauseLabel()));
        }, null);
        pauseBtn.active = RegearRunner.isRunning();

        // ---- kit: pick, create, delete ----
        y += 30;
        section("Kit", y);
        y += 12;
        int arrow = 26;
        boolean hasKits = !RegearKits.custom().isEmpty();
        ButtonWidget prev = button("<", left, y, arrow, () -> { RegearKits.cycle(-1); clearAndInit(); }, "Previous kit");
        ButtonWidget next = button(">", left + inner - arrow, y, arrow, () -> { RegearKits.cycle(1); clearAndInit(); }, "Next kit");
        prev.active = hasKits;
        next.active = hasKits;
        String kitName = RegearConfig.activeKit.isEmpty() ? (hasKits ? "No kit loaded - tap to choose" : "No kits yet - tap to create one")
                : RegearConfig.activeKit;
        button(kitName, left + arrow + 4, y, inner - 2 * (arrow + 4), () -> client.setScreen(new RegearKitScreen(this)),
                "Create, load or delete kits. Changes below are saved into the loaded kit.");
        y += 24;
        button("Hotbar & offhand...", left, y, half, () -> client.setScreen(new RegearSortScreen(this)),
                "Which item goes in which hotbar slot (saved in the kit)");
        button("Sort now", left + half + 4, y, half, () -> {
            client.setScreen(null);
            RegearRunner.sortOnly(client);
        }, "Arrange hotbar and offhand now, without buying");

        // ---- items: how many of each to have (0 = skip) ----
        y += 34;
        section("How many of each (0 = skip)", y);
        y += 12;
        for (KitItem it : KitItem.values()) {
            int i = it.ordinal();
            int max = Math.max(it.maxStack(), 1) * 36;
            stepper(y, it.label, () -> RegearConfig.kitAmount[i], v -> RegearConfig.kitAmount[i] = v,
                    0, max, 1, it.maxStack(), v -> v == 0 ? "skip" : String.valueOf(v), RegearConfig::save, null);
            y += 22;
        }

        // ---- options ----
        y += 6;
        toggle(left, y, half, "Only buy missing", () -> RegearConfig.topUp, v -> RegearConfig.topUp = v,
                RegearConfig::save, "ON = counts what you already carry");
        toggle(left + half + 4, y, half, "Arrange hotbar", () -> RegearConfig.sortEnabled,
                v -> RegearConfig.sortEnabled = v, RegearConfig::save, "Puts the items in the usual hotbar order after buying");

        y += 22;
        toggle(left, y, half, "Fill with totems", () -> RegearConfig.fillTotems, v -> RegearConfig.fillTotems = v,
                RegearConfig::save, "Buy totems last until every free slot is full");

        // ---- shop setup (rarely needed) ----
        y += 34;
        section("Shop setup (same for every kit)", y);
        y += 12;
        label("Shop command (without the /)", left, y + 6, 0xFFFFFFFF);
        y += 18;
        TextFieldWidget field = new TextFieldWidget(textRenderer, left, y, inner, 20, Text.literal("Shop command"));
        field.setMaxLength(40);
        field.setText(RegearConfig.command);
        field.setChangedListener(s -> {
            RegearConfig.command = RegearConfig.cleanCommand(s);
            RegearConfig.save();
        });
        addDrawableChild(field);
        y += 26;
        stepper(y, "Click delay", () -> RegearConfig.clickDelayTicks, v -> RegearConfig.clickDelayTicks = v,
                1, 40, 1, 5, RegearConfigScreen::secs, RegearConfig::save, "Pause between two clicks in the shop");
        y += 22;
        stepper(y, "Wait time", () -> RegearConfig.waitTicks, v -> RegearConfig.waitTicks = v,
                10, 200, 5, 20, RegearConfigScreen::secs, RegearConfig::save, "How long to wait for a menu or message");
        y += 28;
        button("Shop slots...", left, y, half, () -> client.setScreen(slotsScreen()),
                "Which slot each item has in the shop menus");
        button("Chat messages...", left + half + 4, y, half, () -> client.setScreen(messagesScreen()),
                "Chat messages the shop sends");
        y += 22;
        toggle(left, y, half, "Hide shop window", () -> RegearConfig.hideWindow, v -> RegearConfig.hideWindow = v,
                RegearConfig::save, "Shop windows are clicked without being shown");
        toggle(left + half + 4, y, half, "Debug chat", () -> RegearConfig.debug, v -> RegearConfig.debug = v,
                RegearConfig::save, "Print every step in chat");

        // ---- progress box ----
        y += 34;
        section("Progress box", y);
        y += 12;
        cycle(left, y, inner, "Position", 4, () -> RegearConfig.hudPos, v -> RegearConfig.hudPos = v,
                v -> new String[]{"off", "top center", "top left", "top right"}[v], RegearConfig::save,
                "Where the box shows while a regear runs");
        y += 24;
        stepper(y, "Size", () -> RegearConfig.hudScale, v -> RegearConfig.hudScale = v, 50, 200, 5, 25,
                v -> v + "%", RegearConfig::save, null);
        y += 22;
        stepper(y, "Move X", () -> RegearConfig.hudX, v -> RegearConfig.hudX = v, -600, 600, 1, 10,
                v -> v + " px", RegearConfig::save, null);
        y += 22;
        stepper(y, "Move Y", () -> RegearConfig.hudY, v -> RegearConfig.hudY = v, -600, 600, 1, 10,
                v -> v + " px", RegearConfig::save, null);
    }

    private Screen slotsScreen() {
        List<RegearFieldsScreen.Row> rows = new ArrayList<>();
        rows.add(slotRow("Gear category", () -> RegearConfig.categorySlot, v -> RegearConfig.categorySlot = v));
        for (KitItem it : KitItem.values()) {
            int i = it.ordinal();
            rows.add(slotRow(it.label, () -> RegearConfig.kitSlot[i], v -> RegearConfig.kitSlot[i] = v));
        }
        rows.add(slotRow("Item shown", () -> RegearConfig.displaySlot, v -> RegearConfig.displaySlot = v));
        rows.add(slotRow("Minus 64", () -> RegearConfig.minus64Slot, v -> RegearConfig.minus64Slot = v));
        rows.add(slotRow("Minus 10", () -> RegearConfig.minus10Slot, v -> RegearConfig.minus10Slot = v));
        rows.add(slotRow("Minus 1", () -> RegearConfig.minus1Slot, v -> RegearConfig.minus1Slot = v));
        rows.add(slotRow("Plus 1", () -> RegearConfig.plus1Slot, v -> RegearConfig.plus1Slot = v));
        rows.add(slotRow("Plus 10", () -> RegearConfig.plus10Slot, v -> RegearConfig.plus10Slot = v));
        rows.add(slotRow("Plus 64", () -> RegearConfig.plus64Slot, v -> RegearConfig.plus64Slot = v));
        rows.add(slotRow("Cancel", () -> RegearConfig.cancelSlot, v -> RegearConfig.cancelSlot = v));
        rows.add(slotRow("Confirm", () -> RegearConfig.confirmSlot, v -> RegearConfig.confirmSlot = v));
        return new RegearFieldsScreen("Shop slots", this, rows, 2,
                "Slots count from 0: left to right, top to bottom (row 2, column 5 = 13)");
    }

    private static RegearFieldsScreen.Row slotRow(String label, IntSupplier get, IntConsumer set) {
        return new RegearFieldsScreen.Row(label,
                () -> String.valueOf(get.getAsInt()),
                s -> set.accept(RegearConfig.parse(s, get.getAsInt(), 0, 53)));
    }

    private Screen messagesScreen() {
        List<RegearFieldsScreen.Row> rows = new ArrayList<>();
        rows.add(new RegearFieldsScreen.Row("Bought", () -> RegearConfig.msgBought,
                s -> RegearConfig.msgBought = s.isBlank() ? RegearConfig.msgBought : s));
        rows.add(new RegearFieldsScreen.Row("Inventory full", () -> RegearConfig.msgFull,
                s -> RegearConfig.msgFull = s.isBlank() ? RegearConfig.msgFull : s));
        rows.add(new RegearFieldsScreen.Row("No money", () -> RegearConfig.msgMoney,
                s -> RegearConfig.msgMoney = s.isBlank() ? RegearConfig.msgMoney : s));
        return new RegearFieldsScreen("Chat messages", this, rows, 1,
                "Part of the message is enough. Upper/lower case does not matter.");
    }

    @Override
    protected void saveAndLeave() {
        RegearConfig.save();
    }
}
