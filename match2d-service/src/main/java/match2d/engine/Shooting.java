package match2d.engine;

import java.util.ArrayList;
import java.util.Map;

/**
 * Sutul. Rezultatul se hotaraste in momentul lovirii, din executia trasa la intamplare (directie,
 * viteza, inaltime), din sansa de blocaj a fiecarui adversar de pe traiectorie si din sansa
 * portarului; mingea doar zboara apoi pana acolo.
 */
final class Shooting {
    private Shooting() {
    }

    /** Executia unui sut: directia, viteza, lungimea drumului, inaltimea la capat si unde ajunge. */
    static final class Kick {
        double ux;
        double uy;
        double speed;
        double pathLen;
        double zEnd;
        boolean onTarget;
        double ex;
        double ey;
        double distG;
    }

    /**
     * Executia unui sut, trasa la intamplare din {@code rng}. Nu schimba nimic in meci, deci
     * serveste si sutului adevarat, si estimarii sansei de gol.
     */
    static Kick shotKick(Sim s, Player p, double press, boolean special, Mt rng) {
        Team team = p.team;
        int d = team.dir;
        double[] n = p.n;
        double px = p.x;
        double py = p.y;
        double gx = d == 1 ? Geo.L : 0.0;
        Player gk = s.opp(team).gk;
        double distG = Sim.hyp(gx - px, Geo.MID_Y - py);
        // tinteste coltul mai departat de portar; calmul decide cat de aproape de bara
        double margin = Wt.shot_aim_base + Wt.shot_aim_composure * (1.0 - n[A.composure]);
        double side = gk.y < Geo.MID_Y ? 1.0 : -1.0;
        if (rng.random() < 0.25) {
            side = -side;
        }
        double aimY = Geo.MID_Y + side * (Geo.GOAL_HALF - margin);
        double w = Geo.clamp((distG - 18.0) / 10.0, 0.0, 1.0);
        double skill = Wt.shot_error_finishing * n[A.finishing] * (1.0 - w)
                + Wt.shot_error_longShots * n[A.longShots] * w;
        double sigma = (Math.max(Wt.shot_error_floor, Wt.shot_error_slope * (1.0 - skill) - Wt.shot_error_offset)
                * (1.0 + (Wt.shot_error_pressure + Wt.shot_error_composure * (1.0 - n[A.composure])) * press));
        double ang = PMath.atan2(aimY - py, gx - px) + rng.gauss(0.0, sigma);
        double ux = PMath.cos(ang);
        double uy = PMath.sin(ang);
        double speed = (Wt.shot_power_base + Wt.shot_power_technique * n[A.technique]
                + Wt.shot_power_strength * n[A.strength] + rng.gauss(0.0, 1.0));
        if (special) {
            // sut special: foarte puternic si precis; presiunea ii strica precizia
            sigma = 0.018 * (1.0 + 3.5 * press + 6.0 * (press * press * press));
            speed = 62.0 + 3.0 * p.specialLvl + rng.gauss(0.0, 1.5);
            ang = PMath.atan2(Geo.MID_Y + side * (Geo.GOAL_HALF - 0.7) - py, gx - px) + rng.gauss(0.0, sigma);
            ux = PMath.cos(ang);
            uy = PMath.sin(ang);
        }
        double pathLen;
        double yCross;
        if ((gx - px) * ux > 1e-6) {
            pathLen = (gx - px) / ux;
            yCross = py + uy * pathLen;
        } else {
            pathLen = 1e9;
            yCross = -50.0;
        }
        // daca iese intai prin tusa (sut tras aproape paralel cu linia portii), se opreste acolo
        double sideLen = uy > 1e-6 ? (Geo.W + 1.0 - py) / uy : uy < -1e-6 ? (-1.0 - py) / uy : 1e9;
        if (sideLen < pathLen) {
            pathLen = sideLen;
            yCross = -50.0;
        }
        double zEnd = Math.max(0.0, rng.gauss(1.0, (Wt.shot_height_base
                + Wt.shot_height_technique * (1.0 - n[A.technique])) * distG
                * (1.0 + 0.5 * press)));
        if (special) {
            zEnd = Math.max(0.0, rng.gauss(1.2, 0.012 * distG * (1.0 + 2.0 * press)));
        }
        Kick k = new Kick();
        k.ux = ux;
        k.uy = uy;
        k.speed = speed;
        k.pathLen = pathLen;
        k.zEnd = zEnd;
        k.onTarget = Math.abs(yCross - Geo.MID_Y) < Geo.GOAL_HALF && zEnd < Geo.GOAL_H;
        k.ex = px + ux * pathLen;
        k.ey = py + uy * pathLen;
        k.distG = distG;
        return k;
    }

