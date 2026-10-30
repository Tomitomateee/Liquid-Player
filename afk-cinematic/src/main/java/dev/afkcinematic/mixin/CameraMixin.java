package dev.afkcinematic.mixin;

import dev.afkcinematic.cinematic.CameraPose;
import dev.afkcinematic.cinematic.CinematicController;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves the camera for the cinematic. Runs after vanilla's {@code Camera.setup} has placed the camera,
 * then overrides position and rotation of the camera only – the player's own rotation stays untouched,
 * so nothing is sent to the server and no anti-cheat can notice anything.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setPosition(Vec3 pos);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "setup", at = @At("TAIL"))
	private void afkcinematic$moveCamera(CallbackInfo ci) {
		CinematicController controller = CinematicController.get();
		if (!controller.controlsCamera()) return;
		Minecraft mc = Minecraft.getInstance();
		Camera self = (Camera) (Object) this;
		Entity entity = self.getEntity();
		if (mc.level == null || entity == null || entity != mc.player) return;

		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		CameraPose vanilla = new CameraPose(self.getPosition(), self.getYRot(), self.getXRot());
		CameraPose pose = controller.cameraPose(mc.level, entity, partialTick, vanilla);
		setRotation(pose.yaw(), pose.pitch());
		setPosition(pose.pos());
	}
}
