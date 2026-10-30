package dev.afkcinematic.render;

import dev.afkcinematic.cinematic.CinematicController;
import dev.afkcinematic.cinematic.Easing;
import dev.afkcinematic.config.CinematicConfig;
import dev.afkcinematic.lyrics.Lyrics;
import dev.afkcinematic.media.MediaSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Optional;

/**
 * Everything drawn on screen during the cinematic, instead of the normal HUD: vignette, cinema bars,
 * song title (top left) and the synced lyrics in the lower third, music-video style.
 */
public final class CinematicOverlay {
	/** Lyrics are shown this much early: with the fade they then feel exactly on time. */
	private static final long LYRICS_LEAD_MS = 150;
	private static final float BAR_HEIGHT = 0.11f;

	private static long lastFrame;
	private static String clockSong;
	private static double clockMs;
	/** Smoothly follows the index of the current lyric line, which makes the lines glide. */
	private static double scrollPos = -1;
	private static String scrollSong;

	private CinematicOverlay() {}

	public static void render(GuiGraphics g) {
		CinematicController controller = CinematicController.get();
		CinematicConfig cfg = CinematicConfig.get();
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		int w = g.guiWidth(), h = g.guiHeight();
		float visible = controller.overlayProgress();

		long now = System.nanoTime();
		double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1e9);
		lastFrame = now;

		if (cfg.vignette) drawVignette(g, w, h, visible * 0.6f);

		int bar = cfg.letterbox ? Math.round(h * BAR_HEIGHT * visible) : 0;
		if (bar > 0) {
			g.fill(0, 0, w, bar, 0xFF000000);
			g.fill(0, h - bar, w, h, 0xFF000000);
		}

		MediaSession.Track track = controller.media().track();
		if (track == null || visible <= 0.01f) return;

		float pulse = controller.beats().musicPlaying() ? controller.director().punch() * cfg.beatStrength : 0f;

		if (cfg.songInfo) drawSongInfo(g, font, track, visible);

