package com.bilalutility.tpa;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.Locale;

/** One simple page: the few things that matter first, the rest below. */
public class TpaConfigScreen extends BilalScreen {
    private static final String[] HUD_NAMES = {"off", "top center", "top left", "top right"};

    private ButtonWidget pauseBtn;

    public TpaConfigScreen(Screen parent) {
        super("TPAHere", "Queued /tpahere with combat detection", 0x3FA9FF, parent);
    }

    private static String secs(int ticks) {
        return String.format(Locale.ROOT, "%.1f s", ticks / 20.0);
    }

    @Override
    protected void build() {
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int half = (inner - 4) / 2;
        int y = contentTop();

        // ---- basics ----
        stepper(y, "Delay", () -> TpaConfig.delayTicks, v -> TpaConfig.delayTicks = v, 2, 100, 2, 10,
                TpaConfigScreen::secs, TpaConfig::save, "Pause between players");
        y += 22;
        toggle(left, y, half, "Combat pause", () -> TpaConfig.combatPause, v -> TpaConfig.combatPause = v,
                TpaConfig::save, "Hold the queue while 'Combat: Xs' shows");
        toggle(left + half + 4, y, half, "Confirm window", () -> !TpaConfig.hideWindow, v -> TpaConfig.hideWindow = !v,
                TpaConfig::save, "ON = you see the confirm window, OFF = it is clicked without being shown");
        y += 22;
        button("Skip list (" + TpaConfig.skipCount() + ")", left, y, half,
                () -> client.setScreen(new TpaSkipScreen(this)), "Players that are never sent a request");
        pauseBtn = button(TpaQueue.isPaused() ? "Resume queue" : "Pause queue", left + half + 4, y, half, () -> {
            TpaQueue.togglePause(client);
            pauseBtn.setMessage(Text.literal(TpaQueue.isPaused() ? "Resume queue" : "Pause queue"));
        }, null);
        pauseBtn.active = TpaQueue.isRunning();

        // ---- more ----
        y += 34;
        section("More", y);
        y += 12;
        label("Command (without the /)", left, y + 6, 0xFFFFFFFF);
        y += 18;
        TextFieldWidget field = new TextFieldWidget(textRenderer, left, y, inner, 20, Text.literal("Command"));
        field.setMaxLength(40);
        field.setText(TpaConfig.command);
        field.setChangedListener(s -> {
            TpaConfig.command = TpaConfig.cleanCommand(s);
            TpaConfig.save();
        });
        addDrawableChild(field);
        y += 26;
        stepper(y, "Skip wait", () -> TpaConfig.windowTimeoutTicks, v -> TpaConfig.windowTimeoutTicks = v,
                10, 100, 2, 10, TpaConfigScreen::secs, TpaConfig::save, "How long to wait for the confirm window");
        y += 22;
        stepper(y, "Round pause", () -> TpaConfig.roundPauseTicks, v -> TpaConfig.roundPauseTicks = v,
                10, 600, 10, 100, TpaConfigScreen::secs, TpaConfig::save, "Pause before a new round (auto mode)");
        y += 22;
        stepper(y, "Max rounds", () -> TpaConfig.maxRounds, v -> TpaConfig.maxRounds = v,
                0, 999, 1, 5, v -> v == 0 ? "endless" : v + " rounds", TpaConfig::save,
                "Auto repeat stops after this many rounds");
        y += 22;
        stepper(y, "Auto hit", () -> TpaConfig.autoHitDelayMs, v -> TpaConfig.autoHitDelayMs = v,
                50, 5000, 50, 100, v -> v + " ms", TpaConfig::save, "Right click once every X milliseconds");

        // ---- progress box ----
        y += 34;
        section("Progress box", y);
        y += 12;
        cycle(left, y, inner, "Position", 4, () -> TpaConfig.hudPos, v -> TpaConfig.hudPos = v,
                v -> HUD_NAMES[v], TpaConfig::save, "Where the box shows while a queue runs");
        y += 24;
        stepper(y, "Size", () -> TpaConfig.hudScale, v -> TpaConfig.hudScale = v, 50, 200, 5, 25,
                v -> v + "%", TpaConfig::save, null);
        y += 22;
        stepper(y, "Move X", () -> TpaConfig.hudX, v -> TpaConfig.hudX = v, -600, 600, 1, 10,
                v -> v + " px", TpaConfig::save, null);
        y += 22;
        stepper(y, "Move Y", () -> TpaConfig.hudY, v -> TpaConfig.hudY = v, -600, 600, 1, 10,
                v -> v + " px", TpaConfig::save, null);

        dynamic(() -> "Requests sent, all time: " + TpaConfig.totalSent, left, y + 30, inner, 0xFF9A9AA8);
    }

    @Override
    protected void saveAndLeave() {
        TpaConfig.save();
    }
}
