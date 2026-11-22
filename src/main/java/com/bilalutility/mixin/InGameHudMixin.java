package com.bilalutility.mixin;

import com.bilalutility.tpa.TpaCombat;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads the text shown above the hotbar (the "action bar"). Every route the server can use to
 * set it ends in setOverlayMessage, so this sees the "Combat: Xs" text however it was sent.
 * require = 0: if this ever fails to apply, the mod still loads (the chat check still works).
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "setOverlayMessage", at = @At("HEAD"), require = 0)
    private void bilal$onOverlay(Text message, boolean tinted, CallbackInfo ci) {
        if (message != null) TpaCombat.onOverlay(message.getString());
    }
}
