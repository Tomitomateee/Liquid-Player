package dev.spotifyglass;

import com.google.gson.JsonArray;
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

/**
 * The cover from Windows only exists as a local file, which other players can't load.
 * This finds a public cover URL for the song via Apple's free iTunes Search API.
 */
public final class ArtworkLookup {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

	private ArtworkLookup() {}

	/** Returns a public cover URL, or "" while unknown/loading. */
	public static String get(String title, String artist) {
		if (title == null || title.isBlank()) return "";
		String key = title + "\u0000" + artist;
		String cached = CACHE.get(key);
		if (cached != null) return cached;
		CACHE.put(key, "");
		if (CACHE.size() > 200) CACHE.clear();
		String term = cleanTitle(title) + " " + (artist.contains(",") ? artist.substring(0, artist.indexOf(',')) : artist);
		HttpRequest req = HttpRequest.newBuilder(URI.create("https://itunes.apple.com/search?media=music&entity=song&limit=1&term="
						+ URLEncoder.encode(term.trim(), StandardCharsets.UTF_8)))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", "LiquidPlayer/1.0 (Minecraft mod)")
				.GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(res -> {
			try {
				JsonArray results = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("results");
				if (results.isEmpty()) return;
				JsonObject first = results.get(0).getAsJsonObject();
				String url = first.get("artworkUrl100").getAsString().replace("100x100bb", "300x300bb");
				CACHE.put(key, url);
			} catch (Exception ignored) {
				// no cover then
			}
		});
		return "";
	}

	/** "Song (feat. X) - Remastered 2011" → "Song", which matches far more reliably. */
	private static String cleanTitle(String t) {
		return t.replaceAll("\\s*[(\\[].*?[)\\]]", "").replaceAll("\\s+-\\s+.*$", "").trim();
	}
}
