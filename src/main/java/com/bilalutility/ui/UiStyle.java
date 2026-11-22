package com.bilalutility.ui;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Menu look: modern (dark panel, coloured header) or classic (plain Minecraft menu). */
public final class UiStyle {
    public static boolean classic = false;

    private UiStyle() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("bilal_ui.txt");
    }

    public static void load() {
        try {
            if (Files.exists(file())) classic = Files.readString(file()).trim().equals("classic");
        } catch (Exception ignored) {
            // keep the default
        }
    }

    public static void toggle() {
        classic = !classic;
        try {
            Files.writeString(file(), classic ? "classic" : "modern");
        } catch (Exception ignored) {
            // not fatal
        }
    }
}
