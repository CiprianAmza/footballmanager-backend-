package match2d.engine;

/**
 * Functii matematice "portabile": sin, cos, atan2, exp, log scrise doar cu adunari, inmultiri,
 * impartiri si radical, in aceeasi ordine ca in motorul Python (engine/pmath.py).
 *
 * <p>Functiile bibliotecii ({@code Math.sin}, {@code math.sin}) difera intre limbaje la ultima
 * zecimala, iar o asemenea diferenta ajunge sa schimbe un meci. Cu acestea, motorul Java si cel
 * Python dau exact acelasi meci, pas cu pas. Precizia e de ~1e-15, mult peste ce cere simularea.
 */
public final class PMath {
    public static final double PI = 3.141592653589793;
    public static final double TWO_PI = 2.0 * PI;
    public static final double HALF_PI = 1.5707963267948966;

    private static final double LN2_HI = 6.93147180369123816490e-01;
    private static final double LN2_LO = 1.90821492927058770002e-10;
    private static final double INV_LN2 = 1.44269504088896338700e+00;
    private static final double SQRT_HALF = 0.7071067811865476;

    // pi/2 in trei bucati (ca in fdlibm), pentru o reducere precisa a unghiului
    private static final double PIO2_1 = 1.57079632673412561417e+00;
    private static final double PIO2_2 = 6.07710050630396597660e-11;
    private static final double PIO2_3 = 2.02226624871116645580e-21;
    private static final double PIO2_3T = 8.47842766036889956997e-32;
    private static final double TWO_OVER_PI = 6.36619772367581382433e-01;

    /** 1/n! pentru exp, sin, cos. */
    private static final double[] INV_FACT = new double[21];
    /** atan(i/8), i = 0..8. */
    private static final double[] ATAN_STEP = {0.0, 0.12435499454676144, 0.24497866312686414,
        0.3587706702705722, 0.4636476090008061, 0.5585993153435624, 0.6435011087932844,
        0.7188299996216245, 0.7853981633974483};

    static {
        INV_FACT[0] = 1.0;
        for (int n = 1; n <= 20; n++) {
            INV_FACT[n] = INV_FACT[n - 1] / n;
        }
    }

    private PMath() {
    }

    public static double exp(double x) {
        if (x > 709.0) {
            return Double.POSITIVE_INFINITY;
        }
        if (x < -745.0) {
            return 0.0;
        }
        double k = Math.floor(x * INV_LN2 + 0.5);
        double r = (x - k * LN2_HI) - k * LN2_LO;
        double p = INV_FACT[14];
        for (int n = 13; n >= 0; n--) {
            p = p * r + INV_FACT[n];
        }
        return Math.scalb(p, (int) k);
    }

    /** Logaritm natural, pentru x > 0. */
    public static double log(double x) {
        int e = Math.getExponent(x) + 1;
        double m = Math.scalb(x, -e);
        if (m < SQRT_HALF) {
            m = m * 2.0;
            e -= 1;
        }
        double f = (m - 1.0) / (m + 1.0);
        double f2 = f * f;
        double s = 1.0 / 27.0;
        for (double d = 25.0; d >= 1.0; d -= 2.0) {
            s = s * f2 + 1.0 / d;
        }
        return e * LN2_HI + (2.0 * f * s + e * LN2_LO);
    }

    private static double sinPoly(double r) {
        double r2 = r * r;
        double p = -INV_FACT[19];
        p = p * r2 + INV_FACT[17];
        p = p * r2 - INV_FACT[15];
        p = p * r2 + INV_FACT[13];
        p = p * r2 - INV_FACT[11];
        p = p * r2 + INV_FACT[9];
        p = p * r2 - INV_FACT[7];
        p = p * r2 + INV_FACT[5];
        p = p * r2 - INV_FACT[3];
        return r + r * (r2 * p);
    }

    private static double cosPoly(double r) {
        double r2 = r * r;
        double p = INV_FACT[20];
        p = p * r2 - INV_FACT[18];
        p = p * r2 + INV_FACT[16];
        p = p * r2 - INV_FACT[14];
        p = p * r2 + INV_FACT[12];
        p = p * r2 - INV_FACT[10];
        p = p * r2 + INV_FACT[8];
        p = p * r2 - INV_FACT[6];
        p = p * r2 + INV_FACT[4];
        p = p * r2 - INV_FACT[2];
        return 1.0 + r2 * p;
    }

    /** x = k * pi/2 + r, cu r in [-pi/4, pi/4]; restul. */
    private static double reduce(double x, double k) {
        return (((x - k * PIO2_1) - k * PIO2_2) - k * PIO2_3) - k * PIO2_3T;
    }

    public static double sin(double x) {
        double k = Math.floor(x * TWO_OVER_PI + 0.5);
        double r = reduce(x, k);
        switch ((int) k & 3) {
            case 0:
                return sinPoly(r);
            case 1:
                return cosPoly(r);
            case 2:
                return -sinPoly(r);
            default:
                return -cosPoly(r);
        }
    }

    public static double cos(double x) {
        double k = Math.floor(x * TWO_OVER_PI + 0.5);
        double r = reduce(x, k);
        switch ((int) k & 3) {
            case 0:
                return cosPoly(r);
            case 1:
                return -sinPoly(r);
            case 2:
                return -cosPoly(r);
            default:
                return sinPoly(r);
        }
    }

    /** atan(t) pentru t in [0, 1]: pleaca de la cel mai apropiat multiplu de 1/8. */
    private static double atan01(double t) {
        int i = (int) (t * 8.0 + 0.5);
        double c = i * 0.125;
        double u = (t - c) / (1.0 + t * c);
        double u2 = u * u;
        double p = 1.0 / 17.0;
        p = p * u2 - 1.0 / 15.0;
        p = p * u2 + 1.0 / 13.0;
        p = p * u2 - 1.0 / 11.0;
        p = p * u2 + 1.0 / 9.0;
        p = p * u2 - 1.0 / 7.0;
        p = p * u2 + 1.0 / 5.0;
        p = p * u2 - 1.0 / 3.0;
        return ATAN_STEP[i] + (u + u * (u2 * p));
    }

    public static double atan2(double y, double x) {
        double ax = x < 0.0 ? -x : x;
        double ay = y < 0.0 ? -y : y;
        if (ax == 0.0 && ay == 0.0) {
            return 0.0;
        }
        double a;
        if (ay > ax) {
            a = HALF_PI - atan01(ax / ay);
        } else {
            a = atan01(ay / ax);
        }
        if (x < 0.0) {
            a = PI - a;
        }
        return y < 0.0 ? -a : a;
    }
}
