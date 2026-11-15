package match2d.engine;

import java.util.ArrayList;
import java.util.List;

/** Cine primeste un rol in afara pozitiei: iese la presing, primeste pasa, intercepteaza, alearga dupa minge. */
final class Roles {
    private Roles() {
    }

    /** Unde va fi mingea din 0.15 in 0.15 s: randuri {tau, x, y, inaltime}, pana iese din teren. */
    static List<double[]> ballTrack(Sim s) {
        Ball b = s.ball;
        double t = s.t;
        List<double[]> track = new ArrayList<>(34);
        double tau = 0.1;
        while (tau <= 5.0) {
            b.at(tau, t);
            double x = b.ax;
            double y = b.ay;
            track.add(new double[] {tau, x, y, b.az});
            if (x < -0.5 || x > Geo.L + 0.5 || y < -0.5 || y > Geo.W + 0.5) {
                break;                  // a iesit din teren: nimeni nu o mai ajunge dupa
            }
            tau += 0.15;
        }
        return track;
    }

    /** Primul moment in care {@code p} poate ajunge la minge pe traiectoria ei, sau null. */
    static double[] firstReach(Sim s, Player p, List<double[]> track) {
        Ball b = s.ball;
        double t = s.t;
        double delay = 0.0;
        if (p != b.receiver) {
            delay = b.kickT + s.reactTo(p) - t;
            if (delay < 0.0) {
                delay = 0.0;
            }
        }
        double st = p.stunUntil - t;
        if (st > 0.0) {
            delay += st;
        }
        double inv = 1.0 / (p.vmax * 0.92);
        double px = p.x;
        double py = p.y;
        double zmax = (p.isGk && s.inOwnBox(p)) ? 2.6 : 1.9;
        for (double[] row : track) {
            double tau = row[0];
            double x = row[1];
            double y = row[2];
            if (x < -0.5 || x > Geo.L + 0.5 || y < -0.5 || y > Geo.W + 0.5) {
                return null;
            }
            if (row[3] <= zmax) {
                double dd = Sim.hyp(x - px, y - py) - 0.9;
                double tp = delay + (dd > 0.0 ? dd * inv + 0.2 : 0.0);
                if (tp <= tau) {
                    return row;
                }
            }
        }
        return null;
    }

    /**
     * Cine iese la minge: cei mai apropiati jucatori in a caror zona a intrat mingea si, peste
     * numarul lor, cel care tocmai a pierdut-o si inca o urmareste.
     */
    static void pickPressers(Sim s, Team dteam, double x, double y, int count) {
        double t = s.t;
        Player chaser = null;
        double chaseD = Wt.chase_lost_radius;
        for (Player p : dteam.players) {
            if (p.isGk || p.role != Sim.R_NONE || p.stunUntil > t || t - p.lostT >= p.chaseFor) {
                continue;
            }
            double dd = Sim.hyp(p.x - x, p.y - y);
            if (dd < chaseD) {              // il ia pe cel mai apropiat, oriunde i-ar fi zona
                chaseD = dd;
                chaser = p;
            }
        }
        double baseMargin = dteam.pressMargin;
        double kTired = Wt.tired_press;
        // (distanta, jucator), tinute sortate dupa distanta si, la egalitate, dupa locul in echipa
        List<Player> order = new ArrayList<>(10);
        List<Double> keys = new ArrayList<>(10);
        for (Player p : dteam.players) {
            if (p.isGk || p.role != Sim.R_NONE || p.stunUntil > t) {
                continue;
            }
            double dd = Sim.hyp(p.x - x, p.y - y);
            double margin = baseMargin;
            if (p.tired > 0.0) {
                margin *= 1.0 - kTired * p.tired;       // obosit: iese mai greu din zona lui
            }
            double key;
            if (s.inZone(p, x, y, margin)) {
                key = dd;
            } else if (s.inZone(p, x, y, 14.0)) {
                key = dd + 12.0;
            } else {
                continue;
            }
            int at = 0;
            while (at < keys.size() && keys.get(at) <= key) {
                at++;
            }
            keys.add(at, key);
            order.add(at, p);
        }
        List<Player> chosen = new ArrayList<>(order.subList(0, Math.min(count, order.size())));
        // iese in plus doar daca nu merge deja si daca nu sunt deja destui la minge
        if (chaser != null && !chosen.contains(chaser) && chosen.size() < Wt.chase_lost_max_pressers) {
            chosen.add(chaser);
        }
        int d = dteam.dir;
        double gx = d == 1 ? 0.0 : Geo.L;
        double gl = Sim.hyp(gx - x, Geo.MID_Y - y);
        if (gl == 0.0) {
            gl = 1.0;
        }
        for (Player p : chosen) {
            p.role = Sim.R_PRESS;
            p.rx = x + (gx - x) / gl * 1.0;
            p.ry = y + (Geo.MID_Y - y) / gl * 1.0;
        }
    }