    /**
     * O executie data direct (unghi, viteza, inaltimea la capat) din ({@code px}, {@code py}) spre
     * linia portii de la {@code gx}: lungimea drumului si unde ajunge, ca in {@link #shotKick}.
     * Folosita de fazele fixe (lovitura libera directa, penalty).
     */
    static Kick kickAlong(double px, double py, double gx, double ang, double speed, double zEnd, double distG) {
        double ux = PMath.cos(ang);
        double uy = PMath.sin(ang);
        double pathLen;
        double yCross;
        if ((gx - px) * ux > 1e-6) {
            pathLen = (gx - px) / ux;
            yCross = py + uy * pathLen;
        } else {
            pathLen = 1e9;
            yCross = -50.0;
        }
        double sideLen = uy > 1e-6 ? (Geo.W + 1.0 - py) / uy : uy < -1e-6 ? (-1.0 - py) / uy : 1e9;
        if (sideLen < pathLen) {
            pathLen = sideLen;
            yCross = -50.0;
        }
        Kick k = new Kick();
        k.ux = ux;
        k.uy = uy;
        k.speed = speed;
        k.pathLen = pathLen;
        k.zEnd = zEnd;
        k.onTarget = Math.abs(yCross - Geo.MID_Y) < Geo.GOAL_HALF && zEnd < Geo.GOAL_H;
        k.ex = px + ux * pathLen;
        k.ey = py + uy * pathLen;
        k.distG = distG;
        return k;
    }

    // ------------------------------------------------------------------ lovitura de cap (etapa B)
    /**
     * {@code q} poate da cu capul la poarta: mingea e o pasa inalta inca in zbor de la un
     * coechipier, iar el e in careul advers, la cel mult {@code header_range} m de centrul portii.
     * Primitorul aflat in ofsaid nu: preluarea lui fluiera ofsaidul, ca azi.
     */
    static boolean canHeadAtGoal(Sim s, Player q) {
        Ball b = s.ball;
        Sim.Pending pend = s.pending;
        if (q.isGk || b.kind != Ball.PASS || !b.lofted || pend == null || pend.kicker.team != q.team
                || (pend.offside && q == pend.recv)) {
            return false;
        }
        double cx = Geo.cx(q.x, q.team.dir);
        double cy = Geo.cy(q.y, q.team.dir);
        return cx >= Geo.L - 16.5 && Math.abs(cy - Geo.MID_Y) <= 20.16
                && Sim.hyp(Geo.L - cx, Geo.MID_Y - cy) <= Wt.header_range;
    }

    /** Executia loviturii de cap: viteza din jumpingReach si strength, precizia din heading. */
    static Kick headerKick(Sim s, Player p, double press, Mt rng) {
        double[] n = p.n;
        double px = p.x;
        double py = p.y;
        double gx = p.team.dir == 1 ? Geo.L : 0.0;
        Player gk = s.opp(p.team).gk;
        double distG = Sim.hyp(gx - px, Geo.MID_Y - py);
        double side = gk.y < Geo.MID_Y ? 1.0 : -1.0;
        if (rng.random() < 0.3) {
            side = -side;
        }
        double aimY = Geo.MID_Y + side * (Geo.GOAL_HALF - Wt.header_aim_margin);
        double sigma = Math.max(Wt.header_error_floor, Wt.header_error_base
                - Wt.header_error_heading * (n[A.heading] - 0.5)) * (1.0 + Wt.header_error_pressure * press);
        double ang = PMath.atan2(aimY - py, gx - px) + rng.gauss(0.0, sigma);
        double speed = Math.max(6.0, Wt.header_power_base + Wt.header_power_jumpingReach * (n[A.jumpingReach] - 0.5)
                + Wt.header_power_strength * (n[A.strength] - 0.5) + rng.gauss(0.0, 1.0));
        double zsd = Math.max(Wt.header_height_sd_floor, Wt.header_height_sd
                - Wt.header_height_heading * (n[A.heading] - 0.5)) * (1.0 + Wt.header_error_pressure * press);
        double zEnd = Math.max(0.0, rng.gauss(Wt.header_height_base, zsd));
        return kickAlong(px, py, gx, ang, speed, zEnd, distG);
    }

