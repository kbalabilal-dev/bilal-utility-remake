package com.bilalutility.mixin;

import com.bilalutility.regear.RegearRunner;
import com.bilalutility.tpa.TpaQueue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops chest-style windows from being drawn while TPAHere (confirm window) or Auto Regear
 * (shop windows) is working. The window handler is still open and filled by the server,
 * so both modules can read it and click through it without showing anything.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void bilal$hideWindows(Screen screen, CallbackInfo ci) {
        if (screen instanceof GenericContainerScreen
                && (TpaQueue.wantsHiddenWindow() || RegearRunner.wantsHiddenWindow())) {
            ci.cancel();
        }
    }
}