    static void assignRoles(Sim s) {
        double t = s.t;
        Ball b = s.ball;
        s.rolesAt = t + 0.3;
        for (Player p : s.all) {
            p.role = Sim.R_NONE;
        }
        if (s.state == Sim.DEAD) {
            Player tk = s.taker;
            if (tk != null) {
                tk.role = Sim.R_RESTART;
                tk.rx = b.x;
                tk.ry = b.y;
            }
            return;
        }
        if (s.state != Sim.LIVE) {
            return;
        }
        Player c = b.holder;
        if (c != null) {
            if (c.isGk && s.gkHold) {
                return;
            }
            if (t < s.noTackleUntil) {
                return;
            }
            Team dteam = s.opp(c.team);
            double ccx = dteam.dir == 1 ? c.x : Geo.L - c.x;
            // langa poarta proprie ies trei, la mijloc doi, sus unul; presingul agresiv adauga oameni
            pickPressers(s, dteam, c.x, c.y, Math.max(1, (ccx < 22.0 ? 3 : ccx < 42.0 ? 2 : 1) + dteam.pressExtra));
            return;
        }
        if (b.kind == Ball.SHOT) {
            Sim.ShotState sh = s.shot;
            Player gk = sh.gk;
            gk.role = Sim.R_SAVE;
            gk.rx = sh.gx;
            gk.ry = sh.gy;
            return;
        }
        // toti cei care pot ajunge la minge, in ordinea in care ajung (la egalitate, dupa uid)
        List<double[]> track = ballTrack(s);
        List<Player> who = new ArrayList<>(22);
        List<double[]> where = new ArrayList<>(22);
        for (Player p : s.all) {
            double[] fr = firstReach(s, p, track);
            if (fr != null) {
                int at = 0;
                while (at < where.size() && where.get(at)[0] <= fr[0]) {
                    at++;
                }
                where.add(at, fr);
                who.add(at, p);
            }
        }
        b.at(6.0, t);
        double ex = Geo.clamp(b.ax, 0.5, Geo.L - 0.5);
        double ey = Geo.clamp(b.ay, 0.5, Geo.W - 0.5);
        if (b.kind == Ball.PASS) {
            Player r = b.receiver;
            Team kt = b.kicker.team;
            double tr = Double.NaN;
            for (int i = 0; i < who.size(); i++) {
                if (who.get(i) == r) {
                    double[] fr = where.get(i);
                    tr = fr[0];
                    r.role = Sim.R_RECV;
                    r.rx = fr[1];
                    r.ry = fr[2];
                    break;
                }
            }
            if (Double.isNaN(tr)) {
                tr = 99.0;
                r.role = Sim.R_RECV;
                r.rx = ex;
                r.ry = ey;
            }
            int n = 0;
            boolean helper = false;
            int maxInter = (s.pending != null && s.pending.through) ? 3 : 2;
            for (int i = 0; i < who.size(); i++) {
                Player p = who.get(i);
                double[] fr = where.get(i);
                double tt = fr[0];
                if (p.team == kt) {
                    if (!helper && p != r && tt + 0.6 < tr && !(p == b.kicker && t - b.kickT < 0.8)) {
                        p.role = Sim.R_RECV;
                        p.rx = fr[1];
                        p.ry = fr[2];
                        helper = true;
                    }
                    continue;
                }
                if (n >= maxInter || tt > tr + 0.5) {
                    continue;
                }
                boolean ok;
                if (p.isGk) {
                    ok = s.inZone(p, fr[1], fr[2], 0.0);
                } else {
                    ok = s.inZone(p, fr[1], fr[2], 7.0) || (n == 0 && tt < tr);
                }
                if (ok) {
                    p.role = Sim.R_INTERCEPT;
                    p.rx = fr[1];
                    p.ry = fr[2];
                    n++;
                }
            }
            if (n < 2) {
                pickPressers(s, s.opp(kt), r.rx, r.ry, 1);
            }
            return;
        }
        // minge libera: cate un urmaritor de echipa (cel care ajunge primul)
        for (Team team : s.teams) {
            boolean got = false;
            for (int i = 0; i < who.size(); i++) {
                Player p = who.get(i);
                if (p.team != team) {
                    continue;
                }
                double[] fr = where.get(i);
                if (p.isGk && !s.inZone(p, fr[1], fr[2], 0.0)) {
                    continue;
                }
                p.role = Sim.R_CHASE;
                p.rx = fr[1];
                p.ry = fr[2];
                got = true;
                break;
            }
            if (!got) {
                Player best = null;
                double bd = 0.0;
                for (Player p : team.players) {
                    if (p.isGk) {
                        continue;
                    }
                    double dd = Sim.hyp(p.x - ex, p.y - ey);
                    if (best == null || dd < bd) {
                        best = p;
                        bd = dd;
                    }
                }
                best.role = Sim.R_CHASE;
                best.rx = ex;
                best.ry = ey;
            }
        }
    }
}
