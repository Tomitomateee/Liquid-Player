package dev.afkcinematic.lyrics;

import java.util.List;

/** Time-stamped lyrics of one song, sorted by time. */
public record Lyrics(List<Line> lines) {
	public record Line(long timeMs, String text) {}

	/** Index of the line that is sung at {@code positionMs}, or -1 before the first line. */
	public int indexAt(long positionMs) {
		int lo = 0, hi = lines.size() - 1, found = -1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (lines.get(mid).timeMs() <= positionMs) {
				found = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		return found;
	}
}