    /** Sansa de gol a loviturii de cap (esantionata pe {@code s.xrng}, ca {@link #shotXg}). */
    static double headerXg(Sim s, Player p, double press) {
        final int samples = 16;
        Team opp = s.opp(p.team);
        Player gk = opp.gk;
        double total = 0.0;
        for (int i = 0; i < samples; i++) {
            Kick k = headerKick(s, p, press, s.xrng);
            if (!k.onTarget) {
                continue;
            }
            double free = 1.0;
            for (Player o : opp.players) {
                if (o.isGk) {
                    continue;
                }
                double pb = blockProb(p, o, k);
                if (pb >= 0.0) {
                    free *= 1.0 - pb;
                }
            }
            double gu = Geo.clamp(Geo.segU(gk.x, gk.y, p.x, p.y, k.ex, k.ey), 0.05, 1.0);
            double dlat = Geo.segDist(gk.x, gk.y, p.x, p.y, k.ex, k.ey);
            total += free * (1.0 - saveProb(s, p, k, false, gu, dlat));
        }
        return total / samples;
    }

    /**
     * Lovitura de cap la poarta a lui {@code q}, din mingea inalta a unui coechipier: pasa a
     * reusit (centrarea a ajuns), apoi mingea pleaca spre poarta prin blocaje si parada.
     */
    static void doHeader(Sim s, Player q, double press) {
        Mt rng = s.rng;
        Ball b = s.ball;
        Team team = q.team;
        Team opp = s.opp(team);
        Sim.Pending pend = s.pending;
        s.pending = null;
        Player k0 = pend.kicker;
        team.passesOk++;
        double[] cal = s.calib.get(pend.key);
        if (cal != null) {
            cal[2] += 1;
        }
        if (pend.through) {
            team.throughOk++;
        }
        if (pend.loft) {
            team.longOk++;
        }
        if (pend.sub != 0) {
            team.crossesOk++;
        }
        s.ev("PASS_OK", team, q, k0, null);
        q.lastFrom = k0;
        q.lastFromT = s.t;
        q.assistFrom = k0 != q ? k0 : null;
        // capul se da din locul mingii
        q.x = Geo.clamp(b.x * 0.5 + q.x * 0.5, 0.2, Geo.L - 0.2);
        q.y = Geo.clamp(b.y * 0.5 + q.y * 0.5, 0.2, Geo.W - 0.2);
        team.mXg += headerXg(s, q, press);
        s.played(q);
        Kick k = headerKick(s, q, press, rng);
        team.shots++;
        team.headers++;
        if (s.t >= s.halfLen * 4.0 / 3.0) {
            team.mShotsLate++;
        }
        Map<String, Object> e = s.ev("SHOT", team, q, null,
                "Lovitură de cap " + q.shortName + " de la " + (long) Math.rint(k.distG) + " m",
                Double.NaN, Double.NaN, k.ex, Geo.clamp(k.ey, -3.0, Geo.W + 3.0));
        e.put("special", false);
        e.put("header", true);
        Player blocker = null;
        double bu = 2.0;
        for (Player o : opp.players) {
            if (o.isGk) {
                continue;
            }
            double pb = blockProb(q, o, k);
            if (pb >= 0.0) {
                double u = Geo.segU(o.x, o.y, q.x, q.y, k.ex, k.ey);
                if (rng.random() < pb && u < bu) {
                    bu = u;
                    blocker = o;
                }
            }
        }
        launch(s, q, k, blocker, bu, false, -1, 3);
    }

    /** Sansa ca adversarul {@code o} sa blocheze sutul, sau -1 daca nu e pe traiectorie. */
    static double blockProb(Player p, Player o, Kick k) {
        double u = Geo.segU(o.x, o.y, p.x, p.y, k.ex, k.ey);
        double dseg = Geo.segDist(o.x, o.y, p.x, p.y, k.ex, k.ey);
        // cei de langa sutor se pot arunca in fata mingii chiar daca nu sunt fix pe linie
        double br = Wt.block_reach + Wt.block_reach_near * Math.max(0.0, 1.0 - u * k.pathLen / 8.0);
        if (0.02 < u && u < 0.92 && dseg < br && u * k.pathLen > 0.8 && k.zEnd * u < 1.9) {
            double pb = (1.0 - dseg / br) * (Wt.block_base + Wt.block_slope * (
                    Wt.block_bravery * o.n[A.bravery] + Wt.block_positioning * o.n[A.positioning]) / 2);
            // cel mult 1: cu block_base 0.90 un aparator brav, chiar pe linie, trecea de 1, iar in
            // xG (1 - pb) iesea negativ; tragerea rng.random() < pb da acelasi blocaj si cu 1
            return Sim.LEGACY ? pb : Math.min(1.0, pb);
        }
        return -1.0;
    }

