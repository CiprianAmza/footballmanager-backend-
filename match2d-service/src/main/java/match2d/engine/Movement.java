package match2d.engine;

import java.util.ArrayList;
import java.util.List;

/** Alegerea tintei fiecarui jucator si deplasarea spre ea, cu viteza si acceleratie limitate. */
final class Movement {
    private Movement() {
    }

    static void setTarget(Sim s, Player p, double tx, double ty, boolean useGrid) {
        p.tx = tx;
        p.ty = ty;
        double[] pg = p.pg;
        double t = s.t;
        if (pg == null || (tx - pg[0]) * (tx - pg[0]) + (ty - pg[1]) * (ty - pg[1]) > 2.25
                || (useGrid && t >= p.replanAt)) {
            if (useGrid && (tx - p.x) * (tx - p.x) + (ty - p.y) * (ty - p.y) > 9.0) {
                s.dijkstraRuns++;
                p.path = s.grids[1 - p.team.index].path(p.x, p.y, tx, ty, 0.6, 1.2);
                if (p.path.size() > 1) {
                    s.dijkstraDetours++;
                }
            } else {
                p.path = one(tx, ty);
            }
            p.pg = new double[] {tx, ty};
            p.replanAt = t + 1.0;
        } else if (!p.path.isEmpty()) {
            p.path.set(p.path.size() - 1, new double[] {tx, ty});
        } else {
            p.path = one(tx, ty);
        }
    }

    private static List<double[]> one(double x, double y) {
        List<double[]> path = new ArrayList<>(1);
        path.add(new double[] {x, y});
        return path;
    }

    static void think(Sim s, Player p) {
        Ball b = s.ball;
        if (b.holder == p) {
            return;
        }
        int role = p.role;
        if (role != Sim.R_NONE) {
            p.sprint = true;
            if (role == Sim.R_PRESS) {
                Player c = b.holder;
                if (c != null) {
                    // iese in intampinare: tinteste unde va fi purtatorul, pe partea portii
                    int d = p.team.dir;
                    double tau = Math.min(1.0, Sim.hyp(c.x - p.x, c.y - p.y) / p.vmax) * 0.8;
                    double lx = c.x + c.vx * tau;
                    double ly = c.y + c.vy * tau;
                    double gx = d == 1 ? 0.0 : Geo.L;
                    double gl = Sim.hyp(gx - lx, Geo.MID_Y - ly);
                    if (gl == 0.0) {
                        gl = 1.0;
                    }
                    p.rx = Geo.clamp(lx + (gx - lx) / gl * 0.8, 0.3, Geo.L - 0.3);
                    p.ry = Geo.clamp(ly + (Geo.MID_Y - ly) / gl * 0.8, 0.3, Geo.W - 0.3);
                }
            }
            setTarget(s, p, p.rx, p.ry, false);
            return;
        }
        boolean dead = s.state == Sim.DEAD;
        SetPiece plan = s.sp;
        if (!Sim.LEGACY && dead && plan != null && plan.has[p.uid]) {
            // faza fixa: are loc de ocupat (zid, linia de la penalty, portarul pe linie)
            double wx = plan.x[p.uid];
            double wy = plan.y[p.uid];
            double far = (wx - p.x) * (wx - p.x) + (wy - p.y) * (wy - p.y);
            p.sprint = far > Wt.setpiece_sprint * Wt.setpiece_sprint;
            setTarget(s, p, wx, wy, false);
            return;
        }
        Team team = p.team;
        int d = team.dir;
        double bcx = Geo.cx(b.x, d);
        double bcy = Geo.cy(b.y, d);
        boolean has = s.poss == team;
        double cx;
        double cy;
        boolean useGrid;
        if (p.isGk) {
            Positioning.gkTarget(s, p, bcx, bcy, has);
            cx = s.ox;
            cy = s.oy;
            useGrid = false;
        } else if (dead && "kickoff".equals(s.rkind)) {
            s.kickoffPoint(p, has);
            cx = s.ox;
            cy = s.oy;
            useGrid = false;
        } else {
            Positioning.formTarget(s, p, bcx, bcy, has);
            double fx = s.ox;
            double fy = s.oy;
            if (has) {
                Player c = b.holder;
                if (p.running) {
                    // alearga in careu: pleaca de la locul lui de pe linia de atac
                    fx = p.runX;
                    fy = p.runY;
                } else if (!Sim.LEGACY && c != null && c.team == team) {
                    Positioning.helpPoint(p, fx, fy, bcx, bcy, s.holderPress(), s);
                    fx = s.ox;
                    fy = s.oy;
                }
                Positioning.supportPoint(s, p, fx, fy, bcx, bcy);
                cx = s.ox;
                cy = s.oy;
                if (!Sim.LEGACY && dead && "penalty".equals(s.rkind)) {
                    // la penalty, cine nu e pe linie sta ca de obicei, dar in afara careului si
                    // la cel putin 9.15 m de minge
                    double lim = Geo.L - Wt.pen_line;
                    if (cx > lim && Math.abs(cy - Geo.MID_Y) < 22.0) {
                        cx = lim;
                    }
                }
            } else {
                Positioning.markPoint(s, p, fx, fy, bcx, bcy);
                cx = s.ox;
                cy = s.oy;
                if (dead) {
                    double dd = Sim.hyp(cx - bcx, cy - bcy);
                    if (dd < 9.5) {
                        // distanta regulamentara: se retrage spre poarta proprie
                        cx = bcx > 12.0 ? Math.max(p.dbox[0], bcx - 9.5) : cx;
                        if (Sim.hyp(cx - bcx, cy - bcy) < 9.5) {
                            cy = Geo.clamp(bcy + (cy >= bcy ? 9.5 : -9.5), p.dbox[2], p.dbox[3]);
                        }
                    }
                }
            }
            useGrid = true;
        }
        double wx = Geo.cx(cx, d);
        double wy = Geo.cy(cy, d);
        double far = (wx - p.x) * (wx - p.x) + (wy - p.y) * (wy - p.y);
        // sprinteaza daca e departe de locul lui: in atac de la 14 m; in aparare de la o distanta
        // care scade cu workRate (track2), iar langa poarta lui porneste oricine de la 7 m
        p.sprint = has ? far > (p.running ? 9.0 : 196.0) : (far > p.track2 || (far > 49.0 && bcx < 40.0));
        setTarget(s, p, wx, wy, useGrid);
    }

