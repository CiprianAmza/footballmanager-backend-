package match2d.engine;

/**
 * Unde vrea sa stea un jucator fara minge. Toate punctele sunt in canonicul echipei si raman in
 * zona pozitiei lui; rezultatul fiecarei functii e lasat in {@code s.ox, s.oy}.
 */
final class Positioning {
    private Positioning() {
    }

    /** Punctul de baza al pozitiei, in functie de minge si de faza. */
    static void formTarget(Sim s, Player p, double bcx, double bcy, boolean has) {
        Team team = p.team;
        double k = bcx / Geo.L + (has ? 0.10 : -0.10) + team.block;
        if (p.isDef && team.lineShift != 0.0) {
            k += team.lineShift;
        }
        if (k < 0.0) {
            k = 0.0;
        } else if (k > 1.0) {
            k = 1.0;
        }
        double dx = p.deep[0];
        double dy = p.deep[1];
        double hx = p.high[0];
        double hy = p.high[1];
        double lo = has ? p.abox[0] : p.dbox[0];
        if (!has && !Sim.LEGACY) {
            if (p.dropK > 0.0) {
                // atacantul care coboara: cu mingea in jumatatea lui sta ca un mijlocas
                double w = p.dropK * Geo.clamp((Wt.drop_from - bcx) / Wt.drop_blend, 0.0, 1.0);
                if (w > 0.0) {
                    dx -= (dx - Wt.drop_deep) * w;
                    if (lo > Wt.drop_box) {
                        lo -= (lo - Wt.drop_box) * w;
                    }
                }
            }
            p.dlo = lo;
        }
        double cx = dx + (hx - dx) * k;
        double cy0 = dy + (hy - dy) * k;
        double cy;
        double[] b;
        if (has) {
            double wa = team.widthAtt;
            if (wa == 1.0) {
                cy = cy0 + (bcy - Geo.MID_Y) * 0.15;
            } else {
                cy = Geo.MID_Y + (cy0 - Geo.MID_Y) * wa + (bcy - Geo.MID_Y) * 0.15;
            }
            b = p.abox;
        } else {
            cy = Geo.MID_Y + (cy0 - Geo.MID_Y) * team.widthDef + (bcy - Geo.MID_Y) * 0.33;
            b = p.dbox;
        }
        if (cx < lo) {
            cx = lo;
        } else if (cx > b[1]) {
            cx = b[1];
        }
        if (cy < b[2]) {
            cy = b[2];
        } else if (cy > b[3]) {
            cy = b[3];
        }
        s.ox = cx;
        s.oy = cy;
    }

    /**
     * teamwork: purtatorul e presat ({@code press} 0..1), deci coechipierul care nu e cu mult in
     * fata mingii isi muta punctul de plecare (fx, fy) spre el, sa-i ofere o pasa scurta. Nu vine
     * mai aproape de {@code help_keep} metri.
     */
    static void helpPoint(Player p, double fx, double fy, double bcx, double bcy, double press, Sim s) {
        double dd = Sim.hyp(fx - bcx, fy - bcy);
        if (press > 0.0 && dd > Wt.help_keep && dd < Wt.help_range && fx < bcx + Wt.help_ahead) {
            double k = Math.min(press * (Wt.help_base + Wt.help_teamwork * p.n[A.teamwork]),
                    1.0 - Wt.help_keep / dd);
            if (k > 0.0) {
                fx += (bcx - fx) * k;
                fy += (bcy - fy) * k;
            }
        }
        s.ox = fx;
        s.oy = fy;
    }