    /** Sansa ca portarul sa apere un sut care merge pe poarta; gu si dlat spun pe unde trece de el. */
    static double saveProb(Sim s, Player p, Kick k, boolean special, double gu, double dlat) {
        return saveProb(s, p, k, special, gu, dlat, 0.0);
    }

    /**
     * La fel, cu portarul care pleaca {@code late} secunde mai tarziu (lovitura libera directa:
     * zidul ii ascunde mingea). {@code late} = 0 e exact parada obisnuita.
     */
    static double saveProb(Sim s, Player p, Kick k, boolean special, double gu, double dlat, double late) {
        double tGk = gu * k.pathLen / k.speed;
        if (late != 0.0) {
            tGk -= late;
        }
        double[] g = s.opp(p.team).gk.n;
        double refl = k.distG > 10.0 ? g[A.reflexes] : Wt.save_oneOnOnes_blend * (
                g[A.reflexes] + g[A.oneOnOnes]);
        double react = Wt.save_react_base - Wt.save_reflexes * refl;
        double dive = Wt.save_dive_base + Wt.save_agility * g[A.agility];
        double reach = Wt.save_reach + Math.max(0.0, tGk - react) * dive;
        if (special) {
            // mingea isi schimba traiectoria: portarul pleaca tarziu si acopera mai putin
            reach = 1.1 + Math.max(0.0, tGk - react - 0.35) * dive;
        }
        if (k.distG < 10.0) {
            // se face mare in fata atacantului
            reach += Wt.save_oneOnOnes * (g[A.oneOnOnes] - Wt.save_oneOnOnes_from);
        }
        double mg = reach - dlat - (k.zEnd > 1.8 ? 0.4 : 0.0);
        // cel mult 1: la portarii buni inmultitorul trece de 1, iar xG-ul (1 - saveProb) ar iesi negativ
        return Math.min(1.0, Sim.sigmoid(Wt.save_slope * mg) * (
                Wt.save_sure_base + Wt.save_handling * 0.5 * (refl + g[A.handling])));
    }

    /**
     * Sansa de gol a sutului pe care {@code p} il trage acum: media, peste cateva executii trase
     * la intamplare, a lui P(nu e blocat) x P(portarul nu apara). Golurile unui meci variaza mult
     * in jurul sumei acestor sanse, de aceea masuratorile o folosesc in locul golurilor.
     */
    static double shotXg(Sim s, Player p, double press, boolean special) {
        final int samples = 16;
        Player gk = s.opp(p.team).gk;
        double total = 0.0;
        for (int i = 0; i < samples; i++) {
            Kick k = shotKick(s, p, press, special, s.xrng);
            if (!k.onTarget) {
                continue;               // pe langa poarta
            }
            double free = 1.0;
            for (Player o : s.opp(p.team).players) {
                if (o.isGk) {
                    continue;
                }
                double pb = blockProb(p, o, k);
                if (pb >= 0.0) {
                    free *= 1.0 - pb;
                }
            }
            double gu = Geo.clamp(Geo.segU(gk.x, gk.y, p.x, p.y, k.ex, k.ey), 0.05, 1.0);
            double dlat = Geo.segDist(gk.x, gk.y, p.x, p.y, k.ex, k.ey);
            total += free * (1.0 - saveProb(s, p, k, special, gu, dlat));
        }
        return total / samples;
    }