		Optional<Lyrics> lyrics = cfg.lyrics ? controller.lyrics().get(track) : Optional.empty();
		long position = songClock(track, dt) + LYRICS_LEAD_MS;
		int baseY = Math.min(Math.round(h * 0.76f), h - Math.round(h * BAR_HEIGHT) - Math.round(14 * cfg.fontScale));
		if (lyrics != null && lyrics.isPresent()) {
			drawLyrics(g, font, lyrics.get(), track, position, w, baseY, visible, pulse, dt, cfg);
		} else if (lyrics != null) {
			// No synced lyrics for this song: a title card instead.
			drawTitleCard(g, font, track, w, baseY, visible, pulse, cfg);
		}
	}

	/**
	 * Song position for the lyrics. Follows the reported position but runs smoothly between updates and
	 * never jumps back by a few milliseconds (that would make lines flicker).
	 */
	private static long songClock(MediaSession.Track track, double dt) {
		double reported = track.positionNow();
		if (!track.key().equals(clockSong) || Math.abs(reported - clockMs) > 1200) {
			clockSong = track.key();
			clockMs = reported;
		} else {
			if (track.playing()) clockMs += dt * 1000;
			clockMs += (reported - clockMs) * Easing.smoothing(dt, 0.5);
		}
		return Math.round(clockMs);
	}

	private static void drawLyrics(GuiGraphics g, Font font, Lyrics lyrics, MediaSession.Track track, long position,
	                               int w, int baseY, float visible, float pulse, double dt, CinematicConfig cfg) {
		int index = lyrics.indexAt(position);
		if (!track.key().equals(scrollSong) || Math.abs(index - scrollPos) > 3) {
			scrollSong = track.key();
			scrollPos = index;
		} else {
			scrollPos += (index - scrollPos) * Easing.smoothing(dt, 0.12);
		}

		float scale = cfg.fontScale;
		float spacing = font.lineHeight * scale * 1.45f;
		int from = (int) Math.floor(scrollPos) - 2, to = (int) Math.ceil(scrollPos) + 2;
		for (int i = Math.max(0, from); i <= Math.min(lyrics.lines().size() - 1, to); i++) {
			String text = lyrics.lines().get(i).text();
			if (text.isEmpty()) continue;
			double offset = i - scrollPos;
			double dist = Math.abs(offset);
			if (dist >= 2) continue;
			// Current line: full size and opacity; previous/next: smaller and transparent; beyond: fading out.
			double alpha = dist <= 1 ? Easing.lerp(1, 0.4, dist) : Easing.lerp(0.4, 0, dist - 1);
			double lineScale = scale * Easing.lerp(1, 0.6, Math.min(1, dist));
			if (dist < 0.5) lineScale *= 1 + 0.06 * pulse * (1 - dist * 2);
			float y = (float) (baseY + offset * spacing);
			int gray = (int) Easing.lerp(255, 200, Math.min(1, dist));
			drawCentered(g, font, text, w / 2f, y, (float) lineScale, (float) (alpha * visible), gray << 16 | gray << 8 | gray, w * 0.86f);
		}
	}

	private static void drawTitleCard(GuiGraphics g, Font font, MediaSession.Track track, int w, int baseY,
	                                  float visible, float pulse, CinematicConfig cfg) {
		float scale = cfg.fontScale * 0.9f * (1 + 0.05f * pulse);
		drawCentered(g, font, track.title(), w / 2f, baseY, scale, visible, 0xFFFFFF, w * 0.86f);
		if (!track.artist().isEmpty()) {
			drawCentered(g, font, track.artist(), w / 2f, baseY + font.lineHeight * scale * 1.1f + 2,
					cfg.fontScale * 0.55f, visible * 0.7f, 0xD0D0D0, w * 0.86f);
		}
	}

	private static void drawSongInfo(GuiGraphics g, Font font, MediaSession.Track track, float visible) {
		int alpha = Math.round(255 * visible);
		if (alpha < 8) return;
		int x = 10, y = 8;
		g.drawString(font, clip(font, track.title(), 220), x, y, alpha << 24 | 0xFFFFFF, true);
		if (!track.artist().isEmpty()) {
			g.pose().pushMatrix();
			g.pose().translate(x, y + font.lineHeight + 2);
			g.pose().scale(0.85f, 0.85f);
			g.drawString(font, clip(font, track.artist(), 258), 0, 0, Math.round(alpha * 0.75f) << 24 | 0xC8C8C8, true);
			g.pose().popMatrix();
		}
	}

	/** Draws {@code text} centred at (cx, cy), scaled, shrunk further if it would be wider than {@code maxWidth}. */
	private static void drawCentered(GuiGraphics g, Font font, String text, float cx, float cy, float scale,
	                                 float alpha, int rgb, float maxWidth) {
		int a = Math.round(255 * Math.max(0, Math.min(1, alpha)));
		if (a < 8) return; // nearly invisible text is skipped (very low alpha also renders oddly)
		int width = font.width(text);
		if (width * scale > maxWidth) scale = maxWidth / width;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(scale, scale);
		g.drawString(font, text, -width / 2, -font.lineHeight / 2, a << 24 | rgb, true);
		g.pose().popMatrix();
	}

	private static String clip(Font font, String s, int maxWidth) {
		if (font.width(s) <= maxWidth) return s;
		return font.plainSubstrByWidth(s, maxWidth - font.width("…")) + "…";
	}

	/** Darkened edges built from gradient strips (top/bottom) and stepped columns (left/right). */
	private static void drawVignette(GuiGraphics g, int w, int h, float strength) {
		int maxAlpha = Math.round(255 * strength);
		if (maxAlpha < 4) return;
		int edgeY = h / 4;
		g.fillGradient(0, 0, w, edgeY, maxAlpha << 24, 0);
		g.fillGradient(0, h - edgeY, w, h, 0, maxAlpha << 24);
		int edgeX = w / 5, steps = 16;
		for (int i = 0; i < steps; i++) {
			int x0 = edgeX * i / steps, x1 = edgeX * (i + 1) / steps;
			double t = 1 - (i + 0.5) / steps;
			int a = (int) (maxAlpha * t * t);
			if (a <= 0) continue;
			g.fill(x0, 0, x1, h, a << 24);
			g.fill(w - x1, 0, w - x0, h, a << 24);
		}
	}
}
