package com.bilalutility;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;

/** The one keybinding category shared by all modules (a category can only be registered once). */
public final class BilalKeys {
    public static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("bilal_utility", "main"));

    private BilalKeys() {}
}
