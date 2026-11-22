package com.bilalutility.regear;

import com.bilalutility.ui.BilalScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A page of text boxes (one label + one box per row), in 1 or 2 columns.
 * Everything typed is saved right away.
 */
public class RegearFieldsScreen extends BilalScreen {
    public record Row(String label, Supplier<String> get, Consumer<String> set) {}

    private final List<Row> rows;
    private final int columns;
    private final String hintText;

    public RegearFieldsScreen(String title, Screen parent, List<Row> rows, int columns, String hint) {
        super(title, "", 0x55FF55, parent);
        this.rows = rows;
        this.columns = Math.max(1, columns);
        this.hintText = hint;
    }

    @Override
    protected void build() {
        int inner = panelW() - 24;
        int perCol = (rows.size() + columns - 1) / columns;
        boolean wide = columns == 1;
        int colW = (inner - 4 * (columns - 1)) / columns;
        int labelW = wide ? 90 : 88;
        int fieldW = wide ? inner - labelW : colW - labelW;
        int x0 = panelLeft() + 12;
        int top = contentTop();

        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int col = i / perCol;
            int row = i % perCol;
            int x = x0 + col * (colW + 4);
            int y = top + row * 22;

            label(r.label(), x, y + 6, 0xFFFFFFFF);
            TextFieldWidget f = new TextFieldWidget(textRenderer, x + labelW, y, fieldW, 20, Text.literal(r.label()));
            f.setMaxLength(wide ? 80 : 6);
            f.setText(r.get().get());
            f.setChangedListener(s -> {
                r.set().accept(s);
                RegearConfig.save();
            });
            addDrawableChild(f);
        }
        setHint(hintText);
    }

    @Override
    protected void saveAndLeave() {
        RegearConfig.save();
    }
}
