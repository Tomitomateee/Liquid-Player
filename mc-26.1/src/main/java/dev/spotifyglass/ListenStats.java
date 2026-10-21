package dev.spotifyglass;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Listening statistics counted by the mod itself (no Spotify login): only time in which the mod
 * saw Spotify actually playing is counted, per main artist.
 */
public final class ListenStats {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("liquid-player-stats.json");

	private static final class Data {
		long totalMs;
		Map<String, Long> artistMs = new HashMap<>();
	}

	private static Data data = load();
	private static long lastTick;
	private static long lastSave;

	private ListenStats() {}

	/** Called every client tick with the current playback state. */
	public static void tick(SpotifyClient.PlayerState s) {
		long now = System.currentTimeMillis();
		long dt = lastTick == 0 ? 0 : Math.min(1000, now - lastTick);
		lastTick = now;
		if (dt <= 0 || !s.playing() || s.uri() == null) return;
		data.totalMs += dt;
		String artist = mainArtist(s.artist());
		if (!artist.isEmpty()) data.artistMs.merge(artist, dt, Long::sum);
		if (now - lastSave > 30_000) save();
	}

	public static int totalMinutes() {
		return (int) Math.min(Integer.MAX_VALUE, data.totalMs / 60_000);
	}

	public static String topArtist() {
		String best = "";
		long bestMs = 0;
		for (var e : data.artistMs.entrySet()) {
			if (e.getValue() > bestMs) {
				bestMs = e.getValue();
				best = e.getKey();
			}
		}
		return best;
	}

	/** "12 Std 5 Min" style text. */
	public static String formatMinutes(int minutes) {
		if (minutes < 60) return minutes + " Min";
		return (minutes / 60) + " Std " + (minutes % 60) + " Min";
	}

	private static String mainArtist(String artist) {
		if (artist == null) return "";
		int comma = artist.indexOf(", ");
		return (comma > 0 ? artist.substring(0, comma) : artist).trim();
	}

	public static synchronized void save() {
		lastSave = System.currentTimeMillis();
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(data), StandardCharsets.UTF_8);
		} catch (Exception e) {
			SpotifyGlassClient.LOGGER.warn("Hörstatistik konnte nicht gespeichert werden", e);
		}
	}

	private static Data load() {
		try {
			if (Files.exists(PATH)) {
				Data d = GSON.fromJson(Files.readString(PATH, StandardCharsets.UTF_8), Data.class);
				if (d != null) {
					if (d.artistMs == null) d.artistMs = new HashMap<>();
					return d;
				}
			}
		} catch (Exception e) {
			SpotifyGlassClient.LOGGER.warn("Hörstatistik konnte nicht gelesen werden", e);
		}
		return new Data();
	}
}
