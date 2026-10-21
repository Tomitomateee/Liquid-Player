package dev.spotifyglass;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a song's Spotify track ID without any Spotify login, via the free MusicBrainz database
 * (which links many recordings to their Spotify page). With the ID, opening
 * {@code spotify:track:<id>} makes the Spotify app play exactly that song.
 */
public final class TrackIdLookup {
	private static final Pattern SPOTIFY_TRACK = Pattern.compile("open\\.spotify\\.com/track/([A-Za-z0-9]{22})");
	private static final String UA = "LiquidPlayer/1.0 (Minecraft mod; https://modrinth.com)";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	/** One request at a time: MusicBrainz allows about one request per second. */
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "liquidplayer-trackid");
		t.setDaemon(true);
		return t;
	});
	private static final String NOT_FOUND = "-";
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
	/** Found IDs are kept on disk, so songs played before start instantly next time. */
	private static final java.nio.file.Path FILE = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("liquid-player-trackids.json");
	private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

	static {
		try {
			if (java.nio.file.Files.exists(FILE)) {
				Map<String, String> saved = GSON.fromJson(java.nio.file.Files.readString(FILE, StandardCharsets.UTF_8),
						new com.google.gson.reflect.TypeToken<Map<String, String>>() { }.getType());
				if (saved != null) CACHE.putAll(saved);
			}
		} catch (Exception e) {
			SpotifyGlassClient.LOGGER.debug("Spotify-ID-Speicher nicht lesbar", e);
		}
	}

	private TrackIdLookup() {}

	private static synchronized void saveFound() {
		try {
			Map<String, String> found = new java.util.TreeMap<>();
			CACHE.forEach((k, v) -> { if (v.length() == 22) found.put(k, v); });
			java.nio.file.Files.writeString(FILE, GSON.toJson(found), StandardCharsets.UTF_8);
		} catch (Exception e) {
			SpotifyGlassClient.LOGGER.debug("Spotify-ID-Speicher nicht schreibbar", e);
		}
	}

	/** The 22-character Spotify track ID, "" while still searching, or "" if not found. */
	public static String get(String title, String artist) {
		if (title == null || title.isBlank()) return "";
		String key = (title + "|" + artist).toLowerCase(java.util.Locale.ROOT);
		String v = CACHE.get(key);
		if (v != null) return NOT_FOUND.equals(v) ? "" : v;
		CACHE.put(key, "");
		WORKER.execute(() -> {
			String id = find(title, artist);
			CACHE.put(key, id);
			if (id.length() == 22) saveFound();
		});
		return "";
	}

	/** True once a lookup has finished (found or not). */
	public static boolean done(String title, String artist) {
		String v = CACHE.get((title + "|" + artist).toLowerCase(java.util.Locale.ROOT));
		return v != null && !v.isEmpty();
	}

	private static String find(String title, String artist) {
		String t = cleanTitle(title);
		// Try the full artist first ("Tyler, The Creator"), then only the first of several ("A, B").
		java.util.LinkedHashSet<String> artists = new java.util.LinkedHashSet<>();
		artists.add(artist.trim());
		if (artist.contains(", ")) artists.add(artist.substring(0, artist.indexOf(", ")).trim());
		for (String a : artists) {
			String id = findFor(t, a);
			if (!id.isEmpty()) return id;
		}
		return NOT_FOUND;
	}

	private static String findFor(String title, String artist) {
		try {
			String query = "recording:\"" + escape(title) + "\" AND artist:\"" + escape(artist) + "\"";
			JsonObject search = getJson("https://musicbrainz.org/ws/2/recording/?limit=25&fmt=json&query="
					+ URLEncoder.encode(query, StandardCharsets.UTF_8));
			if (search == null || !search.has("recordings")) return "";
			// Good matches only, the one on the most releases first: that's usually the original
			// studio recording, which is the one most likely linked to Spotify.
			java.util.List<JsonObject> candidates = new java.util.ArrayList<>();
			for (JsonElement el : search.getAsJsonArray("recordings")) {
				JsonObject rec = el.getAsJsonObject();
				if (!rec.has("score") || rec.get("score").getAsInt() >= 80) candidates.add(rec);
			}
			candidates.sort((x, y) -> Integer.compare(releases(y), releases(x)));
			for (JsonObject rec : candidates.subList(0, Math.min(6, candidates.size()))) {
				Thread.sleep(1100);
				JsonObject full = getJson("https://musicbrainz.org/ws/2/recording/" + rec.get("id").getAsString() + "?inc=url-rels&fmt=json");
				if (full == null || !full.has("relations")) continue;
				for (JsonElement r : full.getAsJsonArray("relations")) {
					JsonObject rel = r.getAsJsonObject();
					if (!rel.has("url")) continue;
					Matcher m = SPOTIFY_TRACK.matcher(rel.getAsJsonObject("url").get("resource").getAsString());
					if (m.find()) return m.group(1);
				}
			}
			Thread.sleep(1100);
		} catch (Exception e) {
			SpotifyGlassClient.LOGGER.debug("Spotify-ID nicht gefunden", e);
		}
		return "";
	}
	private static int releases(JsonObject rec) {
		return rec.has("releases") && rec.get("releases").isJsonArray() ? rec.getAsJsonArray("releases").size() : 0;
	}

	private static JsonObject getJson(String url) throws Exception {
		for (int attempt = 0; attempt < 4; attempt++) {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
					.header("User-Agent", UA).header("Accept", "application/json").GET().build();
			HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() == 200) return JsonParser.parseString(res.body()).getAsJsonObject();
			if (res.statusCode() == 503) Thread.sleep(2000L * (attempt + 1)); // rate limited: wait and retry
			else return null;
		}
		return null;
	}

	/** "Song (feat. X) - Remastered" → "Song". */
	private static String cleanTitle(String t) {
		return t.replaceAll("\\s*[(\\[].*?[)\\]]", "").replaceAll("\\s+-\\s+.*$", "").trim();
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
