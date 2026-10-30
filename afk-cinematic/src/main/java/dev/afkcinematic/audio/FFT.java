package dev.afkcinematic.audio;

/** Minimal in-place radix-2 FFT (Cooley–Tukey). Size must be a power of two. */
final class FFT {
	private final int n;
	private final double[] cos, sin;
	private final int[] reversed;

	FFT(int n) {
		if (Integer.bitCount(n) != 1) throw new IllegalArgumentException("FFT size must be a power of two");
		this.n = n;
		cos = new double[n / 2];
		sin = new double[n / 2];
		for (int i = 0; i < n / 2; i++) {
			cos[i] = Math.cos(-2 * Math.PI * i / n);
			sin[i] = Math.sin(-2 * Math.PI * i / n);
		}
		reversed = new int[n];
		int bits = Integer.numberOfTrailingZeros(n);
		for (int i = 0; i < n; i++) reversed[i] = Integer.reverse(i) >>> (32 - bits);
	}

	/** Transforms {@code re}/{@code im} in place. */
	void transform(double[] re, double[] im) {
		for (int i = 0; i < n; i++) {
			int j = reversed[i];
			if (j > i) {
				double t = re[i]; re[i] = re[j]; re[j] = t;
				t = im[i]; im[i] = im[j]; im[j] = t;
			}
		}
		for (int size = 2; size <= n; size <<= 1) {
			int half = size / 2, step = n / size;
			for (int start = 0; start < n; start += size) {
				for (int k = 0; k < half; k++) {
					int a = start + k, b = a + half;
					double c = cos[k * step], s = sin[k * step];
					double tr = re[b] * c - im[b] * s;
					double ti = re[b] * s + im[b] * c;
					re[b] = re[a] - tr;
					im[b] = im[a] - ti;
					re[a] += tr;
					im[a] += ti;
				}
			}
		}
	}
}