    static void doShot(Sim s, Player p, double press, boolean special) {
        Mt rng = s.rng;
        Team team = p.team;
        Team opp = s.opp(team);
        double px = p.x;
        double py = p.y;
        team.mXg += shotXg(s, p, press, special);
        s.played(p);
        Kick k = shotKick(s, p, press, special, rng);
        team.shots++;
        if (s.t >= s.halfLen * 4.0 / 3.0) {
            team.mShotsLate++;          // sut in ultima treime a meciului
        }
        Map<String, Object> e = s.ev("SHOT", team, p, null,
                (special ? "Șut special" : "Șut") + " " + p.shortName + " de la " + (long) Math.rint(k.distG) + " m",
                Double.NaN, Double.NaN, k.ex, Geo.clamp(k.ey, -3.0, Geo.W + 3.0));
        e.put("special", special);
        // blocaj: un adversar aflat chiar pe traiectorie
        Player blocker = null;
        double bu = 2.0;
        for (Player o : opp.players) {
            if (o.isGk) {
                continue;
            }
            double pb = blockProb(p, o, k);
            if (pb >= 0.0) {
                double u = Geo.segU(o.x, o.y, px, py, k.ex, k.ey);
                if (rng.random() < pb && u < bu) {
                    bu = u;
                    blocker = o;
                }
            }
        }
        launch(s, p, k, blocker, bu, special, -1, 0);
    }

    /**
     * Pune sutul in zbor: blocat de {@code blocker} (daca nu e null), altfel pe langa, parat sau
     * gol. {@code forced} &gt;= 0 impune rezultatul (penalty-ul, a carui sansa se trage separat);
     * -1 = parada se trage din {@link #saveProb}. {@code setPiece}: 0 = sut din joc, 1 = penalty,
     * 2 = lovitura libera directa (doar pe drumul nou).
     */
    static Sim.ShotState launch(Sim s, Player p, Kick k, Player blocker, double bu, boolean special,
                                int forced, int setPiece) {
        double t = s.t;
        Mt rng = s.rng;
        Ball b = s.ball;
        Team team = p.team;
        double px = p.x;
        double py = p.y;
        Player gk = s.opp(team).gk;
        Sim.ShotState sh = new Sim.ShotState();
        sh.shooter = p;
        sh.gk = gk;
        sh.zEnd = k.zEnd;
        sh.len = k.pathLen;
        sh.assist = p.assistFrom;
        sh.setPiece = setPiece;
        if (blocker != null) {
            sh.outcome = Sim.ShotState.BLOCK;
            sh.blocker = blocker;
            sh.frac = bu;
            sh.gx = gk.x;
            sh.gy = gk.y;
        } else {
            double gu = Geo.clamp(Geo.segU(gk.x, gk.y, px, py, k.ex, k.ey), 0.05, 1.0);
            double dlat = Geo.segDist(gk.x, gk.y, px, py, k.ex, k.ey);
            sh.gx = Geo.clamp(px + k.ux * k.pathLen * gu, 0.3, Geo.L - 0.3);
            sh.gy = Geo.clamp(py + k.uy * k.pathLen * gu, 0.3, Geo.W - 0.3);
            if (!k.onTarget || forced == Sim.ShotState.MISS) {
                sh.outcome = Sim.ShotState.MISS;
                sh.frac = 1.08;
            } else {
                boolean save;
                if (forced >= 0) {
                    save = forced == Sim.ShotState.SAVE;
                } else if (s.target == null) {
                    save = rng.random() < saveProb(s, p, k, special, gu, dlat, setPiece == 2 ? Wt.fk_gk_late : 0.0);
                } else {
                    // scor tinta: aceeasi tragere, cu sansa de gol condusa (Sim.guideGoal); cu cota
                    // atinsa golul devine parada
                    double r = rng.random();
                    double sp = saveProb(s, p, k, special, gu, dlat, setPiece == 2 ? Wt.fk_gk_late : 0.0);
                    save = r < 1.0 - s.guideGoal(team, 1.0 - sp);
                }
                if (save) {
                    sh.outcome = Sim.ShotState.SAVE;
                    sh.frac = gu;
                    sh.caught = rng.random() < (Wt.save_catch_base + Wt.save_handling_catch * gk.n[A.handling]
                            - Wt.save_per_speed * (k.speed - Wt.save_speed_from));
                } else {
                    sh.outcome = Sim.ShotState.GOAL;
                    sh.frac = 1.02;
                }
            }
        }
        if (sh.outcome == Sim.ShotState.SAVE || sh.outcome == Sim.ShotState.GOAL) {
            team.onTarget++;
        }
        sh.tEnd = t + sh.frac * k.pathLen / k.speed;
        s.shot = sh;
        s.pending = null;
        b.holder = null;
        b.kind = Ball.SHOT;
        b.kicker = p;
        b.receiver = null;
        b.kickT = t;
        b.lastTeam = team;
        b.x = px;
        b.px = px;
        b.y = py;
        b.py = py;
        b.ux = k.ux;
        b.uy = k.uy;
        b.v = k.speed;
        b.decel = 0.5;
        b.lofted = false;
        s.restartKind = null;
        p.carryTo = null;
        p.tx = px;
        p.ty = py;
        p.path = new ArrayList<>();
        p.pg = null;
        p.touchCd = t + 0.6;
        Roles.assignRoles(s);
        return sh;
    }

