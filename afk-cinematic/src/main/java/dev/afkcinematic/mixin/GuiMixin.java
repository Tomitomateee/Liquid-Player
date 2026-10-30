package dev.afkcinematic.mixin;

import dev.afkcinematic.cinematic.CinematicController;
import dev.afkcinematic.render.CinematicOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces the whole HUD (hotbar, hearts, crosshair, ...) with the cinematic overlay while the mode runs. */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void afkcinematic$replaceHud(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (CinematicController.get().hidesHud()) {
			CinematicOverlay.render(graphics);
			ci.cancel();
		}
	}
}
