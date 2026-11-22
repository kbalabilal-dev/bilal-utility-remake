package com.bilalutility.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * A custom drawing of the server's sidebar. It reads the same data the vanilla sidebar uses (the
 * objective shown in the SIDEBAR slot: its title and its lines, with team prefixes/suffixes applied),
 * and draws it with the size, position, opacity, number and shadow settings from the HUD config.
 */
public final class ScoreboardPanel {
    private ScoreboardPanel() {}

    public static void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        HudConfig.Data c = HudConfig.d;
        if (mc.world == null || mc.player == null) return;

        Scoreboard sb = mc.world.getScoreboard();
        ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (obj == null) return;

        List<ScoreboardEntry> entries = new ArrayList<>();
        for (ScoreboardEntry e : sb.getScoreboardEntries(obj)) {
            if (!e.hidden()) entries.add(e);
        }
        entries.sort((a, b) -> {
            int cmp = Integer.compare(b.value(), a.value());
            return cmp != 0 ? cmp : a.owner().compareToIgnoreCase(b.owner());
        });
        if (entries.size() > c.sbMaxLines) entries = new ArrayList<>(entries.subList(0, c.sbMaxLines));

        TextRenderer tr = mc.textRenderer;
        Text title = obj.getDisplayName();
        List<Text> names = new ArrayList<>();
        List<String> nums = new ArrayList<>();
        int w = tr.getWidth(title);
        int numW = 0;
        for (ScoreboardEntry e : entries) {
            Team team = sb.getScoreHolderTeam(e.owner());
            Text name = Team.decorateName(team, e.name());
            names.add(name);
            String num = c.sbNumbers ? Integer.toString(e.value()) : "";
            nums.add(num);
            numW = Math.max(numW, tr.getWidth(num));
            w = Math.max(w, tr.getWidth(name) + (c.sbNumbers ? 8 + tr.getWidth(num) : 0));
        }
        if (c.sbNumbers) w = Math.max(w, numW + 8);

        int lineH = 10;
        int h = (names.size() + 1) * lineH + 4;
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        float s = c.scoreboardScale / 100f;

        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(sw + c.sbX, sh / 2f + c.sbY);
        m.scale(s, s);
        m.translate(-sw, -sh / 2f);

        int x2 = sw - 4;
        int x1 = x2 - w - 6;
        int top = sh / 2 - h / 2;
        ctx.fill(x1, top, x2, top + h, (c.sbOpacity * 255 / 100) << 24);

        ctx.drawText(tr, title, x1 + 3 + (w - tr.getWidth(title)) / 2, top + 3, 0xFFFFFFFF, c.sbShadow);
        for (int i = 0; i < names.size(); i++) {
            int y = top + 3 + (i + 1) * lineH;
            ctx.drawText(tr, names.get(i), x1 + 3, y, 0xFFFFFFFF, c.sbShadow);
            if (c.sbNumbers) {
                ctx.drawText(tr, nums.get(i), x2 - 3 - tr.getWidth(nums.get(i)), y, 0xFFFF5555, c.sbShadow);
            }
        }
        m.popMatrix();
    }
}
