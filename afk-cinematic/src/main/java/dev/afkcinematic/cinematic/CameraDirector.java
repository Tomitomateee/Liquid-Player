package dev.afkcinematic.cinematic;

import dev.afkcinematic.audio.BeatDetector;
import dev.afkcinematic.config.CinematicConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * The "camera operator": decides which shot is running, moves the camera along it and reacts to the music.
 * Runs on the render thread once per frame and only works on its own state – the player is never touched.
 * <p>
 * Every shot describes a camera position and a point to look at. When the shot changes, both are cross-faded
 * with an easing curve, then smoothed, then the collision check pulls the camera in front of blocks.
 */
public final class CameraDirector {
	public enum ShotType {
		/** Slow orbit around the player. */
		ORBIT,
		/** Wide, slow sweep that looks past the player into the surroundings. */
		WIDE_PAN,
		/** Low camera near the ground looking up at the player. */
		LOW_ANGLE,
		/** Starts close and slowly pulls back and up. */
		ZOOM_OUT
	}

	private static final double CROSSFADE_SECONDS = 2.5;
	private static final double CALM_SHOT_SECONDS = 18;

	private static final class Shot {
		final ShotType type;
		final double startAngle;
		final int direction;
		final double duration;
		/** Real time in the shot (seconds). */
		double age;
		/** Movement progress: time scaled by speed and music energy. */
		double phase;

		Shot(ShotType type, double startAngle, int direction, double duration) {
			this.type = type;
			this.startAngle = startAngle;
			this.direction = direction;
			this.duration = duration;
		}
	}

	private final Random random = new Random();
	private Shot current, previous;
	private double crossfade = 1;
	private Vec3 smoothPos, smoothLook;
	private double collisionDistance = -1;
	private float punch;
	private long seenBeats = -1, seenDrops = -1;
	private long lastNanos;

	/** Starts with an orbit right behind the player, so the first frames look like normal third person. */
	public void reset(float playerYaw) {
		current = new Shot(ShotType.ORBIT, playerYaw + 180, random.nextBoolean() ? 1 : -1, CALM_SHOT_SECONDS);
		previous = null;
		crossfade = 1;
		smoothPos = null;
		smoothLook = null;
		collisionDistance = -1;
		punch = 0;
		seenBeats = -1;
		seenDrops = -1;
		lastNanos = 0;
	}

	/** Beat punch, 1 right on a beat and decaying quickly to 0. Also drives the lyric pulse. */
	public float punch() {
		return punch;
	}

	/** FOV factor for the beat punch (a short zoom-in on every beat). */
	public float fovMultiplier() {
		return 1f - 0.07f * CinematicConfig.get().beatStrength * punch;
	}

	/** Advances all animation to now and returns where the camera should be. */
	public CameraPose update(Level level, Entity entity, float partialTick, BeatDetector beats) {
		CinematicConfig cfg = CinematicConfig.get();
		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
		lastNanos = now;

		boolean music = beats != null && beats.musicPlaying();
		reactToMusic(beats, music, dt, cfg);

		// Speed: calm without music; with music it speeds up in loud parts.
		double speed = cfg.orbitSpeed * (music ? 1 + 0.6 * beats.energy() * cfg.beatStrength : 0.8);
		advance(current, dt, speed);
		if (previous != null) advance(previous, dt, speed);

		Vec3 feet = entity.getPosition(partialTick);
		double focusHeight = entity.getBbHeight() * 0.6;
		Vec3 focus = feet.add(0, focusHeight, 0);

		// Shot change: after the planned length (a few bars), or at once on a drop.
		if (current.age >= current.duration) {
			nextShot(focus, music, beats, cfg);
		}

		Vec3[] cur = compute(current, focus, focusHeight, cfg.cameraDistance);
		Vec3 pos = cur[0], look = cur[1];
		if (previous != null) {
			crossfade += dt / CROSSFADE_SECONDS;
			if (crossfade >= 1) {
				previous = null;
			} else {
				Vec3[] prev = compute(previous, focus, focusHeight, cfg.cameraDistance);
				double w = Easing.inOutCubic(crossfade);
				pos = prev[0].lerp(pos, w);
				look = prev[1].lerp(look, w);
			}
		}

		// Extra smoothing on top, so player movement or a shot change never jerks the camera.
		if (smoothPos == null) {
			smoothPos = pos;
			smoothLook = look;
		} else {
			smoothPos = smoothPos.lerp(pos, Easing.smoothing(dt, 0.35));
			smoothLook = smoothLook.lerp(look, Easing.smoothing(dt, 0.25));
		}

		Vec3 camera = avoidBlocks(level, entity, focus, smoothPos, dt);
		// Beat "punch": a small push towards the subject.
		if (punch > 0.001f) {
			Vec3 toLook = smoothLook.subtract(camera);
			double len = toLook.length();
			if (len > 1.5) camera = camera.add(toLook.scale(0.2 * cfg.beatStrength * punch / len));
		}
		return CameraPose.lookAt(camera, smoothLook);
	}

