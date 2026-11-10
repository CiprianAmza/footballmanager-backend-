package com.footballmanagergamesimulator.algorithms.numbertheory;

import java.math.BigInteger;
import java.util.Random;

/**
 * Lagrange's four-square theorem made executable: {@link #decompose(BigInteger)}
 * returns (a, b, c, d) with a² + b² + c² + d² = n for any non-negative n,
 * fast enough for 1024-bit inputs.
 *
 * <p>The engine is the randomized Rabin–Shallit reduction: strip factors of 4,
 * route on n mod 8, then repeatedly draw a random x and hope p = n − x² is a
 * prime ≡ 1 (mod 4) — such a prime splits into two squares via a square root
 * of −1 and a truncated Euclidean algorithm. The dice only decide how many
 * draws are needed, never whether the answer is correct.
 */
public final class FourSquaresLab {

    /** Deterministic seed so measured attempt counts are reproducible. */
    private static final Random RANDOM = new Random(63022799L);

    private static final BigInteger TWO = BigInteger.TWO;
    private static final BigInteger FOUR = BigInteger.valueOf(4);
    private static final BigInteger EIGHT = BigInteger.valueOf(8);

    /**
     * Inputs at most this large are handled by nested-loop search. The bound is
     * deliberately generous: below it, ⌊√m⌋ offers so few x candidates that the
     * random prime hunt can run out of luck entirely (no x makes m − x² prime);
     * above it there are ≥ ~500 candidates and the hunt succeeds with
     * probability 1 − ~10⁻¹⁶.
     */
    private static final BigInteger SMALL = BigInteger.ONE.shiftLeft(20);

    /** How many random x candidates the last {@link #decompose(BigInteger)} drew. */
    private static long attempts;

    /** How many Miller–Rabin tests the last {@link #decompose(BigInteger)} ran. */
    private static long primalityTests;

    private FourSquaresLab() {
    }

    public static long attempts() {
        return attempts;
    }

    public static long primalityTests() {
        return primalityTests;
    }

    /**
     * Baseline: three nested loops over a ≤ b ≤ c, then check whether the rest
     * is a perfect square. Correct for every n ≥ 0, hopeless beyond a few
     * thousand — this is the wall the randomized path exists to break.
     */
    public static long[] bruteForce(long n) {
        for (long a = 0; a * a <= n; a++) {
            for (long b = a; a * a + b * b <= n; b++) {
                for (long c = b; a * a + b * b + c * c <= n; c++) {
                    long rest = n - a * a - b * b - c * c;
                    long d = (long) Math.sqrt((double) rest);
                    while (d * d < rest) {
                        d++;
                    }
                    while (d * d > rest) {
                        d--;
                    }
                    if (d * d == rest && d >= c) {
                        return new long[] {a, b, c, d};
                    }
                }
            }
        }
        throw new IllegalStateException("Lagrange guarantees this line is unreachable for n >= 0");
    }

    /** Entry point: any n ≥ 0 comes back as four squares, largest inputs included. */
    public static BigInteger[] decompose(BigInteger n) {
        attempts = 0;
        primalityTests = 0;
        if (n.signum() < 0) {
            throw new IllegalArgumentException("n must be non-negative: " + n);
        }
        if (n.signum() == 0) {
            return new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
        }

        // Step 1 — strip factors of 4: n = 4^k * m, solve m, scale the answer by 2^k.
        BigInteger m = n;
        BigInteger scale = BigInteger.ONE;
        while (m.mod(FOUR).signum() == 0) {
            m = m.shiftRight(2);
            scale = scale.shiftLeft(1);
        }

        BigInteger[] squares = decomposeStripped(m);
        for (int i = 0; i < 4; i++) {
            squares[i] = squares[i].multiply(scale);
        }
        return squares;
    }

    /** m is not divisible by 4 here; route on m mod 8. */
    private static BigInteger[] decomposeStripped(BigInteger m) {
        if (m.compareTo(SMALL) <= 0) {
            long[] small = bruteForce(m.longValueExact());
            return new BigInteger[] {
                    BigInteger.valueOf(small[0]), BigInteger.valueOf(small[1]),
                    BigInteger.valueOf(small[2]), BigInteger.valueOf(small[3])};
        }

        // Step 2 — the one class that truly needs a fourth square: m ≡ 7 (mod 8).
        // Reserve one odd square d²; the rest is ≡ 6 (mod 8) and splits into three.
        if (m.mod(EIGHT).intValueExact() == 7) {
            BigInteger d = m.sqrt();
            if (!d.testBit(0)) {
                d = d.subtract(BigInteger.ONE);
            }
            BigInteger rest = m.subtract(d.multiply(d));
            BigInteger[] three = threeSquares(rest);
            return new BigInteger[] {d, three[0], three[1], three[2]};
        }
        BigInteger[] three = threeSquares(m);
        return new BigInteger[] {BigInteger.ZERO, three[0], three[1], three[2]};
    }

