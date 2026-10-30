package dev.afkcinematic.cinematic;

/** Easing curves (input and output 0..1) and small interpolation helpers. */
public final class Easing {
	private Easing() {}

	public static double clamp01(double t) {
		return t < 0 ? 0 : t > 1 ? 1 : t;
	}

	public static double inOutCubic(double t) {
		t = clamp01(t);
		return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
	}

	public static double inOutSine(double t) {
		return -(Math.cos(Math.PI * clamp01(t)) - 1) / 2;
	}

	public static double outCubic(double t) {
		return 1 - Math.pow(1 - clamp01(t), 3);
	}

	public static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	/** Interpolates angles in degrees along the shorter way round. */
	public static float lerpAngle(float a, float b, double t) {
		float diff = wrapDegrees(b - a);
		return (float) (a + diff * t);
	}

	public static float wrapDegrees(float deg) {
		float d = deg % 360f;
		if (d >= 180f) d -= 360f;
		if (d < -180f) d += 360f;
		return d;
	}

	/** Frame-rate independent exponential smoothing factor for a time constant {@code tau} (seconds). */
	public static double smoothing(double dt, double tau) {
		return 1 - Math.exp(-dt / tau);
	}
}
