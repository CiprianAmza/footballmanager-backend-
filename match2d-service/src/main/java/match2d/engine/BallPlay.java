package match2d.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Ce se intampla cu mingea: preluari, interceptii, iesiri din teren, goluri, deposedari. */
final class BallPlay {
    private BallPlay() {
    }

    static void looseBall(Sim s, double x, double y, double ang, double speed, Team lastTeam) {
        looseBall(s, x, y, ang, speed, lastTeam, null);
    }

    /** {@code toucher} = cine a atins mingea fara s-o controleze (deviere, respingere), daca se stie. */
    static void looseBall(Sim s, double x, double y, double ang, double speed, Team lastTeam, Player toucher) {
        Ball b = s.ball;
        if (toucher != null) {
            b.toucher = toucher;
        }
        s.pending = null;
        s.shot = null;
        s.gkHold = false;
        b.holder = null;
        b.kind = Ball.LOOSE;
        b.lofted = false;
        b.z = 0.0;
        b.x = Geo.clamp(x, 0.1, Geo.L - 0.1);
        b.px = b.x;
        b.y = Geo.clamp(y, 0.1, Geo.W - 0.1);
        b.py = b.y;
        b.ux = PMath.cos(ang);
        b.uy = PMath.sin(ang);
        b.v = speed;
        b.decel = Sim.A_GROUND;
        b.kicker = null;
        b.receiver = null;
        b.kickT = s.t;
        b.lastTeam = lastTeam;
        Roles.assignRoles(s);
    }

    /** Posesia s-a schimbat: fiecare jucator isi regandeste pozitia dupa timpul lui de reactie. */
    static void turnover(Sim s) {
        double t = s.t;
        for (Player p : s.all) {
            double ta = t + p.react * (0.5 + s.rng.random());
            if (ta < p.thinkAt) {
                p.thinkAt = ta;
            }
        }
    }

