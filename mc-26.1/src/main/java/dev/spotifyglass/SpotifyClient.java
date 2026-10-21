package dev.spotifyglass;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Util;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Spotify without any login: playback info and controls come from the Windows media session
 * ({@link MediaBridge}); the song search uses Apple's free iTunes Search API and opens the
 * chosen song in the Spotify app.
 */
public class SpotifyClient {
	/** A search result. {@code uri} is only used to identify the row. */
	public record Track(String uri, String title, String subtitle, String imageUrl) {}

	public record PlayerState(String title, String artist, String imageUrl, String uri,
	                          boolean playing, long progressMs, long durationMs, long fetchedAt,
	                          boolean shuffle, String repeat) {
		public static final PlayerState EMPTY = new PlayerState("Nichts wird abgespielt", "Starte Spotify auf diesem PC",
				null, null, false, 0, 0, 0, false, "off");

		/** Progress interpolated from the last update, so the bar moves smoothly. */
		public long liveProgress() {
			if (!playing || durationMs <= 0) return progressMs;
			return Math.min(durationMs, progressMs + (System.currentTimeMillis() - fetchedAt));
		}
	}

	public final SpotifyConfig config;
	private final MediaBridge bridge = new MediaBridge();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	private volatile List<Track> results = List.of();
	private volatile String message = "";
	private volatile long messageAt = 0;
	private volatile boolean busy;

	public SpotifyClient(SpotifyConfig config) {
		this.config = config;
	}

	public List<Track> results() { return results; }
	public boolean busy() { return busy; }

	/** A short status/error message for the toast; empty once it is older than 5 seconds. */
	public String message() {
		return System.currentTimeMillis() - messageAt < 5000 ? message : "";
	}

	private void toast(String text) {
		message = text;
		messageAt = System.currentTimeMillis();
	}

	public PlayerState state() {
		MediaBridge.Snapshot s = bridge.latest();
		if (!s.ok()) {
			return MediaBridge.supported()
					? new PlayerState("Spotify läuft nicht", "Starte Spotify auf diesem PC", null, null, false, 0, 0, 0, false, "off")
					: new PlayerState("Nur unter Windows", "Die Mod liest Spotify aus Windows aus", null, null, false, 0, 0, 0, false, "off");
		}
		String image = s.thumbPath().isEmpty() ? null : Path.of(s.thumbPath()).toUri().toString();
		String repeat = switch (s.repeat()) {
			case "list" -> "context";
			case "track" -> "track";
			default -> "off";
		};
		return new PlayerState(s.title(), s.artist(), image, "local:" + s.title() + "|" + s.artist(),
				s.playing(), s.positionMs(), s.durationMs(), s.receivedAt(), s.shuffle(), repeat);
	}

	/** Live audio level of Spotify, 0..1 (raw peak; -1 if unknown). */
	public float audioPeak() {
		return bridge.peak();
	}

	// ---------------------------------------------------------------- controls

	public void togglePlay() { bridge.send("toggle"); }

	public void next() { bridge.send("next"); }

	public void previous() { bridge.send("prev"); }

	public void seek(long positionMs) { bridge.send("seek " + positionMs); }

	/** Spotify's volume in the Windows volume mixer, 0..1 (-1 if unknown). */
	public float volume() { return bridge.volume(); }

	public void setVolume(float level) {
		bridge.send("volume " + String.format(java.util.Locale.ROOT, "%.3f", Math.max(0f, Math.min(1f, level))));
	}

	public void toggleShuffle() { bridge.send("shuffle " + !state().shuffle()); }

	public void cycleRepeat() {
		bridge.send("repeat " + switch (state().repeat()) {
			case "off" -> "List";
			case "context" -> "Track";
			default -> "None";
		});
	}

	/** Opens the song in the Spotify app (its search), where it can be played. */
	/**
	 * Plays the song in Spotify, in the background (Minecraft keeps the focus): directly via its
	 * Spotify ID if known, otherwise the bridge searches in the Spotify app and presses play on
	 * the matching song.
	 */
	public void play(Track track) {
		if (track.uri().startsWith("spotify:track:")) {
			openBackground(track.uri());
			toast("Spielt: " + track.title());
			return;
		}
		String known = TrackIdLookup.get(track.title(), track.subtitle());
		if (!known.isEmpty()) {
			openBackground("spotify:track:" + known);
			toast("Spielt: " + track.title());
			return;
		}
		searchAndPlay(track.title(), track.subtitle());
		toast("Spielt: " + track.title());
	}

	/** Searches in the Spotify app and presses play on the matching song (Windows), in the background. */
	public void searchAndPlay(String title, String artist) {
		if (!MediaBridge.supported()) {
			openSearch(title + " " + artist, false);
			return;
		}
		bridge.send("searchplay " + enc(title + " " + artist) + " " + enc(title) + " " + enc(artist));
	}

	private static String enc(String s) {
		return URLEncoder.encode(s.trim(), StandardCharsets.UTF_8).replace("+", "%20");
	}
	/** Opens a spotify: link; on Windows in the background, handing the focus back to Minecraft. */
	private void openBackground(String uri) {
		try {
			if (MediaBridge.supported()) bridge.send("open " + uri);
			else Util.getPlatform().openUri(URI.create(uri));
		} catch (Exception e) {
			toast("Spotify konnte nicht geöffnet werden");
		}
	}

	/** Opens Spotify's search; {@code background} keeps Minecraft in front. */
	public void openSearch(String query, boolean background) {
		String q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8).replace("+", "%20");
		if (background) openBackground("spotify:search:" + q);
		else openInSpotify(query);
	}
	// ---------------------------------------------------------------- search

	public void search(String query) {
		if (query.isBlank()) return;
		busy = true;
		HttpRequest req = HttpRequest.newBuilder(URI.create("https://itunes.apple.com/search?media=music&entity=song&limit=15&term="
						+ URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", "LiquidPlayer/1.0 (Minecraft mod)")
				.GET().build();
		http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
			try {
				if (err != null || res.statusCode() != 200) {
					toast("Suche fehlgeschlagen");
					return;
				}
				List<Track> list = new ArrayList<>();
				for (JsonElement el : JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("results")) {
					JsonObject o = el.getAsJsonObject();
					String title = str(o, "trackName"), artist = str(o, "artistName");
					if (title.isEmpty()) continue;
					String art = str(o, "artworkUrl100").replace("100x100bb", "200x200bb");
					list.add(new Track("search:" + list.size() + ":" + title + "|" + artist, title, artist, art.isEmpty() ? null : art));
				}
				results = list;
				if (list.isEmpty()) toast("Keine Treffer");
			} catch (Exception e) {
				toast("Suche fehlgeschlagen");
			} finally {
				busy = false;
			}
		});
	}

	private void openInSpotify(String query) {
		try {
			String q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8).replace("+", "%20");
			Util.getPlatform().openUri(URI.create("spotify:search:" + q));
		} catch (Exception e) {
			toast("Spotify konnte nicht geöffnet werden");
		}
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}
}
