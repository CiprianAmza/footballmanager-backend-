package match2d.league;

import java.util.Collections;
import java.util.List;

/**
 * Atat cat foloseste liga din {@code random.Random} al lui Python: Mersenne Twister cu samanta
 * intreaga si {@code shuffle} (prin {@code getrandbits}), ca tragerea la sorti a etapelor sa iasa
 * identica cu cea din {@code league_tv.py}.
 */
final class PyRandom {
    private static final int N = 624;
    private static final int M = 397;

    private final int[] mt = new int[N];
    private int index = N;

    PyRandom(long seed) {
        long a = Math.abs(seed);
        int lo = (int) a;
        int hi = (int) (a >>> 32);
        initByArray(hi != 0 ? new int[] {lo, hi} : new int[] {lo});
    }

    private void initGenrand(int s) {
        mt[0] = s;
        for (int i = 1; i < N; i++) {
            mt[i] = 1812433253 * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i;
        }
        index = N;
    }

    private void initByArray(int[] key) {
        initGenrand(19650218);
        int i = 1;
        int j = 0;
        for (int k = Math.max(N, key.length); k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + key[j] + j;
            i++;
            j++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
            if (j >= key.length) {
                j = 0;
            }
        }
        for (int k = N - 1; k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i;
            i++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
        }
        mt[0] = 0x80000000;
    }

    private int next32() {
        if (index >= N) {
            for (int k = 0; k < N; k++) {
                int y = (mt[k] & 0x80000000) | (mt[(k + 1) % N] & 0x7fffffff);
                mt[k] = mt[(k + M) % N] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            }
            index = 0;
        }
        int y = mt[index++];
        y ^= y >>> 11;
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        y ^= y >>> 18;
        return y;
    }

    /** {@code getrandbits(k)}, 1 <= k <= 31. */
    private int bits(int k) {
        return next32() >>> (32 - k);
    }

    /** {@code _randbelow(n)}, n >= 1. */
    private int below(int n) {
        int k = 32 - Integer.numberOfLeadingZeros(n);
        int r = bits(k);
        while (r >= n) {
            r = bits(k);
        }
        return r;
    }

    /** {@code shuffle(x)}. */
    <T> void shuffle(List<T> x) {
        for (int i = x.size() - 1; i >= 1; i--) {
            int j = below(i + 1);
            Collections.swap(x, i, j);
        }
    }
}