    /** Jucatorul {@code q} a preluat mingea. */
    static void gain(Sim s, Player q) {
        double t = s.t;
        Ball b = s.ball;
        Sim.Pending pend = s.pending;
        s.pending = null;
        // are mingea dintr-o pasa a unui coechipier doar daca urmeaza ramura de mai jos
        q.assistFrom = null;
        if (pend != null) {
            Player k = pend.kicker;
            if (q.team == k.team) {
                if (pend.offside && q == pend.recv) {
                    q.team.offsides++;
                    s.ev("OFFSIDE", q.team, q, null, "Ofsaid: " + q.shortName);
                    s.setRestart("free_kick", s.opp(q.team), pend.ox, pend.oy);
                    return;
                }
                Team kt = k.team;
                kt.passesOk++;
                if (pend.sub != 0) {
                    kt.crossesOk++;         // centrarea a ajuns la un coechipier (doar drumul nou)
                }
                s.calib.get(pend.key)[2] += 1;
                if (pend.through) {
                    kt.throughOk++;
                }
                if (pend.loft) {
                    kt.longOk++;
                }
                String txt = null;
                if (pend.through) {
                    txt = "Pasă în adâncime reușită: " + k.shortName + " → " + q.shortName;
                }
                s.ev("PASS_OK", k.team, q, k, txt);
                q.lastFrom = k;
                q.lastFromT = t;
                if (k != q) {
                    q.assistFrom = k;
                }
            } else {
                q.team.interceptions++;
                s.ev("INTERCEPT", q.team, q, k, q.shortName + " interceptează pasa lui " + k.shortName);
                k.lostT = t;
            }
        }
        b.holder = q;
        s.played(q);
        b.kind = Ball.HELD;
        b.v = 0.0;
        b.lofted = false;
        b.z = 0.0;
        b.x = q.x;
        b.px = q.x;
        b.y = q.y;
        b.py = q.y;
        b.receiver = null;
        b.lastTeam = q.team;
        s.shot = null;
        if (s.poss != q.team) {
            if (t - q.team.lostAt <= 5.0) {
                q.team.mRegains++;          // minge recastigata in 5 s de la pierdere
            }
            if (t - q.lostT <= 6.0) {
                q.team.mChaseRegains++;     // recastigata chiar de cel care a pierdut-o
            }
            s.poss.lostAt = t;
            if (!Sim.LEGACY) {
                Runs.stop(s.poss);          // au pierdut mingea: alergarile in careu se opresc
            }
            s.poss = q.team;
            turnover(s);
        }
        s.gkHold = false;
        double kt = q.team.tempoTime;
        q.canActAt = (t + Wt.control_time_base * kt
                + Wt.control_time_firstTouch * (1.0 - q.n[A.firstTouch]) * kt);
        q.gainT = t;
        for (Player o : s.opp(q.team).players) {
            if (Math.abs(o.x - q.x) < 3.5 && Math.abs(o.y - q.y) < 3.5) {
                // are adversar in coasta: joaca dintr-o atingere
                q.canActAt = Math.min(q.canActAt, t + 0.3);
                break;
            }
        }
        if (q.special) {
            double qcx = Geo.cx(q.x, q.team.dir);
            double qcy = Geo.cy(q.y, q.team.dir);
            double dg = Sim.hyp(Geo.L - qcx, Geo.MID_Y - qcy);
            if (16.0 <= dg && dg <= 34.0) {
                q.canActAt = Math.min(q.canActAt, t + 0.15);      // sutul special pleaca din prima
            }
        }
        q.safeUntil = q.canActAt + 0.15;
        q.path = new ArrayList<>();
        q.pg = null;
        if (q.isGk && s.inOwnBox(q)) {
            s.gkHold = true;
            q.canActAt = t + 1.3 + s.rng.random();
            q.carryTo = null;
            q.tx = q.x;
            q.ty = q.y;
        } else {
            // isi continua alergarea cu mingea pana decide
            double sp = Sim.hyp(q.vx, q.vy);
            if (sp > 2.0) {
                int d = q.team.dir;
                double cx = Geo.cx(q.x + q.vx * 0.9, d);
                double cy = Geo.cy(q.y + q.vy * 0.9, d);
                double[] ab = q.abox;
                cx = Geo.clamp(cx, ab[0], Math.min(ab[1], Geo.L - 1.5));
                cy = Geo.clamp(cy, Math.max(1.0, ab[2]), Math.min(Geo.W - 1.0, ab[3]));
                q.carryTo = new double[] {Geo.cx(cx, d), Geo.cy(cy, d)};
                q.tx = q.carryTo[0];
                q.ty = q.carryTo[1];
                List<double[]> path = new ArrayList<>(1);
                path.add(q.carryTo);
                q.path = path;
            } else {
                q.carryTo = null;
                q.tx = q.x;
                q.ty = q.y;
            }
        }
        q.decideAt = q.canActAt;
        q.sprint = true;
        Roles.assignRoles(s);
    }

    static void failPending(Sim s, String why, String text) {
        Sim.Pending pend = s.pending;
        s.pending = null;
        if (pend != null) {
            Player k = pend.kicker;
            Map<String, Object> e = s.ev("PASS_FAIL", k.team, k, null, text);
            e.put("why", why);
        }
    }

