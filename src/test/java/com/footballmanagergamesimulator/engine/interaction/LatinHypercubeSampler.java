package com.footballmanagergamesimulator.engine.interaction;

import java.util.Random;

/**
 * Latin Hypercube Sampling — generates an {@code N × k} matrix where each
 * column is a uniform sample over [0, 1] with the extra property that the
 * range is split into N equal bins and exactly one sample lands in each bin
 * (per column). This gives <b>much</b> better space-filling coverage than
 * i.i.d. uniform sampling at the same N.
 *
 * <p>Used as the base sampler for the Saltelli scheme in
 * {@link InteractionAnalyzer}: pure i.i.d. would need N→∞ to converge Sobol
 * indices; LHS gives usable estimates at N=128-256.
 *
 * <p>To map an LHS sample {@code u ∈ [0, 1]} onto a parameter's range,
 * caller does {@code value = param.min() + u * (param.max() − param.min())}
 * followed by {@code param.quantize(value)} so the candidate stays on the
 * tuner's grid.
 */
public final class LatinHypercubeSampler {

    private LatinHypercubeSampler() {}

    /**
     * Draw {@code n} samples in {@code k}-dimensional unit hypercube. Each
     * column {@code j} is independently permuted (so columns are uncorrelated)
     * and each cell is jittered uniformly within its bin (so the sample is
     * truly continuous, not just on a grid).
     */
    public static double[][] sample(int n, int k, Random rng) {
        double[][] out = new double[n][k];
        for (int col = 0; col < k; col++) {
            int[] perm = randomPermutation(n, rng);
            for (int row = 0; row < n; row++) {
                // bin [perm[row]/n, (perm[row]+1)/n) + jitter inside
                double u = (perm[row] + rng.nextDouble()) / n;
                out[row][col] = u;
            }
        }
        return out;
    }

    private static int[] randomPermutation(int n, Random rng) {
        int[] p = new int[n];
        for (int i = 0; i < n; i++) p[i] = i;
        // Fisher-Yates shuffle in place
        for (int i = n - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = p[i]; p[i] = p[j]; p[j] = tmp;
        }
        return p;
    }
}
