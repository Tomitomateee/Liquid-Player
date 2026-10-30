package dev.afkcinematic.cinematic;

import net.minecraft.world.phys.Vec3;

/** Camera position plus rotation in Minecraft's convention (yaw: 0 = +Z/south, pitch: positive = down). */
public record CameraPose(Vec3 pos, float yaw, float pitch) {
	/** A pose at {@code pos} looking at {@code target}. */
	public static CameraPose lookAt(Vec3 pos, Vec3 target) {
		double dx = target.x - pos.x, dy = target.y - pos.y, dz = target.z - pos.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
		return new CameraPose(pos, yaw, pitch);
	}

	public CameraPose lerp(CameraPose to, double t) {
		return new CameraPose(pos.lerp(to.pos, t),
				Easing.lerpAngle(yaw, to.yaw, t),
				(float) Easing.lerp(pitch, to.pitch, t));
	}
}