    static void outOfPlay(Sim s) {
        Ball b = s.ball;
        double x = b.x;
        double y = b.y;
        Team last = b.lastTeam != null ? b.lastTeam : s.poss;
        if (x < 0.0 || x > Geo.L) {
            // trecerea liniei de poarta: interpolam unde a taiat-o
            double gx = x < 0.0 ? 0.0 : Geo.L;
            double den = x - b.px;
            double yc = Math.abs(den) > 1e-6 ? b.py + (y - b.py) * ((gx - b.px) / den) : y;
            Team defending = ((s.teams[0].dir == 1) == (gx == 0.0)) ? s.teams[0] : s.teams[1];
            Team attacking = s.opp(defending);
            // scor tinta cu cota atinsa: golul fara sut (pasa, centrare, autogol) nu se mai da, mingea
            // e socotita iesita pe langa poarta (corner sau lovitura de poarta)
            if (Math.abs(yc - Geo.MID_Y) < Geo.GOAL_HALF && b.z < Geo.GOAL_H
                    && (s.target == null || s.goalsLeft(attacking) > 0)) {
                failPending(s, "goal", null);
                if (Sim.LEGACY) {
                    goal(s, attacking, null, last == defending, null);
                } else if (b.toucher != null && b.toucher.team == attacking) {
                    // o pasa, o centrare sau o lovitura de cap care a intrat: golul e al lui
                    scored(s, attacking, b.toucher, null);
                } else {
                    scored(s, attacking, null, b.toucher);      // autogol
                }
                return;
            }
            if (s.pending != null) {
                failPending(s, "out", "Pasă greșită: mingea iese din teren");
            }
            if (last == defending) {
                attacking.corners++;
                s.ev("CORNER", attacking, null, null, "Corner pentru " + attacking.name);
                s.setRestart("corner", attacking, gx, yc < Geo.MID_Y ? 0.0 : Geo.W);
            } else {
                s.ev("GOAL_KICK", defending, null, null, "Lovitură de poartă: " + defending.name);
                double cy = Geo.cy(Geo.clamp(yc, 0, Geo.W), defending.dir);
                double kx = Geo.cx(5.5, defending.dir);
                double ky = Geo.cy(Geo.MID_Y + (cy > Geo.MID_Y ? 6.0 : -6.0), defending.dir);
                s.setRestart("goal_kick", defending, kx, ky);
            }
            return;
        }
        if (s.pending != null) {
            failPending(s, "out", "Pasă greșită: mingea iese în aut");
        }
        Team team = s.opp(last);
        s.ev("THROW_IN", team, null, null, "Aut pentru " + team.name, x, Geo.clamp(y, 0, Geo.W),
                Double.NaN, Double.NaN);
        s.setRestart("throw", team, Geo.clamp(x, 1.0, Geo.L - 1.0), y < Geo.MID_Y ? 0.0 : Geo.W);
    }

    /**
     * Gol dupa regulile noi: pasa decisiva vine din sirul celor care au jucat mingea, iar un
     * autogol ({@code scorer} null) spune cine l-a dat.
     */
    static void scored(Sim s, Team team, Player scorer, Player ownPlayer) {
        team.goals++;
        if (s.t >= s.halfLen * 4.0 / 3.0) {
            team.mGoalsLate++;          // gol in ultima treime a meciului
        }
        s.updateEffort();
        Player assist = scorer != null ? s.assistFor(scorer) : null;
        String how = s.goalHow;
        s.goalHow = null;
        if ("penalty".equals(how)) {
            team.penaltyGoals++;
        } else if ("free_kick".equals(how)) {
            team.fkGoals++;
        } else if ("header".equals(how)) {
            team.headerGoals++;
        }
        boolean recent = s.lastSpKind != null && s.t - s.lastSpT <= Wt.setpiece_after;
        if (recent && "corner".equals(s.lastSpKind)) {
            team.cornerGoals++;                 // gol la cel mult 10 s dupa un corner (si direct)
        }
        String who;
        if (scorer != null) {
            who = scorer.shortName + (assist != null ? ", pasă " + assist.shortName : "");
            if ("penalty".equals(how)) {
                who += ", din penalty";
            } else if ("free_kick".equals(how)) {
                who += ", din lovitură liberă";
            }
            if ("header".equals(how)) {
                who = scorer.shortName + ", cu capul" + (assist != null ? ", pasă " + assist.shortName : "");
            }
        } else {
            who = ownPlayer != null ? "autogol " + ownPlayer.shortName : "autogol";
        }
        int g0 = s.teams[0].goals;
        int g1 = s.teams[1].goals;
        Map<String, Object> e = s.ev("GOAL", team, scorer, null,
                "GOL " + team.name + "! " + who + " (" + g0 + " - " + g1 + ")",
                s.ball.x, s.ball.y, Double.NaN, Double.NaN);
        e.put("score", List.of(g0, g1));
        if (assist != null) {
            e.put("assist", assist.uid);        // cine a dat pasa decisiva
        }
        if (scorer == null && ownPlayer != null) {
            e.put("own", ownPlayer.uid);        // cine a dat autogolul
        }
        if (how != null) {
            e.put("how", how);                  // direct din penalty sau din lovitura libera
        }
        if (recent && !"penalty".equals(how) && !"free_kick".equals(how)) {
            // gol venit la scurt timp dupa o faza fixa (golul direct din ea are doar "how")
            e.put("after", s.lastSpKind);
        }
        s.setRestart("kickoff", s.opp(team), Geo.L / 2, Geo.W / 2, 4.0);
    }