    /**
     * m ≡ 1, 2, 3, 5 or 6 (mod 8): draw random x until p derived from m − x²
     * is 0, 1, 2 or a prime ≡ 1 (mod 4). Las Vegas: the loop only ever exits
     * with a verified-correct answer.
     */
    private static BigInteger[] threeSquares(BigInteger m) {
        if (m.compareTo(SMALL) <= 0) {
            long[] small = bruteForce(m.longValueExact());
            if (small[0] != 0) {
                throw new IllegalStateException("three squares expected for " + m);
            }
            return new BigInteger[] {
                    BigInteger.valueOf(small[1]), BigInteger.valueOf(small[2]), BigInteger.valueOf(small[3])};
        }

        BigInteger sqrtM = m.sqrt();
        boolean mOdd = m.testBit(0);
        boolean caseMod3 = m.mod(EIGHT).intValueExact() == 3;

        while (true) {
            attempts++;
            BigInteger x = randomAtMost(sqrtM);
            if (caseMod3) {
                // m ≡ 3 (mod 8): x must be odd, then (m − x²)/2 ≡ 1 (mod 4).
                x = withParity(x, true);
                BigInteger p = m.subtract(x.multiply(x)).shiftRight(1);
                if (p.equals(BigInteger.ONE)) {
                    return new BigInteger[] {x, BigInteger.ONE, BigInteger.ONE};
                }
                primalityTests++;
                if (p.isProbablePrime(30)) {
                    BigInteger[] ab = twoSquaresOfPrime(p);
                    // 2p = (a+b)² + (a−b)²
                    return new BigInteger[] {x, ab[0].add(ab[1]), ab[0].subtract(ab[1]).abs()};
                }
            } else {
                // m ≡ 1, 2 (mod 4): pick x parity so that p = m − x² ≡ 1 (mod 4).
                x = withParity(x, !mOdd);
                BigInteger p = m.subtract(x.multiply(x));
                if (p.signum() == 0) {
                    return new BigInteger[] {x, BigInteger.ZERO, BigInteger.ZERO};
                }
                if (p.equals(BigInteger.ONE)) {
                    return new BigInteger[] {x, BigInteger.ONE, BigInteger.ZERO};
                }
                if (p.equals(TWO)) {
                    return new BigInteger[] {x, BigInteger.ONE, BigInteger.ONE};
                }
                primalityTests++;
                if (p.isProbablePrime(30)) {
                    BigInteger[] ab = twoSquaresOfPrime(p);
                    return new BigInteger[] {x, ab[0], ab[1]};
                }
            }
        }
    }

    /**
     * A prime p ≡ 1 (mod 4) is a sum of two squares. Find z with z² ≡ −1 (mod p),
     * then run Euclid on (p, z) and stop at the first remainder ≤ ⌊√p⌋: that
     * remainder and the next one are the two squares (Hermite–Serret).
     */
    public static BigInteger[] twoSquaresOfPrime(BigInteger p) {
        BigInteger z = sqrtOfMinusOne(p);
        BigInteger limit = p.sqrt();
        BigInteger a = p;
        BigInteger b = z;
        while (b.compareTo(limit) > 0) {
            BigInteger r = a.mod(b);
            a = b;
            b = r;
        }
        return new BigInteger[] {b, a.mod(b)};
    }

    /**
     * For prime p ≡ 1 (mod 4): a quadratic non-residue q raised to (p−1)/4 is a
     * square root of −1 mod p. Half of all residues are non-residues, so the
     * scan over q = 2, 3, 4, ... ends almost immediately.
     */
    public static BigInteger sqrtOfMinusOne(BigInteger p) {
        BigInteger legendreExponent = p.subtract(BigInteger.ONE).shiftRight(1);
        BigInteger minusOne = p.subtract(BigInteger.ONE);
        for (BigInteger q = TWO; ; q = q.add(BigInteger.ONE)) {
            if (q.modPow(legendreExponent, p).equals(minusOne)) {
                return q.modPow(legendreExponent.shiftRight(1), p);
            }
        }
    }

    /** Uniform draw in [0, bound]; rejection keeps the distribution honest. */
    private static BigInteger randomAtMost(BigInteger bound) {
        while (true) {
            BigInteger x = new BigInteger(bound.bitLength(), RANDOM);
            if (x.compareTo(bound) <= 0) {
                return x;
            }
        }
    }

    /** Nudge x down (never up, so it stays ≤ its bound) until its parity matches. */
    private static BigInteger withParity(BigInteger x, boolean odd) {
        if (x.testBit(0) == odd) {
            return x;
        }
        if (x.signum() == 0) {
            return BigInteger.ONE;
        }
        return x.subtract(BigInteger.ONE);
    }

    /** The proof the caller can always run: do the four squares really sum to n? */
    public static boolean verify(BigInteger n, BigInteger[] squares) {
        BigInteger sum = BigInteger.ZERO;
        for (BigInteger s : squares) {
            sum = sum.add(s.multiply(s));
        }
        return sum.equals(n);
    }
}
