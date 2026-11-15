package match2d.engine;

import java.util.ArrayList;
import java.util.Map;

/**
 * Decizia purtatorului: compara sutul, pasa la picior, pasa in alergare (pe jos sau pe sus),
 * conducerea pana intr-un punct si pastrarea mingii. Fiecare varianta are valoarea
 * P(reusita) x valoarea pozitiei x spatiul de acolo - P(esec) x cat costa pierderea mingii,
 * cu un zgomot care scade cu {@code decisions} si {@code composure}.
 */
final class Decision {
    private static final int SHOT = 1, PASS = 2, CARRY = 3, HOLD = 4;

    private Decision() {
    }

    /** Adversarii vazuti de purtator: pozitie, timp de reactie (cu ameteala), inversul vitezei. */
    static final class Opp {
        final double[] x = new double[11];
        final double[] y = new double[11];
        final double[] react = new double[11];
        final double[] invV = new double[11];
        final Player[] p = new Player[11];
    }

    /**
     * Probabilitatea ca nimeni sa nu ajunga la pasa inaintea primitorului. {@code race} = la capat
     * e cursa pentru minge (pasa in alergare / minge inalta); la o pasa la picior primitorul e deja
     * acolo, deci adversarul o poate lua doar iesind in fata lui.
     */
    static double lane(double ax, double ay, double bx, double by, double v0, double tEnd,
                       boolean loft, Opp ol, double shield, boolean race) {
        double dx = bx - ax;
        double dy = by - ay;
        double d2 = dx * dx + dy * dy;
        double d = Math.sqrt(d2);
        double prob = 1.0;
        for (int i = 0; i < 11; i++) {
            double ox = ol.x[i];
            double oy = ol.y[i];
            double react = ol.react[i];
            double invV = ol.invV[i];
            double m;
            if (race) {
                double de = Sim.hyp(ox - bx, oy - by) - 0.9;
                m = react + (de > 0.0 ? de * invV : 0.0) + shield - tEnd;
            } else {
                m = 9.9;
            }
            if (!loft) {
                double u = ((ox - ax) * dx + (oy - ay) * dy) / d2;
                if (u > 0.04 && (race || u < 1.0)) {
                    if (u > 1.0) {
                        u = 1.0;
                    }
                    double dc = Sim.hyp(ox - ax - u * dx, oy - ay - u * dy) - 0.9;
                    double disc = v0 * v0 - 2.0 * Sim.A_GROUND * u * d;
                    double tb = disc > 0.0 ? (v0 - Math.sqrt(disc)) / Sim.A_GROUND : 99.0;
                    double mc = react + (dc > 0.0 ? dc * invV : 0.0) - tb;
                    if (mc < m) {
                        m = mc;
                    }
                }
            } else {
                // o minge inalta poate fi blocata doar chiar langa cel care paseaza
                double u = ((ox - ax) * dx + (oy - ay) * dy) / d2;
                double ud = u * d;
                if (0.0 < ud && ud < 2.5 && Sim.hyp(ox - ax - u * dx, oy - ay - u * dy) < 1.0) {
                    m = Math.min(m, -0.1);
                }
            }
            if (m < 0.9) {
                prob *= 1.0 / (1.0 + PMath.exp(-7.0 * m));
                if (prob < 0.02) {
                    return prob;
                }
            }
        }
        return prob;
    }

    /** Cat spatiu va avea cineva in (x, y) peste {@code tarr} secunde: 0 (adversar in el) .. 1. */
    static double freeAt(double x, double y, double tarr, Opp ol) {
        double dn = 9.0;
        for (int i = 0; i < 11; i++) {
            double ex = ol.x[i] - x;
            double ey = ol.y[i] - y;
            if (!(-16.0 < ex && ex < 16.0 && -16.0 < ey && ey < 16.0)) {
                continue;               // sigur la peste 16 m
            }
            double dd = Sim.hyp(ex, ey);
            if (dd < 16.0) {
                double tm = tarr - ol.react[i];
                if (tm > 0.0) {
                    dd -= tm / ol.invV[i] * 0.6;
                }
                if (dd < dn) {
                    dn = dd;
                }
            }
        }
        double f = (dn - 1.0) / 7.0;
        return f < 0.0 ? 0.0 : f > 1.0 ? 1.0 : f;
    }

