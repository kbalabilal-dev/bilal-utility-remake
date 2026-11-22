package com.bilalutility.regear;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Offhand and hotbar layout, drawn like the real hotbar. Click a slot to cycle: Leave, then every kit item. */
public class RegearSortScreen extends BilalScreen {
    private int offY, hotY, hotX, offX;
    private int selected = -2;   // -1 = offhand, 0-8 = hotbar slot, -2 = none

    public RegearSortScreen(Screen parent) {
        super("Hotbar & offhand", "Where the regear puts your items after buying", 0x55FF55, parent);
    }

    private static String nameOf(int v) {
        return v < 0 ? "Leave alone" : KitItem.values()[v].label;
    }

    private static int next(int v) {
        int n = v + 1;
        return n >= KitItem.values().length ? -1 : n;
    }

    @Override
    protected void build() {
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int half = (inner - 4) / 2;
        int y = contentTop();

        section("Offhand", y);
        offX = left;
        offY = y + 12;
        buttonSized("", offX, offY, 32, 32, () -> {
            RegearConfig.offhandItem = next(RegearConfig.offhandItem);
            RegearConfig.save();
            selected = -1;
        }, "Offhand");

        y = offY + 42;
        section("Hotbar (click a slot to change it)", y);
        hotX = left + (inner - (9 * 35 - 3)) / 2;
        hotY = y + 12;
        for (int i = 0; i < 9; i++) {
            int idx = i;
            buttonSized("", hotX + i * 35, hotY, 32, 32, () -> {
                RegearConfig.hotbarItem[idx] = next(RegearConfig.hotbarItem[idx]);
                RegearConfig.save();
                selected = idx;
            }, "Slot " + (i + 1));
            label(String.valueOf(i + 1), hotX + i * 35 + 14, hotY + 35, 0xFF9A9AA8);
        }

        dynamic(() -> selected == -2 ? "Click a slot"
                        : selected == -1 ? "Offhand: " + nameOf(RegearConfig.offhandItem)
                        : "Slot " + (selected + 1) + ": " + nameOf(RegearConfig.hotbarItem[selected]),
                left + 40, offY + 10, 160, 0xFFFFFFFF);

        y = hotY + 56;
        section("Options", y);
        y += 12;
        toggle(left, y, half, "Sort after buying", () -> RegearConfig.sortEnabled, v -> RegearConfig.sortEnabled = v,
                RegearConfig::save, "Arrange offhand and hotbar when the purchases are done");
        toggle(left + half + 4, y, half, "Fill with totems", () -> RegearConfig.fillTotems,
                v -> RegearConfig.fillTotems = v, RegearConfig::save,
                "Buy totems last until every free slot is full");
        setHint("Sorting only moves items you already have; 'Leave alone' slots are never touched");
    }

    @Override
    protected void saveAndLeave() {
        RegearConfig.save();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int oy = scrollY(offY), hy = scrollY(hotY);
        if (inViewport(oy, 32)) drawSlot(ctx, offX, oy, RegearConfig.offhandItem, selected == -1);
        if (inViewport(hy, 32)) {
            for (int i = 0; i < 9; i++) {
                drawSlot(ctx, hotX + i * 35, hy, RegearConfig.hotbarItem[i], selected == i);
            }
        }
    }

    private void drawSlot(DrawContext ctx, int x, int y, int value, boolean sel) {
        if (value >= 0) {
            ctx.drawItem(KitItem.values()[value].item.getDefaultStack(), x + 8, y + 8);
        } else {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("-"), x + 16, y + 12, 0xFF777788);
        }
        if (sel) box(ctx, x, y, x + 32, y + 32, 0x2255FF55, 0xFF55FF55);
    }
}