    /**
     * Directia unei mingi respinse de portar sau blocate: daca ar intra in poarta celui care a
     * respins-o, e intoarsa spre teren. O parada reusita nu se mai termina cu gol.
     */
    static double awayFromGoal(double x, double y, double ang, Team defending) {
        if (Sim.LEGACY) {
            return ang;
        }
        double gx = defending.dir == 1 ? 0.0 : Geo.L;
        // mingea unui sut puternic poate fi deja dincolo de linie in pasul paradei; pleaca din
        // teren, din acelasi loc in care o pune looseBall
        x = Geo.clamp(x, 0.1, Geo.L - 0.1);
        y = Geo.clamp(y, 0.1, Geo.W - 0.1);
        double ux = PMath.cos(ang);
        if ((gx - x) * ux > 0.0) {
            double yCross = y + PMath.sin(ang) * ((gx - x) / ux);
            if (Math.abs(yCross - Geo.MID_Y) < Geo.GOAL_HALF + 1.0) {
                return PMath.PI - ang;
            }
        }
        return ang;
    }

    /** Gol dupa regulile motorului Python (doar cu {@code -Dmatch2d.legacy=true} si la sut). */
    static void goal(Sim s, Team team, Player scorer, boolean own, Player assist) {
        team.goals++;
        if (s.t >= s.halfLen * 4.0 / 3.0) {
            team.mGoalsLate++;          // gol in ultima treime a meciului
        }
        s.updateEffort();
        String who = scorer != null ? scorer.shortName : (own ? "autogol" : team.name);
        boolean assisted = assist != null && scorer != null && assist.team == team;
        if (assisted) {
            who += ", pasă " + assist.shortName;
        }
        int g0 = s.teams[0].goals;
        int g1 = s.teams[1].goals;
        Map<String, Object> e = s.ev("GOAL", team, scorer, null,
                "GOL " + team.name + "! " + who + " (" + g0 + " - " + g1 + ")",
                s.ball.x, s.ball.y, Double.NaN, Double.NaN);
        e.put("score", List.of(g0, g1));
        if (assisted) {
            e.put("assist", assist.uid);        // cine a dat pasa decisiva
        }
        s.setRestart("kickoff", s.opp(team), Geo.L / 2, Geo.W / 2, 4.0);
    }

    static void updateBall(Sim s) {
        Ball b = s.ball;
        double t = s.t;
        if (b.holder != null) {
            return;
        }
        b.step(t - Sim.DT);
        if (b.kind == Ball.SHOT) {
            if (t >= s.shot.tEnd) {
                Shooting.finishShot(s);
            }
            return;
        }
        if (b.x < 0.0 || b.x > Geo.L || b.y < 0.0 || b.y > Geo.W) {
            outOfPlay(s);
            return;
        }
        // preluari / interceptii: verificam segmentul parcurs de minge in acest pas
        double z = b.z;
        if (z > 2.6) {
            return;
        }
        double ax = b.px;
        double ay = b.py;
        double bx = b.x;
        double by = b.y;
        Player kicker = b.kicker;
        Player best = null;
        double bd = 9.0;
        for (Player q : s.all) {
            if (q.touchCd > t) {
                continue;
            }
            if (Math.abs(q.x - bx) > 4.0 && Math.abs(q.x - ax) > 4.0) {
                continue;
            }
            double r = Sim.CTRL_R;
            double zmax = 1.9;
            if (q.isGk && s.inOwnBox(q)) {
                r = 1.7;
                zmax = 2.6;
            }
            if (z > zmax) {
                continue;
            }
            double dd = Geo.segDist(q.x, q.y, ax, ay, bx, by);
            if (dd <= r && dd < bd) {
                if (kicker != null && q.team != kicker.team && dd > 0.45 && t - b.kickT < s.reactTo(q)) {
                    continue;    // n-a avut timp sa reactioneze; mingea trece pe langa el
                }
                bd = dd;
                best = q;
            }
        }
        if (best != null) {
            tryControl(s, best);
        }
    }

