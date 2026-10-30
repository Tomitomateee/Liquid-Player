package dev.afkcinematic.mixin;

import dev.afkcinematic.cinematic.CinematicController;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every key event counts as activity (resets the AFK timer, ends the cinematic). */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"))
	private void afkcinematic$onKey(CallbackInfo ci) {
		CinematicController.get().onKeyInput();
	}
}
