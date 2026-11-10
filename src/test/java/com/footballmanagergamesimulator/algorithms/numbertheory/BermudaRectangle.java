package com.footballmanagergamesimulator.algorithms.numbertheory;

import java.util.Random;

/**
 * Codeforces 2257D "Bermuda Rectangle" made executable: the hidden rectangle
 * has integer sides, area S and its bottom-left corner at (0,0); a query
 * (x, y) asks how many cells of the x×y rectangle can lie inside at least one
 * candidate a × (S/a) with a | S.
 *
 * <p>The union of all candidates is a staircase: column i (1-indexed) has
 * height S / (smallest divisor of S that is ≥ i). {@link Solver} precomputes
 * the sorted divisors and the cumulative step areas once per S — O(√S) — and
 * then answers each query with two binary searches, no cell ever visited.
 * {@link #oracle(long, long, long)} is the cell-by-cell baseline the fast
 * path is fuzz-checked against.
 */
public final class BermudaRectangle {

    /** Deterministic seed so fuzz runs and measured counts are reproducible. */
    private static final Random RANDOM = new Random(2257L);

    private BermudaRectangle() {
    }

    /** One Bermuda instance: divisors and step areas precomputed once per S. */
    public static final class Solver {

        /** The fixed area of the hidden rectangle. */
        final long s;

        /** All divisors of s, ascending; divisors[k] is the right edge of step k. */
        final long[] divisors;

        /** areaPrefix[k] = cells in columns 1..divisors[k] of the staircase. */
        final long[] areaPrefix;

        public Solver(long s) {
            this.s = s;
            this.divisors = divisorsOf(s);
            this.areaPrefix = new long[divisors.length];
            long previousEdge = 0;
            long cells = 0;
            for (int k = 0; k < divisors.length; k++) {
                long edge = divisors[k];
                cells += (edge - previousEdge) * (s / edge);
                areaPrefix[k] = cells;
                previousEdge = edge;
            }
        }

        /** Cells of [1..x]×[1..y] covered by at least one candidate rectangle. */
        public long query(long x, long y) {
            long tallColumns = largestDivisorAtMost(s / y);
            long fullColumns = Math.min(x, tallColumns);
            long answer = fullColumns * y;
            if (x > tallColumns) {
                answer += staircaseCells(x) - staircaseCells(tallColumns);
            }
            return answer;
        }

        /** Cells of the whole staircase restricted to columns 1..x (1 ≤ x ≤ s). */
        long staircaseCells(long x) {
            int step = firstDivisorIndexAtLeast(x);
            long previousEdge = step == 0 ? 0 : divisors[step - 1];
            long before = step == 0 ? 0 : areaPrefix[step - 1];
            return before + (x - previousEdge) * (s / divisors[step]);
        }

        /** Largest divisor ≤ bound; every query has y ≤ s, so 1 ≤ s/y always qualifies. */
        long largestDivisorAtMost(long bound) {
            int lo = 0;
            int hi = divisors.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (divisors[mid] <= bound) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return divisors[lo];
        }

        /** Index of the smallest divisor ≥ x — the step whose column x lives in. */
        int firstDivisorIndexAtLeast(long x) {
            int lo = 0;
            int hi = divisors.length - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (divisors[mid] >= x) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            return lo;
        }
    }

    /** Trial division up to √s: every hit a yields the pair (a, s/a). Ascending result. */
    static long[] divisorsOf(long s) {
        long[] small = new long[64];
        long[] large = new long[64];
        int count = 0;
        for (long a = 1; a * a <= s; a++) {
            if (s % a != 0) {
                continue;
            }
            if (count == small.length) {
                small = java.util.Arrays.copyOf(small, count * 2);
                large = java.util.Arrays.copyOf(large, count * 2);
            }
            small[count] = a;
            large[count] = s / a;
            count++;
        }
        boolean perfectSquare = small[count - 1] == large[count - 1];
        long[] all = new long[2 * count - (perfectSquare ? 1 : 0)];
        System.arraycopy(small, 0, all, 0, count);
        for (int k = perfectSquare ? count - 2 : count - 1, i = count; k >= 0; k--, i++) {
            all[i] = large[k];
        }
        return all;
    }