    static void move(Sim s, Player p, Player holder) {
        List<double[]> path = p.path;
        double wx;
        double wy;
        if (!path.isEmpty()) {
            wx = path.get(0)[0];
            wy = path.get(0)[1];
        } else {
            wx = p.tx;
            wy = p.ty;
        }
        double dx = wx - p.x;
        double dy = wy - p.y;
        double dd = Math.sqrt(dx * dx + dy * dy);
        if (path.size() > 1 && dd < 1.2) {
            path.remove(0);
            wx = path.get(0)[0];
            wy = path.get(0)[1];
            dx = wx - p.x;
            dy = wy - p.y;
            dd = Math.sqrt(dx * dx + dy * dy);
        }
        double sp = p.vmax;
        if (p == holder) {
            sp *= p.carryK;
        } else if (!p.sprint) {
            sp *= p.jog;
            if (p.team == s.poss) {
                sp *= p.team.tempoMove;
            }
        }
        if (p.stunUntil > s.t) {
            sp *= 0.3;
        }
        if (path.size() <= 1 && dd * 2.5 < sp) {
            sp = dd * 2.5;
        }
        double dvx;
        double dvy;
        if (dd > 1e-6) {
            dvx = dx / dd * sp - p.vx;
            dvy = dy / dd * sp - p.vy;
        } else {
            dvx = -p.vx;
            dvy = -p.vy;
        }
        double dv = Math.sqrt(dvx * dvx + dvy * dvy);
        double lim = p.acc * Sim.DT;
        if (dv > lim) {
            double k = lim / dv;
            dvx *= k;
            dvy *= k;
        }
        p.vx += dvx;
        p.vy += dvy;
        double x = p.x + p.vx * Sim.DT;
        double y = p.y + p.vy * Sim.DT;
        if (x < 0.2) {
            x = 0.2;
        } else if (x > Geo.L - 0.2) {
            x = Geo.L - 0.2;
        }
        if (y < 0.2) {
            y = 0.2;
        } else if (y > Geo.W - 0.2) {
            y = Geo.W - 0.2;
        }
        p.x = x;
        p.y = y;
    }

    static void refreshGrids(Sim s) {
        s.gridAt = s.t + 0.3;
        for (Team team : s.teams) {
            Grid g = s.grids[team.index];
            g.clear();
            for (Player p : team.players) {
                g.stamp(p.x, p.y, 4.5, 3.0);
            }
        }
    }
}
