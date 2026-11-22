package com.bilalutility.tpa;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects when the server will not accept the teleport command. Two signals:
 *
 * 1. The text above the hotbar saying "Combat: Xs". It is read in InGameHudMixin, which sees every
 *    action-bar message however the server sent it (the plain game-message event can miss some).
 *    The server re-sends the text about once a second, so we count as "in combat" while it keeps
 *    arriving (or until it shows 0s).
 * 2. The chat message "You can't use this command in your current state". When it shows up the
 *    queue is held for TpaConfig.blockedHoldTicks and the player it failed for is sent again.
 */
public final class TpaCombat {
    private static final Pattern COMBAT = Pattern.compile("combat[^\\d]{0,12}(\\d+)", Pattern.CASE_INSENSITIVE);
    // matches both the straight and the curly apostrophe
    private static final Pattern BLOCKED = Pattern.compile("can.?t use this command", Pattern.CASE_INSENSITIVE);
    private static final int TIMEOUT_TICKS = 30; // no combat text for 1.5s -> combat is over

    private static int tickCounter = 0;
    private static int lastSeen = Integer.MIN_VALUE / 2;
    private static int seconds = 0;
    private static int blockedUntil = Integer.MIN_VALUE / 2;

    private TpaCombat() {}

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String s = message.getString();
            if (overlay) onOverlay(s);
            else onChat(s);
        });
    }

    /** Text above the hotbar. Called from the HUD mixin and from the game-message event. */
    public static void onOverlay(String text) {
        Matcher m = COMBAT.matcher(text);
        if (!m.find()) return;
        try {
            seconds = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return;
        }
        lastSeen = tickCounter;
    }

    /** A chat / system message. */
    public static void onChat(String text) {
        if (!BLOCKED.matcher(text).find()) return;
        blockedUntil = tickCounter + TpaConfig.blockedHoldTicks;
        TpaQueue.onCommandBlocked();
    }

    public static void tick(MinecraftClient client) {
        tickCounter++;
        if (client.player == null) {
            lastSeen = Integer.MIN_VALUE / 2;
            blockedUntil = Integer.MIN_VALUE / 2;
        }
    }

    private static boolean combatText() {
        return seconds > 0 && tickCounter - lastSeen < TIMEOUT_TICKS;
    }

    public static boolean inCombat() {
        return combatText() || tickCounter < blockedUntil;
    }

    /** True only while the queue is held because the server refused the command. */
    public static boolean blockedNow() {
        return tickCounter < blockedUntil;
    }

    public static int secondsLeft() {
        if (combatText()) return seconds;
        return tickCounter < blockedUntil ? (blockedUntil - tickCounter + 19) / 20 : 0;
    }
}