    static void tryControl(Sim s, Player q) {
        Ball b = s.ball;
        double t = s.t;
        Mt rng = s.rng;
        double v = !b.lofted ? b.v : Sim.hyp(b.lx1 - b.lx0, b.ly1 - b.ly0) / b.lT;
        double[] n = q.n;
        Player kicker = b.kicker;
        double pc;
        if (b.kind == Ball.PASS && kicker != null && q.team == kicker.team) {
            double nd = Double.POSITIVE_INFINITY;
            Player marker = null;
            for (Player o : s.opp(q.team).players) {
                double dd = Sim.hyp(o.x - q.x, o.y - q.y);
                if (dd < nd) {
                    nd = dd;
                    marker = o;
                }
            }
            double press = Geo.clamp((5.0 - nd) / 4.0, 0.0, 1.0);
            pc = (Wt.control_base - Math.max(0.0, v - Wt.control_speed_from) * Wt.control_firstTouch * (1.0 - n[A.firstTouch])
                    - (b.lofted ? Wt.control_firstTouch_loft * (1.0 - n[A.firstTouch]) : 0.0)
                    - press * Wt.control_technique * (1.0 - n[A.technique]));
            if (!Sim.LEGACY && press > 0.0 && !marker.isGk) {
                // marking: cel care il tine ii strica preluarea, cu atat mai mult cu cat e mai aproape
                pc -= press * Wt.mark_touch_marking * (marker.n[A.marking] - Wt.mark_react_marking_from);
            }
        } else if (b.kind == Ball.PASS) {
            pc = Wt.intercept_base - Math.max(0.0, v - Wt.intercept_speed_from) * Wt.intercept_per_speed * (
                    Wt.intercept_anticipation_from - n[A.anticipation]);
            pc += q.role == Sim.R_INTERCEPT ? Wt.intercept_ready : -Wt.intercept_unready;
            if (!Sim.LEGACY && s.pending != null && s.pending.marker == q) {
                // marking: cel care il tinea pe primitor iese in fata lui si ia pasa mai des
                pc += Wt.mark_intercept_marking * (n[A.marking] - Wt.mark_react_marking_from);
            }
        } else {
            pc = Wt.loose_control_base - Math.max(0.0, v - Wt.loose_control_speed_from) * Wt.loose_control_per_speed * (
                    Wt.loose_control_firstTouch_from - n[A.firstTouch]);
            if (!Sim.LEGACY) {
                pc += Wt.loose_control_ballRecovery * (n[A.ballRecovery] - Wt.loose_react_ballRecovery_from);
            }
        }
        if (q.isGk && s.inOwnBox(q)) {
            pc = Wt.gk_claim_base + Wt.gk_claim_handling * n[A.handling];
        } else if (b.lofted) {
            // minge inalta: daca are un adversar langa el, sar amandoi la cap
            Player rival = null;
            double rd = 2.2;
            for (Player o : s.opp(q.team).players) {
                if (o.isGk || o.stunUntil > t) {
                    continue;
                }
                double dd = Sim.hyp(o.x - q.x, o.y - q.y);
                if (dd < rd) {
                    rd = dd;
                    rival = o;
                }
            }
            if (rival != null && !Sim.LEGACY && SetPiece.aerialFoul(s, q, rival)) {
                return;                 // impingere la saritura: jocul s-a oprit
            }
            boolean duel = rival != null;
            if (rival != null) {
                double lg = 3.0 * (q.air - rival.air);
                if (rng.random() < Sim.sigmoid(lg)) {
                    q.team.aerialsWon++;
                    pc *= 0.85;
                    if (!Sim.LEGACY) {
                        s.ev("HEADER", q.team, q, rival, null);     // a castigat duelul aerian
                    }
                } else {
                    rival.team.aerialsWon++;
                    q.touchCd = t + 0.6;
                    if (!Sim.LEGACY && Shooting.canHeadAtGoal(s, rival)) {
                        // atacantul a sarit peste aparatorul la care ajungea mingea: cap la poarta
                        s.ev("HEADER", rival.team, rival, q, null);
                        Shooting.doHeader(s, rival, Wt.header_press_duel);
                        return;
                    }
                    rival.touchCd = t + 0.4;
                    boolean theirs = s.pending != null && q.team == s.pending.kicker.team;
                    int sub = theirs ? s.pending.sub : 0;
                    if (!Sim.LEGACY && theirs && s.inOwnBox(rival) && rng.random() < (sub == 2
                            ? Wt.corner_clear_corner : sub == 0 ? Wt.corner_clear_long : Wt.corner_clear)) {
                        // aparatorul respinge centrarea cu capul in corner
                        failPending(s, "header", rival.shortName + " câștigă duelul aerian cu " + q.shortName);
                        s.ev("HEADER", rival.team, rival, q, null);
                        s.played(rival);
                        SetPiece.toCorner(s, q.team, b.y);
                        return;
                    }
                    if (s.pending != null && q.team == s.pending.kicker.team) {
                        failPending(s, "header", rival.shortName + " câștigă duelul aerian cu " + q.shortName);
                    } else {
                        failPending(s, "header", null);
                    }
                    s.ev("HEADER", rival.team, rival, q, null);
                    double ang = (rival.team.dir == 1 ? 0.0 : Math.PI) + rng.gauss(0.0, 0.7);
                    double speed = 8.0 + 5.0 * rng.random();
                    s.played(rival);        // a jucat mingea cu capul
                    looseBall(s, b.x, b.y, ang, speed, rival.team);
                    return;
                }
            }
            if (!Sim.LEGACY && Shooting.canHeadAtGoal(s, q)) {
                // minge inalta de la un coechipier, in fata portii: da cu capul la poarta
                Shooting.doHeader(s, q, duel ? Wt.header_press_duel : Wt.header_press_free);
                return;
            }
        }
        pc = Geo.clamp(pc, 0.15, 0.99);
        if (kicker != null && q.team != kicker.team && t - b.kickT < s.reactTo(q)) {
            // lovit de minge fara sa apuce sa reactioneze: cel mult o deviaza
            if (rng.random() > 0.4) {
                q.touchCd = t + 0.4;
                return;
            }
            pc = 0.0;
        }
        if (rng.random() < pc) {
            q.x = Geo.clamp(b.x * 0.5 + q.x * 0.5, 0.2, Geo.L - 0.2);
            q.y = Geo.clamp(b.y * 0.5 + q.y * 0.5, 0.2, Geo.W - 0.2);
            if (b.kind == Ball.LOOSE) {
                q.team.mLooseWon++;
            }
            gain(s, q);
            return;
        }
        // atingere proasta: mingea sare din el
        q.touchCd = t + 0.7;
        Sim.Pending pend = s.pending;
        if (pend != null) {
            if (q.team == pend.kicker.team) {
                q.lostT = t;
                failPending(s, "touch", "Preluare greșită: " + q.shortName);
            } else {
                failPending(s, "deflect", q.shortName + " deviază pasa");
                if (!Sim.LEGACY && s.inOwnBox(q) && rng.random() < Wt.corner_deflect) {
                    SetPiece.toCorner(s, pend.kicker.team, b.y);    // deviata in corner
                    return;
                }
            }
        }
        double ang = PMath.atan2(b.uy, b.ux) + rng.gauss(0.0, 0.9);
        if (q.isGk && s.inOwnBox(q)) {
            ang = awayFromGoal(b.x, b.y, ang, q.team);      // portarul scapa mingea in fata, nu in poarta
        }
        double speed = Math.max(2.5, v * (0.3 + 0.3 * rng.random()));
        looseBall(s, b.x, b.y, ang, speed, q.team, q);
    }

