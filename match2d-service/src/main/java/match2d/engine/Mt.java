package match2d.engine;

/**
 * Generatorul de numere al lui Python ({@code random.Random}): Mersenne Twister MT19937, cu
 * aceleasi {@code random()} si {@code gauss()}. Acelasi seed da acelasi sir ca in motorul
 * Python, deci un meci poate fi comparat pas cu pas intre cele doua implementari.
 */
public final class Mt {
    private static final int N = 624;
    private static final int M = 397;
    private static final double TWO_PI = 2.0 * Math.PI;

    private final int[] mt = new int[N];
    private int index = N;
    private boolean hasNext;
    private double next;
    private long calls;

    public Mt(long seed) {
        seed(seed);
    }

    /** Ca {@code random.seed(numar_intreg)} din Python: cheia e valoarea absoluta, pe bucati de 32 de biti. */
    public void seed(long seed) {
        long a = Math.abs(seed);
        int lo = (int) a;
        int hi = (int) (a >>> 32);
        initByArray(hi != 0 ? new int[] {lo, hi} : new int[] {lo});
        hasNext = false;
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

    private void twist() {
        for (int k = 0; k < N; k++) {
            int y = (mt[k] & 0x80000000) | (mt[(k + 1) % N] & 0x7fffffff);
            mt[k] = mt[(k + M) % N] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
        }
        index = 0;
    }

    private int nextInt32() {
        if (index >= N) {
            twist();
        }
        int y = mt[index++];
        y ^= y >>> 11;
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        y ^= y >>> 18;
        return y;
    }

    /** Cate numere uniforme s-au tras pana acum (pentru compararea pas cu pas cu Python). */
    public long calls() {
        return calls;
    }

    /** Numar uniform in [0, 1), cu 53 de biti, exact ca {@code random.random()}. */
    public double random() {
        calls++;
        long a = nextInt32() >>> 5;
        long b = nextInt32() >>> 6;
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0);
    }

    /** Ca {@code random.gauss(mu, sigma)}: genereaza doua valori o data si o pastreaza pe a doua. */
    public double gauss(double mu, double sigma) {
        double z;
        if (hasNext) {
            z = next;
            hasNext = false;
        } else {
            double x2pi = random() * TWO_PI;
            double g2rad = Math.sqrt(-2.0 * PMath.log(1.0 - random()));
            z = PMath.cos(x2pi) * g2rad;
            next = PMath.sin(x2pi) * g2rad;
            hasNext = true;
        }
        return mu + z * sigma;
    }
}
