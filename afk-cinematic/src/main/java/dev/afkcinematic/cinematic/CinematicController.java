package dev.afkcinematic.cinematic;

import dev.afkcinematic.audio.BeatDetector;
import dev.afkcinematic.audio.LoopbackAudio;
import dev.afkcinematic.config.CinematicConfig;
import dev.afkcinematic.lyrics.LyricsService;
import dev.afkcinematic.media.MediaSession;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The heart of the mod: AFK detection and the cinematic's state machine
 * ({@code IDLE → ACTIVE → EXITING → IDLE}).
 * <p>
 * Input arrives from the keyboard/mouse mixins, everything else happens in the client tick (game state,
 * starting/stopping) and in the camera mixin (the per-frame pose). The real player rotation is never changed;
 * only the {@link net.minecraft.client.Camera} is moved, and the perspective is switched to third person so
 * the player model is drawn.
 */
public final class CinematicController {
	public enum State { IDLE, ACTIVE, EXITING }

	private static final double ENTER_SECONDS = 1.5;
	private static final double EXIT_SECONDS = 0.5;
	/** Inputs right after a manual start are ignored (e.g. letting go of the mouse). */
	private static final long START_GRACE_NANOS = 300_000_000L;
	/** The helper processes are started this long before the AFK time is reached, so they're ready in time. */
	private static final long BRIDGE_LEAD_NANOS = 15_000_000_000L;
	/** ...and kept alive this long after they were last needed. */
	private static final long BRIDGE_LINGER_NANOS = 60_000_000_000L;
	/** A slightly narrower field of view looks more like a camera lens. */
	private static final float CINEMATIC_FOV = 0.9f;

	private static final CinematicController INSTANCE = new CinematicController();

	private final CameraDirector director = new CameraDirector();
	private final LoopbackAudio audio = new LoopbackAudio();
	private final MediaSession media = new MediaSession();
	private final LyricsService lyrics = new LyricsService();

	private State state = State.IDLE;
	private long stateSince = System.nanoTime();
	private long lastInput = System.nanoTime();
	private CameraType savedCameraType = CameraType.FIRST_PERSON;
	/** Blend values at the moment the exit started, so an early exit doesn't jump. */
	private double cameraBlendAtExit = 1, overlayAtExit = 1;
	private boolean pendingKeyInput, toggleWasDown;
	private double lastMouseX = Double.NaN, lastMouseY = Double.NaN;
	private long bridgesNeededUntil;
	private boolean bridgesRunning;

	public static CinematicController get() {
		return INSTANCE;
	}

	public State state() {
		return state;
	}

	public CameraDirector director() {
		return director;
	}

	public MediaSession media() {
		return media;
	}

	public LyricsService lyrics() {
		return lyrics;
	}

	public BeatDetector beats() {
		return audio.detector();
	}

	// ------------------------------------------------------------------ input (called by the mixins)

	/**
	 * Any key event. Evaluated in the next tick, because the toggle key itself must not end the cinematic
	 * it is about to start.
	 */
	public void onKeyInput() {
		pendingKeyInput = true;
	}

	public void onMouseButtonOrScroll() {
		onInput();
	}

	/** Mouse moved; a tiny threshold ignores sensor jitter. */
	public void onMouseMoved(double x, double y) {
		if (!Double.isNaN(lastMouseX) && Math.abs(x - lastMouseX) + Math.abs(y - lastMouseY) > 1.5) {
			onInput();
		}
		lastMouseX = x;
		lastMouseY = y;
	}

	private void onInput() {
		long now = System.nanoTime();
		lastInput = now;
		if (state == State.ACTIVE && now - stateSince > START_GRACE_NANOS) stop();
	}

	// ------------------------------------------------------------------ client tick

	public void tick(Minecraft mc, KeyMapping toggleKey) {
		long now = System.nanoTime();
		CinematicConfig cfg = CinematicConfig.get();

		boolean toggled = false;
		while (toggleKey.consumeClick()) toggled = true;
		boolean toggleDown = toggleKey.isDown();
		if (pendingKeyInput) {
			pendingKeyInput = false;
			// Pressing/releasing the toggle key is not "activity", the key handles that itself.
			if (!toggled && !toggleDown && !toggleWasDown) onInput();
		}
		toggleWasDown = toggleDown;

		if (mc.level == null || mc.player == null) {
			if (state != State.IDLE) finish(mc);
			lastInput = now;
			manageBridges(now, false);
			return;
		}

		// Never while a menu, chat or inventory is open, or while dead.
		boolean blocked = mc.screen != null || mc.player.isDeadOrDying();
		if (blocked) {
			lastInput = now;
			if (state == State.ACTIVE) stop();
		}

		if (toggled && !blocked) {
			if (state == State.ACTIVE) stop();
			else start(mc);
		}

		double idleSeconds = (now - lastInput) / 1e9;
		if (state == State.IDLE && cfg.autoStart && !blocked && idleSeconds >= cfg.afkSeconds) start(mc);
		if (state == State.EXITING && exitDone()) finish(mc);

		boolean soon = cfg.autoStart && now - lastInput >= cfg.afkSeconds * 1_000_000_000L - BRIDGE_LEAD_NANOS;
		manageBridges(now, state != State.IDLE || soon);

		// Load the lyrics in the background as early as possible.
		MediaSession.Track track = media.track();
		if (cfg.lyrics && track != null) lyrics.get(track);
	}

