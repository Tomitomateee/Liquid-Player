package dev.spotifyglass.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Seasonal looks for the GUI (settings drawer → "Saison"): their own colors plus decorations drawn
 * on the panels — pumpkins and bats for Halloween, a garland and fairy lights for Christmas, snow
 * for winter, eggs for Easter. Everything is drawn from simple shapes and animated by the clock,
 * so nothing needs to be stored.
 */
public final class Season {
	public static final int OFF = 0, HALLOWEEN = 1, CHRISTMAS = 2, WINTER = 3, EASTER = 4;
	public static final String[] NAMES = {"Aus", "Halloween", "Weihnachten", "Winter", "Ostern"};

	private static final Theme HALLOWEEN_THEME = new Theme("Halloween", 0xFFFFA43A, 0xFFE8590C, 0xFFB98CFF, 0xFF6A2FB8,
			0xFFF1E4, 0xF7DCC2, 0xFF8C1A, 0x8A3FD1, 0xFF2A1405, 0xFF7A5A40, 0xFF9A7A60, 0xF8FFFFFF, 0xE6FBEADB);
	private static final Theme CHRISTMAS_THEME = new Theme("Weihnachten", 0xFFFF6B6B, 0xFFC4161C, 0xFF7ED99A, 0xFF1E8C45,
			0xFFF7F4, 0xF5E4E0, 0xE53935, 0x2E9E55, 0xFF2A0C0C, 0xFF7A5050, 0xFF9A7474, 0xF8FFFFFF, 0xE6F9EDEA);
	private static final Theme WINTER_THEME = new Theme("Winter", 0xFFA8DCFF, 0xFF4A90D9, 0xFFE3F5FF, 0xFF9CC9EE,
			0xF5FAFF, 0xE3EFF9, 0xA8DCFF, 0xFFFFFF, 0xFF10243A, 0xFF587088, 0xFF7C92A8, 0xF8FFFFFF, 0xE6EEF5FB);
	private static final Theme EASTER_THEME = new Theme("Ostern", 0xFFFFE07A, 0xFFF0A93B, 0xFFB8F0B0, 0xFF5FC46B,
			0xFFFDF4, 0xF7F0E0, 0xFFB8D8, 0xB8F0B0, 0xFF2B2410, 0xFF7A6E4A, 0xFF9A8E6C, 0xF8FFFFFF, 0xE6FBF6E6);

	private Season() {}

	/** The season's colors; Halloween is always dark. Null when off. */
	public static Theme theme(int season, boolean black) {
		return switch (season) {
			case HALLOWEEN -> HALLOWEEN_THEME.black();
			case CHRISTMAS -> black ? CHRISTMAS_THEME.black() : CHRISTMAS_THEME;
			case WINTER -> black ? WINTER_THEME.black() : WINTER_THEME;
			case EASTER -> black ? EASTER_THEME.black() : EASTER_THEME;
			default -> null;
		};
	}

	private static float time() {
		return (System.currentTimeMillis() % 1_000_000L) / 1000f;
	}

	/** Small stable pseudo-random number 0..1 for decoration details. */
	private static float rnd(int a, int b) {
		int h = a * 374761393 + b * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f;
	}

	// ================================================================ per panel

	/** Decorations on one panel; {@code main} is the player in the middle (gets the big pieces). */
	public static void decoratePanel(GuiGraphicsExtractor g, int season, int x, int y, int w, int h, boolean main) {
		switch (season) {
			case HALLOWEEN -> {
				cobweb(g, x + 2, y + 2);
				// Sitting on the bottom corners, half outside, so they don't cover any buttons.
				if (main) {
					pumpkin(g, x - 9, y + h - 10, 22, 0);
					pumpkin(g, x + w - 9, y + h - 8, 16, 1);
				} else {
					pumpkin(g, x + w - 10, y + h - 7, 14, 2);
				}
			}
			case CHRISTMAS -> {
				if (main) garland(g, x + 8, y - 2, w - 16);
				else fairyLights(g, x + 10, y - 1, w - 20);
			}
			case WINTER -> snowCap(g, x, y, w);
			case EASTER -> {
				grass(g, x + 10, y + h - 2, w - 20);
				egg(g, x - 5, y + h - 9, 0xFFFFB8D8, 0xFFFF7FB0, 0);
				if (main) {
					egg(g, x + 5, y + h - 6, 0xFFB8E4FF, 0xFF6FB8F0, 1);
					egg(g, x + w - 6, y + h - 9, 0xFFFFE89A, 0xFFF5B83D, 2);
				}
			}
			default -> { }
		}
	}

