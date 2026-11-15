package match2d.engine;

import java.util.Map;

/**
 * Urma pas cu pas a unui meci, cu toate zecimalele: pentru compararea cu motorul Python
 * (tools/trace_compare.py). Un rand pe pas: numarul pasului, cate numere aleatoare s-au tras,
 * starea mingii si, pentru fiecare jucator, pozitia, viteza, tinta, rolul si drumul.
 */
public final class Trace {
    private Trace() {
    }

    public static String run(Map<String, Object> home, Map<String, Object> away, long seed,
                             double minutes, int from, int to) {
        Sim s = new Sim(home, away, seed, minutes, 10, false);
        StringBuilder out = new StringBuilder();
        Movement.refreshGrids(s);
        s.record();
        while (s.state != Sim.OVER && s.tickNo < to) {
            s.tick();
            if (s.tickNo >= from) {
                line(s, out);
            }
        }
        return out.toString();
    }

    private static void line(Sim s, StringBuilder out) {
        Ball b = s.ball;
        out.append('[').append(s.tickNo).append(',').append(s.rng.calls()).append(',').append(s.state)
                .append(',').append(b.kind).append(',').append(b.holder == null ? -1 : b.holder.uid)
                .append(',').append(s.poss.index);
        num(out, b.x);
        num(out, b.y);
        num(out, b.z);
        num(out, b.v);
        for (Player p : s.all) {
            num(out, p.x);
            num(out, p.y);
            num(out, p.vx);
            num(out, p.vy);
            num(out, p.tx);
            num(out, p.ty);
            out.append(',').append(p.role).append(',').append(p.sprint ? 1 : 0).append(',').append(p.path.size());
            num(out, p.energy);
            num(out, p.thinkAt);
        }
        out.append("]\n");
    }

    private static void num(StringBuilder out, double v) {
        out.append(',').append(Double.toString(v));
    }
}
