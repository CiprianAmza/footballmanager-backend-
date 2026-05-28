package com.footballmanagergamesimulator.engine.interaction;

/**
 * Sobol variance-decomposition indices, estimated via the Jansen (1999)
 * formulas. These give better small-sample bias than the original Saltelli
 * estimators.
 *
 * <h2>What the indices mean</h2>
 * <ul>
 *   <li><b>S1_i</b> — first-order index: fraction of total output variance
 *       caused by parameter <i>i</i> acting <b>alone</b>. {@code 0 = parameter
 *       has no main effect}, {@code 1 = parameter alone explains everything}.</li>
 *   <li><b>ST_i</b> — total-order index: fraction of variance caused by
 *       parameter <i>i</i> AND any interaction it participates in. By
 *       definition {@code ST_i ≥ S1_i}.</li>
 *   <li><b>ST_i − S1_i</b> — interaction strength: how much of the variance
 *       comes from <i>i</i>'s interactions with other parameters. Large
 *       value = parameter doesn't act independently; its effect changes
 *       depending on other knobs' values.</li>
 * </ul>
 *
 * <h2>Saltelli sampling scheme</h2>
 * Caller supplies:
 * <ul>
 *   <li>{@code yA[N]} — model outputs for the "base" sample matrix A</li>
 *   <li>{@code yB[N]} — outputs for an independent matrix B</li>
 *   <li>{@code yC[k][N]} — for each parameter i, outputs for matrix C_i which
 *       takes column i from B and all other columns from A</li>
 * </ul>
 * Total evaluations: {@code N(k+2)}.
 *
 * <h2>References</h2>
 * <ul>
 *   <li>Saltelli et al. 2010 — "Variance based sensitivity analysis of model
 *       output: Design and estimator for the total sensitivity index"</li>
 *   <li>Jansen 1999 — original estimators for Si and ST_i</li>
 * </ul>
 */
public final class SobolEstimator {

    private SobolEstimator() {}

    /**
     * Estimate first- and total-order indices for one scalar output channel.
     *
     * @param yA  outputs from base matrix A, length N
     * @param yB  outputs from permutation matrix B, length N
     * @param yC  outputs from C_i for each parameter i (yC[i] has length N)
     * @return    one {@link Indices} per parameter, in the same order as yC
     */
    public static Indices[] estimateJansen(double[] yA, double[] yB, double[][] yC) {
        int n = yA.length;
        if (yB.length != n) throw new IllegalArgumentException("yA and yB must have same length");
        int k = yC.length;

        // Total variance estimator: variance of [yA ; yB] together (2N samples)
        // gives a more stable denominator than Var(yA) alone.
        double totalVar = combinedVariance(yA, yB);
        if (totalVar < 1e-12) {
            // No variance → all indices are 0 (parameter has no effect or all eval'd the same).
            Indices[] out = new Indices[k];
            for (int i = 0; i < k; i++) out[i] = new Indices(0.0, 0.0);
            return out;
        }

        Indices[] indices = new Indices[k];
        for (int i = 0; i < k; i++) {
            double sumSqDiffB = 0; // for S1 (Jansen)
            double sumSqDiffA = 0; // for ST (Jansen)
            for (int j = 0; j < n; j++) {
                double dB = yB[j] - yC[i][j];
                double dA = yA[j] - yC[i][j];
                sumSqDiffB += dB * dB;
                sumSqDiffA += dA * dA;
            }
            // Jansen first-order: S1_i = (totalVar − (1/(2N)) Σ (yB − yC_i)²) / totalVar
            double s1 = (totalVar - sumSqDiffB / (2.0 * n)) / totalVar;
            // Jansen total-order: ST_i = (1/(2N)) Σ (yA − yC_i)² / totalVar
            double st = sumSqDiffA / (2.0 * n) / totalVar;
            indices[i] = new Indices(s1, st);
        }
        return indices;
    }

    /**
     * Estimate <b>second-order</b> Sobol indices S_ij = V_ij / Var(Y), the
     * "pure" pair interaction (main effects of i and j already subtracted).
     *
     * <p>Uses the Saltelli (2002) cross-product estimator:
     * <pre>
     *   S_ij_closed = ( (1/N) Σ_n yBA_j[n] · yAB_i[n]  −  f0² ) / Var(Y)
     *   S_ij        = S_ij_closed − S1_i − S1_j
     * </pre>
     * where {@code f0² ≈ mean(yA) · mean(yB)} (using the cross-product makes
     * the estimator unbiased under independent sampling).
     *
     * @param yA          length-N output of base matrix A
     * @param yB          length-N output of permutation matrix B
     * @param yAB         {@code yAB[i]} = output of matrix C_i (col i from B,
     *                    others from A) — same data already used for S1/ST
     * @param yBA         {@code yBA[i]} = output of matrix where col i is from
     *                    A and all others are from B
     * @param firstOrder  the S1 estimates from {@link #estimateJansen} (used
     *                    to subtract main effects)
     * @return            one entry per ordered pair (i, j) with i &lt; j; row
     *                    layout is (0,1), (0,2), ..., (0,k-1), (1,2), ..., (k-2,k-1)
     */
    public static double[] estimateSecondOrderSaltelli(
            double[] yA, double[] yB,
            double[][] yAB, double[][] yBA,
            Indices[] firstOrder) {
        int n = yA.length;
        int k = yAB.length;
        if (yBA.length != k) throw new IllegalArgumentException("yAB/yBA dimensions mismatch");
        int numPairs = k * (k - 1) / 2;
        double[] out = new double[numPairs];

        double totalVar = combinedVariance(yA, yB);
        if (totalVar < 1e-12) return out;

        // f0² estimated as mean(yA) * mean(yB) — Saltelli 2002 recommendation.
        double meanA = mean(yA);
        double meanB = mean(yB);
        double f0sq = meanA * meanB;

        int idx = 0;
        for (int i = 0; i < k; i++) {
            for (int j = i + 1; j < k; j++) {
                double sum = 0;
                for (int p = 0; p < n; p++) {
                    sum += yBA[j][p] * yAB[i][p];
                }
                double sClosed = ((sum / n) - f0sq) / totalVar;
                // Subtract main effects to get the pure pair interaction.
                double sIj = sClosed - firstOrder[i].s1() - firstOrder[j].s1();
                out[idx++] = Math.max(0.0, sIj);
            }
        }
        return out;
    }

    private static double mean(double[] x) {
        double m = 0;
        for (double v : x) m += v;
        return m / x.length;
    }

    /** Variance of the concatenation of two equal-length arrays. */
    private static double combinedVariance(double[] a, double[] b) {
        int n = a.length;
        double mean = 0;
        for (int i = 0; i < n; i++) mean += a[i] + b[i];
        mean /= (2.0 * n);
        double v = 0;
        for (int i = 0; i < n; i++) {
            double dA = a[i] - mean;
            double dB = b[i] - mean;
            v += dA * dA + dB * dB;
        }
        return v / (2.0 * n);
    }

    /**
     * First- and total-order Sobol indices for one parameter on one output.
     *
     * @param s1 first-order index (main effect, [0..1] in theory; can be slightly
     *           negative or &gt;1 due to small-sample noise)
     * @param st total-order index (main + interactions; should be ≥ S1)
     */
    public record Indices(double s1, double st) {
        public double interactionStrength() {
            return Math.max(0.0, st - s1);
        }
    }
}