	/** Effects over the whole screen: falling snow, flying bats. */
	public static void overlay(GuiGraphicsExtractor g, int season, int width, int height) {
		switch (season) {
			case HALLOWEEN -> {
				moon(g, width - 46, 30);
				for (int i = 0; i < 6; i++) bat(g, i, width, height);
			}
			case CHRISTMAS -> snow(g, width, height, 40);
			case WINTER -> snow(g, width, height, 90);
			default -> { }
		}
	}

	// ================================================================ Christmas

	/** Fir garland hanging in swags between red bows, with baubles and tiny lights. */
	private static void garland(GuiGraphicsExtractor g, int x, int y, int w) {
		int swags = Math.max(2, Math.round(w / 70f));
		float seg = w / (float) swags;
		float t = time();
		int[] needles = {0xFF15502A, 0xFF1E6B32, 0xFF2E8B45, 0xFF3FA055};
		for (int px = 0; px <= w; px++) {
			float f = (px % seg) / seg;
			int yy = y + 3 + Math.round((float) Math.sin(Math.PI * f) * 7);
			for (int k = 0; k < 4; k++) {
				int off = Math.round((rnd(px, k) - 0.5f) * 7);
				int c = needles[(int) (rnd(px, k + 7) * needles.length) % needles.length];
				g.fill(x + px, yy + off - 1, x + px + 1, yy + off + 2, c);
			}
			// Tiny warm lights twinkling in the branches.
			if (px % 9 == 4) {
				float tw = 0.5f + 0.5f * (float) Math.sin(t * 3 + px);
				g.fill(x + px, yy - 1, x + px + 1, yy, Glass.argb(Math.round(120 + tw * 135), 0xFFE680));
			}
		}
		// Baubles hanging below the strand.
		for (int px = 10; px < w - 6; px += 17) {
			float f = (px % seg) / seg;
			int yy = y + 6 + Math.round((float) Math.sin(Math.PI * f) * 7);
			boolean red = (px / 17) % 2 == 0;
			int top = red ? 0xFFFF5A5F : 0xFFFFE08A, bottom = red ? 0xFFB71C1C : 0xFFD4A017;
			Glass.circle(g, x + px - 3, yy, 6, bottom);
			Glass.circle(g, x + px - 2, yy, 4, top);
			g.fill(x + px - 1, yy + 1, x + px, yy + 2, 0xE0FFFFFF);
		}
		for (int i = 0; i <= swags; i++) bow(g, x + Math.round(i * seg), y + 2);
	}

	private static void bow(GuiGraphicsExtractor g, int cx, int cy) {
		int light = 0xFFFF5252, dark = 0xFFB71C1C;
		// Ribbon tails.
		for (int i = 0; i < 6; i++) {
			g.fill(cx - 2 - i / 2, cy + 2 + i, cx - i / 2, cy + 3 + i, dark);
			g.fill(cx + i / 2, cy + 2 + i, cx + 2 + i / 2, cy + 3 + i, dark);
		}
		// Loops and knot.
		Glass.roundRect(g, cx - 8, cy - 3, 7, 6, 3, light, dark);
		Glass.roundRect(g, cx + 1, cy - 3, 7, 6, 3, light, dark);
		Glass.circle(g, cx - 2, cy - 2, 4, 0xFFE53935);
		g.fill(cx - 6, cy - 2, cx - 4, cy - 1, 0x90FFFFFF);
		g.fill(cx + 3, cy - 2, cx + 5, cy - 1, 0x90FFFFFF);
	}

