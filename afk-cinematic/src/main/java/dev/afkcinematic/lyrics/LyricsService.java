package dev.afkcinematic.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.afkcinematic.AfkCinematicClient;
import dev.afkcinematic.media.MediaSession;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Loads synced lyrics from <a href="https://lrclib.net">LRCLIB</a> (free, no API key) on a background
 * thread and keeps them in an in-memory cache per song.
 * <p>
 * Titles from YouTube & co. are often messy ("Artist - Title (Official Video)", channel "ArtistVEVO"),
 * so several cleaned-up variants are tried one after another.
 */
public final class LyricsService {
	private static final String API = "https://lrclib.net/api/";
	private static final String USER_AGENT = "AFK Cinematic/1.0 (Minecraft mod; https://github.com/Tomitomateee/Liquid-Player)";
	private static final int CACHE_SIZE = 64;
	private static final long RETRY_MS = 30_000;
	/** Removes "(Official Video)", "[Lyrics]", "(feat. X)", "- Remastered 2011", ... */
	private static final Pattern JUNK = Pattern.compile(
			"\\s*[(\\[][^)\\]]*(official|video|audio|lyric|visuali[sz]er|hd|4k|mv|feat\\.?|ft\\.?|with )[^)\\]]*[)\\]]"
					+ "|\\s+-\\s+(\\d{4}\\s+)?remaster(ed)?.*$|\\s+-\\s+.*\\bversion\\b.*$",
			Pattern.CASE_INSENSITIVE);

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "afkcinematic-lyrics");
		t.setDaemon(true);
		return t;
	});
	/** Song key -> lyrics (empty = no synced lyrics found). LRU, access order. */
	private final Map<String, CacheEntry> cache =
			new LinkedHashMap<>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
					return size() > CACHE_SIZE;
				}
			};

	/**
	 * Returns the lyrics if they are already loaded; otherwise starts loading them and returns null.
	 * {@link Optional#empty()} means: loaded, but LRCLIB has no synced lyrics for this song.
	 */
	public synchronized Optional<Lyrics> get(MediaSession.Track track) {
		CacheEntry entry = cache.get(track.key());
		// Network trouble (not "no lyrics"): try again after a while.
		if (entry != null && entry.future.isDone() && entry.future.getNow(null) == null
				&& System.currentTimeMillis() - entry.started > RETRY_MS) {
			entry = null;
		}
		if (entry == null) {
			entry = new CacheEntry(CompletableFuture.supplyAsync(() -> fetch(track), executor), System.currentTimeMillis());
			cache.put(track.key(), entry);
		}
		if (!entry.future.isDone()) return null;
		Optional<Lyrics> result = entry.future.getNow(null);
		return result != null ? result : Optional.empty();
	}

	private record CacheEntry(CompletableFuture<Optional<Lyrics>> future, long started) {}

	/** Returns null if every request failed (network error), so the caller retries later. */
	private Optional<Lyrics> fetch(MediaSession.Track track) {
		boolean anyAnswer = false;
		for (String[] q : queries(track)) {
			try {
				Lyrics lyrics = lookup(q[0], q[1], q[2], track.durationMs());
				anyAnswer = true;
				if (lyrics != null) return Optional.of(lyrics);
			} catch (Exception e) {
				AfkCinematicClient.LOGGER.debug("LRCLIB-Anfrage fehlgeschlagen", e);
			}
		}
		return anyAnswer ? Optional.empty() : null;
	}

	/** Candidate (artist, title, album) triples, most exact first. */
	private static List<String[]> queries(MediaSession.Track t) {
		List<String[]> out = new ArrayList<>();
		String artist = cleanArtist(t.artist());
		String title = clean(t.title());
		out.add(new String[]{t.artist(), t.title(), t.album()});
		if (!artist.equals(t.artist()) || !title.equals(t.title())) out.add(new String[]{artist, title, ""});
		// "Artist - Title" as video title (YouTube); the "artist" is often just the channel name.
		int dash = title.indexOf(" - ");
		if (dash > 0) {
			out.add(new String[]{title.substring(0, dash).trim(), clean(title.substring(dash + 3)), ""});
		}
		// Several artists: try the first one only.
		String first = artist.split("\\s*(,|&| x | feat\\.? | ft\\.? )\\s*")[0];
		if (!first.equals(artist) && !first.isBlank()) out.add(new String[]{first, title, ""});
		return out;
	}

	private static String clean(String title) {
		String s = title, prev;
		do {
			prev = s;
			s = JUNK.matcher(s).replaceAll("").trim();
		} while (!s.equals(prev));
		return s;
	}

	private static String cleanArtist(String artist) {
		return artist.replaceAll("(?i)\\s*-\\s*topic$", "").replaceAll("(?i)vevo$", "").trim();
	}

	private Lyrics lookup(String artist, String title, String album, long durationMs) throws Exception {
		if (title.isBlank()) return null;
		// 1. Exact lookup (LRCLIB matches duration within ±2 s).
		StringBuilder url = new StringBuilder(API + "get?track_name=").append(enc(title))
				.append("&artist_name=").append(enc(artist));
		if (!album.isBlank()) url.append("&album_name=").append(enc(album));
		if (durationMs > 0) url.append("&duration=").append(Math.round(durationMs / 1000.0));
		JsonElement exact = request(url.toString());
		if (exact != null && exact.isJsonObject()) {
			Lyrics l = LrcParser.parse(str(exact.getAsJsonObject(), "syncedLyrics"));
			if (l != null) return l;
		}

		// 2. Search, prefer the synced result with the closest duration.
		String search = API + "search?track_name=" + enc(title) + (artist.isBlank() ? "" : "&artist_name=" + enc(artist));
		JsonElement results = request(search);
		if (results == null || !results.isJsonArray()) return null;
		JsonArray arr = results.getAsJsonArray();
		JsonObject best = null;
		double bestDiff = Double.MAX_VALUE;
		for (JsonElement e : arr) {
			if (!e.isJsonObject()) continue;
			JsonObject o = e.getAsJsonObject();
			if (str(o, "syncedLyrics") == null) continue;
			double diff = 0;
			if (durationMs > 0 && o.has("duration") && !o.get("duration").isJsonNull()) {
				diff = Math.abs(o.get("duration").getAsDouble() - durationMs / 1000.0);
				// A different length usually means a different version: lyrics would be out of sync.
				if (diff > 8) continue;
			}
			if (diff < bestDiff) {
				bestDiff = diff;
				best = o;
			}
		}
		return best == null ? null : LrcParser.parse(str(best, "syncedLyrics"));
	}

	private JsonElement request(String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", USER_AGENT)
				.GET()
				.build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (res.statusCode() != 200) return null;
		return JsonParser.parseString(res.body());
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}
}
