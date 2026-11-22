package com.bilalutility;

import com.bilalutility.anchor.AnchorConfigScreen;
import com.bilalutility.flight.FlightRunner;
import com.bilalutility.flight.FlightScreen;
import com.bilalutility.hud.HudConfig;
import com.bilalutility.hud.HudConfigScreen;
import com.bilalutility.regear.RegearConfigScreen;
import com.bilalutility.regear.RegearRunner;
import com.bilalutility.tpa.TpaConfig;
import com.bilalutility.tpa.TpaConfigScreen;
import com.bilalutility.tpa.TpaQueue;
import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.function.Supplier;

/** One settings entry point: a plain list, one row per module, with its icon and a short status. */
public class BilalHubScreen extends BilalScreen {
    private static final int ROW_H = 28;

    private record Row(net.minecraft.client.gui.widget.ButtonWidget btn, int x, int w, Item icon, String title,
                       Supplier<String> status) {}

    private final java.util.List<Row> rows = new java.util.ArrayList<>();

    public BilalHubScreen(Screen parent) {
        super("Bilal Utility", "Tap a module", 0xFFC84A, parent);
    }

    @Override
    protected void build() {
        rows.clear();
        int left = panelLeft() + 12;
        int w = panelW() - 24;
        int y = contentTop();

        add(left, y, w, Items.COMPASS, "HUD",
                () -> HudConfig.d.enabled ? "On" : "Off",
                () -> client.setScreen(new HudConfigScreen(this)));
        y += ROW_H + 4;
        add(left, y, w, Items.RESPAWN_ANCHOR, "Anchor Macro",
                AnchorConfigScreen::currentPreset,
                () -> client.setScreen(new AnchorConfigScreen(this)));
        y += ROW_H + 4;
        add(left, y, w, Items.ENDER_PEARL, "TPAHere",
                () -> TpaQueue.isRunning() ? "Running" : "Idle",
                () -> client.setScreen(new TpaConfigScreen(this)));
        y += ROW_H + 4;
        add(left, y, w, Items.TOTEM_OF_UNDYING, "Auto Regear",
                () -> RegearRunner.isRunning() ? "Running" : (com.bilalutility.regear.RegearConfig.activeKit.isEmpty() ? "No kit" : com.bilalutility.regear.RegearConfig.activeKit),
                () -> client.setScreen(new RegearConfigScreen(this)));

        y += ROW_H + 4;
        add(left, y, w, Items.ELYTRA, "Auto Elytra",
                FlightRunner::label,
                () -> client.setScreen(new FlightScreen(this)));

        y += ROW_H + 8;
        button("Menu style: " + (com.bilalutility.ui.UiStyle.classic ? "Classic" : "Modern"), left, y, w, () -> {
            com.bilalutility.ui.UiStyle.toggle();
            clearAndInit();
        }, "Classic = plain Minecraft menu look. Reopen other menus to see it.");

        setHint("Keys: Options > Controls > Key Binds > Bilal Utility");
    }

    private void add(int x, int y, int w, Item icon, String title, Supplier<String> status, Runnable open) {
        rows.add(new Row(buttonSized("", x, y, w, ROW_H, open, null), x, w, icon, title, status));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        for (Row r : rows) {
            if (!r.btn().visible) continue;
            int y = r.btn().getY();
            ctx.drawItem(r.icon().getDefaultStack(), r.x() + 8, y + 6);
            ctx.drawTextWithShadow(textRenderer, r.title(), r.x() + 32, y + 10, 0xFFFFFFFF);
            String st = r.status().get();
            ctx.drawTextWithShadow(textRenderer, st, r.x() + r.w() - 8 - textRenderer.getWidth(st), y + 10, 0xFFFFCC66);
        }
    }
}
