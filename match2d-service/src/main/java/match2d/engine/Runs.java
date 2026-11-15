package match2d.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Echilibrul dintre formatii: cati oameni are echipa pe linia de atac si cati raman sus cand se
 * apara. Fara el, golurile le dau aproape doar jucatorii ale caror zone ajung in careu, deci o
 * formatie cu un singur atacant aproape ca nu trage la poarta, iar una cu patru oameni sus da
 * de doua ori mai multe goluri decat un 4-4-2.
 *
 * <ul>
 * <li><b>Alergarea in careu.</b> Formatia careia ii lipsesc oameni de atac
 * ({@link Team#runMissing}) ii completeaza din mers: cand mingea trece de un prag (cu atat mai
 * jos cu cat lipsesc mai multi), intai mijlocasul ofensiv din linia a doua, apoi cei mai avansati
 * jucatori care nu sunt nici fundasi, nici oameni de atac sprinteaza pe linia de atac si raman
 * acolo pana se pierde mingea sau se intoarce jocul.</li>
 * <li><b>Omul tinut in spate.</b> Formatia cu prea multi oameni pe linia de atac ii tine pe cei
 * in plus mai jos ({@link #attackLine}): vin din linia a doua, nu stau pe ultimul fundas.</li>
 * <li><b>Coborarea atacantilor.</b> Cand echipa se apara, sus raman doar cei mai avansati
 * {@code drop_stay_up} jucatori; ceilalti mijlocasi ofensivi coboara pe linia mijlocasilor pe
 * masura ce mingea se apropie de poarta lor ({@link #dropShares}, {@link Positioning#formTarget}).</li>
 * </ul>
 */
final class Runs {
    /** locurile din careu, fata de axul terenului: intai barele, apoi centrul, apoi marginile. */
    private static final double[] SLOT_Y = {-7.0, 7.0, 0.0, -14.0, 14.0};
    /** de la cati metri peste {@link Zones#ARRIVE_X} un om de atac sta pe linie (atacant, extrema). */
    private static final double LINE_MARGIN = 2.0;

    private Runs() {
    }

    /** Om de atac: jucatorul a carui zona ajunge in careu cand echipa ataca. */
    static boolean isForward(Player p) {
        return !p.isGk && p.high[0] >= Zones.ARRIVE_X;
    }

    /** Om de atac din linia a doua (mijlocas ofensiv): zona lui nu ajunge pe ultimul fundas. */
    static boolean isSecond(Player p) {
        return p.high[0] < Zones.ARRIVE_X + LINE_MARGIN;
    }

    /** O data la 0.5 s: porneste si opreste alergarile in careu. */
    static void update(Sim s) {
        s.runsAt = s.t + 0.5;
        for (Team team : s.teams) {
            if (team.runNeed > 0 && s.state == Sim.LIVE && s.poss == team && s.ball.kind != Ball.SHOT) {
                attack(s, team);
            } else {
                stop(team);
            }
        }
    }

    /** Echipa nu mai ataca: fiecare se intoarce in zona lui. */
    static void stop(Team team) {
        for (Player p : team.players) {
            if (p.running) {
                p.running = false;
                p.abox = p.abox0;
            }
        }
    }

    private static void attack(Sim s, Team team) {
        double t = s.t;
        int d = team.dir;
        Ball b = s.ball;
        double bcx = Geo.cx(b.x, d);
        // cu cat ii lipsesc mai multi oameni de atac, cu atat pleaca mai devreme
        double from = Wt.run_from - Wt.run_slope * team.runMissing;
        if (bcx < from) {
            if (bcx < from - Wt.run_keep) {
                stop(team);
            }
            return;
        }
        // locurile ocupate pe latime: oamenii de atac si cei care alearga deja
        double[] upY = new double[11];
        int up = 0;
        int running = 0;
        for (Player p : team.players) {
            if (p.running) {
                running++;
                upY[up++] = p.runY;
            } else if (p.forward) {
                upY[up++] = Geo.cy(p.y, d);
            }
        }
        for (int need = team.runNeed - running; need > 0; need--) {
            // intai mijlocasul ofensiv din linia a doua (om de atac care nu sta pe ultimul fundas),
            // apoi cel mai avansat care nu e fundas si nu e om de atac; niciunul cu mingea sau
            // prea departe in urma ei
            Player best = null;
            double bx = 0.0;
            boolean bestSecond = false;
            for (Player p : team.players) {
                boolean second = p.forward && isSecond(p);
                if (p.isGk || p.isDef || (p.forward && !second) || p.running || p == b.holder
                        || p.role != Sim.R_NONE || p.stunUntil > t) {
                    continue;
                }
                double cx = Geo.cx(p.x, d);
                if (cx < bcx - Wt.run_reach) {
                    continue;
                }
                if (best == null || (second && !bestSecond) || (second == bestSecond && cx > bx)) {
                    best = p;
                    bx = cx;
                    bestSecond = second;
                }
            }
            if (best == null) {
                break;
            }
            // locul liber cel mai apropiat de el pe latime
            double cy = Geo.cy(best.y, d);
            double slot = Geo.MID_Y;
            double bestKey = Double.POSITIVE_INFINITY;
            for (double off : SLOT_Y) {
                double y = Geo.MID_Y + off;
                boolean taken = false;
                for (int i = 0; i < up; i++) {
                    if (Math.abs(upY[i] - y) < 4.5) {
                        taken = true;
                        break;
                    }
                }
                double key = Math.abs(y - cy) + (taken ? 100.0 : 0.0);
                if (key < bestKey) {
                    bestKey = key;
                    slot = y;
                }
            }
            best.running = true;
            best.runX = Geo.L - Wt.run_depth;
            best.runY = slot;
            best.abox = best.runBox;
            best.thinkAt = t;               // pleaca acum, nu la urmatoarea gandire
            upY[up++] = slot;
        }
    }

    /** Jucatorii de camp in ordinea data de {@code key}, cei mai avansati primii. */
    private static List<Player> advanced(Player[] players, boolean byHigh) {
        List<Player> order = new ArrayList<>();
        for (Player p : players) {
            if (!p.isGk) {
                double key = byHigh ? p.high[0] : p.deep[0];
                int at = 0;
                while (at < order.size() && (byHigh ? order.get(at).high[0] : order.get(at).deep[0]) >= key) {
                    at++;
                }
                order.add(at, p);           // la egalitate, in ordinea din echipa
            }
        }
        return order;
    }

    /**
     * Cat din loc ii revine fiecaruia cand doar primii {@code places} raman: 1 = ramane, 0 = nu.
     * Daca la limita sunt mai multi la fel de avansati (cele doua extreme), impart locul ramas.
     */
    private static double[] shares(List<Player> order, double places, boolean byHigh) {
        double[] share = new double[order.size()];
        int i = 0;
        while (i < order.size()) {
            double first = byHigh ? order.get(i).high[0] : order.get(i).deep[0];
            int j = i;
            while (j < order.size() && first - (byHigh ? order.get(j).high[0] : order.get(j).deep[0]) < 0.5) {
                j++;
            }
            int size = j - i;
            double each = Math.max(0.0, Math.min(1.0, places / size));
            places = Math.max(0.0, places - size);
            for (int k = i; k < j; k++) {
                share[k] = each;
            }
            i = j;
        }
        return share;
    }

    /**
     * Linia de atac a formatiei. Un atacant valoreaza 1, un mijlocas ofensiv (linia a doua)
     * {@code run_second}, o extrema {@code run_wide}. Intoarce cat ii lipseste echipei pana la
     * {@code run_target}: de aici vin numarul alergarilor in careu si cat de devreme pornesc.
     * Daca pe linie sunt mai mult de {@code hold_line} oameni, cei in plus sunt tinuti mai jos
     * cu {@code hold_depth} metri.
     */
    static double attackLine(Player[] players) {
        List<Player> order = advanced(players, true);
        int line = 0;
        double strength = 0.0;
        for (Player p : order) {
            if (isForward(p)) {
                boolean wide = Math.abs(p.high[1] - Geo.MID_Y) > Zones.CENTRAL_Y;
                boolean second = isSecond(p);
                strength += wide ? Wt.run_wide : second ? Wt.run_second : 1.0;
                if (!second) {
                    line++;         // sta pe ultimul fundas (atacant, extrema), nu in linia a doua
                }
            }
        }
        int most = (int) Math.round(Wt.hold_line);
        if (line > most) {
            double[] share = shares(order.subList(0, line), most, true);
            for (int k = 0; k < line; k++) {
                Player p = order.get(k);
                p.high = new double[] {p.high[0] - Wt.hold_depth * (1.0 - share[k]), p.high[1]};
            }
        }
        for (Player p : players) {
            p.forward = isForward(p);       // cel tinut mai jos nu mai e om de atac
        }
        return Math.max(0.0, Wt.run_target - strength);
    }

    /**
     * Cat coboara fiecare jucator cand echipa se apara (0 = ramane unde il tine pozitia, 1 = coboara
     * pe linia mijlocasilor). Cei mai avansati {@code drop_stay_up} raman sus.
     */
    static void dropShares(Player[] players) {
        List<Player> order = advanced(players, false);
        double[] share = shares(order, Wt.drop_stay_up, false);
        for (int k = 0; k < order.size(); k++) {
            Player p = order.get(k);
            p.dropK = p.deep[0] > Wt.drop_deep ? 1.0 - share[k] : 0.0;
        }
    }
}
