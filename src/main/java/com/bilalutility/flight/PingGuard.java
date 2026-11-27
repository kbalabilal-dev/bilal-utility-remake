package com.bilalutility.flight;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ping estimate for the ship routine. Main value: the client's own latency of the player (tab list), smoothed by the
 * median of the last samples. Corrector: the "Ping: XXms" line of the server scoreboard (sidebar). If the scoreboard
 * says the ping is higher, we move towards it; if it is lower, we only move a little.
 * The routine uses it to wait longer for server side actions (/enderchest, inventory clicks, pearl teleport).
 */
public final class PingGuard {
    private PingGuard() {}

    private static final Pattern SCOREBOARD_PING = Pattern.compile("ping\\s*:?\\s*(\\d{1,4})\\s*ms", Pattern.CASE_INSENSITIVE);
    private static final int RING = 7;
    private static final int[] ring = new int[RING];
    private static int ringCount, ringPos, tickCount;
    private static int tabMs = -1, scoreboardMs = -1;
    private static double estimate = -1;

    public static void tick(MinecraftClient c) {
        if (c == null || c.player == null || c.world == null) {
            estimate = -1;
            ringCount = 0;
            return;
        }
        if (++tickCount % 10 != 0) return;
        tabMs = readTabLatency(c);
        scoreboardMs = readScoreboardPing(c);
        if (tabMs > 0) {
            ring[ringPos] = tabMs;
            ringPos = (ringPos + 1) % RING;
            if (ringCount < RING) ringCount++;
        }
        double own = -1;
        if (ringCount > 0) {
            int[] copy = Arrays.copyOf(ring, ringCount);
            Arrays.sort(copy);
            own = copy[ringCount / 2];                  // median: one spike does not fool it
        }
        if (own < 0) {
            estimate = scoreboardMs >= 0 ? scoreboardMs : -1;
        } else if (scoreboardMs >= 0) {
            estimate = scoreboardMs > own ? (own + scoreboardMs) / 2.0 : 0.8 * own + 0.2 * scoreboardMs;
        } else {
            estimate = own;
        }
        if (tickCount % 400 == 0) {
            FlightRecorder.routine("PING own=" + tabMs + " scoreboard=" + scoreboardMs + " used=" + ms() + " extraTicks=" + extraTicks());
        }
    }

    private static int readTabLatency(MinecraftClient c) {
        ClientPlayNetworkHandler nh = c.getNetworkHandler();
        PlayerListEntry e = nh != null ? nh.getPlayerListEntry(c.player.getGameProfile().name()) : null;
        return e != null ? e.getLatency() : -1;
    }

    private static int readScoreboardPing(MinecraftClient c) {
        try {
            Scoreboard sb = c.world.getScoreboard();
            ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (obj == null) return -1;
            for (ScoreboardEntry e : sb.getScoreboardEntries(obj)) {
                if (e.hidden()) continue;
                Team team = sb.getScoreHolderTeam(e.owner());
                String text = Team.decorateName(team, e.name()).getString();
                Matcher m = SCOREBOARD_PING.matcher(text);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        } catch (RuntimeException ignored) {
            // a strange scoreboard must never break the routine
        }
        return -1;
    }

    /** Estimated ping in ms (50 when nothing is known yet). */
    public static int ms() {
        return estimate < 0 ? 50 : (int) Math.round(estimate);
    }

    /** Extra ticks to add to waits for server side actions: 0 at a low ping, about +1 per 50 ms above 40 ms. */
    public static int extraTicks() {
        return Math.max(0, Math.min(20, (int) Math.ceil((ms() - 40) / 50.0)));
    }

    /** Very high ping right now: risky server side steps should wait. */
    public static boolean spike() {
        return ms() >= 350;
    }
}
