package dev.spotifyglass.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-rendered, seamlessly looping animation of silky glowing waves (many thin strands per wave,
 * like a sound visualizer) in the theme's colors. Frames are baked once per theme on a
 * background thread at high resolution, then just blitted.
 */
public final class WaveFrames {
	public static final int W = 144, H = 22, FRAMES = 60, FPS = 20;
	private static final int S = 4; // texture pixels per GUI pixel
	/** Baked themes, oldest first; only the last few are kept (custom colors create many). */
	private static final Map<String, Identifier[]> READY = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
	private static final int MAX_THEMES = 3;
	private static final Map<String, Boolean> PENDING = new ConcurrentHashMap<>();

	private WaveFrames() {}

	/** The frames for this theme, or null while they are still being rendered. */
	public static Identifier[] get(Theme t) {
		String key = t.name();
		Identifier[] frames = READY.get(key);
		if (frames != null || PENDING.putIfAbsent(key, true) != null) return frames;
		Thread th = new Thread(() -> bake(key, t), "liquidplayer-wave");
		th.setDaemon(true);
		th.start();
		return null;
	}

	private static void bake(String key, Theme t) {
		int[] colors = {t.accentTop() & 0xFFFFFF, t.accentBottom() & 0xFFFFFF, t.secondTop() & 0xFFFFFF};
		int[] cycles = {1, -2, 3};          // whole cycles per loop → seamless
		float[] freq = {1.4f, 2.1f, 2.9f};
		float[] amp = {7.5f, 6f, 4.5f};
		int pw = W * S, ph = H * S;
		float cy = H / 2f;
		NativeImage[] images = new NativeImage[FRAMES];
		float[] r = new float[pw * ph], g = new float[pw * ph], b = new float[pw * ph], a = new float[pw * ph];

		for (int f = 0; f < FRAMES; f++) {
			java.util.Arrays.fill(r, 0);
			java.util.Arrays.fill(g, 0);
			java.util.Arrays.fill(b, 0);
			java.util.Arrays.fill(a, 0);
			double time = f / (double) FRAMES;
			for (int k = 0; k < 3; k++) {
				float ampK = amp[k] * (0.7f + 0.3f * (float) Math.sin(2 * Math.PI * time + k * 2.1));
				double phase = 2 * Math.PI * cycles[k] * time + k * 1.3;
				for (int strand = 0; strand < 5; strand++) {
					double off = strand * 0.32;
					float scale = 1f - strand * 0.12f;
					for (int px = 0; px < pw; px++) {
						float u = (px + 0.5f) / pw;
						float env = (float) Math.sin(Math.PI * u);
						env = env * env * (3 - 2 * env); // smoother taper at the ends
						float yc = cy + ampK * scale * env * (float) Math.sin(2 * Math.PI * u * freq[k] + phase + off);
						int lo = Math.max(0, (int) ((yc - 3) * S)), hi = Math.min(ph - 1, (int) ((yc + 3) * S));
						for (int py = lo; py <= hi; py++) {
							float d = Math.abs((py + 0.5f) / S - yc);
							float core = (float) Math.exp(-(d / 0.28f) * (d / 0.28f));
							float glow = (float) Math.exp(-(d / 1.5f) * (d / 1.5f)) * 0.22f;
							float in = (core * 0.55f + glow) * (0.06f + 0.94f * env) / (1 + strand * 0.35f);
							int i = py * pw + px;
							int c = colors[k];
							r[i] += ((c >> 16) & 0xFF) * in;
							g[i] += ((c >> 8) & 0xFF) * in;
							b[i] += (c & 0xFF) * in;
							a[i] += in;
						}
					}
				}
			}
			NativeImage img = new NativeImage(pw, ph, false);
			for (int i = 0; i < pw * ph; i++) {
				float al = Math.min(1f, a[i]);
				if (al <= 0.002f) {
					img.setPixel(i % pw, i / pw, 0);
					continue;
				}
				// Where strands overlap the light gets whiter, like the reference.
				float boost = Math.min(1f, Math.max(0, a[i] - 1f) * 0.5f);
				int cr = Math.min(255, Math.round(r[i] / a[i] + (255 - r[i] / a[i]) * boost));
				int cg = Math.min(255, Math.round(g[i] / a[i] + (255 - g[i] / a[i]) * boost));
				int cb = Math.min(255, Math.round(b[i] / a[i] + (255 - b[i] / a[i]) * boost));
				img.setPixel(i % pw, i / pw, (Math.round(al * 255) << 24) | (cr << 16) | (cg << 8) | cb);
			}
			images[f] = img;
		}

		Minecraft.getInstance().execute(() -> {
			Identifier[] ids = new Identifier[FRAMES];
			for (int f = 0; f < FRAMES; f++) {
				ids[f] = Identifier.fromNamespaceAndPath("spotifyglass", "wave/" + Integer.toHexString(key.hashCode()) + "/" + f);
				Minecraft.getInstance().getTextureManager().register(ids[f], new DynamicTexture(ids[f]::toString, images[f]));
			}
			READY.put(key, ids);
			synchronized (READY) {
				while (READY.size() > MAX_THEMES) {
					String oldest = READY.keySet().iterator().next();
					for (Identifier id : READY.remove(oldest)) Minecraft.getInstance().getTextureManager().release(id);
					PENDING.remove(oldest);
				}
			}
		});
	}
}