	/** Colorful fairy lights on a sagging wire, softly blinking. */
	private static void fairyLights(GuiGraphicsExtractor g, int x, int y, int w) {
		int[] colors = {0xFFFFE14D, 0xFF3DDC5A, 0xFFFF3B3B, 0xFFFF9A2E, 0xFF4FA8FF};
		float t = time();
		for (int px = 0; px <= w; px++) {
			int yy = y + 2 + Math.round((float) Math.sin(Math.PI * px / w) * 6);
			g.fill(x + px, yy, x + px + 1, yy + 1, 0xFF2A2A2E);
		}
		int i = 0;
		for (int px = 6; px < w - 2; px += 16, i++) {
			int yy = y + 2 + Math.round((float) Math.sin(Math.PI * px / w) * 6);
			int c = colors[i % colors.length];
			float glow = 0.55f + 0.45f * (float) Math.sin(t * 2.2f + i * 1.7f);
			ShapeCache.glow(g, x + px, yy + 6, 14, c & 0xFFFFFF, Math.round(70 * glow));
			g.fill(x + px - 1, yy, x + px + 2, yy + 2, 0xFF2A2A2E);
			Glass.roundRect(g, x + px - 2, yy + 2, 5, 7, 2.5f, c, Glass.argb(0xFF, darker(c)));
			g.fill(x + px - 1, yy + 3, x + px, yy + 5, 0xC0FFFFFF);
		}
	}

	// ================================================================ winter

	private static void snowCap(GuiGraphicsExtractor g, int x, int y, int w) {
		for (int px = 6; px < w - 10; px += 10) {
			int bump = 4 + Math.round(rnd(px, 3) * 3);
			Glass.roundRect(g, x + px, y - bump + 2, 14, bump + 3, (bump + 3) / 2f, 0xFFFFFFFF, 0xFFE6F0FA);
		}
	}

	private static void snow(GuiGraphicsExtractor g, int width, int height, int count) {
		float t = time();
		for (int i = 0; i < count; i++) {
			float speed = 12 + rnd(i, 1) * 22;
			float y = (rnd(i, 2) * (height + 20) + t * speed) % (height + 20) - 10;
			float x = rnd(i, 3) * width + (float) Math.sin(t * 0.8f + i) * 8;
			int size = rnd(i, 4) < 0.3f ? 3 : rnd(i, 4) < 0.7f ? 2 : 1;
			int alpha = 120 + 60 * (int) (rnd(i, 5) * 3); // few levels: the flakes share cached shapes
			if (size >= 3) Glass.circle(g, Math.round(x), Math.round(y), 3, Glass.argb(alpha, 0xFFFFFF));
			else g.fill(Math.round(x), Math.round(y), Math.round(x) + size, Math.round(y) + size, Glass.argb(alpha, 0xFFFFFF));
		}
	}

	// ================================================================ Halloween

