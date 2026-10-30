package dev.afkcinematic.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses LRC lyrics: {@code [mm:ss.xx]text}. A line may carry several time stamps
 * ({@code [00:12.00][01:30.50]chorus}); {@code [offset:+/-ms]} shifts all lines.
 */
public final class LrcParser {
	private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
	private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)]", Pattern.CASE_INSENSITIVE);

	private LrcParser() {}

	/** Returns null if the text contains no timed lines. */
	public static Lyrics parse(String lrc) {
		if (lrc == null || lrc.isBlank()) return null;
		long offset = 0;
		List<Lyrics.Line> lines = new ArrayList<>();
		for (String raw : lrc.split("\\r?\\n")) {
			Matcher off = OFFSET.matcher(raw);
			if (off.find()) {
				// Positive offset = lyrics come earlier.
				offset = Long.parseLong(off.group(1));
				continue;
			}
			Matcher m = TIME.matcher(raw);
			List<Long> times = new ArrayList<>();
			int textStart = 0;
			while (m.find() && m.start() == textStart) {
				long min = Long.parseLong(m.group(1));
				long sec = Long.parseLong(m.group(2));
				long frac = 0;
				if (m.group(3) != null) {
					String f = m.group(3);
					// ".5" = 500 ms, ".05" = 50 ms, ".005" = 5 ms
					frac = Long.parseLong(f) * (f.length() == 1 ? 100 : f.length() == 2 ? 10 : 1);
				}
				times.add(min * 60_000 + sec * 1000 + frac);
				textStart = m.end();
			}
			if (times.isEmpty()) continue;
			String text = raw.substring(textStart).trim();
			for (long t : times) lines.add(new Lyrics.Line(Math.max(0, t - offset), text));
		}
		if (lines.isEmpty()) return null;
		lines.sort(Comparator.comparingLong(Lyrics.Line::timeMs));
		return new Lyrics(List.copyOf(lines));
	}
}
