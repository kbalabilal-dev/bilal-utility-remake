package com.bilalutility;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Adds the "Configure" button for Bilal Utility in Mod Menu. Only loaded when Mod Menu is installed. */
public class BilalModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new BilalHubScreen(parent);
    }
}
