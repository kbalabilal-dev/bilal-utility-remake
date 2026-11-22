package com.bilalutility.regear;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Simple list of saved kits. Scroll to browse, click a kit to load it. */
public class RegearKitScreen extends BilalScreen {
    private static final int ROW_H = 38;
    private final List<Card> cards = new ArrayList<>();
    private int scroll;
    private int visibleRows;
    private int listTop;
    private int listBottom;
    private TextFieldWidget nameField;
    private ButtonWidget saveBtn;

    private record Card(int x, int y, int w, RegearKits.Kit kit) {}

    public RegearKitScreen(Screen parent) {
        super("My kits", "Save, load or delete your own regear setups", 0x55FF55, parent);
    }

    @Override
    protected boolean hasDoneButton() {
        return false;
    }

    @Override
    protected void build() {
        cards.clear();
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        listTop = contentTop() + 4;
        listBottom = height - 91;
        visibleRows = Math.max(1, (listBottom - listTop) / ROW_H);

        List<RegearKits.Kit> kits = RegearKits.custom();
        int maxScroll = Math.max(0, kits.size() - visibleRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        for (int i = scroll; i < Math.min(kits.size(), scroll + visibleRows); i++) {
            RegearKits.Kit kit = kits.get(i);
            int y = listTop + (i - scroll) * ROW_H;
            int w = inner - 22;
            buttonSized("", left, y, w, 34, () -> {
                RegearKits.apply(kit);
                if (client.player != null) client.player.sendMessage(Text.literal("Loaded kit: " + kit.name), true);
            }, RegearKits.summary(kit));
            buttonSized("×", left + w + 3, y, 19, 34, () -> {
                RegearKits.delete(kit.name);
                scroll = Math.min(scroll, Math.max(0, RegearKits.custom().size() - visibleRows));
                clearAndInit();
            }, "Delete kit");
            cards.add(new Card(left, y, w, kit));
        }

        if (kits.isEmpty()) {
            centered("No saved kits yet.", listTop + 18, 0xFFAAAAAA);
            centered("Type a name below and tap New kit.", listTop + 33, 0xFF888899);
        }

        int ry = height - 56;
        String old = nameField != null ? nameField.getText() : "";
        nameField = new TextFieldWidget(textRenderer, left, ry, inner - 110, 20, Text.literal("Kit name"));
        nameField.setMaxLength(RegearKits.NAME_MAX);
        nameField.setText(old);
        nameField.setChangedListener(s -> {
            if (saveBtn != null) saveBtn.active = RegearKits.canSave(s);
        });
        addDrawableChild(nameField);
        saveBtn = button("New kit", left + inner - 106, ry, 106, () -> {
            if (RegearKits.saveCurrent(nameField.getText())) {
                nameField.setText("");
                clearAndInit();
            }
        }, "Creates a kit from what is set up right now");
        saveBtn.active = RegearKits.canSave(old);

        button("Edit kit", left, height - 30, 90, () -> client.setScreen(new RegearConfigScreen(this)),
                "Items, amounts and hotbar of the loaded kit");
        button("Done", centerX() - 45, height - 30, 90, this::close, null);
        ButtonWidget buy = button("Buy now", left + inner - 90, height - 30, 90, () -> {
            client.setScreen(null);
            RegearRunner.start(client);
        }, "Run regear with the current setup");
        buy.active = client != null && client.world != null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= panelLeft() && mouseX <= panelRight()
                && mouseY >= listTop && mouseY <= listBottom) {
            int max = Math.max(0, RegearKits.custom().size() - visibleRows);
            int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(verticalAmount)));
            if (next != scroll) {
                scroll = next;
                clearAndInit();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        for (Card c : cards) {
            int cy = scrollY(c.y());
            if (!inViewport(cy, 34)) continue;
            RegearKits.Kit k = c.kit();
            boolean active = k.name.equalsIgnoreCase(RegearConfig.activeKit);
            ctx.drawItem(KitItem.values()[k.icon].item.getDefaultStack(), c.x() + 7, cy + 8);
            ctx.drawTextWithShadow(textRenderer, k.name, c.x() + 30, cy + 5,
                    active ? 0xFF55FF55 : 0xFFFFFFFF);
            if (active) {
                ctx.drawTextWithShadow(textRenderer, "ACTIVE", c.x() + c.w() - 48, cy + 5, 0xFF55FF55);
                box(ctx, c.x(), cy, c.x() + c.w(), cy + 34, 0x2255FF55, 0xFF55FF55);
            }
            int ix = c.x() + 30;
            for (KitItem it : KitItem.values()) {
                if (k.amounts[it.ordinal()] <= 0) continue;
                ctx.drawItem(it.item.getDefaultStack(), ix, cy + 17);
                ix += 17;
            }
            if (k.fillTotems) ctx.drawItem(KitItem.TOTEM.item.getDefaultStack(), ix, cy + 17);
        }

        int total = RegearKits.custom().size();
        if (total > visibleRows) {
            int trackX = panelRight() - 8;
            int trackH = listBottom - listTop;
            ctx.fill(trackX, listTop, trackX + 3, listBottom, 0xFF343440);
            int thumbH = Math.max(12, trackH * visibleRows / total);
            int max = total - visibleRows;
            int thumbY = listTop + (trackH - thumbH) * scroll / max;
            ctx.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFF55FF55);
        }
    }
}
