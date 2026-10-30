package dev.afkcinematic.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.afkcinematic.util.ScriptProcess;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * What the PC is playing right now, read from the Windows media session (SMTC) by the bridge script.
 * Works with every player that shows up in the Windows volume flyout: Spotify, browsers (YouTube), etc.
 */
public final class MediaSession {
	/** One update from the bridge. {@code receivedAt} is {@link System#nanoTime()} on arrival. */
	public record Track(String title, String artist, String album, boolean playing,
	                    long positionMs, long durationMs, long receivedAt) {
		/** Identifies the song, used as cache key for lyrics. */
		public String key() {
			return artist + "\u0000" + title;
		}

		/** Position extrapolated to now (the bridge only reports every ~250 ms). */
		public long positionNow() {
			long pos = positionMs;
			if (playing) pos += (System.nanoTime() - receivedAt) / 1_000_000L;
			return durationMs > 0 ? Math.min(pos, durationMs) : pos;
		}
	}

	private final ScriptProcess process = new ScriptProcess("media-session.ps1", this::readLoop);
	private volatile Track track;

	/** The current track, or null if nothing is playing / no session exists. */
	public Track track() {
		return track;
	}

	public void ensureRunning() {
		process.ensureRunning();
	}

	public void stop() {
		process.stop();
		track = null;
	}

	private void readLoop(InputStream in) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				try {
					track = parse(JsonParser.parseString(line).getAsJsonObject());
				} catch (Exception ignored) {
					// garbled line
				}
			}
		} catch (Exception ignored) {
			// bridge exited
		}
		track = null;
	}

	private static Track parse(JsonObject o) {
		if (!o.has("ok") || !o.get("ok").getAsBoolean()) return null;
		return new Track(str(o, "title"), str(o, "artist"), str(o, "album"),
				o.has("playing") && o.get("playing").getAsBoolean(),
				o.has("pos") ? o.get("pos").getAsLong() : 0,
				o.has("dur") ? o.get("dur").getAsLong() : 0,
				System.nanoTime());
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}
}
