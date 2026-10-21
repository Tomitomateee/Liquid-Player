package dev.spotifyglass.gui;

import dev.spotifyglass.CoverCache;
import dev.spotifyglass.SpotifyClient;
import dev.spotifyglass.SpotifyClient.PlayerState;
import dev.spotifyglass.SpotifyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The song display on the in-game HUD, in one of three styles: a glass card, a spinning vinyl
 * record (cover in the middle) or an animated wave. Vinyl and wave have no box, just the title
 * underneath. The whole display can be scaled in the settings.
 */
public final class SongHud {
	public static final int STYLE_CARD = 0, STYLE_VINYL = 1, STYLE_WAVE = 2;
	public static final float MIN_SCALE = 0.5f, MAX_SCALE = 2f;
	private static final int W = 160;
	/** Record and cover sizes; both even so the cover sits exactly in the middle. */
	private static final int VINYL = 64, LABEL = 30;
	/** Vinyl style is wider than the record so the title underneath has room. */
	private static final int VINYL_BOX = 130, TITLE_GAP = 19;
	private static final int WAVE_COVER = 12;

	private static float vinylAngle;
	private static float waveTime;
	/** Smoothed, auto-gained music level driving the wave (0..1). */
	private static float waveLevel;
	/** Slowly decaying loudest recent peak, so quiet and loud Spotify volumes both fill the wave. */
	private static float peakMax = 0.1f;
	private static long lastFrame;

	private SongHud() {}

	public static float scale(SpotifyConfig cfg) {
		return Math.max(MIN_SCALE, Math.min(MAX_SCALE, cfg.hudScale));
	}

	private static int baseHeight(SpotifyConfig cfg) {
		return baseHeight(cfg.hudStyle);
	}

	public static int baseHeight(int style) {
		return switch (style) {
			case STYLE_VINYL -> VINYL + TITLE_GAP;
			case STYLE_WAVE -> WaveFrames.H + WAVE_COVER + 9;
			default -> 38;
		};
	}

	private static int baseWidth(SpotifyConfig cfg) {
		return baseWidth(cfg.hudStyle);
	}

	public static int baseWidth(int style) {
		return switch (style) {
			case STYLE_VINYL -> VINYL_BOX;
			case STYLE_WAVE -> WaveFrames.W;
			default -> W;
		};
	}

	/** On-screen size including the user's scale. */
	public static int width(SpotifyConfig cfg) {
		return Math.round(baseWidth(cfg) * scale(cfg));
	}

	public static int height(SpotifyConfig cfg) {
		return Math.round(baseHeight(cfg) * scale(cfg));
	}

	public static int x(SpotifyConfig cfg, int screenW) {
		return Math.round(clamp(cfg.hudX) * Math.max(0, screenW - width(cfg)));
	}

	public static int y(SpotifyConfig cfg, int screenH) {
		return Math.round(clamp(cfg.hudY) * Math.max(0, screenH - height(cfg)));
	}

	/** HUD callback: draws the display unless it is disabled, the HUD is hidden or our own screen is open. */
	public static void renderHud(GuiGraphics g, SpotifyClient spotify) {
		Minecraft mc = Minecraft.getInstance();
		SpotifyConfig cfg = spotify.config;
		if (!cfg.hudEnabled || mc.options.hideGui || mc.screen instanceof SpotifyScreen) return;
		PlayerState s = spotify.state();
		if (s.uri() == null) return;
		render(g, spotify, x(cfg, g.guiWidth()), y(cfg, g.guiHeight()));
	}

	public static void render(GuiGraphics g, SpotifyClient spotify, int x, int y) {
		render(g, spotify, x, y, spotify.config.hudStyle, scale(spotify.config));
	}

	/** Draws a given style at a given scale (also used for the theme preview in the settings). */
	public static void render(GuiGraphics g, SpotifyClient spotify, int x, int y, int style, float scale) {
		long now = System.currentTimeMillis();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;

		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		renderUnscaled(g, spotify, style, dt);
		g.pose().popMatrix();
	}

