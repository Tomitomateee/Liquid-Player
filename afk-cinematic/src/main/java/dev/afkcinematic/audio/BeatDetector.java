package dev.afkcinematic.audio;

import dev.afkcinematic.config.CinematicConfig;

import java.util.ArrayDeque;

/**
 * Beat detection on the system audio. Runs entirely on the audio reader thread; the render thread only
 * reads the volatile results.
 * <p>
 * How it works:
 * <ol>
 *   <li>Samples (mono, ~11 kHz) are collected; every {@value #HOP} new samples a {@value #SIZE}-point
 *       Hann-windowed FFT is computed (~23 ms hop, ~11 Hz resolution).</li>
 *   <li><b>Onsets:</b> the spectral flux of the bass band (40–150 Hz) – the sum of positive changes of the
 *       log magnitudes – is compared with an adaptive threshold (mean + k·σ of the last ~1.5 s).
 *       A local peak above the threshold, at least 280 ms after the previous one, is a beat.</li>
 *   <li><b>BPM:</b> intervals between recent onsets are folded into 80–170 BPM and collected in a
 *       smoothed histogram; its peak is the tempo estimate.</li>
 *   <li><b>Drops:</b> a short-term loudness average that suddenly jumps well above the long-term one
 *       (after a calmer part) counts as a drop.</li>
 * </ol>
 */
public final class BeatDetector {
	static final int SIZE = 1024;
	static final int HOP = 256;
	private static final double BASS_LOW_HZ = 40, BASS_HIGH_HZ = 150;
	private static final double MIN_BEAT_INTERVAL = 0.28;
	private static final double FLUX_HISTORY_SECONDS = 1.5;
	private static final double THRESHOLD_SIGMA = 1.3;

	private final FFT fft = new FFT(SIZE);
	private final double[] window = new double[SIZE];
	private final double[] ring = new double[SIZE];
	private final double[] re = new double[SIZE], im = new double[SIZE];
	private double[] prevLogMag = new double[SIZE / 2];
	private int ringPos, sinceHop;
	private int sampleRate = 11025;
	/** Audio time in seconds (counts processed samples, independent of the wall clock). */
	private double time;

	private final ArrayDeque<double[]> fluxHistory = new ArrayDeque<>(); // {time, flux}
	private final ArrayDeque<Double> onsets = new ArrayDeque<>();
	private double prevFlux, prevPrevFlux;
	private double lastBeatTime = -10;
	private double shortEnergy, longEnergy, rmsSmooth, peakEnergy = 1e-4;
	private double lastDropTime = -100, calmSince = -1;
	private double musicSince = -1, silentSince = 0;

	// --- Results, read by the render thread ---
	private volatile boolean musicPlaying;
	private volatile float bpm = 120;
	private volatile long beatCount;
	private volatile long dropCount;
	/** Smoothed loudness relative to the recent maximum, 0..1. */
	private volatile float energy;
	private volatile long lastDataNanos;

