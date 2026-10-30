package dev.afkcinematic.mixin;

import dev.afkcinematic.cinematic.CinematicController;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Field of view during the cinematic: a slightly narrower "lens" plus a short zoom punch on every beat. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void afkcinematic$cinematicFov(CallbackInfoReturnable<Float> cir) {
		CinematicController controller = CinematicController.get();
		if (controller.controlsCamera()) {
			cir.setReturnValue(cir.getReturnValue() * controller.fovMultiplier());
		}
	}
}