	private void manageBridges(long now, boolean needed) {
		if (needed) {
			bridgesNeededUntil = now + BRIDGE_LINGER_NANOS;
			audio.ensureRunning();
			media.ensureRunning();
			bridgesRunning = true;
		} else if (bridgesRunning && now > bridgesNeededUntil) {
			audio.stop();
			media.stop();
			bridgesRunning = false;
		}
	}

	public void shutdown() {
		audio.stop();
		media.stop();
	}

	// ------------------------------------------------------------------ state changes

	private void start(Minecraft mc) {
		if (state == State.IDLE) {
			savedCameraType = mc.options.getCameraType();
			director.reset(mc.player.getYRot());
		}
		mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		state = State.ACTIVE;
		stateSince = System.nanoTime();
	}

	/** Begins the soft return (≈0.5 s) to the perspective the player had before. */
	public void stop() {
		if (state != State.ACTIVE) return;
		cameraBlendAtExit = enterBlend();
		overlayAtExit = overlayProgress();
		state = State.EXITING;
		stateSince = System.nanoTime();
		// Third person back/front: let vanilla compute that view and blend into it.
		// First person: stay in third person (so the body is drawn) until the camera has arrived at the eyes.
		if (!savedCameraType.isFirstPerson()) Minecraft.getInstance().options.setCameraType(savedCameraType);
	}

	private void finish(Minecraft mc) {
		mc.options.setCameraType(savedCameraType);
		state = State.IDLE;
		stateSince = System.nanoTime();
	}

	private double secondsInState() {
		return (System.nanoTime() - stateSince) / 1e9;
	}

	private boolean exitDone() {
		// The last few percent of the ease are invisible; switching then avoids a frame inside the head.
		return secondsInState() >= EXIT_SECONDS * 0.92;
	}

	private double enterBlend() {
		return Easing.inOutCubic(secondsInState() / ENTER_SECONDS);
	}

	/** How far the cinematic camera has taken over from the normal view (0..1). */
	private double cameraBlend() {
		return switch (state) {
			case ACTIVE -> enterBlend();
			case EXITING -> cameraBlendAtExit * (1 - Easing.inOutCubic(secondsInState() / EXIT_SECONDS));
			case IDLE -> 0;
		};
	}

	// ------------------------------------------------------------------ rendering hooks

	public boolean controlsCamera() {
		return state != State.IDLE;
	}

	/** True while the vanilla HUD should be hidden (whole cinematic, including the way out). */
	public boolean hidesHud() {
		return state != State.IDLE;
	}

	/** Letterbox / lyrics visibility: slides in over 1 s, out with the exit. */
	public float overlayProgress() {
		return (float) switch (state) {
			case ACTIVE -> Easing.outCubic(secondsInState() / 1.0);
			case EXITING -> overlayAtExit * (1 - Easing.inOutCubic(secondsInState() / EXIT_SECONDS));
			case IDLE -> 0;
		};
	}

	/**
	 * Called by the camera mixin every frame after vanilla placed the camera.
	 *
	 * @param vanilla where vanilla put the camera this frame
	 * @return the pose to use instead
	 */
	public CameraPose cameraPose(Level level, Entity entity, float partialTick, CameraPose vanilla) {
		Minecraft mc = Minecraft.getInstance();
		if (state == State.EXITING && exitDone()) {
			finish(mc);
			return vanilla;
		}
		// The view we come from / return to. For first person that is the eyes, even though the camera
		// type is third person during the cinematic.
		CameraPose base = vanilla;
		if (savedCameraType.isFirstPerson()) {
			Vec3 eyes = entity.getEyePosition(partialTick);
			base = new CameraPose(eyes, entity.getViewYRot(partialTick), entity.getViewXRot(partialTick));
		}
		CameraPose cinematic = director.update(level, entity, partialTick, beats());
		return base.lerp(cinematic, cameraBlend());
	}

	/** FOV factor: narrower lens plus the beat punch, faded with the camera blend. */
	public float fovMultiplier() {
		if (state == State.IDLE) return 1f;
		double blend = cameraBlend();
		float target = CINEMATIC_FOV * director.fovMultiplier();
		return (float) Easing.lerp(1, target, blend);
	}
}