	public BeatDetector() {
		for (int i = 0; i < SIZE; i++) window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (SIZE - 1));
	}

	/** True while audio above the threshold arrives (with a short hold so pauses between songs don't flicker). */
	public boolean musicPlaying() {
		return musicPlaying && System.nanoTime() - lastDataNanos < 400_000_000L;
	}

	public float bpm() {
		return bpm;
	}

	/** Increases by one on every detected beat. */
	public long beatCount() {
		return beatCount;
	}

	/** Increases by one on every detected drop / sudden loud part. */
	public long dropCount() {
		return dropCount;
	}

	public float energy() {
		return musicPlaying() ? energy : 0f;
	}

	/** Feeds one block of samples. Called by the reader thread only. */
	void process(float[] samples, int rate) {
		if (rate != sampleRate) {
			sampleRate = rate;
			prevLogMag = new double[SIZE / 2];
		}
		lastDataNanos = System.nanoTime();
		for (float s : samples) {
			ring[ringPos] = s;
			ringPos = (ringPos + 1) % SIZE;
			if (++sinceHop == HOP) {
				sinceHop = 0;
				time += HOP / (double) sampleRate;
				analyzeFrame();
			}
		}
	}

	private void analyzeFrame() {
		double sumSq = 0;
		for (int i = 0; i < SIZE; i++) {
			double v = ring[(ringPos + i) % SIZE];
			if (i >= SIZE - HOP) sumSq += v * v;
			re[i] = v * window[i];
			im[i] = 0;
		}
		double rms = Math.sqrt(sumSq / HOP);
		fft.transform(re, im);

		double binHz = sampleRate / (double) SIZE;
		int lo = Math.max(1, (int) Math.floor(BASS_LOW_HZ / binHz));
		int hi = Math.min(SIZE / 2 - 1, (int) Math.ceil(BASS_HIGH_HZ / binHz));
		double flux = 0, bassEnergy = 0;
		for (int k = lo; k <= hi; k++) {
			double mag = Math.hypot(re[k], im[k]);
			bassEnergy += mag * mag;
			// Log compression makes the detection work at any volume.
			double logMag = Math.log1p(mag * 10);
			flux += Math.max(0, logMag - prevLogMag[k]);
			prevLogMag[k] = logMag;
		}

		updateMusicState(rms);
		updateEnergy(rms, Math.sqrt(bassEnergy) / SIZE);
		detectOnset(flux);
	}

	private void updateMusicState(double rms) {
		double dt = HOP / (double) sampleRate;
		rmsSmooth += (rms - rmsSmooth) * (1 - Math.exp(-dt / 0.3));
		boolean loud = rmsSmooth > CinematicConfig.get().musicThreshold;
		if (loud) {
			silentSince = -1;
			if (musicSince < 0) musicSince = time;
			// Needs ~1 s of sound before we call it music (no beat effects on a single click).
			if (time - musicSince > 1.0) musicPlaying = true;
		} else {
			musicSince = -1;
			if (silentSince < 0) silentSince = time;
			if (time - silentSince > 1.5) musicPlaying = false;
		}
	}

	private void updateEnergy(double rms, double bass) {
		double dt = HOP / (double) sampleRate;
		double level = rms + bass * 2;
		shortEnergy += (level - shortEnergy) * (1 - Math.exp(-dt / 0.4));
		longEnergy += (level - longEnergy) * (1 - Math.exp(-dt / 6.0));
		peakEnergy = Math.max(shortEnergy, peakEnergy * Math.exp(-dt / 20.0));
		energy = (float) Math.min(1, shortEnergy / Math.max(1e-4, peakEnergy));

		// A drop: after a calmer stretch, the short-term level jumps far above the long-term average.
		if (shortEnergy < longEnergy * 0.8) {
			if (calmSince < 0) calmSince = time;
		} else if (shortEnergy > longEnergy * 1.6 && musicPlaying && time - lastDropTime > 6
				&& calmSince >= 0 && time - calmSince > 1.5) {
			lastDropTime = time;
			calmSince = -1;
			dropCount++;
		}
	}

	private void detectOnset(double flux) {
		fluxHistory.addLast(new double[]{time, flux});
		while (!fluxHistory.isEmpty() && time - fluxHistory.peekFirst()[0] > FLUX_HISTORY_SECONDS) fluxHistory.removeFirst();

		double mean = 0;
		for (double[] f : fluxHistory) mean += f[1];
		mean /= fluxHistory.size();
		double var = 0;
		for (double[] f : fluxHistory) var += (f[1] - mean) * (f[1] - mean);
		double threshold = mean + THRESHOLD_SIGMA * Math.sqrt(var / fluxHistory.size());

		// The previous frame is a beat if it is a local maximum above the adaptive threshold.
		double beatTime = time - HOP / (double) sampleRate;
		if (musicPlaying && prevFlux > threshold && prevFlux > prevPrevFlux && prevFlux >= flux
				&& prevFlux > 0.05 && beatTime - lastBeatTime > MIN_BEAT_INTERVAL) {
			lastBeatTime = beatTime;
			beatCount++;
			onsets.addLast(beatTime);
			estimateTempo();
		}
		prevPrevFlux = prevFlux;
		prevFlux = flux;
	}

	private void estimateTempo() {
		while (!onsets.isEmpty() && time - onsets.peekFirst() > 10) onsets.removeFirst();
		if (onsets.size() < 6) return;
		double[] histogram = new double[171];
		Double[] times = onsets.toArray(new Double[0]);
		for (int i = 0; i < times.length; i++) {
			for (int j = i + 1; j < times.length && j <= i + 4; j++) {
				double interval = times[j] - times[i];
				if (interval < 0.25 || interval > 3) continue;
				double candidate = 60 / interval;
				// Fold into a typical range: 70 BPM -> 140, 200 BPM -> 100.
				while (candidate < 80) candidate *= 2;
				while (candidate > 170) candidate /= 2;
				// Closer pairs (consecutive beats) count more than pairs that skip beats.
				double weight = 1.0 / (j - i);
				for (int b = 80; b <= 170; b++) {
					double d = b - candidate;
					histogram[b] += weight * Math.exp(-d * d / 4);
				}
			}
		}
		int best = 120;
		for (int b = 80; b <= 170; b++) if (histogram[b] > histogram[best]) best = b;
		bpm += (best - bpm) * 0.25f;
	}

	/** Called when the audio stream is gone (e.g. bridge restarted). */
	void reset() {
		musicPlaying = false;
		onsets.clear();
		fluxHistory.clear();
		musicSince = -1;
	}
}