	/** Draws at (0, 0) in unscaled units; {@link #render} applies position and size. */
	private static void renderUnscaled(GuiGraphics g, SpotifyClient spotify, int style, float dt) {
		Font font = Minecraft.getInstance().font;
		SpotifyConfig cfg = spotify.config;
		Theme t = Theme.current(cfg);
		PlayerState s = spotify.state();
		switch (style) {
			case STYLE_VINYL -> {
				// A calm spin (~15 rpm) while playing; it simply stops when paused.
				if (s.playing()) vinylAngle = (vinylAngle + dt * 1.6f) % (float) (Math.PI * 2);
				drawVinyl(g, s, t, (VINYL_BOX - VINYL) / 2, 0);
				String title = clip(font, s.title(), VINYL_BOX - 16);
				int pw = font.width(title) + 16;
				glassPill(g, cfg, t, VINYL_BOX / 2 - pw / 2, VINYL + 3, pw, 15);
				g.drawString(font, title, VINYL_BOX / 2 - font.width(title) / 2, VINYL + 7, 0xFFFFFFFF, true);
			}
			case STYLE_WAVE -> {
				drawWave(g, t, s.playing(), spotify.audioPeak(), 0, 0, dt);
				// Cover and title side by side, centered under the wave.
				int ty = WaveFrames.H + 5;
				String title = clip(font, s.title(), WaveFrames.W - WAVE_COVER - 18);
				int groupW = WAVE_COVER + 4 + font.width(title);
				int gx = WaveFrames.W / 2 - groupW / 2;
				glassPill(g, cfg, t, gx - 4, ty - 2, groupW + 11, WAVE_COVER + 4);
				cover(g, s, t, gx, ty, WAVE_COVER, 3);
				g.drawString(font, title, gx + WAVE_COVER + 4, ty + 2, 0xFFFFFFFF, true);
			}
			default -> {
				int h = baseHeight(style);
				float alpha = Math.max(20, Math.min(100, cfg.opacity)) / 100f;
				Glass.shadow(g, 0, 0, W, h, 12, 90);
				ShapeCache.roundRectFaded(g, 0, 0, W, h, 12, Glass.argb(0xFF, t.panelTop()), Glass.argb(0xD4, t.panelBottom()),
						Math.min(1, alpha + 0.25f));
				Glass.roundRect(g, 3, 1, W - 6, 12, 11, 0x50FFFFFF, 0x00FFFFFF);
				Glass.roundOutline(g, 0, 0, W, h, 12, 0xC0FFFFFF);
				cover(g, s, t, 6, 6, 26, 6);
				int tx = 38, maxW = W - 46;
				g.drawString(font, clip(font, s.title(), maxW), tx, 7, t.text(), false);
				g.drawString(font, clip(font, s.artist(), maxW), tx, 18, t.textSoft(), false);
				float frac = s.durationMs() > 0 ? Math.min(1, s.liveProgress() / (float) s.durationMs()) : 0;
				progress(g, t, s, tx, 30, maxW - 8, frac, W - 9);
			}
		}
	}

	/** Small liquid glass pill (theme glass, sheen, white rim) behind the titles. */
	private static void glassPill(GuiGraphics g, SpotifyConfig cfg, Theme t, int x, int y, int w, int h) {
		float alpha = Math.max(20, Math.min(100, cfg.opacity)) / 100f;
		float r = h / 2f;
		Glass.shadow(g, x, y, w, h, r, 80);
		// Filled with the theme's accent gradient, so it matches the chosen color.
		ShapeCache.roundRectFaded(g, x, y, w, h, r, t.accentTop() | 0xFF000000, t.accentBottom() | 0xFF000000,
				Math.min(1, alpha + 0.3f));
		Glass.roundRect(g, x + 3, y + 1, w - 6, h / 2, r - 1, 0x50FFFFFF, 0x00FFFFFF);
		Glass.roundOutline(g, x, y, w, h, r, 0xC0FFFFFF);
	}