    // ------------------------------------------------------------------ purtatorul
    static void carrierLogic(Sim s) {
        Ball b = s.ball;
        Player c = b.holder;
        double t = s.t;
        Team opp = s.opp(c.team);
        double near = 99.0;
        Player[] close = new Player[11];
        double[] closeD = new double[11];
        int nc = 0;
        double cx = c.x;
        double cy = c.y;
        for (Player o : opp.players) {
            if (Math.abs(o.x - cx) > 4.0) {
                continue;
            }
            double dd = Sim.hyp(o.x - cx, o.y - cy);
            if (dd < near) {
                near = dd;
            }
            if (dd < Sim.TACKLE_R && o.tackleCd <= t && o.stunUntil <= t && !o.isGk) {
                close[nc] = o;
                closeD[nc] = dd;
                nc++;
            }
        }
        // intai decide purtatorul (poate scapa de minge), abia apoi se incearca deposedarea
        if (t >= c.canActAt) {
            if (t >= c.decideAt || (near < 3.2 && t >= c.decideAt - 0.25)
                    || (c.carryTo != null && Sim.hyp(c.carryTo[0] - c.x, c.carryTo[1] - c.y) < 0.9)) {
                Decision.decide(s, c);
                if (b.holder != c) {
                    return;
                }
            }
        }
        if (nc == 0 || t < c.safeUntil || t < s.noTackleUntil || (c.isGk && s.gkHold)) {
            return;
        }
        Mt rng = s.rng;
        double kTired = Wt.tired_press;
        double sp = Sim.hyp(c.vx, c.vy);
        for (int i = 0; i < nc; i++) {
            Player o = close[i];
            double dd = closeD[i];
            boolean go;
            if (sp > 1.0 && (c.vx * (o.x - cx) + c.vy * (o.y - cy)) / (sp * (dd != 0.0 ? dd : 0.1)) > 0.2) {
                go = true;       // purtatorul incearca sa treaca de el: duel direct
            } else {
                // purtatorul sta cu spatele sau fuge de el: fundasul il tine si ataca doar din cand in cand
                go = rng.random() < (Wt.tackle_eagerness_base + Wt.tackle_eagerness_aggression * o.n[A.aggression]) * (
                        dd < 0.9 ? Wt.tackle_eagerness_close : 1.0) * opp.pressEager * (
                        1.0 - kTired * o.tired) * (o.booked ? Wt.booked_eager : 1.0);   // cu galben: prudent
            }
            if (go) {
                doTackle(s, o, c);
                if (b.holder != c) {
                    return;
                }
            }
        }
    }

