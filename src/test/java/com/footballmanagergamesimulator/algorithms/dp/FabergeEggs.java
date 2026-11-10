package com.footballmanagergamesimulator.algorithms.dp;

import java.math.BigInteger;

/**
 * Codewars 1 kyu "Faberge easter eggs crush test [linear]": with n identical eggs
 * and m throws, the tallest skyscraper on which the critical floor can always be
 * found is height(n, m) = C(m,1) + C(m,2) + ... + C(m,n), reported mod 998244353.
 *
 * <p>Three implementations of the same number, ordered by generality:
 * {@link #heightByRecurrence(int, int)} is the exact dynamic-programming table,
 * {@link #heightViaFactorials(int, int)} evaluates the closed form with factorial
 * tables (regime n &le; 80000, m &le; 100000), and {@link #height(BigInteger, BigInteger)}
 * is the Codewars entry point that also survives m up to 2^200 by never touching
 * m with anything but a single {@code mod p}.
 */
public final class FabergeEggs {

    /** Prime modulus demanded by the kata (998244353 = 119 * 2^23 + 1). */
    public static final long MOD = 998_244_353L;

    private FabergeEggs() {
    }

    /**
     * Exact answer by the egg-drop recurrence f(e,t) = f(e-1,t-1) + f(e,t-1) + 1:
     * one throw either cracks the egg (search below with e-1 eggs, t-1 throws) or
     * not (search above with e eggs, t-1 throws), and the throw floor itself is +1.
     * Small inputs only - the values themselves overflow past m around 60.
     */
    public static long heightByRecurrence(int eggs, int tries) {
        long[][] f = new long[eggs + 1][tries + 1];
        for (int e = 1; e <= eggs; e++) {
            for (int t = 1; t <= tries; t++) {
                f[e][t] = f[e - 1][t - 1] + f[e][t - 1] + 1;
            }
        }
        return f[eggs][tries];
    }

    /**
     * Closed form under the modulus with precomputed tables:
     * C(m,i) = fact[m] * invFact[i] * invFact[m-i], each sum term read in O(1)
     * after an O(m) build. Requires m to fit as an array index.
     */
    public static long heightViaFactorials(int n, int m) {
        long[] fact = new long[m + 1];
        long[] invFact = new long[m + 1];
        fact[0] = 1;
        for (int i = 1; i <= m; i++) {
            fact[i] = fact[i - 1] * i % MOD;
        }
        invFact[m] = modPow(fact[m], MOD - 2);
        for (int i = m; i >= 1; i--) {
            invFact[i - 1] = invFact[i] * i % MOD;
        }
        long sum = 0;
        for (int i = 1; i <= Math.min(n, m); i++) {
            long binom = fact[m] * invFact[i] % MOD * invFact[m - i] % MOD;
            sum = (sum + binom) % MOD;
        }
        return sum;
    }

    /**
     * Codewars entry point. Walks i = 1..min(n,m) once, upgrading each term to the
     * next by the exact integer identity C(m,i) = C(m,i-1) * (m-i+1) / i, with the
     * division done as a multiplication by the modular inverse of i. The huge m is
     * reduced mod p exactly once; afterwards everything is 64-bit arithmetic.
     */
    public static BigInteger height(BigInteger n, BigInteger m) {
        BigInteger p = BigInteger.valueOf(MOD);
        int cap = n.min(m).min(BigInteger.valueOf(200_000)).intValueExact();
        if (cap <= 0) {
            return BigInteger.ZERO;
        }
        long mModP = m.mod(p).longValue();
        long[] inv = new long[cap + 1];
        inv[1] = 1;
        for (int i = 2; i <= cap; i++) {
            inv[i] = (MOD - (MOD / i) * inv[(int) (MOD % i)] % MOD) % MOD;
        }
        long term = 1;
        long sum = 0;
        for (int i = 1; i <= cap; i++) {
            long factor = ((mModP - i + 1) % MOD + MOD) % MOD;
            term = term * factor % MOD * inv[i] % MOD;
            sum = (sum + term) % MOD;
        }
        return BigInteger.valueOf(sum);
    }

    /** Fermat's little theorem: for prime p and a not divisible by p, a^(p-2) is 1/a mod p. */
    static long modPow(long base, long exp) {
        long result = 1;
        long b = base % MOD;
        for (long e = exp; e > 0; e >>= 1) {
            if ((e & 1) == 1) {
                result = result * b % MOD;
            }
            b = b * b % MOD;
        }
        return result;
    }
}