    static void finishShot(Sim s) {
        Sim.ShotState sh = s.shot;
        Ball b = s.ball;
        Mt rng = s.rng;
        Player p = sh.shooter;
        Team team = p.team;
        Team opp = s.opp(team);
        Player gk = sh.gk;
        b.z = 0.0;
        if (sh.outcome == Sim.ShotState.GOAL) {
            s.shot = null;
            if (Sim.LEGACY) {
                BallPlay.goal(s, team, p, false, sh.assist);
            } else {
                if (sh.setPiece != 0) {
                    s.goalHow = sh.setPiece == 1 ? "penalty" : sh.setPiece == 2 ? "free_kick" : "header";
                }
                BallPlay.scored(s, team, p, null);
            }
            return;
        }
        if (sh.outcome == Sim.ShotState.MISS) {
            s.shot = null;
            team.offTarget++;
            s.ev("MISS", team, p, null, (sh.setPiece == 1 ? "Penalty ratat: " : sh.setPiece == 2
                    ? "Lovitură liberă pe lângă: " : "Șut pe lângă: ") + p.shortName, b.x, Geo.clamp(b.y, 0, Geo.W),
                    Double.NaN, Double.NaN);
            double gxl = team.dir == 1 ? Geo.L : 0.0;
            double cy = Geo.cy(Geo.clamp(b.y, 0, Geo.W), opp.dir);
            double kx = Geo.cx(5.5, opp.dir);
            double ky = Geo.cy(Geo.MID_Y + (cy > Geo.MID_Y ? 6.0 : -6.0), opp.dir);
            s.ev("GOAL_KICK", opp, null, null, "Lovitură de poartă: " + opp.name);
            s.setRestart("goal_kick", opp, kx, ky);
            return;
        }
        double back = PMath.atan2(-b.uy, -b.ux);
        if (sh.outcome == Sim.ShotState.BLOCK) {
            Player o = sh.blocker;
            team.blocked++;
            s.ev("BLOCK", opp, o, p, sh.wall ? "Lovitură liberă blocată de zid"
                    : (sh.setPiece == 2 ? "Lovitură liberă blocată de " : "Șut blocat de ") + o.shortName);
            o.touchCd = s.t + 0.5;
            if (!Sim.LEGACY && Geo.cx(o.x, opp.dir) <= Wt.corner_block_dist && rng.random() < Wt.corner_block) {
                SetPiece.toCorner(s, team, b.y);        // blocajul deviaza mingea in corner
                return;
            }
            double ang = BallPlay.awayFromGoal(b.x, b.y, back + rng.gauss(0.0, 1.0), opp);
            double speed = 4.0 + 6.0 * rng.random();
            BallPlay.looseBall(s, b.x, b.y, ang, speed, opp, o);
            return;
        }
        // parada
        opp.saves++;
        if (Sim.hyp(gk.x - b.x, gk.y - b.y) < 3.5) {
            gk.x = Geo.clamp(b.x, 0.3, Geo.L - 0.3);
            gk.y = Geo.clamp(b.y, 0.3, Geo.W - 0.3);
        }
        String saved = sh.setPiece == 1 ? "Penalty apărat de " + gk.shortName + ": "
                : sh.setPiece == 2 ? "Lovitură liberă apărată de " + gk.shortName + ": "
                : "Paradă " + gk.shortName + ": ";
        if (sh.caught) {
            s.ev("SAVE", opp, gk, p, saved + "prinde mingea");
            s.shot = null;
            b.kind = Ball.LOOSE;
            BallPlay.gain(s, gk);
        } else {
            s.ev("SAVE", opp, gk, p, saved + "respinge");
            gk.touchCd = s.t + 0.6;
            if (!Sim.LEGACY && rng.random() < Wt.corner_save) {
                SetPiece.toCorner(s, team, b.y);        // portarul o scoate peste linia portii
                return;
            }
            double ang = BallPlay.awayFromGoal(b.x, b.y, back + rng.gauss(0.0, 1.1), opp);
            double speed = 5.0 + 6.0 * rng.random();
            BallPlay.looseBall(s, b.x, b.y, ang, speed, opp, gk);
        }
    }
}