    /** Probabilitatea de a ajunge cu mingea din A in B fara sa fie deposedat. */
    static double keepProb(Player p, double ax, double ay, double bx, double by, double ln,
                           double cs, Opp ol) {
        double tc = ln / cs;
        double pk = 1.0;
        for (int i = 0; i < 11; i++) {
            double ox = ol.x[i];
            double oy = ol.y[i];
            if (Math.abs(ox - ax) > ln + 9.0) {
                continue;
            }
            double u = Geo.segU(ox, oy, ax, ay, bx, by);
            double dseg = Geo.segDist(ox, oy, ax, ay, bx, by);
            if (dseg > 9.0) {
                continue;
            }
            double reach;
            double behind;
            if (u > 0.05) {
                double uc = u > 1.0 ? 1.0 : u;
                double tm = uc * tc - ol.react[i];
                reach = tm > 0.0 ? (tm / ol.invV[i] * 0.7) : 0.0;
                behind = 1.0;
            } else {
                double rel = 1.0 / ol.invV[i] - cs;
                reach = rel > 0.0 ? rel * tc * 0.7 : 0.0;
                behind = 0.4;        // il urmareste din spate: ataca rar
            }
            double gap = dseg - reach - Sim.TACKLE_R;
            if (gap > 3.5) {
                continue;
            }
            double risk = Sim.sigmoid(-gap * 1.5) * behind;
            Player o = ol.p[i];
            double duel = o.isGk ? 0.85 : Sim.sigmoid(Wt.duel_slope * (o.tk - p.dr) + Wt.duel_belief_bias);
            pk *= 1.0 - risk * duel;
        }
        return pk;
    }

    /**
     * Centrare: purtatorul e pe banda (la cel putin {@code cross_wide} m de ax) si sus (x canonic
     * de la {@code cross_from_x}), iar tinta e in careul advers. Totul in canonicul purtatorului.
     */
    static boolean isCross(double pcx, double pcy, double qcx, double qcy) {
        return Math.abs(pcy - Geo.MID_Y) >= Wt.cross_wide && pcx >= Wt.cross_from_x
                && qcx >= Geo.L - 16.5 && Math.abs(qcy - Geo.MID_Y) <= 20.16;
    }

    /** Cat de probabil crede jucatorul ca da gol de aici. */
    static double xgEst(Sim s, Player p, double pcx, double pcy, double press, Team opp) {
        double[] n = p.n;
        double dgoal = Sim.hyp(Geo.L - pcx, Geo.MID_Y - pcy);
        double w = Geo.clamp((dgoal - 18.0) / 10.0, 0.0, 1.0);
        double skill = ((Wt.shot_belief_finishing_base + Wt.shot_belief_finishing * n[A.finishing]) * (1.0 - w)
                + (Wt.shot_belief_longShots_base + Wt.shot_belief_longShots * n[A.longShots]) * w);
        double xg = Geo.goalGeom(pcx, pcy) * skill * (1.0 - Wt.shot_belief_pressure * press);
        double gx = p.team.dir == 1 ? Geo.L : 0.0;
        for (Player o : opp.players) {
            if (o.isGk) {
                continue;
            }
            double u = Geo.segU(o.x, o.y, p.x, p.y, gx, Geo.MID_Y);
            if (0.03 < u && u < 0.9 && Geo.segDist(o.x, o.y, p.x, p.y, gx, Geo.MID_Y) < 1.2) {
                xg *= 0.7;
            }
        }
        return xg;
    }

    /**
     * leadership: langa capitan presiunea apasa mai putin (sau mai mult, daca e un capitan slab).
     * Efectul e intreg pana la {@code captain_radius} metri de capitan si se stinge la dublul ei;
     * capitanul insusi nu se ajuta singur.
     */
    static double feltPressure(Player p, double press) {
        Player cap = p.team.captain;
        if (Sim.LEGACY || cap == p || press <= 0.0) {
            return press;
        }
        double near = Geo.clamp(2.0 - Sim.hyp(cap.x - p.x, cap.y - p.y) / Wt.captain_radius, 0.0, 1.0);
        return Geo.clamp(press * (1.0 - Wt.captain_leadership
                * (cap.n[A.leadership] - Wt.captain_leadership_from) * near), 0.0, 1.0);
    }