	private static void cover(GuiGraphics g, PlayerState s, Theme t, int x, int y, int size, float radius) {
		Identifier cover = CoverCache.get(s.imageUrl());
		if (cover != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, cover, x, y, 0, 0, size, size, size, size);
		} else {
			Glass.roundRect(g, x, y, size, size, radius, t.accentTop(), t.accentBottom());
		}
	}

	private static void progress(GuiGraphics g, Theme t, PlayerState s, int x, int y, int w, float frac, int iconX) {
		g.fill(x, y, x + w, y + 2, 0x30000000);
		g.fill(x, y, x + Math.round(w * frac), y + 2, t.accentBottom());
		if (s.playing()) Glass.playIcon(g, iconX, y + 1, 5, t.accentBottom());
		else Glass.pauseIcon(g, iconX, y + 1, 5, t.textSoft());
	}

	/** Spinning record with the round album cover as its label. */
	private static void drawVinyl(GuiGraphics g, PlayerState s, Theme t, int x, int y) {
		float c = VINYL / 2f;
		Glass.shadow(g, x, y, VINYL, VINYL, c, 110);
		g.pose().pushMatrix();
		g.pose().translate(x + c, y + c);
		g.pose().rotate(vinylAngle);
		g.pose().translate(-c, -c);
		g.blit(RenderPipelines.GUI_TEXTURED, ShapeCache.vinylTexture(VINYL, t), 0, 0, 0, 0, VINYL, VINYL, VINYL, VINYL);
		String url = s.imageUrl() == null ? "" : s.imageUrl();
		Identifier label = ShapeCache.labelTexture(CoverCache.image(url), url, LABEL, t);
		int lo = (VINYL - LABEL) / 2;
		g.blit(RenderPipelines.GUI_TEXTURED, label, lo, lo, 0, 0, LABEL, LABEL, LABEL, LABEL);
		g.pose().popMatrix();
	}

	/**
	 * Silky animated waves (pre-rendered loop, see {@link WaveFrames}) that move with the music:
	 * Spotify's live audio level drives their height and speed. Freezes when paused.
	 */
	private static void drawWave(GuiGraphics g, Theme t, boolean playing, float peak, int x0, int y0, float dt) {
		Identifier[] frames = WaveFrames.get(t);
		if (frames == null) return; // still rendering in the background

		// Auto-gain: compare against the loudest recent peak, which slowly decays.
		if (peak > peakMax) peakMax = peak;
		peakMax = Math.max(0.05f, peakMax - dt * 0.03f);
		float target = !playing ? 0.12f : peak < 0 ? 0.6f : Math.min(1f, peak / peakMax);
		// Fast attack on beats, softer release.
		float k = 1f - (float) Math.exp(-dt * (target > waveLevel ? 28f : 7f));
		waveLevel += (target - waveLevel) * k;

		if (playing) waveTime += dt * (0.35f + waveLevel * 1.4f);
		Identifier frame = frames[(int) (waveTime * WaveFrames.FPS) % WaveFrames.FRAMES];
		float stretch = 0.2f + waveLevel * 0.95f;
		g.pose().pushMatrix();
		g.pose().translate(x0, y0 + WaveFrames.H / 2f);
		g.pose().scale(1f, stretch);
		g.pose().translate(0, -WaveFrames.H / 2f);
		g.blit(RenderPipelines.GUI_TEXTURED, frame, 0, 0, 0, 0, WaveFrames.W, WaveFrames.H, WaveFrames.W, WaveFrames.H);
		g.pose().popMatrix();
	}
	private static float clamp(float v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}

	private static String clip(Font font, String text, int maxWidth) {
		if (text == null) return "";
		if (font.width(text) <= maxWidth) return text;
		return font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
	}
}
