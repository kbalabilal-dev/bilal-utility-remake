package com.bilalutility.tpa;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Tap a name to put it on / take it off the skip list. */
public class TpaSkipScreen extends BilalScreen {
    private static final int ROW_H = 24;

    private TextFieldWidget search;
    private ButtonWidget prev, next, addBtn;
    private final List<ButtonWidget> buttons = new ArrayList<>();
    private int page = 0;
    private boolean empty = false;

    public TpaSkipScreen(Screen parent) {
        super("Skip list", "Red names are never sent a request. Tap a name to switch.", 0xFF5555, parent);
    }

    @Override
    protected boolean hasDoneButton() {
        return false;
    }

    @Override
    protected void build() {
        buttons.clear();
        int left = panelLeft() + 12;
        int inner = panelW() - 24;
        int y = contentTop();

        String old = search != null ? search.getText() : "";
        search = new TextFieldWidget(textRenderer, left, y, inner - 60, 20, Text.literal("Search"));
        search.setText(old);
        search.setChangedListener(s -> { page = 0; rebuild(); });
        addDrawableChild(search);

        addBtn = button("Add", left + inner - 56, y, 56, () -> {
            String t = search.getText().trim();
            if (valid(t)) {
                TpaConfig.setSkipped(t, true);
                TpaConfig.save();
                search.setText("");
                rebuild();
            }
        }, "Adds the typed name even if that player is offline");

        prev = button("<", centerX() - 104, height - 30, 44, () -> { page--; rebuild(); }, null);
        next = button(">", centerX() + 60, height - 30, 44, () -> { page++; rebuild(); }, null);
        button("Done", centerX() - 50, height - 30, 100, this::close, null);

        setInitialFocus(search);
        rebuild();
    }

    private static boolean valid(String name) {
        return name.matches("[A-Za-z0-9_.]{1,20}");
    }

    private Text nameLabel(String name) {
        return TpaConfig.isSkipped(name) ? Text.literal(name).formatted(Formatting.RED) : Text.literal(name);
    }

    private List<String> entries() {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(TpaQueue.onlineNames(client, ""));
        set.addAll(TpaConfig.skipList());
        String q = search.getText().toLowerCase();
        return set.stream().filter(n -> n.toLowerCase().contains(q)).toList();
    }

    private void rebuild() {
        for (ButtonWidget b : buttons) remove(b);
        buttons.clear();

        int top = contentTop() + 28;
        List<String> all = entries();
        empty = all.isEmpty();
        int rows = Math.max(1, (height - top - 40) / ROW_H);
        int perPage = rows * 3;
        int maxPage = Math.max(0, (all.size() - 1) / perPage);
        page = MathHelper.clamp(page, 0, maxPage);

        int x0 = panelLeft() + 13;
        int start = page * perPage;
        int end = Math.min(all.size(), start + perPage);
        for (int i = start; i < end; i++) {
            String name = all.get(i);
            int idx = i - start;
            ButtonWidget b = ButtonWidget.builder(nameLabel(name), btn -> {
                        TpaConfig.setSkipped(name, !TpaConfig.isSkipped(name));
                        TpaConfig.save();
                        btn.setMessage(nameLabel(name));
                    })
                    .dimensions(x0 + (idx % 3) * 104, top + (idx / 3) * ROW_H, 102, 20).build();
            buttons.add(b);
            addDrawableChild(b);
        }
        prev.active = page > 0;
        next.active = page < maxPage;
        String typed = search.getText().trim();
        addBtn.active = valid(typed) && !TpaConfig.isSkipped(typed);
    }

    @Override
    public void render(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        if (empty) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Nobody here. Type a name and tap Add."), width / 2, contentTop() + 44, 0xFFAAAAAA);
        }
    }
}