    /** Faza ofensiva: cel mai bun punct din zona (liber, cu culoar de pasa, periculos). */
    static void supportPoint(Sim s, Player p, double fx, double fy, double bcx, double bcy) {
        if (Math.abs(fx - bcx) > 45.0) {
            s.ox = fx;
            s.oy = fy;
            return;
        }
        Team team = p.team;
        int d = team.dir;
        double otb = p.n[A.offTheBall];
        double kThreat = Wt.support_threat;
        double otbBase = Wt.support_offTheBall_base;
        // teamwork: cat de mult conteaza pentru el sa fie liber la pasa
        double kLane = Sim.LEGACY ? 0.7 : Wt.support_lane_base
                + Wt.support_lane_teamwork * (p.n[A.teamwork] - Wt.support_lane_teamwork_from);
        // sutorul isi cauta pozitie de sut
        double r = Wt.support_radius + Wt.support_offTheBall * otb + (p.special ? 4.0 : 0.0);
        double[] b = p.abox;
        double lim = Math.max(s.offsideLine(team) - 0.8, bcx);
        Player[] opps = s.opp(team).players;
        double[] ox = new double[11];
        double[] oy = new double[11];
        for (int i = 0; i < 11; i++) {
            ox[i] = Geo.cx(opps[i].x, d);
            oy[i] = Geo.cy(opps[i].y, d);
        }
        double[] mx = new double[10];
        double[] my = new double[10];
        int nm = 0;
        for (Player m : team.players) {
            if (m != p) {
                mx[nm] = Geo.cx(m.x, d);
                my[nm] = Geo.cy(m.y, d);
                nm++;
            }
        }
        double pcx = Geo.cx(p.x, d);
        double pcy = Geo.cy(p.y, d);
        double prevX = Geo.cx(p.tx, d);
        double prevY = Geo.cy(p.ty, d);
        double bestX = 0.0;
        double bestY = 0.0;
        double bestS = -1e9;
        double[] candX = {0.0, r, 0.5 * r, 0.5 * r, -0.5 * r, -0.5 * r, -r, 1.8 * r};
        double[] candY = {0.0, 0.0, 0.87 * r, -0.87 * r, 0.87 * r, -0.87 * r, 0.0, 0.0};
        for (int c = 0; c < 8; c++) {
            double x = fx + candX[c];
            double y = fy + candY[c];
            if (x > lim) {
                x = lim;
            }
            if (x < b[0]) {
                x = b[0];
            } else if (x > b[1]) {
                x = b[1];
            }
            if (y < b[2]) {
                y = b[2];
            } else if (y > b[3]) {
                y = b[3];
            }
            double dn = 7.0;
            double lane = 1.0;
            double ddx = x - bcx;
            double ddy = y - bcy;
            double d2 = ddx * ddx + ddy * ddy;
            for (int i = 0; i < 11; i++) {
                double qx = ox[i];
                double qy = oy[i];
                double ex = qx - x;
                double ey = qy - y;
                if (-dn < ex && ex < dn && -dn < ey && ey < dn) {     // altfel e sigur mai departe decat dn
                    double dd = Sim.hyp(ex, ey);
                    if (dd < dn) {
                        dn = dd;
                    }
                }
                if (lane > 0.0 && d2 > 4.0) {
                    double u = ((qx - bcx) * ddx + (qy - bcy) * ddy) / d2;
                    if (0.1 < u && u < 0.95) {
                        double px = bcx + u * ddx - qx;
                        double py = bcy + u * ddy - qy;
                        if (px * px + py * py < 3.6) {
                            lane = 0.0;
                        }
                    }
                }
            }
            double crowd = 0.0;
            for (int i = 0; i < nm; i++) {
                double ex = mx[i] - x;
                double ey = my[i] - y;
                if (-8.0 < ex && ex < 8.0 && -8.0 < ey && ey < 8.0) {
                    double dd = Sim.hyp(ex, ey);
                    if (dd < 8.0) {
                        crowd += 1.0 - dd / 8.0;
                    }
                }
            }
            double dGoal = Sim.hyp(Geo.L - x, Geo.MID_Y - y);
            double score = (0.9 * dn / 7.0 + kLane * lane + kThreat * Geo.threat(x, y) * (otbBase + otb)
                    + (p.special && 18.0 <= dGoal && dGoal <= 30.0 ? 2.0 : 0.0)
                    - 0.08 * Sim.hyp(x - fx, y - fy) - 0.6 * crowd
                    - 0.02 * Sim.hyp(x - pcx, y - pcy) - 0.05 * Sim.hyp(x - prevX, y - prevY));
            if (score > bestS) {
                bestS = score;
                bestX = x;
                bestY = y;
            }
        }
        s.ox = bestX;
        s.oy = bestY;
    }

