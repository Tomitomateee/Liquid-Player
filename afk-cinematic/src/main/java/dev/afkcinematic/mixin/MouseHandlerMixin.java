package dev.afkcinematic.mixin;

import dev.afkcinematic.cinematic.CinematicController;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse clicks, scrolling and movement count as activity (resets the AFK timer, ends the cinematic). */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"))
	private void afkcinematic$onButton(CallbackInfo ci) {
		CinematicController.get().onMouseButtonOrScroll();
	}

	@Inject(method = "onScroll", at = @At("HEAD"))
	private void afkcinematic$onScroll(CallbackInfo ci) {
		CinematicController.get().onMouseButtonOrScroll();
	}

	@Inject(method = "onMove", at = @At("TAIL"))
	private void afkcinematic$onMove(CallbackInfo ci) {
		MouseHandler self = (MouseHandler) (Object) this;
		CinematicController.get().onMouseMoved(self.xpos(), self.ypos());
	}
}
