package dev.afkcinematic.audio;

import dev.afkcinematic.util.ScriptProcess;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Listens to the PC's system audio (what you hear from Spotify, YouTube, ...) through the WASAPI loopback
 * bridge script and feeds it into the {@link BeatDetector}. Reading and analysis run on the bridge's
 * reader thread.
 */
public final class LoopbackAudio {
	private static final int MAGIC = 0x41464B41;
	private static final int MAX_SAMPLES = 1 << 16;

	private final BeatDetector detector = new BeatDetector();
	private final ScriptProcess process = new ScriptProcess("audio-loopback.ps1", this::readLoop);

	public BeatDetector detector() {
		return detector;
	}

	public void ensureRunning() {
		process.ensureRunning();
	}

	public void stop() {
		process.stop();
		detector.reset();
	}

	private void readLoop(InputStream raw) {
		DataInputStream in = new DataInputStream(raw);
		byte[] header = new byte[12];
		ByteBuffer headerBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
		byte[] payload = new byte[0];
		try {
			while (true) {
				in.readFully(header);
				headerBuf.rewind();
				if (headerBuf.getInt() != MAGIC) {
					resync(in);
					continue;
				}
				int rate = headerBuf.getInt();
				int count = headerBuf.getInt();
				if (rate < 1000 || rate > 200_000 || count <= 0 || count > MAX_SAMPLES) {
					resync(in);
					continue;
				}
				if (payload.length < count * 4) payload = new byte[count * 4];
				in.readFully(payload, 0, count * 4);
				ByteBuffer data = ByteBuffer.wrap(payload, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN);
				float[] samples = new float[count];
				for (int i = 0; i < count; i++) samples[i] = data.getFloat();
				detector.process(samples, rate);
			}
		} catch (EOFException e) {
			// bridge exited
		} catch (Exception e) {
			// stream closed
		}
		detector.reset();
	}

	/** Skips bytes until the next frame magic (only needed if the stream ever gets out of step). */
	private static void resync(DataInputStream in) throws Exception {
		int window = 0;
		while (true) {
			int b = in.read();
			if (b < 0) throw new EOFException();
			// Magic is written little endian: 41 4B 46 41.
			window = (window >>> 8) | (b << 24);
			if (window == MAGIC) {
				// Push the magic back by reading the rest of the header in the caller: easier to just
				// skip this frame's rate+count+payload.
				byte[] rest = new byte[8];
				in.readFully(rest);
				ByteBuffer bb = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
				bb.getInt();
				int count = bb.getInt();
				if (count > 0 && count <= MAX_SAMPLES) in.skipNBytes(count * 4L);
				return;
			}
		}
	}
}
