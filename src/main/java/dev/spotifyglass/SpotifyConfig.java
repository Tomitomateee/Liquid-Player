package dev.spotifyglass;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted GUI settings. */
public class SpotifyConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("spotify-glass.json");

	/** Index into {@code Theme.ALL}. */
	public int theme = 0;
	/** Own colors (theme index Theme.CUSTOM): two hues 0–360 and intensity 0–1. */
	public float customHue1 = 285f;
	public float customHue2 = 200f;
	public float customIntensity = 0.7f;
	/** Black glass instead of light glass, for any color theme. */
	public boolean blackMode = false;
	/** Glass panel opacity in percent (20–100). */
	public int opacity = 60;
	/** On-screen "now playing" display and its position as a fraction of the free screen space. */
	public boolean hudEnabled = false;
	/** 0 = card, 1 = spinning vinyl, 2 = animated wave (see SongHud). */
	public int hudStyle = 0;
	/** Size of the in-game song display (0.5–2.0). */
	public float hudScale = 1f;
	/** Whether the listen-along panel left of the player is expanded. */
	public boolean lyricsOpen = false;
	/** Whether the song search panel right of the player is expanded. */
	public boolean searchOpen = true;
	/** Show my song above my name (and share it with players who have the mod). */
	public boolean nametagShare = true;
	/** List my song in other players' "Reinhören" panel (independent of the name tag). */
	public boolean listenShare = true;
	/** Hide my listening time and top artist from other players. */
	public boolean profilePrivate = false;
	/** "Reinhören" shows every Liquid Player user (true) or only players on my server (false). */
	public boolean listenGlobal = false;
	/** Seasonal look: 0 = off, 1 = Halloween, 2 = Christmas, 3 = winter, 4 = Easter (see Season). */
	public int season = 0;
	/** Show other players' songs above their names. */
	public boolean nametagOthers = true;
	/** Size of the song card above names (0.5–2.0). */
	public float nametagScale = 1f;
	public float hudX = 0.01f;
	public float hudY = 0.02f;

	public static SpotifyConfig load() {
		try {
			if (Files.exists(PATH)) {
				String json = Files.readString(PATH, StandardCharsets.UTF_8);
				SpotifyConfig cfg = GSON.fromJson(json, SpotifyConfig.class);
				if (cfg != null) {
					// Older versions stored Spotify login tokens here: rewrite the file without them.
					if (json.contains("refreshToken") || json.contains("accessToken")) cfg.save();
					return cfg;
				}
			}
		} catch (Exception e) {
			SpotifyGlassClient.LOGGER.warn("Konnte Config nicht lesen", e);
		}
		return new SpotifyConfig();
	}

	public synchronized void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			SpotifyGlassClient.LOGGER.warn("Konnte Config nicht speichern", e);
		}
	}
}