    static void doTackle(Sim s, Player o, Player c) {
        double t = s.t;
        Mt rng = s.rng;
        o.tackleCd = t + 1.4;
        double sp = Sim.hyp(c.vx, c.vy);
        double hx;
        double hy;
        if (sp > 0.5) {
            hx = c.vx / sp;
            hy = c.vy / sp;
        } else {
            hx = c.team.dir;
            hy = 0.0;
        }
        double ex = o.x - c.x;
        double ey = o.y - c.y;
        double el = Sim.hyp(ex, ey);
        if (el == 0.0) {
            el = 1.0;
        }
        double front = (hx * ex + hy * ey) / el;
        if (!Sim.LEGACY && SetPiece.foul(s, o, c, front)) {
            return;                     // fault: jocul s-a oprit, urmeaza lovitura libera sau penalty
        }
        double logit = Wt.duel_slope * (o.tk - c.dr) + Wt.duel_front * front - Wt.duel_bias;
        if (!Sim.LEGACY) {
            logit += o.team.tackleEdge;     // foulHardness: "Hard" castiga ceva mai multe dueluri
        }
        double pw = Sim.sigmoid(logit);
        double r = rng.random();
        if (r < pw * 0.55) {
            o.team.tackles++;
            c.team.dribblesLost++;
            c.stunUntil = t + 0.7;
            c.tackleCd = t + 1.8;
            c.lostT = t;
            s.ev("TACKLE", o.team, o, c, o.shortName + " îl deposedează pe " + c.shortName);
            gain(s, o);
        } else if (r < pw) {
            o.team.tackles++;
            c.team.dribblesLost++;
            c.stunUntil = t + 0.5;
            c.tackleCd = t + 1.8;
            c.lostT = t;
            c.touchCd = t + 0.8;
            o.touchCd = t + 0.4;
            s.ev("TACKLE", o.team, o, c, o.shortName + " îi scoate mingea lui " + c.shortName);
            if (!Sim.LEGACY && Geo.cx(c.x, o.team.dir) <= Wt.corner_tackle_dist
                    && Math.abs(Geo.cy(c.y, o.team.dir) - Geo.MID_Y) <= 20.16 + 5.0
                    && rng.random() < Wt.corner_tackle) {
                SetPiece.toCorner(s, c.team, c.y);      // langa poarta lui o scoate peste linie
                return;
            }
            double ang = PMath.atan2(-ey, -ex) + rng.gauss(0.0, 1.2);
            double speed = 5.0 + 4.0 * rng.random();
            s.played(o);                // i-a scos mingea: a jucat-o cu voie
            looseBall(s, c.x, c.y, ang, speed, o.team);
        } else {
            o.stunUntil = t + 0.5 + 0.5 * rng.random();
            o.tackleCd = t + 1.5;
            if (sp > 1.0) {
                c.team.dribblesWon++;
                s.ev("DRIBBLE", c.team, c, o, c.shortName + " trece de " + o.shortName);
            }
        }
    }
}
