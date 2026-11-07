package com.tony.angulo;

/* Detector de silbido (mismo algoritmo que la versión web).
   Busca un tono casi puro entre 600 y 3500 Hz, sin armónicos, continuo ~0,6 s y que cambie de nota.
   Recibe audio con feed() y se evalúa cada 40 ms de audio. Sin dependencias de Android (se prueba en el PC). */
public class Detector {
    public interface Listener { void onWhistle(long tMs); }

    // ajustes (iguales que en index.html)
    double lo = 600, hi = 3500, ratioMin = 17, harmMin = 11, subMin = 6, minDb = -80, need = 0.5, spread = 1.10;
    long winMs = 1500, runMs = 900;

    static final int N = 4096;
    final int sr;
    final float[] ring = new float[N];
    int pos = 0, sinceFrame = 0;
    final int hop;
    final double[] win = new double[N], smooth = new double[N / 2], db = new double[N / 2];
    final double[] re = new double[N], im = new double[N];
    long nowMs = 0;
    boolean paused = false;
    public boolean lastOk = false;
    Listener listener;

    // historial circular de fotogramas
    final long[] ht = new long[256]; final boolean[] hok = new boolean[256]; final double[] hf = new double[256];
    int hs = 0, hn = 0;

    public Detector(int sampleRate, Listener l) {
        sr = sampleRate; listener = l; hop = sampleRate * 40 / 1000;
        for (int i = 0; i < N; i++) win[i] = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / N) + 0.08 * Math.cos(4 * Math.PI * i / N);
    }

    public void pause(boolean v) { if (paused != v) { paused = v; hn = 0; } }

    /** muestras en [-1,1] */
    public void feed(float[] x, int n) {
        for (int i = 0; i < n; i++) {
            ring[pos] = x[i]; pos = (pos + 1) % N;
            if (++sinceFrame >= hop) { sinceFrame = 0; nowMs += 40; frame(); }
        }
    }

    void fft() {
        int n = N;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
    }

    double maxAround(int c, int w) {
        double m = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(1, c - w); i <= Math.min(N / 2 - 1, c + w); i++) if (db[i] > m) m = db[i];
        return m;
    }

    void frame() {
        for (int i = 0; i < N; i++) { re[i] = ring[(pos + i) % N] * win[i]; im[i] = 0; }
        fft();
        for (int k = 0; k < N / 2; k++) {
            double mag = Math.hypot(re[k], im[k]) / N;
            smooth[k] = 0.15 * smooth[k] + 0.85 * mag;
            db[k] = 20 * Math.log10(smooth[k] + 1e-30);
        }
        double hz = (double) sr / N;
        int l = (int) Math.round(lo / hz), h = (int) Math.round(hi / hz), p = l;
        for (int i = l; i <= h; i++) if (db[i] > db[p]) p = i;
        double s = 0; int n = 0;
        for (int i = l; i <= h; i++) if (Math.abs(i - p) > 5) { s += Math.pow(10, db[i] / 10); n++; }
        double ratio = db[p] - 10 * Math.log10(s / n + 1e-30);
        double h2 = 2 * p < N / 2 - 3 ? db[p] - maxAround(2 * p, 3) : 99;
        double sub = db[p] - maxAround((int) Math.round(p / 2.0), 3), sub3 = db[p] - maxAround((int) Math.round(p / 3.0), 3);
        double a = db[p - 1], b = db[p], c = db[p + 1], den = a - 2 * b + c, off = den != 0 ? (a - c) / (2 * den) : 0;
        double f = (p + (Double.isFinite(off) ? off : 0)) * hz;
        boolean ok = db[p] > minDb && ratio >= ratioMin && h2 >= harmMin && sub >= subMin && sub3 >= subMin;
        lastOk = ok;
        // historial
        int idx = (hs + hn) % 256; ht[idx] = nowMs; hok[idx] = ok; hf[idx] = f;
        if (hn < 256) hn++; else hs = (hs + 1) % 256;
        while (hn > 0 && ht[hs] < nowMs - winMs) { hs = (hs + 1) % 256; hn--; }
        if (paused || hn == 0) return;
        long span = nowMs - ht[hs];
        int good = 0; for (int i = 0; i < hn; i++) if (hok[(hs + i) % 256]) good++;
        if (span < winMs * 0.9 || good < hn * need) return;
        long best = 0, st = -1; int miss = 0;
        for (int i = 0; i < hn; i++) {
            int j = (hs + i) % 256;
            if (hok[j]) { if (st < 0) st = ht[j]; miss = 0; best = Math.max(best, ht[j] - st); }
            else if (st >= 0 && ++miss > 2) { st = -1; miss = 0; }
        }
        if (best < runMs) return;
        double[] fs = new double[good]; int g = 0;
        for (int i = 0; i < hn; i++) { int j = (hs + i) % 256; if (hok[j]) fs[g++] = hf[j]; }
        java.util.Arrays.sort(fs);
        if (fs[(int) (good * 0.9)] / fs[(int) (good * 0.1)] < spread) return;
        hn = 0;
        if (listener != null) listener.onWhistle(nowMs);
    }
}
