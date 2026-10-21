package dev.spotifyglass;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Reads what Spotify is playing from the Windows media session (the same data
 * the volume flyout shows) via a small PowerShell helper, and sends play/pause/skip back.
 */
public final class MediaBridge {
	/** Latest snapshot from Windows; {@code ok} is false when Spotify isn't running. */
	public record Snapshot(boolean ok, String title, String artist, String album, boolean playing,
	                       long positionMs, long durationMs, long receivedAt, boolean shuffle,
	                       String repeat, String thumbPath) {
		static final Snapshot NONE = new Snapshot(false, "", "", "", false, 0, 0, 0, false, "off", "");
	}

	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

	private volatile Snapshot latest = Snapshot.NONE;
	private volatile float peak = -1;
	private volatile long peakAt;
	private volatile float volume = -1;
	private volatile Process process;
	private volatile Writer stdin;
	private long nextStart;

	public static boolean supported() {
		return WINDOWS;
	}

	/** Returns the latest snapshot, (re)starting the helper process if needed. */
	public Snapshot latest() {
		ensureRunning();
		return latest;
	}

	/** Spotify's current output peak (0..1, depends on its volume), or -1 if unknown. */
	public float peak() {
		return System.currentTimeMillis() - peakAt < 500 ? peak : -1;
	}

	/** Spotify's volume in the Windows volume mixer (0..1), or -1 if unknown. */
	public float volume() {
		return System.currentTimeMillis() - peakAt < 1500 ? volume : -1;
	}

	/** Sends a command: toggle, play, pause, next, prev, seek &lt;ms&gt;, shuffle &lt;bool&gt;, repeat None|Track|List. */
	public synchronized void send(String command) {
		ensureRunning();
		Writer w = stdin;
		if (w == null) return;
		try {
			w.write(command + "\n");
			w.flush();
		} catch (Exception e) {
			SpotifyGlassClient.LOGGER.debug("Media-Bridge: Befehl fehlgeschlagen", e);
		}
	}

	private synchronized void ensureRunning() {
		if (!WINDOWS) return;
		Process p = process;
		if (p != null && p.isAlive()) return;
		long now = System.currentTimeMillis();
		if (now < nextStart) return;
		nextStart = now + 10_000; // don't respawn more often than every 10 s
		try {
			Path script = extractScript();
			ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
					"-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString());
			pb.redirectError(ProcessBuilder.Redirect.DISCARD);
			Process started = pb.start();
			process = started;
			stdin = new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8);
			Thread reader = new Thread(() -> readLoop(started), "liquidplayer-media");
			reader.setDaemon(true);
			reader.start();
		} catch (Exception e) {
			SpotifyGlassClient.LOGGER.warn("Media-Bridge konnte nicht gestartet werden", e);
		}
	}

	private void readLoop(Process p) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				try {
					JsonObject o = JsonParser.parseString(line).getAsJsonObject();
					if (o.has("peak")) {
						peak = o.get("peak").getAsFloat();
						peakAt = System.currentTimeMillis();
						if (o.has("vol")) volume = o.get("vol").getAsFloat();
					} else {
						latest = parse(o);
					}
				} catch (Exception ignored) {
					// partial/garbled line
				}
			}
		} catch (Exception e) {
			SpotifyGlassClient.LOGGER.debug("Media-Bridge beendet", e);
		}
		latest = Snapshot.NONE;
	}

	private static Snapshot parse(JsonObject o) {
		if (!o.has("ok") || !o.get("ok").getAsBoolean()) return Snapshot.NONE;
		return new Snapshot(true, str(o, "title"), str(o, "artist"), str(o, "album"),
				o.has("playing") && o.get("playing").getAsBoolean(),
				o.has("pos") ? o.get("pos").getAsLong() : 0,
				o.has("dur") ? o.get("dur").getAsLong() : 0,
				System.currentTimeMillis(),
				o.has("shuffle") && o.get("shuffle").getAsBoolean(),
				str(o, "repeat"), str(o, "thumb"));
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	private static Path extractScript() throws Exception {
		Path dir = Path.of(System.getProperty("java.io.tmpdir"), "liquidplayer");
		Files.createDirectories(dir);
		Path script = dir.resolve("media-bridge.ps1");
		try (InputStream in = MediaBridge.class.getResourceAsStream("/assets/spotifyglass/media-bridge.ps1")) {
			if (in == null) throw new IllegalStateException("media-bridge.ps1 fehlt im Mod-Jar");
			Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
		}
		return script;
	}
}
