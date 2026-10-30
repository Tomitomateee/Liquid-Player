package dev.afkcinematic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.afkcinematic.AfkCinematicClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * All settings of the mod, stored as JSON in {@code config/afk-cinematic.json}.
 * Edited through the Cloth Config screen (Mod Menu) or by hand.
 */
public final class CinematicConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("afk-cinematic.json");

	/** Start automatically after {@link #afkSeconds} without input. */
	public boolean autoStart = true;
	/** Seconds without keyboard/mouse input until the cinematic starts. */
	public int afkSeconds = 60;
	/** Base distance of the camera to the player, in blocks. */
	public float cameraDistance = 6.0f;
	/** Multiplier for all camera movement (1 = default). */
	public float orbitSpeed = 1.0f;
	/** Strength of the beat effects (FOV punch, lyric pulse); 0 turns them off. */
	public float beatStrength = 1.0f;
	/** Loudness (RMS, 0..1) above which we consider music to be playing. */
	public float musicThreshold = 0.015f;
	/** Bars (of 4 beats) per camera shot while music plays. */
	public int barsPerShot = 4;
	/** Show synced lyrics. */
	public boolean lyrics = true;
	/** Scale of the current lyric line (GUI pixels × this). */
	public float fontScale = 2.0f;
	/** Show title and artist in the top left corner. */
	public boolean songInfo = true;
	/** Black cinema bars at the top and bottom. */
	public boolean letterbox = true;
	/** Darkened screen edges. */
	public boolean vignette = true;

	private static CinematicConfig instance;

	public static CinematicConfig get() {
		if (instance == null) instance = load();
		return instance;
	}

	private static CinematicConfig load() {
		if (Files.exists(FILE)) {
			try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
				CinematicConfig cfg = GSON.fromJson(r, CinematicConfig.class);
				if (cfg != null) {
					cfg.clamp();
					return cfg;
				}
			} catch (Exception e) {
				AfkCinematicClient.LOGGER.warn("Konfiguration konnte nicht gelesen werden, nutze Standardwerte", e);
			}
		}
		CinematicConfig cfg = new CinematicConfig();
		cfg.save();
		return cfg;
	}

	public void save() {
		clamp();
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				GSON.toJson(this, w);
			}
		} catch (Exception e) {
			AfkCinematicClient.LOGGER.warn("Konfiguration konnte nicht gespeichert werden", e);
		}
	}

	/** Keeps hand-edited values in a sane range. */
	private void clamp() {
		afkSeconds = Math.max(5, Math.min(3600, afkSeconds));
		cameraDistance = Math.max(2f, Math.min(20f, cameraDistance));
		orbitSpeed = Math.max(0.05f, Math.min(5f, orbitSpeed));
		beatStrength = Math.max(0f, Math.min(3f, beatStrength));
		musicThreshold = Math.max(0f, Math.min(0.5f, musicThreshold));
		barsPerShot = Math.max(1, Math.min(32, barsPerShot));
		fontScale = Math.max(0.5f, Math.min(5f, fontScale));
	}
}