    /** Faza defensiva: se trage spre adversarul din zona lui, intre el si poarta. */
    static void markPoint(Sim s, Player p, double fx, double fy, double bcx, double bcy) {
        boolean man = markMan(s, p, fx, fy);
        if (Sim.LEGACY || man || bcx >= Wt.cover_from || s.ox >= bcx - 1.0) {
            return;                 // cine tine un om ramane cu el
        }
        // positioning: mingea e aproape de poarta lui, el e mai aproape de poarta decat mingea si
        // n-are om: se trage lateral spre linia minge - centrul portii, ca sa inchida drumul sutului
        double danger = Geo.clamp((Wt.cover_from - bcx) / (Wt.cover_from - Wt.cover_full), 0.0, 1.0);
        double lineY = Geo.MID_Y + (bcy - Geo.MID_Y) * (s.ox / bcx);
        double off = lineY - s.oy;
        if (Math.abs(off) < Wt.cover_reach) {
            double w = danger * (Wt.cover_base + Wt.cover_positioning * p.n[A.positioning]);
            if (w > 0.0) {
                double[] b = p.dbox;
                s.oy = Geo.clamp(s.oy + off * w, b[2], b[3]);
            }
        }
    }

    /** Punctul de marcaj: intre adversarul din zona lui si poarta. Intoarce daca are un om de tinut. */
    static boolean markMan(Sim s, Player p, double fx, double fy) {
        Team team = p.team;
        int d = team.dir;
        Player holder = s.ball.holder;
        boolean found = false;
        double ocx = 0.0;
        double ocy = 0.0;
        double bd = p.isDef ? 13.0 : 9.0;
        for (Player o : s.opp(team).players) {
            if (o == holder || o.isGk) {
                continue;
            }
            double cx = Geo.cx(o.x, d);
            double cy = Geo.cy(o.y, d);
            double dd = Sim.hyp(cx - fx, cy - fy);
            if (dd < bd && cx < fx + 10.0) {
                bd = dd;
                ocx = cx;
                ocy = cy;
                found = true;
            }
        }
        if (!found) {
            s.ox = fx;
            s.oy = fy;
            return false;
        }
        double gl = Sim.hyp(ocx, ocy - Geo.MID_Y);
        if (gl == 0.0) {
            gl = 1.0;
        }
        double mk = p.n[A.marking];
        boolean deep = ocx < 32.0;
        double gap = deep ? (Wt.marking_gap_deep + Wt.marking_marking_gap_deep * (1.0 - mk))
                : (Wt.marking_gap + Wt.marking_marking_gap * (1.0 - mk));
        double mx = ocx - ocx / gl * gap;
        double my = ocy - (ocy - Geo.MID_Y) / gl * gap;
        double w = ((Wt.marking_pull + Wt.marking_marking * mk + (deep ? Wt.marking_pull_deep : 0.0))
                * (p.isDef ? 1.0 : Wt.marking_non_defender)) * p.team.shape;
        double x = fx + (mx - fx) * w;
        double y = fy + (my - fy) * w;
        double[] b = p.dbox;
        s.ox = Geo.clamp(x, Sim.LEGACY ? b[0] : p.dlo, b[1]);
        s.oy = Geo.clamp(y, b[2], b[3]);
        return true;
    }

    static void gkTarget(Sim s, Player p, double bcx, double bcy, boolean has) {
        double distB = Sim.hyp(bcx, bcy - Geo.MID_Y);
        if (distB == 0.0) {
            distB = 1.0;
        }
        double rmax = Wt.gk_position_range_base + Wt.gk_position_commandOfArea * p.n[A.commandOfArea] * (
                has ? 1.0 : Wt.gk_position_defending);
        double r = Geo.clamp(0.14 * distB - 0.8, 1.0, rmax);
        if (distB < 22.0) {
            r = Math.max(r, Math.min(3.5, distB * 0.3));
        }
        Player holder = s.ball.holder;
        if (holder != null && holder.team != p.team && distB < 16.0) {
            // unu la unu: iese spre atacant daca nu e niciun fundas langa el
            double near = Double.POSITIVE_INFINITY;
            for (Player m : p.team.players) {
                if (m != p) {
                    near = Math.min(near, Sim.hyp(m.x - holder.x, m.y - holder.y));
                }
            }
            if (near > 3.0) {
                r = Math.max(r, distB * (Wt.gk_position_oneOnOnes_base + Wt.gk_position_oneOnOnes * p.n[A.oneOnOnes]));
            }
        }
        double cx = bcx / distB * r;
        double cy = Geo.MID_Y + (bcy - Geo.MID_Y) / distB * r;
        double[] b = has ? p.abox : p.dbox;
        s.ox = Geo.clamp(cx, Math.max(0.8, b[0]), b[1]);
        s.oy = Geo.clamp(cy, b[2], b[3]);
    }
}
