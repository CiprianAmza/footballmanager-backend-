package match2d.engine;

/** Mingea: la un jucator, pe jos (cu franare), in aer (minge inalta) sau moarta. */
public final class Ball {
    static final int HELD = 0, PASS = 1, SHOT = 2, LOOSE = 3, DEAD = 4;

    double x = Geo.L / 2;
    double y = Geo.W / 2;
    double z = 0.0;
    double ux = 1.0;
    double uy = 0.0;
    double v = 0.0;
    double decel = Sim.A_GROUND;
    Player holder;
    /** minge inalta: zboara de la (x0,y0) la (x1,y1), pornita la t0, cu durata T si varful apex; va = viteza dupa aterizare. */
    boolean lofted;
    double lx0, ly0, lx1, ly1, lt0, lT, lApex, lVa;
    int kind = DEAD;
    Player kicker;
    Player receiver;
    double kickT = -9.0;
    Team lastTeam;
    /** ultimul jucator care a atins mingea, cu voie sau fara (pentru atribuirea golului). */
    Player toucher;
    double px = x;
    double py = y;

    // rezultatul ultimei chemari at(): pozitia si inaltimea
    double ax, ay, az;

    void setLoft(double x0, double y0, double x1, double y1, double t0, double dur, double apex, double va) {
        lofted = true;
        lx0 = x0;
        ly0 = y0;
        lx1 = x1;
        ly1 = y1;
        lt0 = t0;
        lT = dur;
        lApex = apex;
        lVa = va;
    }

    /** Unde va fi mingea peste {@code tau} secunde; rezultatul e in {@code ax, ay, az}. */
    void at(double tau, double now) {
        if (lofted) {
            double u = (now + tau - lt0) / lT;
            if (u < 1.0) {
                ax = lx0 + (lx1 - lx0) * u;
                ay = ly0 + (ly1 - ly0) * u;
                az = 4.0 * lApex * u * (1.0 - u);
                return;
            }
            double tr = now + tau - lt0 - lT;
            double ts = lVa / Sim.A_GROUND;
            if (tr > ts) {
                tr = ts;
            }
            double s = lVa * tr - 0.5 * Sim.A_GROUND * tr * tr;
            ax = lx1 + ux * s;
            ay = ly1 + uy * s;
            az = 0.0;
            return;
        }
        double vv = v;
        if (vv <= 0.0) {
            ax = x;
            ay = y;
            az = 0.0;
            return;
        }
        double a = decel;
        double ts = vv / a;
        if (tau > ts) {
            tau = ts;
        }
        double s = vv * tau - 0.5 * a * tau * tau;
        ax = x + ux * s;
        ay = y + uy * s;
        az = 0.0;
    }

    /** Avanseaza mingea cu un pas (now = timpul dinaintea pasului). */
    void step(double now) {
        px = x;
        py = y;
        at(Sim.DT, now);
        double nx = ax;
        double ny = ay;
        double nz = az;
        if (lofted) {
            double tr = now + Sim.DT - lt0 - lT;
            if (tr >= 0.0) {
                lofted = false;
                v = Math.max(0.0, lVa - Sim.A_GROUND * tr);
                decel = Sim.A_GROUND;
            }
        } else {
            v = Math.max(0.0, v - decel * Sim.DT);
        }
        x = nx;
        y = ny;
        z = nz;
    }
}
