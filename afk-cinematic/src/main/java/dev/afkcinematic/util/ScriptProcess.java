package dev.afkcinematic.util;

import dev.afkcinematic.AfkCinematicClient;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Runs one of the bundled PowerShell helper scripts (Windows only) in the background.
 * <p>
 * The script is extracted from the mod jar to the temp folder and started hidden. Its stdout is handed to a
 * reader on a dedicated daemon thread, never the render thread. If the script dies it is restarted, but at
 * most every 10 seconds. Closing our end of its stdin tells the script to exit.
 */
public final class ScriptProcess {
	public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	private static final long RESTART_DELAY_MS = 10_000;

	private final String scriptName;
	private final Consumer<InputStream> reader;
	private Process process;
	private long nextStart;

	/**
	 * @param scriptName file name below {@code assets/afkcinematic/scripts/}
	 * @param reader     consumes the script's stdout until it ends; runs on its own thread
	 */
	public ScriptProcess(String scriptName, Consumer<InputStream> reader) {
		this.scriptName = scriptName;
		this.reader = reader;
	}

	/** Starts the script if it isn't running (and the restart delay has passed). Cheap to call every tick. */
	public synchronized void ensureRunning() {
		if (!WINDOWS) return;
		if (process != null && process.isAlive()) return;
		long now = System.currentTimeMillis();
		if (now < nextStart) return;
		nextStart = now + RESTART_DELAY_MS;
		try {
			Path script = extract();
			ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
					"-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString());
			pb.redirectError(ProcessBuilder.Redirect.DISCARD);
			Process started = pb.start();
			process = started;
			Thread t = new Thread(() -> {
				try (InputStream in = started.getInputStream()) {
					reader.accept(in);
				} catch (Exception e) {
					AfkCinematicClient.LOGGER.debug("{} beendet", scriptName, e);
				}
			}, "afkcinematic-" + scriptName);
			t.setDaemon(true);
			t.start();
		} catch (Exception e) {
			AfkCinematicClient.LOGGER.warn("{} konnte nicht gestartet werden", scriptName, e);
		}
	}

	public synchronized boolean isRunning() {
		return process != null && process.isAlive();
	}

	/** Stops the script: closing stdin makes it exit on its own, destroy() is the safety net. */
	public synchronized void stop() {
		Process p = process;
		process = null;
		nextStart = 0;
		if (p == null) return;
		try {
			p.getOutputStream().close();
		} catch (Exception ignored) {
			// already gone
		}
		p.destroy();
	}

	private Path extract() throws Exception {
		Path dir = Path.of(System.getProperty("java.io.tmpdir"), "afkcinematic");
		Files.createDirectories(dir);
		Path script = dir.resolve(scriptName);
		try (InputStream in = ScriptProcess.class.getResourceAsStream("/assets/afkcinematic/scripts/" + scriptName)) {
			if (in == null) throw new IllegalStateException(scriptName + " fehlt im Mod-Jar");
			Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
		}
		return script;
	}
}
