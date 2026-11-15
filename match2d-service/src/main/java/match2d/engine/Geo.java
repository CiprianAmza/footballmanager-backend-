package match2d.engine;

/**
 * Terenul si transformarile de coordonate.
 *
 * <p>Intern lucram in metri: x pe lungime (0..105), y pe latime (0..68). Fiecare echipa are si un
 * sistem "canonic": poarta proprie la x=0, ataca spre x=105, iar y=0 este tusa din stanga ei.
 * Cand echipa ataca spre stanga pe ecran, canonicul se roteste cu 180 de grade, deci "stanga" se
 * inverseaza la repriza.
 */
public final class Geo {
    public static final double L = 105.0;
    public static final double W = 68.0;
    public static final double GOAL_HALF = 3.66;
    public static final double GOAL_H = 2.44;
    public static final double MID_Y = W / 2.0;

    private static final double[][] THREAT = buildThreat();

    private Geo() {
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** Lume <-> canonic pe lungime (aceeasi functie in ambele sensuri). */
    public static double cx(double x, int dir) {
        return dir == 1 ? x : L - x;
    }

    /** Lume <-> canonic pe latime. */
    public static double cy(double y, int dir) {
        return dir == 1 ? y : W - y;
    }

    /** Pozitia (nelimitata la 0..1) a proiectiei punctului P pe segmentul AB. */
    public static double segU(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double d2 = dx * dx + dy * dy;
        if (d2 < 1e-9) {
            return 0.0;
        }
        return ((px - ax) * dx + (py - ay) * dy) / d2;
    }

    /** Distanta de la punctul P la segmentul AB. */
    public static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double d2 = dx * dx + dy * dy;
        if (d2 < 1e-9) {
            return Math.sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay));
        }
        double u = ((px - ax) * dx + (py - ay) * dy) / d2;
        double uc = u < 0 ? 0.0 : u > 1 ? 1.0 : u;
        double cx = ax + uc * dx;
        double cy = ay + uc * dy;
        return Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy));
    }

    /**
     * Cat de periculos e un punct pentru sut (0..1), doar din geometrie: unghiul sub care se vede
     * poarta, atenuat cu distanta.
     */
    public static double goalGeom(double cx, double cy) {
        double dx = L - cx;
        if (dx < 0.3) {
            dx = 0.3;
        }
        double dy = MID_Y - cy;
        double d = Math.sqrt(dx * dx + dy * dy);
        double ang = Math.abs(PMath.atan2(dy + GOAL_HALF, dx) - PMath.atan2(dy - GOAL_HALF, dx));
        double g = (ang / (Math.PI / 2)) * PMath.exp(-d / 12.0);
        return g > 1.0 ? 1.0 : g;
    }

    /**
     * Valoarea unei pozitii pentru echipa cu mingea (un fel de xT), pe grila de 1 m. Coeficientii
     * sunt potriviti pe ce da motorul insusi: probabilitatea masurata de a marca in urmatoarele
     * 15 s cand ai mingea in acel loc.
     */
    private static double[][] buildThreat() {
        int ni = (int) L + 2;
        int nj = (int) W + 2;
        double[][] tab = new double[ni][nj];
        for (int i = 0; i < ni; i++) {
            double up = Math.min(i, L) / L;
            for (int j = 0; j < nj; j++) {
                tab[i][j] = 0.001 + 0.04 * (up * up * up) + 1.05 * goalGeom(i, j);
            }
        }
        return tab;
    }

    /** Valoarea pozitiei (canonic) pentru echipa in posesie. */
    public static double threat(double cx, double cy) {
        int i = (int) cx;
        int j = (int) cy;
        if (i < 0) {
            i = 0;
        } else if (i > 105) {
            i = 105;
        }
        if (j < 0) {
            j = 0;
        } else if (j > 68) {
            j = 68;
        }
        return THREAT[i][j];
    }
}