    static void decide(Sim s, Player p) {
        double t = s.t;
        Mt rng = s.rng;
        Team team = p.team;
        Team opp = s.opp(team);
        int d = team.dir;
        double px = p.x;
        double py = p.y;
        double pcx = Geo.cx(px, d);
        double pcy = Geo.cy(py, d);
        String rk = s.restartKind;
        double[] n = p.n;
        double nd = 99.0;
        Opp ol = new Opp();
        for (int i = 0; i < 11; i++) {
            Player o = opp.players[i];
            double dd = Sim.hyp(o.x - px, o.y - py);
            if (dd < nd) {
                nd = dd;
            }
            double st = o.stunUntil - t;
            ol.x[i] = o.x;
            ol.y[i] = o.y;
            ol.react[i] = o.react + (st > 0.0 ? st : 0.0);
            ol.invV[i] = 1.0 / (o.vmax * 0.92);
            ol.p[i] = o;
        }
        double press = rk != null ? 0.0 : Geo.clamp((6.0 - nd) / 4.5, 0.0, 1.0);
        // pressM e presiunea "simtita", cea care strica decizia si executia
        double pressM = feltPressure(p, press);
        double noise = (Wt.decision_noise_base + Wt.decision_noise_decisions * (1.0 - n[A.decisions])) * (
                1.0 + Wt.decision_noise_composure * pressM * (1.0 - n[A.composure]));
        double bestEv = -1e9;
        int bestKind = 0;
        Player bestM = null;
        double bestX = 0.0;
        double bestY = 0.0;
        boolean bestLoft = false;
        double bestPar = 0.0;
        boolean bestThrough = false;
        double bestP = 0.0;
        boolean specialShot = false;
        double turn = Sim.TURNOVER * team.risk;
        double psShort = team.passShort;
        double psLong = team.passLong;
        double psThrough = team.passThrough;
        double fwd = team.forward;
        double rawBest = -1e9;        // cea mai buna varianta, fara zgomotul de decizie

        // ---- sut
        if (rk == null && !p.isGk && pcx > 62.0) {
            double xg = xgEst(s, p, pcx, pcy, press, opp);
            if (p.special) {
                double dg = Sim.hyp(Geo.L - pcx, Geo.MID_Y - pcy);
                if (16.0 <= dg && dg <= 34.0 && press < 0.6) {
                    // sutul special: aproape imparabil daca are timp si spatiu sa-l pregateasca
                    double calm = 1.0 - press;
                    double xs = (0.80 + 0.04 * p.specialLvl) * (calm * calm * Math.sqrt(calm));
                    double gxw = d == 1 ? Geo.L : 0.0;
                    for (Player o : opp.players) {
                        if (!o.isGk) {
                            double u = Geo.segU(o.x, o.y, px, py, gxw, Geo.MID_Y);
                            if (0.03 < u && u < 0.9 && Geo.segDist(o.x, o.y, px, py, gxw, Geo.MID_Y) < 1.2) {
                                xs *= 0.6;
                            }
                        }
                    }
                    if (xs > xg) {
                        xg = xs;
                        specialShot = true;
                    }
                }
            }
            if (xg > 0.02) {
                double ev = xg - (1.0 - xg) * 0.008;
                if (ev > rawBest) {
                    rawBest = ev;
                }
                ev += rng.gauss(0.0, noise) * (Math.abs(ev) + 0.004);
                if (ev > bestEv) {
                    bestEv = ev;
                    bestKind = SHOT;
                }
            }
        }

        // ---- pase
        double vision = rk != null ? 60.0 : Wt.vision_base + Wt.vision_vision * n[A.vision];
        double maxd = "throw".equals(rk) ? 24.0 : 70.0;
        double sig = s.passSigma(p, pressM, false);
        double sigL = s.passSigma(p, pressM, true);
        boolean checkOff = rk == null || rk.equals("free_kick") || rk.equals("kickoff");
        double offLine = s.offsideLine(team) + rng.gauss(0.0, Wt.offside_judgement_base
                + Wt.offside_judgement_decisions * (1.0 - n[A.decisions]));
        for (Player m : team.players) {
            if (m == p) {
                continue;
            }
            double mx = m.x;
            double my = m.y;
            double dm = Sim.hyp(mx - px, my - py);
            if (dm < 3.5 || dm > vision) {
                continue;
            }
            double mcx = Geo.cx(mx, d);
            double mcy = Geo.cy(my, d);
            if (checkOff && mcx > offLine && mcx > pcx + 0.5) {
                continue;
            }
            double back = (p.lastFrom == m && t - p.lastFromT < 3.0) ? 0.85 : 1.0;
            // -- la picior (tinta = unde va fi cand ajunge mingea)
            double tb = dm / 14.0;
            double qx = Geo.clamp(mx + m.vx * tb * 0.8, 1.0, Geo.L - 1.0);
            double qy = Geo.clamp(my + m.vy * tb * 0.8, 1.0, Geo.W - 1.0);
            double dq = Sim.hyp(qx - px, qy - py);
            if (3.0 < dq && dq <= maxd && (!m.isGk || pcx < 40.0)) {
                double qcx = Geo.cx(qx, d);
                double qcy = Geo.cy(qy, d);
                double vt = Geo.threat(qcx, qcy);
                if (m.special && vt < 0.6) {
                    double dg = Sim.hyp(Geo.L - qcx, Geo.MID_Y - qcy);
                    if (16.0 <= dg && dg <= 34.0) {
                        vt = 0.6;
                    }
                }
                double lossv = Geo.threat(Geo.L - qcx, Geo.W - qcy) * 0.8 + turn;
                double va = (10.0 + 0.1 * dq) * (Wt.pass_speed_base + Wt.pass_speed_passing * n[A.passing]);
                double v0 = Math.min(26.0, Math.sqrt(va * va + 2.0 * Sim.A_GROUND * dq));
                double tEnd = (v0 - Math.sqrt(v0 * v0 - 2.0 * Sim.A_GROUND * dq)) / Sim.A_GROUND;
                double fr = freeAt(qx, qy, tEnd, ol);
                double space = 0.4 + 0.6 * fr;
                double risk = 1.0 - 0.35 * (1.0 - fr);      // primitorul poate fi atacat imediat
                double off = sig * dq / 4.0;
                double pr = (lane(px, py, qx, qy, v0, tEnd, false, ol, 0.25, false)
                        * PMath.exp(-(off * off)) * 0.97);
                double ev = (pr * risk * vt * space - (1.0 - pr * risk) * lossv) * back;
                double kb = (dq < 18.0 ? psShort : dq >= 28.0 ? psLong : 1.0) * (
                        qcx - pcx >= 8.0 ? fwd : 1.0);
                if (kb != 1.0) {
                    ev = ev > 0.0 ? ev * kb : ev / kb;
                }
                if (ev > rawBest) {
                    rawBest = ev;
                }
                ev += rng.gauss(0.0, noise) * (Math.abs(ev) + 0.004);
                if (ev > bestEv) {
                    bestEv = ev;
                    bestKind = PASS;
                    bestM = m;
                    bestX = qx;
                    bestY = qy;
                    bestLoft = false;
                    bestPar = v0;
                    bestThrough = false;
                    bestP = pr;
                }
                // centrare (doar pe drumul nou): de pe banda, sus, in careu; permisa si de aproape
                boolean cross = !Sim.LEGACY && rk == null && isCross(pcx, pcy, qcx, qcy);
                if (dq > 20.0 || (cross && dq > Wt.cross_min_dist)) {
                    double dur = 0.75 + dq / 24.0;
                    off = (cross ? s.crossSigma(p, pressM, 1) : sigL) * dq / 5.0;
                    pr = (lane(px, py, qx, qy, 0.0, dur, true, ol, 0.6, true) * PMath.exp(-(off * off))
                            * (Wt.receive_belief_loft_base
                               + Wt.receive_belief_firstTouch * m.n[A.firstTouch])
                            * (1.0 - 0.5 * (1.0 - fr)));
                    if (cross) {
                        // in careu mingea se joaca si disputata (duel aerian, cap): centratorul
                        // nu renunta doar pentru ca un adversar e langa primitor
                        pr = Wt.cross_contest + (1.0 - Wt.cross_contest) * pr;
                    }
                    // primitorul poate da direct cu capul: valoarea creste cu jocul lui aerian
                    double vtl = cross ? vt * (1.0 + Wt.cross_header_air * (m.air - 0.5)) : vt;
                    ev = (pr * risk * vtl * space - (1.0 - pr * risk) * lossv) * back;
                    kb = (cross ? Wt.cross_bias : psLong) * (qcx - pcx >= 8.0 ? fwd : 1.0);
                    if (kb != 1.0) {
                        ev = ev > 0.0 ? ev * kb : ev / kb;
                    }
                    if (ev > rawBest) {
                        rawBest = ev;
                    }
                    ev += rng.gauss(0.0, noise) * (Math.abs(ev) + 0.004);
                    if (ev > bestEv) {
                        bestEv = ev;
                        bestKind = PASS;
                        bestM = m;
                        bestX = qx;
                        bestY = qy;
                        bestLoft = true;
                        bestPar = dur;
                        bestThrough = false;
                        bestP = pr;
                    }
                }
            }
            // -- in alergare (tinta = un punct in fata lui, in zona lui de atac)
            if (m.isGk || mcx < 30.0 || "kickoff".equals(rk)) {
                continue;
            }
            double gdx = Geo.L - 6.0 - mcx;
            double gdy = Geo.MID_Y - mcy;
            double gl = Sim.hyp(gdx, gdy);
            if (gl == 0.0) {
                gl = 1.0;
            }
            int nDirs = gdx / gl < 0.93 ? 2 : 1;
            double[] ab = m.abox;
            double mspeed = m.vmax * 0.95;
            for (int di = 0; di < nDirs; di++) {
                double ux = di == 0 ? 1.0 : gdx / gl;
                double uy = di == 0 ? 0.0 : gdy / gl;
                for (int li = 0; li < 3; li++) {
                    double lead = li == 0 ? 9.0 : li == 1 ? 16.0 : 24.0;
                    double tcx = Geo.clamp(mcx + ux * lead, ab[0], Math.min(ab[1], Geo.L - 2.0));
                    double tcy = Geo.clamp(mcy + uy * lead, Math.max(ab[2], 1.5), Math.min(ab[3], Geo.W - 1.5));
                    if (tcx < mcx + 4.0) {
                        continue;
                    }
                    qx = Geo.cx(tcx, d);
                    qy = Geo.cy(tcy, d);
                    dq = Sim.hyp(qx - px, qy - py);
                    if (dq < 6.0 || dq > maxd) {
                        continue;
                    }
                    double tm = 0.25 + Sim.hyp(tcx - mcx, tcy - mcy) / mspeed;
                    double vt = Geo.threat(tcx, tcy) * (tcx > offLine ? 1.15 : 1.0);
                    double lossv = Geo.threat(Geo.L - tcx, Geo.W - tcy) * 0.8 + turn;
                    double tbw = Math.min((Math.sqrt(25.0 + 2.0 * Sim.A_GROUND * dq) - 5.0) / Sim.A_GROUND, tm + 0.2);
                    double v0 = Geo.clamp(dq / tbw + 0.5 * Sim.A_GROUND * tbw, 8.0, 25.0);
                    double disc = v0 * v0 - 2.0 * Sim.A_GROUND * dq;
                    if (disc > 0.0) {
                        double tb2 = (v0 - Math.sqrt(disc)) / Sim.A_GROUND;
                        // daca mingea ajunge prea tare sau cu mult inaintea lui, fuge de el
                        double late = (disc < 100.0 && tb2 > tm - 1.0) ? 1.0 : 0.7;
                        double off = sig * dq / 5.0;
                        double pr = (lane(px, py, qx, qy, v0, Math.max(tb2, tm), false, ol, 0.15, true)
                                * PMath.exp(-(off * off)) * 0.93 * late);
                        double fr = freeAt(qx, qy, Math.max(tb2, tm), ol);
                        double prr = pr * (1.0 - 0.35 * (1.0 - fr));
                        double ev = prr * vt * (0.4 + 0.6 * fr) - (1.0 - prr) * lossv;
                        double kb = psThrough * (tcx - pcx >= 8.0 ? fwd : 1.0);
                        if (kb != 1.0) {
                            ev = ev > 0.0 ? ev * kb : ev / kb;
                        }
                        if (ev > rawBest) {
                            rawBest = ev;
                        }
                        ev += rng.gauss(0.0, noise) * (Math.abs(ev) + 0.004);
                        if (ev > bestEv) {
                            bestEv = ev;
                            bestKind = PASS;
                            bestM = m;
                            bestX = qx;
                            bestY = qy;
                            bestLoft = false;
                            bestPar = v0;
                            bestThrough = true;
                            bestP = pr;
                        }
                    }
                    if (dq > 22.0) {
                        double dur = 0.75 + dq / 24.0;
                        double off = sigL * dq / 6.0;
                        double pr = (lane(px, py, qx, qy, 0.0, Math.max(dur, tm), true, ol, 0.15, true)
                                * PMath.exp(-(off * off))
                                * (Wt.receive_belief_through_loft_base
                                   + Wt.receive_belief_firstTouch * m.n[A.firstTouch]));
                        double fr = freeAt(qx, qy, Math.max(dur, tm), ol);
                        double prr = pr * (1.0 - 0.6 * (1.0 - fr));
                        double ev = prr * vt * (0.4 + 0.6 * fr) - (1.0 - prr) * lossv;
                        double kb = psLong * (tcx - pcx >= 8.0 ? fwd : 1.0);
                        if (kb != 1.0) {
                            ev = ev > 0.0 ? ev * kb : ev / kb;
                        }
                        if (ev > rawBest) {
                            rawBest = ev;
                        }
                        ev += rng.gauss(0.0, noise) * (Math.abs(ev) + 0.004);
                        if (ev > bestEv) {
                            bestEv = ev;
                            bestKind = PASS;
                            bestM = m;
                            bestX = qx;
                            bestY = qy;
                            bestLoft = true;
                            bestPar = dur;
                            bestThrough = true;
                            bestP = pr;
                        }
                    }
                }
            }
        }

        // ---- conducere pana intr-un punct X din zona lui (sau tine de minge)
        if (rk == null && !p.isGk) {
            double[] ab = p.abox;
            double cs = p.vmax * p.carryK;
            double cbias = p.carryBias * team.dribble;
            for (int k = 0; k < 8; k++) {
                double ang = k * (Math.PI / 4);
                double ux = PMath.cos(ang);
                double uy = PMath.sin(ang);
                for (int di = 0; di < 2; di++) {
                    double distC = di == 0 ? 5.0 : 10.0;
                    double tcx = Geo.clamp(pcx + ux * distC, ab[0], Math.min(ab[1], Geo.L - 1.5));
                    double tcy = Geo.clamp(pcy + uy * distC, Math.max(ab[2], 1.0), Math.min(ab[3], Geo.W - 1.0));
                    double ln = Sim.hyp(tcx - pcx, tcy - pcy);
                    if (ln < 2.0) {
                        continue;
                    }
                    double wx = Geo.cx(tcx, d);
                    double wy = Geo.cy(tcy, d);
                    double fr = freeAt(wx, wy, ln / cs, ol);
                    // daca la capatul cursei are adversar langa el, urmeaza un duel
                    double pk = keepProb(p, px, py, wx, wy, ln, cs, ol) * (1.0 - 0.5 * (1.0 - fr));
                    double ev = (pk * Geo.threat(tcx, tcy) * (0.25 + 0.75 * fr) * cbias
                            * (1.0 - 0.10 * ln / cs)
                            - (1.0 - pk) * (Geo.threat(Geo.L - tcx, Geo.W - tcy) * 0.8 + turn));
                    if (ev > rawBest) {
                        rawBest = ev;
                    }
                    ev += rng.gauss(0.0, noise) * (Math.abs(ev) + 0.004);
                    if (ev > bestEv) {
                        bestEv = ev;
                        bestKind = CARRY;
                        bestX = wx;
                        bestY = wy;
                    }
                }
            }
        }

        // ---- tine de minge. Fara presiune, valoreaza cat pozitia. Sub presiune nu mai are timp:
        // valoreaza cel mult cat cea mai buna actiune de dupa, daca intre timp nu pierde mingea.
        if (rk == null && !p.isGk) {
            double pk = 1.0;
            for (int i = 0; i < 11; i++) {
                Player o = ol.p[i];
                double dd = Sim.hyp(ol.x[i] - px, ol.y[i] - py);
                if (dd < 5.0 && !o.isGk) {
                    double duel = Sim.sigmoid(Wt.duel_slope * (o.tk - p.dr) + Wt.duel_belief_bias);
                    pk *= 1.0 - 0.45 * (dd > Sim.TACKLE_R ? 1.0 - (dd - Sim.TACKLE_R) / 3.2 : 1.0) * duel;
                }
            }
            double stay = Geo.threat(pcx, pcy) * (0.25 + 0.75 * freeAt(px, py, 0.6, ol)) * (1.0 - press);
            double ev = (pk * (stay > rawBest ? stay : rawBest)
                    - (1.0 - pk) * (Geo.threat(Geo.L - pcx, Geo.W - pcy) * 0.8 + turn) - 0.0005);
            ev += rng.gauss(0.0, noise) * (Math.abs(ev) + 0.004);
            if (ev > bestEv) {
                bestEv = ev;
                bestKind = HOLD;
            }
        }

        double kt = team.tempoTime;
        p.decideAt = (t + Wt.decision_time_base * kt
                + Wt.decision_time_decisions * (1.0 - n[A.decisions]) * kt);
        if (bestKind == 0 || (rk != null && bestKind != PASS)) {
            // repunere fara nicio varianta buna: paseaza celui mai apropiat coechipier
            Player m = null;
            double bk = 0.0;
            for (Player c : team.players) {
                if (c == p) {
                    continue;
                }
                double key = Math.abs(Sim.hyp(c.x - px, c.y - py) - 12.0);
                if (m == null || key < bk) {
                    m = c;
                    bk = key;
                }
            }
            double dq = Sim.hyp(m.x - px, m.y - py);
            bestKind = PASS;
            bestM = m;
            bestX = m.x;
            bestY = m.y;
            bestLoft = false;
            bestPar = Math.min(26.0, Math.sqrt(121.0 + 2.0 * Sim.A_GROUND * dq));
            bestThrough = false;
            bestP = 0.8;
        }
        if (bestKind == SHOT) {
            Shooting.doShot(s, p, pressM, specialShot);
        } else if (bestKind == PASS) {
            // o minge inalta la picior care indeplineste conditiile e o centrare
            int sub = !Sim.LEGACY && rk == null && bestLoft && !bestThrough
                    && isCross(pcx, pcy, Geo.cx(bestX, d), Geo.cy(bestY, d)) ? 1 : 0;
            doPass(s, p, bestM, bestX, bestY, bestLoft, bestPar, bestThrough, bestP, pressM, sub);
        } else if (bestKind == CARRY) {
            double wx = bestX;
            double wy = bestY;
            if (p.carryTo == null || Sim.hyp(p.carryTo[0] - wx, p.carryTo[1] - wy) > 2.0) {
                team.carries++;
                s.ev("CARRY", team, p, null, null, Double.NaN, Double.NaN, wx, wy);
            }
            p.carryTo = new double[] {wx, wy};
            p.tx = wx;
            p.ty = wy;
            p.pg = new double[] {wx, wy};
            s.dijkstraRuns++;
            p.path = s.grids[opp.index].path(px, py, wx, wy, 1.5, 0.3);
            if (p.path.size() > 1) {
                s.dijkstraDetours++;
            }
            p.sprint = true;
        } else {
            p.carryTo = null;
            p.tx = px;
            p.ty = py;
            p.path = new ArrayList<>();
            p.pg = null;
        }
    }