	/** A jack-o'-lantern; {@code x, y} top-left, {@code s} width. */
	private static void pumpkin(GuiGraphicsExtractor g, int x, int y, int s, int seed) {
		int h = Math.round(s * 0.8f);
		float flicker = 0.75f + 0.25f * (float) Math.sin(time() * 9 + seed * 2.3f) * (float) Math.sin(time() * 5.3f + seed);
		ShapeCache.glow(g, x + s / 2, y + h / 2, s * 2, 0xFF9A2E, Math.round(70 * flicker));
		// Stem.
		g.fill(x + s / 2 - 1, y - 3, x + s / 2 + 2, y + 1, 0xFF4E6B1F);
		g.fill(x + s / 2 + 1, y - 4, x + s / 2 + 3, y - 2, 0xFF4E6B1F);
		// Body: three overlapping lobes with darker ribs.
		Glass.roundRect(g, x, y + 1, s * 5 / 8, h, h / 2f, 0xFFFF9A2E, 0xFFD9560B);
		Glass.roundRect(g, x + s * 3 / 8, y + 1, s * 5 / 8, h, h / 2f, 0xFFFF9A2E, 0xFFD9560B);
		Glass.roundRect(g, x + s / 4, y, s / 2, h + 1, h / 2f, 0xFFFFAD45, 0xFFE8670F);
		g.fill(x + s / 4, y + 3, x + s / 4 + 1, y + h - 2, 0x60802800);
		g.fill(x + s * 3 / 4, y + 3, x + s * 3 / 4 + 1, y + h - 2, 0x60802800);
		// Glowing face.
		int face = Glass.argb(Math.round(255 * flicker), 0xFFE066);
		int ey = y + h / 3, ex = Math.max(2, s / 7);
		triangle(g, x + s / 2 - ex - 2, ey, ex, face);
		triangle(g, x + s / 2 + 2, ey, ex, face);
		int my = y + h * 2 / 3 - 1, mw = s / 2;
		g.fill(x + (s - mw) / 2, my, x + (s + mw) / 2, my + 2, face);
		for (int i = 0; i < mw; i += 3) g.fill(x + (s - mw) / 2 + i, my + 2, x + (s - mw) / 2 + i + 2, my + 3, face);
	}

	private static void triangle(GuiGraphicsExtractor g, int x, int y, int size, int color) {
		for (int row = 0; row < size; row++) g.fill(x + size / 2 - row / 2, y + row, x + size / 2 + row / 2 + 1, y + row + 1, color);
	}

	private static void cobweb(GuiGraphicsExtractor g, int x, int y) {
		int c = 0x70FFFFFF;
		for (int i = 0; i < 14; i++) {
			g.fill(x + i, y, x + i + 1, y + 1, c);
			g.fill(x, y + i, x + 1, y + i + 1, c);
			if (i < 11) g.fill(x + i, y + i, x + i + 1, y + i + 1, c);
		}
		for (int r = 4; r <= 12; r += 4) {
			for (int a = 0; a <= 8; a++) {
				double ang = Math.PI / 2 * a / 8;
				g.fill(x + (int) Math.round(Math.cos(ang) * r), y + (int) Math.round(Math.sin(ang) * r),
						x + (int) Math.round(Math.cos(ang) * r) + 1, y + (int) Math.round(Math.sin(ang) * r) + 1, c);
			}
		}
	}

	private static void moon(GuiGraphicsExtractor g, int cx, int cy) {
		Glass.blob(g, cx, cy, 80, 0x40FFD970);
		Glass.circle(g, cx - 12, cy - 12, 24, 0xFFFFE89A);
		Glass.circle(g, cx - 6, cy - 6, 5, 0x40C8A040);
		Glass.circle(g, cx + 2, cy + 1, 4, 0x40C8A040);
	}

	private static void bat(GuiGraphicsExtractor g, int i, int width, int height) {
		float t = time();
		float speed = 18 + rnd(i, 11) * 20;
		float x = (rnd(i, 12) * (width + 60) + t * speed) % (width + 60) - 30;
		float y = 14 + rnd(i, 13) * height * 0.28f + (float) Math.sin(t * 1.5f + i * 2) * 8;
		int bx = Math.round(x), by = Math.round(y);
		int c = 0xE0140A1E;
		boolean up = ((int) (t * 8 + i * 3)) % 2 == 0;
		g.fill(bx - 1, by - 1, bx + 2, by + 3, c);
		g.fill(bx - 1, by - 2, bx, by - 1, c);
		g.fill(bx + 1, by - 2, bx + 2, by - 1, c);
		for (int k = 1; k <= 5; k++) {
			int dy = up ? -(k / 2) : (k / 2);
			g.fill(bx - 1 - k, by + dy, bx - k, by + dy + 2, c);
			g.fill(bx + 1 + k, by + dy, bx + 2 + k, by + dy + 2, c);
		}
	}

	// ================================================================ Easter