	private void reactToMusic(BeatDetector beats, boolean music, double dt, CinematicConfig cfg) {
		punch *= (float) Math.exp(-dt / 0.15);
		if (beats == null) return;
		long b = beats.beatCount(), d = beats.dropCount();
		if (seenBeats >= 0 && b != seenBeats && music && cfg.beatStrength > 0) punch = 1f;
		if (seenDrops >= 0 && d != seenDrops && music && current.age > 3) {
			// Drop: switch the shot now by ending the current one.
			current.age = current.duration;
		}
		seenBeats = b;
		seenDrops = d;
	}

	private static void advance(Shot s, double dt, double speed) {
		s.age += dt;
		s.phase += dt * speed;
	}

	private void nextShot(Vec3 focus, boolean music, BeatDetector beats, CinematicConfig cfg) {
		ShotType type;
		double duration;
		if (music) {
			ShotType[] all = ShotType.values();
			do {
				type = all[random.nextInt(all.length)];
			} while (type == current.type);
			double beatSeconds = 60.0 / Math.max(60, beats.bpm());
			duration = Math.max(5, Math.min(40, cfg.barsPerShot * 4 * beatSeconds));
		} else {
			// No music: a calm orbit, nothing else.
			type = ShotType.ORBIT;
			duration = CALM_SHOT_SECONDS;
		}
		// Continue from where the camera is now, mostly in the same direction.
		double angle = current.startAngle;
		if (smoothPos != null) {
			Vec3 off = smoothPos.subtract(focus);
			angle = Math.toDegrees(Math.atan2(-off.x, off.z));
		}
		int direction = random.nextDouble() < 0.7 ? current.direction : -current.direction;
		previous = current;
		current = new Shot(type, angle, direction, duration);
		crossfade = 0;
	}

	/** Horizontal unit vector for an angle in degrees (same convention as Minecraft's yaw). */
	private static Vec3 horizontal(double angleDeg) {
		double r = Math.toRadians(angleDeg);
		return new Vec3(-Math.sin(r), 0, Math.cos(r));
	}

	/** Returns {camera position, look-at point} of a shot. */
	private static Vec3[] compute(Shot s, Vec3 focus, double focusHeight, double distance) {
		switch (s.type) {
			case WIDE_PAN -> {
				double angle = s.startAngle + s.direction * s.phase * 3;
				Vec3 out = horizontal(angle);
				Vec3 pos = focus.add(out.scale(distance * 2)).add(0, 2.5 + distance * 0.25, 0);
				// Look ahead of the movement: the landscape fills the frame, the player stays at the side.
				Vec3 tangent = horizontal(angle + 90).scale(s.direction);
				return new Vec3[]{pos, focus.add(tangent.scale(distance * 0.5))};
			}
			case LOW_ANGLE -> {
				double angle = s.startAngle + s.direction * s.phase * 4;
				double d = Math.max(2.2, distance * 0.6);
				Vec3 pos = focus.add(horizontal(angle).scale(d)).add(0, -focusHeight + 0.3, 0);
				return new Vec3[]{pos, focus.add(0, 0.9, 0)};
			}
			case ZOOM_OUT -> {
				double e = Easing.inOutSine(s.age / s.duration);
				double angle = s.startAngle + s.direction * s.phase * 1.5;
				double d = Easing.lerp(1.8, distance * 2.2, e);
				Vec3 pos = focus.add(horizontal(angle).scale(d)).add(0, Easing.lerp(0.2, 2.2, e), 0);
				return new Vec3[]{pos, focus.add(0, 0.2 * (1 - e), 0)};
			}
			default -> {
				double angle = s.startAngle + s.direction * s.phase * 6;
				double height = 1.0 + 0.5 * Math.sin(s.phase * 0.25);
				Vec3 pos = focus.add(horizontal(angle).scale(distance)).add(0, height, 0);
				return new Vec3[]{pos, focus};
			}
		}
	}

	/**
	 * Keeps the camera out of blocks: rays from the player to the camera (like vanilla third person, from
	 * eight slightly shifted points) shorten the distance. Pulling in is instant, moving back out is eased.
	 */
	private Vec3 avoidBlocks(Level level, Entity entity, Vec3 focus, Vec3 desired, double dt) {
		Vec3 offset = desired.subtract(focus);
		double length = offset.length();
		if (length < 1e-3) return desired;
		Vec3 dir = offset.scale(1 / length);
		double allowed = length;
		for (int i = 0; i < 8; i++) {
			double ox = ((i & 1) * 2 - 1) * 0.1, oy = ((i >> 1 & 1) * 2 - 1) * 0.1, oz = ((i >> 2 & 1) * 2 - 1) * 0.1;
			Vec3 from = focus.add(ox, oy, oz);
			Vec3 to = desired.add(ox, oy, oz);
			BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
			if (hit.getType() != HitResult.Type.MISS) {
				allowed = Math.min(allowed, hit.getLocation().distanceTo(from) - 0.2);
			}
		}
		allowed = Math.max(0.3, allowed);
		if (collisionDistance < 0 || allowed < collisionDistance) {
			collisionDistance = allowed;
		} else {
			collisionDistance += (allowed - collisionDistance) * Easing.smoothing(dt, 0.6);
		}
		return focus.add(dir.scale(Math.min(collisionDistance, length)));
	}
}