    static void doPass(Sim s, Player p, Player m, double qx, double qy, boolean loft, double par,
                       boolean through, double est, double press) {
        doPass(s, p, m, qx, qy, loft, par, through, est, press, 0);
    }

    /**
     * {@code sub}: 0 = pasa obisnuita, 1 = centrare din joc, 2 = corner, 3 = lovitura libera
     * centrata (1..3 doar pe drumul nou: eroarea vine din {@link Sim#crossSigma}).
     */
    static void doPass(Sim s, Player p, Player m, double qx, double qy, boolean loft, double par,
                       boolean through, double est, double press, int sub) {
        double t = s.t;
        Mt rng = s.rng;
        Ball b = s.ball;
        Team team = p.team;
        int d = team.dir;
        double px = p.x;
        double py = p.y;
        String rk = s.restartKind;
        double sigma = sub == 0 ? s.passSigma(p, press, loft) : s.crossSigma(p, press, sub);
        double ang = PMath.atan2(qy - py, qx - px) + rng.gauss(0.0, sigma);
        double dq = Sim.hyp(qx - px, qy - py);
        double tech = p.n[A.technique];
        b.holder = null;
        s.played(p);
        b.kind = Ball.PASS;
        b.kicker = p;
        b.receiver = m;
        b.kickT = t;
        b.lastTeam = team;
        b.x = px;
        b.px = px;
        b.y = py;
        b.py = py;
        b.ux = PMath.cos(ang);
        b.uy = PMath.sin(ang);
        b.z = 0.0;
        if (loft) {
            double de = dq * (1.0 + rng.gauss(0.0, Wt.pass_weight_loft_base
                    + Wt.pass_weight_technique_loft * (1.0 - tech)));
            double lx = px + b.ux * de;
            double ly = py + b.uy * de;
            b.setLoft(px, py, lx, ly, t, par, Math.min(9.0, 1.5 + dq * 0.12), Math.min(9.0, dq / par * 0.5));
            b.v = dq / par;
        } else {
            b.lofted = false;
            b.v = par * (1.0 + rng.gauss(0.0, Wt.pass_weight_base
                    + Wt.pass_weight_technique * (1.0 - tech)));
            b.decel = Sim.A_GROUND;
        }
        boolean offside = false;
        if (rk == null || rk.equals("free_kick") || rk.equals("kickoff")) {
            double mcx = d == 1 ? m.x : Geo.L - m.x;
            double pcx = d == 1 ? px : Geo.L - px;
            offside = mcx > s.offsideLine(team) + 0.3 && mcx > pcx + 0.3;
        }
        String key = sub == 1 ? "cross" : sub == 2 ? "corner" : sub == 3 ? "fk_cross"
                : (through ? "through" : "feet") + (loft ? "_loft" : "");
        Sim.Pending pend = new Sim.Pending();
        pend.sub = sub;
        pend.kicker = p;
        pend.recv = m;
        pend.through = through;
        pend.loft = loft;
        pend.offside = offside;
        pend.ox = m.x;
        pend.oy = m.y;
        pend.key = key;
        if (!Sim.LEGACY) {
            // cine il tinea pe primitor: adversarul cel mai apropiat de el, daca e in raza
            double md = Wt.mark_react_radius;
            for (Player o : s.opp(team).players) {
                double dd = Sim.hyp(o.x - m.x, o.y - m.y);
                if (!o.isGk && dd < md) {
                    md = dd;
                    pend.marker = o;
                }
            }
        }
        s.pending = pend;
        double[] cal = s.calib.computeIfAbsent(key, k -> new double[3]);
        cal[0] += 1;
        cal[1] += est;
        team.passes++;
        team.mPassLen += dq;
        if ((qx - px) * d >= 5.0) {
            team.mFwd++;
        }
        if (!Double.isNaN(p.gainT)) {
            team.mBallTime += t - p.gainT;
            team.mBallN++;
        }
        if (through) {
            team.through++;
        }
        if (loft) {
            team.longBalls++;
        }
        String txt;
        if (sub == 1) {
            txt = "Centrare: " + p.shortName + " → " + m.shortName;
        } else if (sub == 2) {
            txt = "Corner: " + p.shortName + " centrează spre " + m.shortName;
        } else if (sub == 3) {
            txt = "Lovitură liberă: " + p.shortName + " centrează spre " + m.shortName;
        } else if (through) {
            txt = "Pasă în adâncime: " + p.shortName + " → " + m.shortName;
        } else if (loft) {
            txt = "Minge lungă: " + p.shortName + " → " + m.shortName;
        } else {
            txt = null;
        }
        if (sub != 0) {
            team.crosses++;
        }
        Map<String, Object> e = s.ev("PASS", team, p, m, txt, Double.NaN, Double.NaN, qx, qy);
        e.put("sub", sub == 1 ? "cross" : sub == 2 ? "corner" : sub == 3 ? "free_kick" : through ? "through" : "feet");
        e.put("loft", loft);
        s.restartKind = null;
        s.gkHold = false;
        p.touchCd = t + 0.6;
        p.carryTo = null;
        p.tx = px;
        p.ty = py;
        p.path = new ArrayList<>();
        p.pg = null;
        Roles.assignRoles(s);
    }
}