	private static void egg(GuiGraphicsExtractor g, int x, int y, int light, int dark, int seed) {
		Glass.roundRect(g, x, y, 10, 13, 5, light, dark);
		int band = seed % 2 == 0 ? 0xFFFFFFFF : 0xFFFFF0A0;
		g.fill(x + 1, y + 5, x + 9, y + 6, band);
		g.fill(x + 1, y + 8, x + 9, y + 9, Glass.argb(0xC0, band));
		g.fill(x + 3, y + 2, x + 4, y + 3, 0xC0FFFFFF);
	}

	private static void grass(GuiGraphicsExtractor g, int x, int y, int w) {
		for (int px = 0; px < w; px += 2) {
			int hgt = 2 + Math.round(rnd(px, 21) * 5);
			int c = rnd(px, 22) < 0.5f ? 0xFF6CCB75 : 0xFF4FAE5A;
			g.fill(x + px, y - hgt, x + px + 1, y, c);
		}
	}

	// ================================================================ drawer icons

	/** Small icon for the season picker (18×18 circle at x, y). */
	public static void icon(GuiGraphicsExtractor g, int season, int x, int y) {
		int cx = x + 9, cy = y + 9;
		switch (season) {
			case OFF -> {
				for (int i = -3; i <= 3; i++) {
					g.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, 0xFFFFFFFF);
					g.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, 0xFFFFFFFF);
				}
			}
			case HALLOWEEN -> {
				g.fill(cx, cy - 6, cx + 1, cy - 3, 0xFF4E6B1F);
				Glass.roundRect(g, cx - 6, cy - 4, 12, 9, 4.5f, 0xFFFFAD45, 0xFFE8670F);
				g.fill(cx - 3, cy - 2, cx - 1, cy, 0xFF3A1A00);
				g.fill(cx + 1, cy - 2, cx + 3, cy, 0xFF3A1A00);
				g.fill(cx - 3, cy + 2, cx + 3, cy + 3, 0xFF3A1A00);
			}
			case CHRISTMAS -> {
				g.fill(cx - 1, cy - 6, cx + 2, cy - 4, 0xFFF2C14E);
				Glass.circle(g, cx - 5, cy - 4, 10, 0xFFE53935);
				g.fill(cx - 2, cy - 2, cx, cy, 0xC0FFFFFF);
			}
			case WINTER -> {
				int c = 0xFFFFFFFF;
				for (int i = -5; i <= 5; i++) {
					g.fill(cx + i, cy, cx + i + 1, cy + 1, c);
					g.fill(cx, cy + i, cx + 1, cy + i + 1, c);
					if (Math.abs(i) <= 3) {
						g.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, c);
						g.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, c);
					}
				}
			}
			case EASTER -> {
				Glass.roundRect(g, cx - 4, cy - 6, 9, 12, 4.5f, 0xFFFFB8D8, 0xFFFF7FB0);
				g.fill(cx - 3, cy - 1, cx + 4, cy, 0xFFFFFFFF);
				g.fill(cx - 3, cy + 2, cx + 4, cy + 3, 0xFFFFF0A0);
			}
			default -> { }
		}
	}

	/** Background of a season's picker circle. */
	public static int[] iconColors(int season) {
		return switch (season) {
			case HALLOWEEN -> new int[] {0xFF3A2048, 0xFF1A0E22};
			case CHRISTMAS -> new int[] {0xFF3FA055, 0xFF1E6B32};
			case WINTER -> new int[] {0xFF8FC8FF, 0xFF3D7BD0};
			case EASTER -> new int[] {0xFFC9F59B, 0xFF7CCB5A};
			default -> new int[] {0xFFB0AEC0, 0xFF8A889C};
		};
	}

	private static int darker(int c) {
		int r = ((c >> 16) & 0xFF) * 6 / 10, gg = ((c >> 8) & 0xFF) * 6 / 10, b = (c & 0xFF) * 6 / 10;
		return (r << 16) | (gg << 8) | b;
	}
}
