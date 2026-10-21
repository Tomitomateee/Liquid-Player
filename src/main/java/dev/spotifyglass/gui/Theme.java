package dev.spotifyglass.gui;

import dev.spotifyglass.SpotifyConfig;

/** A color preset for the GUI, selectable in the settings drawer. */
public record Theme(String name,
                    int accentTop, int accentBottom,
                    int secondTop, int secondBottom,
                    int panelTop, int panelBottom,
                    int blobA, int blobB,
                    int text, int textSoft, int textFaint,
                    int pillTop, int pillBottom) {

	public static final Theme[] ALL = {
			new Theme("Lavendel", 0xFFB59CFF, 0xFF7C5CF2, 0xFF9BF0DF, 0xFF55CDB6, 0xF8F6FC, 0xECE8F3,
					0xC9A6FF, 0x9BF0DF, 0xFF1E1B2E, 0xFF6E6A80, 0xFF8A86A0, 0xF8FFFFFF, 0xE6F1EEF8),
			new Theme("Ozean", 0xFF8FC8FF, 0xFF3D7BF0, 0xFF9BE7FF, 0xFF4FB6E0, 0xEEF5FD, 0xE2ECF8,
					0x8FB8FF, 0x9BE7FF, 0xFF152036, 0xFF5E6E86, 0xFF7D8BA0, 0xF8FFFFFF, 0xE6EEF4FB),
			new Theme("Mint", 0xFF8EF0C4, 0xFF2FBF8A, 0xFFC9F59B, 0xFF8CD65A, 0xF1FBF6, 0xE3F4EC,
					0x8EF0C4, 0xC9F59B, 0xFF13261E, 0xFF5B7368, 0xFF7F9488, 0xF8FFFFFF, 0xE6EEF7F2),
			new Theme("Sunset", 0xFFFFB38A, 0xFFF2665C, 0xFFFFD88A, 0xFFF5A742, 0xFDF4EF, 0xF7E6DE,
					0xFFB38A, 0xFFD88A, 0xFF2E1A16, 0xFF80645C, 0xFF9C8078, 0xF8FFFFFF, 0xE6FAF0EB),
			new Theme("Rosé", 0xFFFFA8D0, 0xFFE5559A, 0xFFD9B3FF, 0xFFA67CF0, 0xFCF2F7, 0xF5E4EE,
					0xFFA8D0, 0xD9B3FF, 0xFF2E1424, 0xFF80627A, 0xFF9C7E94, 0xF8FFFFFF, 0xE6F8EEF4),
			// Hyper Magenta / Ultrasonic Blue / Periwinkle
			new Theme("Neon", 0xFFBF40FA, 0xFF4928C2, 0xFFE3D9FC, 0xFF9F7BF5, 0xF3EEFE, 0xE3D9FC,
					0xBF40FA, 0x4928C2, 0xFF1B0F3A, 0xFF5B4A86, 0xFF7D6CA8, 0xF8FFFFFF, 0xE6EFE9FD),
			// Velvet Purple
			new Theme("Velvet", 0xFFC77DD8, 0xFF5B2A62, 0xFFE3D9FC, 0xFFBF40FA, 0xF6EEF8, 0xE9DCEC,
					0xBF40FA, 0x5B2A62, 0xFF2A1030, 0xFF6E4E76, 0xFF8E7096, 0xF8FFFFFF, 0xE6F3EAF5),
			// Crimson Noir
			new Theme("Crimson", 0xFFE8335E, 0xFFC50337, 0xFFF08AA3, 0xFF8A1030, 0xFCEFF2, 0xF5DDE3,
					0xC50337, 0x8A1030, 0xFF2A0610, 0xFF7A4652, 0xFF9A6A75, 0xF8FFFFFF, 0xE6F9EBEF),
			// Slate greys: #06141B … #CCD0CF
			new Theme("Storm", 0xFF4A5C6A, 0xFF253745, 0xFF9BA8AB, 0xFF4A5C6A, 0xF2F4F4, 0xDDE1E1,
					0x9BA8AB, 0x4A5C6A, 0xFF06141B, 0xFF4A5C6A, 0xFF7A878C, 0xF8FFFFFF, 0xE6EEF0F0),
			new Theme("Gold", 0xFFFFD36E, 0xFFE8A317, 0xFFFFE9B0, 0xFFF2B84B, 0xFFFAF0, 0xF7EBD3,
					0xFFD36E, 0xFFB38A, 0xFF2B1E05, 0xFF806A3D, 0xFF9C8960, 0xF8FFFFFF, 0xE6FBF4E4),
			new Theme("Aqua", 0xFF7FE7F5, 0xFF1BA9C7, 0xFFB5F2D9, 0xFF4FD1A5, 0xEFFBFD, 0xDDF3F7,
					0x7FE7F5, 0xB5F2D9, 0xFF06262E, 0xFF4E7480, 0xFF7393A0, 0xF8FFFFFF, 0xE6EAF8FA),
	};

	/** Theme index meaning "own colors" (built from {@link #custom}). */
	public static final int CUSTOM = ALL.length;

	public static Theme get(int index) {
		return ALL[Math.floorMod(index, ALL.length)];
	}

	/** The selected theme (preset or own colors), without black mode. */
	public static Theme base(SpotifyConfig cfg) {
		return cfg.theme == CUSTOM ? custom(cfg.customHue1, cfg.customHue2, cfg.customIntensity) : get(cfg.theme);
	}

	/** The theme the user picked, in its black variant when black mode is on. */
	public static Theme current(SpotifyConfig cfg) {
		Theme season = Season.theme(cfg.season, cfg.blackMode);
		if (season != null) return season;
		Theme t = base(cfg);
		return cfg.blackMode ? t.black() : t;
	}

	/**
	 * Own colors: color 1 and color 2 (hues 0–360) are blended into every gradient, intensity goes
	 * from soft pastel (0) to vivid (1). Values are rounded so caches don't fill up while dragging.
	 */
	public static Theme custom(float hue1, float hue2, float intensity) {
		int h1 = Math.round(hue1 / 5f) * 5, h2 = Math.round(hue2 / 5f) * 5;
		float s = Math.round(Math.max(0, Math.min(1, intensity)) * 20) / 20f;
		int c1 = hsv(h1, 0.25f + 0.65f * s, 1f), c2 = hsv(h2, 0.35f + 0.6f * s, 0.92f - 0.12f * s);
		int light1 = hsv(h1, 0.18f + 0.3f * s, 1f), light2 = hsv(h2, 0.18f + 0.3f * s, 1f);
		return new Theme("Eigene " + h1 + "-" + h2 + "-" + Math.round(s * 100),
				0xFF000000 | c1, 0xFF000000 | c2,
				0xFF000000 | light2, 0xFF000000 | mix(light2, c2, 0.5f),
				mix(0xFFFFFF, c1, 0.06f), mix(0xFFFFFF, c2, 0.14f),
				c1, c2,
				0xFF000000 | mix(0x16161E, c2, 0.18f), 0xFF000000 | mix(0x6A6A78, c2, 0.25f), 0xFF000000 | mix(0x8C8C98, c2, 0.2f),
				0xF8FFFFFF, 0xE6000000 | mix(0xF3F3F6, light1, 0.25f));
	}

	/** HSV → RGB (no alpha); hue in degrees. */
	public static int hsv(float hue, float sat, float val) {
		float h = ((hue % 360) + 360) % 360 / 60f;
		float c = val * sat, x = c * (1 - Math.abs(h % 2 - 1)), m = val - c;
		float r, g, b;
		if (h < 1) { r = c; g = x; b = 0; }
		else if (h < 2) { r = x; g = c; b = 0; }
		else if (h < 3) { r = 0; g = c; b = x; }
		else if (h < 4) { r = 0; g = x; b = c; }
		else if (h < 5) { r = x; g = 0; b = c; }
		else { r = c; g = 0; b = x; }
		return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
	}

	/** Black mode: near-black glass tinted with the accent, light text, dark pills. */
	public Theme black() {
		int tint = accentBottom & 0xFFFFFF;
		return new Theme(name + " Schwarz", accentTop, accentBottom, secondTop, secondBottom,
				mix(0x0D0D12, tint, 0.12f), mix(0x040506, tint, 0.06f),
				accentTop & 0xFFFFFF, tint,
				0xFFF2F2F7, 0xFFADADBD, 0xFF7E7E8E,
				0xF0000000 | mix(0x24242C, tint, 0.14f), 0xE6000000 | mix(0x16161C, tint, 0.08f));
	}

	private static int mix(int rgb, int with, float t) {
		int r = Math.round(((rgb >> 16) & 0xFF) * (1 - t) + ((with >> 16) & 0xFF) * t);
		int g = Math.round(((rgb >> 8) & 0xFF) * (1 - t) + ((with >> 8) & 0xFF) * t);
		int b = Math.round((rgb & 0xFF) * (1 - t) + (with & 0xFF) * t);
		return (r << 16) | (g << 8) | b;
	}
}