    /**
     * Baseline: walk every cell of [1..x]×[1..y] and ask each divisor pair for
     * shelter. Correct for every input, hopeless beyond toy sizes — this is
     * the wall the staircase exists to break.
     */
    public static long oracle(long s, long x, long y) {
        long[] divisors = divisorsOf(s);
        long covered = 0;
        for (long i = 1; i <= x; i++) {
            for (long j = 1; j <= y; j++) {
                if (isCovered(divisors, s, i, j)) {
                    covered++;
                }
            }
        }
        return covered;
    }

    /** Cell (i, j) is inside some candidate iff a divisor a has a ≥ i and s/a ≥ j. */
    static boolean isCovered(long[] divisors, long s, long i, long j) {
        for (long width : divisors) {
            if (width >= i && s / width >= j) {
                return true;
            }
        }
        return false;
    }

    /** Sample tests from the statement + fuzz vs oracle + a large-S measurement. */
    public static void main(String[] args) {
        long[][][] sampleCases = {
                {{6}, {2, 3, 6}, {4, 5, 11}, {6, 6, 14}, {1, 1, 1}},
                {{5}, {2, 2, 3}, {3, 4, 6}},
                {{8}, {3, 1, 3}, {5, 6, 15}},
        };
        for (long[][] testCase : sampleCases) {
            Solver solver = new Solver(testCase[0][0]);
            for (int q = 1; q < testCase.length; q++) {
                long got = solver.query(testCase[q][0], testCase[q][1]);
                if (got != testCase[q][2]) {
                    throw new IllegalStateException("sample S=" + solver.s + " query ("
                            + testCase[q][0] + "," + testCase[q][1] + "): got " + got
                            + ", expected " + testCase[q][2]);
                }
            }
        }
        System.out.println("samples: all 8 expected answers reproduced");

        long checks = 0;
        for (long s = 1; s <= 60; s++) {
            Solver solver = new Solver(s);
            for (long x = 1; x <= s; x++) {
                for (long y = 1; y <= s; y++) {
                    long fast = solver.query(x, y);
                    long slow = oracle(s, x, y);
                    if (fast != slow) {
                        throw new IllegalStateException("mismatch S=" + s + " x=" + x
                                + " y=" + y + ": fast=" + fast + " oracle=" + slow);
                    }
                    checks++;
                }
            }
        }
        System.out.println("exhaustive fuzz S=1..60, every (x,y): " + checks + " checks, fast == oracle");

        checks = 0;
        for (int round = 0; round < 400; round++) {
            long s = 61 + RANDOM.nextInt(340);
            Solver solver = new Solver(s);
            for (int q = 0; q < 25; q++) {
                long x = 1 + (long) RANDOM.nextInt((int) s);
                long y = 1 + (long) RANDOM.nextInt((int) s);
                long fast = solver.query(x, y);
                long slow = oracle(s, x, y);
                if (fast != slow) {
                    throw new IllegalStateException("mismatch S=" + s + " x=" + x
                            + " y=" + y + ": fast=" + fast + " oracle=" + slow);
                }
                checks++;
            }
        }
        System.out.println("random fuzz S=61..400: " + checks + " checks, fast == oracle");

        long bigS = 96_376_119_840_000L;
        long t0 = System.nanoTime();
        Solver big = new Solver(bigS);
        long buildMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("S=" + bigS + ": " + big.divisors.length + " divizori, build "
                + buildMs + " ms, uniune " + big.areaPrefix[big.areaPrefix.length - 1] + " celule");

        int queries = 300_000;
        long sink = 0;
        t0 = System.nanoTime();
        for (int q = 0; q < queries; q++) {
            long x = 1 + (long) (RANDOM.nextDouble() * bigS);
            long y = 1 + (long) (RANDOM.nextDouble() * bigS);
            sink ^= big.query(x, y);
        }
        long queryMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println(queries + " query-uri aleatoare: " + queryMs + " ms total (sink=" + sink + ")");
    }
}
