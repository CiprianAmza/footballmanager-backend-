package com.footballmanagergamesimulator.algorithms.dp;

import java.math.BigInteger;
import java.util.Random;

/**
 * Lab for the Codewars 1-kyu kata "Become Immortal".
 *
 * <p>elderAge(m, n, l, t) = sum over x in [0,m), y in [0,n) of max(0, (x xor y) - l), mod t.
 * {@link #bruteForce} is the O(m*n) oracle; {@link #elderAge} is the O(log-ish) quadrant
 * decomposition that survives m, n far beyond any loopable size.
 */
public final class BecomeImmortal {

    private BecomeImmortal() {
    }

    /** Oracle: walks every cell. Only usable for small m, n. */
    static long bruteForce(long m, long n, long l, long t) {
        long sum = 0;
        for (long x = 0; x < m; x++) {
            for (long y = 0; y < n; y++) {
                long donated = (x ^ y) - l;
                if (donated > 0) {
                    sum = (sum + donated) % t;
                }
            }
        }
        return sum;
    }

    /** Oracle companion: how many worshippers actually donate (x^y > l). */
    static long countDonors(long m, long n, long l) {
        long donors = 0;
        for (long x = 0; x < m; x++) {
            for (long y = 0; y < n; y++) {
                if ((x ^ y) > l) {
                    donors++;
                }
            }
        }
        return donors;
    }

    /** Number of rect() invocations in the last elderAge call — the measured cost. */
    static long rectCalls;

    /** Fast solution: quadrant decomposition at the largest power of two. */
    static long elderAge(long m, long n, long l, long t) {
        rectCalls = 0;
        return rect(m, n, 0, l, t);
    }

    /**
     * Sum over x in [0,a), y in [0,b) of max(0, off + (x xor y) - l), mod t.
     * off is the accumulated high-bit offset of the current quadrant.
     */
    private static long rect(long a, long b, long off, long l, long t) {
        rectCalls++;
        if (a == 0 || b == 0) {
            return 0;
        }
        if (a < b) {
            long tmp = a;
            a = b;
            b = tmp;
        }
        long p = Long.highestOneBit(a);
        if (p == a) {
            // a is a power of two and b <= a: for every fixed y < b, x^y over x in [0,p)
            // is a permutation of 0..p-1, so each of the b rows contributes the same sum.
            return mulMod(b % t, rowSum(p, off, l, t), t);
        }
        long b0 = Math.min(b, p);
        long res = mulMod(b0 % t, rowSum(p, off, l, t), t);      // x < p,  y < b0
        res = (res + rect(a - p, b0, off + p, l, t)) % t;        // x >= p, y < b0 : x^y = p + (x'^y)
        if (b > p) {
            res = (res + rect(p, b - p, off + p, l, t)) % t;     // x < p,  y >= p : x^y = p + (x^y')
            res = (res + rect(a - p, b - p, off, l, t)) % t;     // x >= p, y >= p : high bits cancel
        }
        return res;
    }

    /**
     * Closed form: sum over v in [0,p) of max(0, off + v - l), mod t.
     * The even factor of the triangular product is halved BEFORE any mod.
     */
    private static long rowSum(long p, long off, long l, long t) {
        long threshold = l - off;                     // v must exceed this to donate
        if (threshold >= p - 1) {
            return 0;                                 // even v = p-1 is swallowed by the loss
        }
        if (threshold < 0) {
            // every v donates: sum = p*(p-1)/2 + p*(off-l)
            long tri = triangularMod(p - 1, t);
            long flat = mulMod(p % t, (-threshold) % t, t);
            return (tri + flat) % t;
        }
        // only v in (threshold, p-1] donate, values 1..D with D = p-1-threshold
        return triangularMod(p - 1 - threshold, t);
    }

    /** D*(D+1)/2 mod t, halving the even factor before mod so the division stays exact. */
    private static long triangularMod(long d, long t) {
        long even = (d % 2 == 0) ? d : d + 1;
        long odd = (d % 2 == 0) ? d + 1 : d;
        return mulMod((even / 2) % t, odd % t, t);
    }

    /** (a*b) mod t for a,b < 2^32: the raw product fits in the unsigned 64-bit range. */
    private static long mulMod(long a, long b, long t) {
        return Long.remainderUnsigned(a * b, t);
    }

    /** F5 demo: the same triangular number computed two wrong ways and the right way. */
    static void overflowDemo(long d, long t) {
        BigInteger ref = BigInteger.valueOf(d).multiply(BigInteger.valueOf(d + 1))
                .divide(BigInteger.TWO).mod(BigInteger.valueOf(t));
        long naive = (d * (d + 1) / 2) % t;                 // product overflows signed 64-bit
        long even = (d % 2 == 0) ? d : d + 1;
        long odd = (d % 2 == 0) ? d + 1 : d;
        long halveAfterMod = mulMod((even % t) / 2, odd % t, t); // even%t poate fi impar: /2 trunchiaza
        long correct = triangularMod(d, t);
        System.out.printf("  D=%d t=%d -> referinta(BigInteger)=%d | naiv(d*(d+1)/2)=%d | injumatatireDupaMod=%d | corect(triangularMod)=%d%n",
                d, t, ref, naive, halveAfterMod, correct);
    }

    public static void main(String[] args) {
        System.out.println("== F1: oracolul pe m=8, n=5, l=1, t=100 ==");
        System.out.printf("  celule vizitate=%d, donatori (x^y > l)=%d, rezultat=%d%n",
                8L * 5, countDonors(8, 5, 1), bruteForce(8, 5, 1, 100));

        System.out.println("== F2: solutia rapida pe acelasi input ==");
        System.out.printf("  elderAge(8,5,1,100)=%d, apeluri rect=%d%n", elderAge(8, 5, 1, 100), rectCalls);

        System.out.println("== F3: patrat complet m=n=16, l=3, t=1000 ==");
        System.out.printf("  brute=%d, fast=%d, apeluri rect=%d%n",
                bruteForce(16, 16, 3, 1000), elderAge(16, 16, 3, 1000), rectCalls);

        System.out.println("== F4: dimensiuni mari nealineate ==");
        System.out.printf("  elderAge(545,435,342,1000007)=%d (asteptat 808451), apeluri rect=%d%n",
                elderAge(545, 435, 342, 1000007), rectCalls);
        System.out.printf("  elderAge(28827050410,35165045587,7109602,13719506)=%d (asteptat 5456283), apeluri rect=%d%n",
                elderAge(28827050410L, 35165045587L, 7109602L, 13719506L), rectCalls);
        System.out.printf("  brute force ar fi vizitat 545*435=%d, respectiv ~1.01e21 celule%n", 545L * 435);

        System.out.println("== F5: capcana de overflow pe D*(D+1)/2 ==");
        overflowDemo(4_000_000_000L, 1_000_000_007L);

        System.out.println("== F6: fuzz fast vs oracol ==");
        Random rnd = new Random(42);
        int runs = 500;
        int mismatches = 0;
        for (int i = 0; i < runs; i++) {
            long m = 1 + rnd.nextInt(64);
            long n = 1 + rnd.nextInt(64);
            long l = rnd.nextInt(80);
            long t = 2 + rnd.nextInt(1000);
            long expected = bruteForce(m, n, l, t);
            long actual = elderAge(m, n, l, t);
            if (expected != actual) {
                mismatches++;
                System.out.printf("  MISMATCH m=%d n=%d l=%d t=%d brute=%d fast=%d%n", m, n, l, t, expected, actual);
            }
        }
        System.out.printf("  %d rulari, %d nepotriviri%n", runs, mismatches);
    }
}
