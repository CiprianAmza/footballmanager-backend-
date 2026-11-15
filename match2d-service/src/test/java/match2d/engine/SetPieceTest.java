package match2d.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Teste pentru fazele fixe (SetPiece) si faulturile din dueluri: sansa de fault, cartonasele
 * galbene, felul loviturii libere, executantii si penalty-ul. Toate pe drumul nou. Fara biblioteci
 * de test:
 *
 * <pre>   ./build.sh &amp;&amp; ./test.sh SetPieceTest</pre>
 *
 * <p>Sansele se masoara exact, fara statistica: un generator geaman (acelasi seed, adus la acelasi
 * numar de trageri) arata numarul pe care motorul il va trage. Daca tragerea {@code u} da fault,
 * sansa e peste {@code u}; daca nu, e cel mult {@code u}. Din multe incercari iese un interval
 * ingust in care sta sansa adevarata.
 */
public final class SetPieceTest {
    private static int checks;
    private static final List<String> FAILED = new ArrayList<>();
    private static final int TRIALS = 1500;

    private SetPieceTest() {
    }

    public static void main(String[] args) {
        foulChanceRisesWithAggression();
        foulChanceRisesWithFoulFrequency();
        foulChanceFallsWithTackling();
        foulChanceIsHigherFromBehind();
        foulChanceIsCappedByFoulMax();
        foulChanceIsLowerInOwnBox();
        bookedPlayerFoulsLessOften();
        bookedPlayerGoesForTheBallLessOften();
        aerialFoulChanceRisesWithAggression();
        foulEventAndRestart();
        foulInOwnBoxIsAPenalty();
        aerialFoulIsMarked();
        onlyYellowAndNeverASecondOne();
        freeKickKindByDistanceAndAngle();
        freeKickEventCarriesTheKind();
        tacticTakersAreUsed();
        missingOrUnknownTakersArePickedByAttribute();
        penaltyChanceRisesWithPenaltyTakingAndFallsWithTheKeeper();
        wallStopsLessWithBetterFreeKick();
        System.out.printf("%d verificari, %d esuate%n", checks, FAILED.size());
        for (String name : FAILED) {
            System.out.println("  ESUAT: " + name);
        }
        System.exit(FAILED.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------------ ajutoare
    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            FAILED.add(name);
        }
    }

    private static double norm(int rating) {
        return 0.15 + 0.70 * (rating - 1) / 19.0;
    }

    private static Map<String, Object> withTactic(Map<String, Object> team, Object... pairs) {
        Map<String, Object> t = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            t.put((String) pairs[i], pairs[i + 1]);
        }
        team.put("tactic", t);
        return team;
    }

    private static Sim sim(Map<String, Object> home, Map<String, Object> away) {
        return new Sim(home, away, 3, 10.0, 10, false);
    }

    private static Sim sim() {
        return sim(GoalRulesTest.team("Rosii", 12), GoalRulesTest.team("Albii", 12));
    }

    /** Generatorul geaman al meciului: arata numarul pe care {@code s.rng} il trage acum. */
    private static final class Peek {
        private final Sim s;
        private Mt twin;

        Peek(Sim s) {
            this.s = s;
            this.twin = new Mt(s.seed);
        }

        double next() {
            if (twin.calls() > s.rng.calls()) {
                twin = new Mt(s.seed);
            }
            while (twin.calls() < s.rng.calls()) {
                twin.random();
            }
            return twin.random();
        }
    }

    private interface Trial {
        boolean run();
    }

    /**
     * Intervalul (lo, hi] in care sta sansa evenimentului a carui tragere e prima din
     * {@code trial}: lo = cea mai mare tragere care l-a produs, hi = cea mai mica care nu.
     */
    private static double[] chance(Sim s, Runnable reset, Trial trial) {
        Peek peek = new Peek(s);
        double lo = 0.0;
        double hi = 1.0;
        for (int i = 0; i < TRIALS; i++) {
            reset.run();
            long before = s.rng.calls();
            double u = peek.next();
            boolean hit = trial.run();
            if (s.rng.calls() == before) {
                if (!hit) {
                    hi = 0.0;               // nu s-a tras nimic: sansa e 0
                }
                continue;
            }
            if (hit) {
                lo = Math.max(lo, u);
            } else {
                hi = Math.min(hi, u);
            }
        }
        return new double[] {lo, hi};
    }

    private static boolean below(double[] a, double[] b) {
        return a[1] < b[0];
    }

    private static String fmt(double[] c) {
        return String.format("(%.4f, %.4f]", c[0], c[1]);
    }

    /** Sansa de fault a lui {@code o} (echipa 1) asupra lui {@code c} (echipa 0), la locul dat. */
    private static double[] foulChance(Sim s, Player o, Player c, double front, double x, double y, boolean booked) {
        return chance(s, () -> {
            o.booked = booked;
            c.x = x;
            c.y = y;
            o.x = x + 1.0;
            o.y = y;
        }, () -> SetPiece.foul(s, o, c, front));
    }

    private static double[] midFoul(Sim s, boolean booked) {
        return foulChance(s, s.teams[1].players[2], s.teams[0].players[9], 0.5, 52.5, Geo.MID_Y, booked);
    }

    private static Map<String, Object> eventAfter(Sim s, int from, String skip) {
        for (int i = from; i < s.events.size(); i++) {
            Map<String, Object> e = s.events.get(i);
            if (!skip.equals(e.get("type"))) {
                return e;
            }
        }
        return null;
    }

    /** Pana iese un fault (sansa ridicata la foul_max); intoarce indexul evenimentului FOUL. */
    private static int forceFoul(Sim s, Player o, Player c, double front) {
        double base = Wt.set("foul.base", 50.0);
        try {
            for (int i = 0; i < 400; i++) {
                int n = s.events.size();
                if (SetPiece.foul(s, o, c, front)) {
                    return n;
                }
            }
        } finally {
            Wt.set("foul.base", base);
        }
        return -1;
    }

    // ------------------------------------------------------------------ sansa de fault
    private static void foulChanceRisesWithAggression() {
        Sim s = sim();
        Player o = s.teams[1].players[2];
        o.n[A.aggression] = norm(4);
        double[] calm = midFoul(s, false);
        o.n[A.aggression] = norm(18);
        double[] rough = midFoul(s, false);
        check("aggression mare = faulturi mai dese " + fmt(calm) + " < " + fmt(rough), below(calm, rough));
        check("intervalele sunt inguste", calm[1] - calm[0] < 0.02 && rough[1] - rough[0] < 0.02);
    }

    private static void foulChanceRisesWithFoulFrequency() {
        double[][] c = new double[3][];
        String[] v = {"Rarely", "Normal", "Often"};
        for (int i = 0; i < 3; i++) {
            Sim s = sim(GoalRulesTest.team("Rosii", 12), withTactic(GoalRulesTest.team("Albii", 12), "foulFrequency", v[i]));
            c[i] = midFoul(s, false);
        }
        check("Rarely < Normal < Often " + fmt(c[0]) + " " + fmt(c[1]) + " " + fmt(c[2]),
                below(c[0], c[1]) && below(c[1], c[2]));
        // proportional cu foul_freq (cat timp nu e plafonat)
        double ratio = 0.5 * (c[2][0] + c[2][1]) / (0.5 * (c[1][0] + c[1][1]));
        check("Often fata de Normal e cam foul_freq 1.4 / 1.0 (" + ratio + ")", Math.abs(ratio - 1.4) < 0.1
                || c[2][1] >= Wt.foul_max - 1e-9);
    }

    private static void foulChanceFallsWithTackling() {
        Sim s = sim();
        Player o = s.teams[1].players[2];
        o.n[A.tackling] = norm(18);
        double[] clean = midFoul(s, false);
        o.n[A.tackling] = norm(4);
        double[] clumsy = midFoul(s, false);
        check("tackling mare = faulturi mai rare " + fmt(clean) + " < " + fmt(clumsy), below(clean, clumsy));
    }

    private static void foulChanceIsHigherFromBehind() {
        Sim s = sim();
        Player o = s.teams[1].players[2];
        Player c = s.teams[0].players[9];
        double[] front = foulChance(s, o, c, 0.6, 52.5, Geo.MID_Y, false);
        double[] behind = foulChance(s, o, c, -0.6, 52.5, Geo.MID_Y, false);
        check("din spate faultul e mai des " + fmt(front) + " < " + fmt(behind), below(front, behind));
        double[] side = foulChance(s, o, c, 0.0, 52.5, Geo.MID_Y, false);
        check("din lateral (front = 0) nu e din spate", Math.abs(0.5 * (side[0] + side[1]) - 0.5 * (front[0] + front[1])) < 0.02);
    }

    private static void foulChanceIsCappedByFoulMax() {
        Sim s = sim();
        double base = Wt.set("foul.base", 50.0);
        try {
            Player o = s.teams[1].players[2];
            o.n[A.aggression] = norm(20);
            double[] c = foulChance(s, o, s.teams[0].players[9], -0.6, 52.5, Geo.MID_Y, false);
            check("sansa nu trece de foul_max " + fmt(c) + " fata de " + Wt.foul_max,
                    c[0] < Wt.foul_max + 1e-12 && c[1] >= Wt.foul_max - 1e-12 && c[1] - c[0] < 0.02);
            double max = Wt.set("foul.max", 0.2);
            try {
                double[] c2 = foulChance(s, o, s.teams[0].players[9], -0.6, 52.5, Geo.MID_Y, false);
                check("plafonul urmeaza foul_max " + fmt(c2), c2[0] < 0.2 + 1e-12 && c2[1] >= 0.2 - 1e-12);
            } finally {
                Wt.set("foul.max", max);
            }
        } finally {
            Wt.set("foul.base", base);
        }
    }

    private static void foulChanceIsLowerInOwnBox() {
        Sim s = sim();
        Player o = s.teams[1].players[2];
        Player c = s.teams[0].players[9];
        // echipa 1 apara poarta de la x = L: careul ei e la x >= L - 16.5
        double[] out = foulChance(s, o, c, 0.5, 52.5, Geo.MID_Y, false);
        double[] box = foulChance(s, o, c, 0.5, Geo.L - 8.0, Geo.MID_Y, false);
        double[] edge = foulChance(s, o, c, 0.5, Geo.L - 20.0, Geo.MID_Y, false);
        check("in propriul careu faulteaza mai rar " + fmt(box) + " < " + fmt(out), below(box, out));
        check("chiar in afara careului sansa e cea obisnuita", Math.abs(0.5 * (edge[0] + edge[1]) - 0.5 * (out[0] + out[1])) < 0.02);
        // careul celuilalt nu conteaza: faultul e in careul advers celui care faulteaza
        double[] otherBox = foulChance(s, o, c, 0.5, 8.0, Geo.MID_Y, false);
        check("in careul advers sansa e cea obisnuita", Math.abs(0.5 * (otherBox[0] + otherBox[1]) - 0.5 * (out[0] + out[1])) < 0.02);
    }

    private static void bookedPlayerFoulsLessOften() {
        Sim s = sim();
        double[] clean = midFoul(s, false);
        double[] booked = midFoul(s, true);
        check("cine are galben faulteaza mai rar " + fmt(booked) + " < " + fmt(clean), below(booked, clean));
    }

    /** Sansa ca {@code o} sa iasa la deposedare pe un purtator care sta pe loc (din carrierLogic). */
    private static double[] goChance(Sim s, Player o, Player c, boolean booked) {
        for (Player q : s.teams[1].players) {
            if (q != o) {
                q.x = 5.0;
                q.y = 5.0 + q.idx;
            }
        }
        return chance(s, () -> {
            s.state = Sim.LIVE;
            s.poss = c.team;
            s.ball.holder = c;
            s.ball.kind = Ball.HELD;
            s.pending = null;
            s.shot = null;
            s.gkHold = false;
            s.noTackleUntil = -1.0;
            c.x = 50.0;
            c.y = Geo.MID_Y;
            c.vx = 0.0;
            c.vy = 0.0;
            c.canActAt = 1e9;
            c.decideAt = 1e9;
            c.carryTo = null;
            c.safeUntil = -1.0;
            c.stunUntil = -1.0;
            o.x = 51.2;
            o.y = Geo.MID_Y;
            o.vx = 0.0;
            o.vy = 0.0;
            o.tackleCd = -1.0;
            o.stunUntil = -1.0;
            o.booked = booked;
        }, () -> {
            BallPlay.carrierLogic(s);
            return o.tackleCd > 0.0;        // doTackle i-a pus pauza: a iesit la minge
        });
    }

    private static void bookedPlayerGoesForTheBallLessOften() {
        Sim s = sim();
        Player o = s.teams[1].players[2];
        Player c = s.teams[0].players[9];
        double[] clean = goChance(s, o, c, false);
        double[] booked = goChance(s, o, c, true);
        check("cine are galben iese mai rar la deposedare (booked_eager) " + fmt(booked) + " < " + fmt(clean),
                below(booked, clean));
    }

    private static double[] aerialChance(Sim s, Player q, Player rival) {
        return chance(s, () -> {
            q.booked = false;
            rival.booked = false;
            q.x = 52.5;
            q.y = Geo.MID_Y;
            rival.x = 53.5;
            rival.y = Geo.MID_Y;
        }, () -> SetPiece.aerialFoul(s, q, rival));
    }

    private static void aerialFoulChanceRisesWithAggression() {
        Sim s = sim();
        Player q = s.teams[0].players[9];
        Player rival = s.teams[1].players[2];
        q.n[A.aggression] = norm(4);
        double[] calm = aerialChance(s, q, rival);
        q.n[A.aggression] = norm(18);
        double[] rough = aerialChance(s, q, rival);
        check("la saritura, aggression mare = faulturi mai dese " + fmt(calm) + " < " + fmt(rough), below(calm, rough));
        check("la saritura sansa e pozitiva si cel mult foul_max pentru fiecare dintre cei doi",
                calm[0] > 0.0 && rough[1] <= 2.0 * Wt.foul_max + 1e-12);
    }

    // ------------------------------------------------------------------ evenimentele
    private static void foulEventAndRestart() {
        Sim s = sim();
        Player o = s.teams[1].players[2];
        Player c = s.teams[0].players[9];
        c.x = 40.0;
        c.y = 20.0;
        int at = forceFoul(s, o, c, -0.5);
        check("faultul iese", at >= 0);
        if (at < 0) {
            return;
        }
        Map<String, Object> e = s.events.get(at);
        check("FOUL e primul eveniment al faultului", "FOUL".equals(e.get("type")));
        check("FOUL: team = echipa care faulteaza", Integer.valueOf(1).equals(e.get("team")));
        check("FOUL: p = cel care faulteaza, q = cel faultat", Integer.valueOf(o.uid).equals(e.get("p"))
                && Integer.valueOf(c.uid).equals(e.get("q")));
        check("FOUL: behind = atacat din spate", Boolean.TRUE.equals(e.get("behind")) && !e.containsKey("aerial"));
        Map<String, Object> next = eventAfter(s, at + 1, "CARD");
        check("dupa fault (si eventualul galben) urmeaza FREE_KICK pentru cel faultat",
                next != null && "FREE_KICK".equals(next.get("type")) && Integer.valueOf(0).equals(next.get("team")));
        check("repunerea e lovitura libera a echipei faultate, din locul faultului",
                "free_kick".equals(s.rkind) && s.poss == s.teams[0] && s.state == Sim.DEAD
                        && s.ball.x == 40.0 && s.ball.y == 20.0);
        check("numaratoarea faulturilor e la echipa care faulteaza", s.teams[1].fouls == 1 && s.teams[0].fouls == 0);
        check("lovitura libera e numarata la echipa faultata", s.teams[0].freeKicks == 1);
        int at2 = forceFoul(s, o, c, 0.7);
        check("faultul din fata are behind = false", at2 >= 0 && Boolean.FALSE.equals(s.events.get(at2).get("behind")));
    }

    private static void foulInOwnBoxIsAPenalty() {
        Sim s = sim();
        Player o = s.teams[1].players[2];
        Player c = s.teams[0].players[9];
        c.x = Geo.L - 10.0;
        c.y = Geo.MID_Y + 5.0;
        int at = forceFoul(s, o, c, 0.5);
        check("faultul din careu iese", at >= 0);
        if (at < 0) {
            return;
        }
        Map<String, Object> next = eventAfter(s, at + 1, "CARD");
        check("faultul in careul propriu da PENALTY celui faultat",
                next != null && "PENALTY".equals(next.get("type")) && Integer.valueOf(0).equals(next.get("team")));
        check("mingea e pe punctul de penalty", "penalty".equals(s.rkind) && s.ball.x == Geo.L - Wt.pen_spot
                && s.ball.y == Geo.MID_Y);
        check("penalty-ul e numarat la echipa faultata", s.teams[0].penalties == 1 && s.teams[0].freeKicks == 0);
        check("executantul penalty-ului e penTaker", s.taker == s.teams[0].penTaker);
    }

    private static void aerialFoulIsMarked() {
        Sim s = sim();
        Player q = s.teams[0].players[9];
        Player rival = s.teams[1].players[2];
        q.x = 50.0;
        q.y = 30.0;
        rival.x = 51.0;
        rival.y = 30.0;
        double base = Wt.set("aerial_foul.base", 50.0);
        int at = -1;
        try {
            for (int i = 0; i < 100 && at < 0; i++) {
                int n = s.events.size();
                if (SetPiece.aerialFoul(s, q, rival)) {
                    at = n;
                }
            }
        } finally {
            Wt.set("aerial_foul.base", base);
        }
        check("faultul la saritura iese", at >= 0);
        if (at < 0) {
            return;
        }
        Map<String, Object> e = s.events.get(at);
        check("faultul la saritura are aerial = true si behind = false", "FOUL".equals(e.get("type"))
                && Boolean.TRUE.equals(e.get("aerial")) && Boolean.FALSE.equals(e.get("behind")));
        int foulTeam = (Integer) e.get("team");
        Map<String, Object> next = eventAfter(s, at + 1, "CARD");
        check("dupa faultul la saritura repunerea e a celeilalte echipe", next != null
                && ("FREE_KICK".equals(next.get("type")) || "PENALTY".equals(next.get("type")))
                && Integer.valueOf(1 - foulTeam).equals(next.get("team")));
    }

    private static void onlyYellowAndNeverASecondOne() {
        Sim s = sim();
        Player o = s.teams[1].players[2];
        Player c = s.teams[0].players[9];
        double card = Wt.set("card.base", 50.0);         // orice fault ar da galben
        try {
            int fouls = 0;
            for (int i = 0; i < 6; i++) {
                c.x = 52.5;
                c.y = Geo.MID_Y;
                if (forceFoul(s, o, c, 0.5) >= 0) {
                    fouls++;
                }
            }
            int cards = 0;
            boolean yellow = true;
            boolean after = true;
            for (int i = 0; i < s.events.size(); i++) {
                Map<String, Object> e = s.events.get(i);
                if ("CARD".equals(e.get("type"))) {
                    cards++;
                    yellow &= "yellow".equals(e.get("card")) && Integer.valueOf(o.uid).equals(e.get("p"))
                            && Integer.valueOf(1).equals(e.get("team"));
                    after &= i > 0 && "FOUL".equals(s.events.get(i - 1).get("type"))
                            && e.get("p").equals(s.events.get(i - 1).get("p"));
                }
            }
            check("au fost mai multe faulturi (" + fouls + ")", fouls >= 2);
            check("un singur galben pentru acelasi jucator (" + cards + ")", cards == 1);
            check("cartonasul e galben, al celui care a faultat", yellow);
            check("cartonasul vine imediat dupa faultul lui", after);
            check("jucatorul are galben, echipa a numarat unul", o.booked && s.teams[1].yellowCards == 1);
            Player other = s.teams[1].players[3];
            other.booked = true;
            int before = s.events.size();
            c.x = 52.5;
            c.y = Geo.MID_Y;
            int at = forceFoul(s, other, c, 0.5);
            boolean none = true;
            for (int i = before; i < s.events.size(); i++) {
                none &= !"CARD".equals(s.events.get(i).get("type"));
            }
            check("cine are deja galben nu mai primeste altul", at >= 0 && none && s.teams[1].yellowCards == 1);
        } finally {
            Wt.set("card.base", card);
        }
    }

    // ------------------------------------------------------------------ lovitura libera
    private static void freeKickKindByDistanceAndAngle() {
        Sim s = sim();
        for (Team team : s.teams) {
            int d = team.dir;
            double r = Wt.fk_shot_range;
            double a = Math.toRadians(Wt.fk_shot_angle);
            // in canonicul echipei care bate (poarta adversa la x = L)
            String inside = SetPiece.fkKind(team, Geo.cx(Geo.L - (r - 1.0), d), Geo.cy(Geo.MID_Y, d));
            check(team.name + ": in fata portii, sub fk_shot_range = sut", "shot".equals(inside));
            double far = Geo.L - (r + 1.0);
            String beyond = SetPiece.fkKind(team, Geo.cx(far, d), Geo.cy(Geo.MID_Y, d));
            check(team.name + ": peste fk_shot_range nu mai e sut", !"shot".equals(beyond)
                    && beyond.equals(far >= Wt.fk_cross_from ? "cross" : "play"));
            double rr = r - 2.0;
            double okA = a - Math.toRadians(5.0);
            String narrow = SetPiece.fkKind(team, Geo.cx(Geo.L - rr * Math.cos(okA), d),
                    Geo.cy(Geo.MID_Y + rr * Math.sin(okA), d));
            check(team.name + ": unghi sub fk_shot_angle = sut", "shot".equals(narrow));
            double badA = Math.min(Math.toRadians(89.0), a + Math.toRadians(5.0));
            double wx = Geo.L - rr * Math.cos(badA);
            String wide = SetPiece.fkKind(team, Geo.cx(wx, d), Geo.cy(Geo.MID_Y - rr * Math.sin(badA), d));
            check(team.name + ": unghi peste fk_shot_angle nu e sut", !"shot".equals(wide)
                    && wide.equals(wx >= Wt.fk_cross_from ? "cross" : "play"));
            String cross = SetPiece.fkKind(team, Geo.cx(Wt.fk_cross_from + 0.5, d), Geo.cy(2.0, d));
            check(team.name + ": de pe banda, de la fk_cross_from = centrare", "cross".equals(cross));
            String play = SetPiece.fkKind(team, Geo.cx(Wt.fk_cross_from - 1.0, d), Geo.cy(2.0, d));
            check(team.name + ": sub fk_cross_from = joc scurt", "play".equals(play));
            String own = SetPiece.fkKind(team, Geo.cx(20.0, d), Geo.cy(Geo.MID_Y, d));
            check(team.name + ": in jumatatea proprie = joc scurt", "play".equals(own));
        }
    }

    private static void freeKickEventCarriesTheKind() {
        Sim s = sim();
        Team team = s.teams[0];
        double[][] spots = {{Geo.L - 20.0, Geo.MID_Y}, {Geo.L - 25.0, 4.0}, {40.0, 30.0}};
        for (double[] at : spots) {
            String kind = SetPiece.fkKind(team, at[0], at[1]);
            int n = s.events.size();
            s.setRestart("free_kick", team, at[0], at[1]);
            Map<String, Object> e = null;
            for (int i = n; i < s.events.size(); i++) {
                if ("FREE_KICK".equals(s.events.get(i).get("type"))) {
                    e = s.events.get(i);
                }
            }
            check("FREE_KICK are kind = " + kind, e != null && kind.equals(e.get("kind"))
                    && Integer.valueOf(0).equals(e.get("team")) && kind.equals(s.fkKind));
            boolean wall = "shot".equals(kind) == (s.sp != null && "fk_shot".equals(s.sp.kind) && s.sp.wall.length > 0);
            check("la lovitura libera directa se face zid (" + kind + ")", wall);
        }
    }

    // ------------------------------------------------------------------ executantii
    @SuppressWarnings("unchecked")
    private static Map<String, Object> withIds(Map<String, Object> team) {
        List<Map<String, Object>> players = (List<Map<String, Object>>) team.get("players");
        for (int i = 0; i < players.size(); i++) {
            players.get(i).put("id", 100 + i);
        }
        return team;
    }

    @SuppressWarnings("unchecked")
    private static void rate(Map<String, Object> team, int player, String attribute, int value) {
        List<Map<String, Object>> players = (List<Map<String, Object>>) team.get("players");
        ((Map<String, Object>) players.get(player).get("ratings")).put(attribute, value);
    }

    private static void tacticTakersAreUsed() {
        Map<String, Object> home = withTactic(withIds(GoalRulesTest.team("Rosii", 12)),
                "penaltyTakerId", 105, "freeKickTakerId", "107", "corner_taker_left_id", 108L,
                "cornerTakerRightId", 109.0);
        rate(home, 9, "penaltyTaking", 20);         // cel mai bun e altul: tactica are intaietate
        rate(home, 9, "freeKick", 20);
        rate(home, 10, "corners", 20);
        Sim s = sim(home, GoalRulesTest.team("Albii", 12));
        Team team = s.teams[0];
        check("id-urile jucatorilor sunt citite", team.players[5].id == 105L && team.players[0].id == 100L);
        check("penalty: executantul din tactica", team.penTaker == team.players[5]);
        check("lovitura libera: executantul din tactica (id ca text)", team.fkTaker == team.players[7]);
        check("cornerul din stanga: executantul din tactica (cheie cu alta scriere)", team.cornerLeft == team.players[8]);
        check("cornerul din dreapta: executantul din tactica (id zecimal)", team.cornerRight == team.players[9]);
        s.setRestart("penalty", team, Geo.L - Wt.pen_spot, Geo.MID_Y);
        check("la penalty bate cel din tactica", s.taker == team.players[5]);
        s.setRestart("free_kick", team, Geo.L - 20.0, Geo.MID_Y);
        check("la lovitura libera directa bate cel din tactica", "shot".equals(s.fkKind) && s.taker == team.players[7]);
        s.setRestart("free_kick", team, Wt.fk_cross_from + 2.0, 3.0);
        check("la lovitura libera centrata bate cel din tactica", "cross".equals(s.fkKind) && s.taker == team.players[7]);
        s.setRestart("corner", team, Geo.L, Geo.cy(0.0, team.dir));
        check("la cornerul din stanga bate cel din tactica", s.taker == team.players[8]);
        s.setRestart("corner", team, Geo.L, Geo.cy(Geo.W, team.dir));
        check("la cornerul din dreapta bate cel din tactica", s.taker == team.players[9]);
        Team away = s.teams[1];
        check("echipa fara tactica nu are executanti de corner alesi", away.cornerLeft == null && away.cornerRight == null);
    }

    private static void missingOrUnknownTakersArePickedByAttribute() {
        Map<String, Object> home = withTactic(withIds(GoalRulesTest.team("Rosii", 12)),
                "penaltyTakerId", 9999, "freeKickTakerId", "abc");
        rate(home, 0, "penaltyTaking", 20);         // portarul nu bate
        rate(home, 0, "freeKick", 20);
        rate(home, 3, "penaltyTaking", 17);         // egalitate: decide composure
        rate(home, 3, "composure", 10);
        rate(home, 6, "penaltyTaking", 17);
        rate(home, 6, "composure", 16);
        rate(home, 8, "freeKick", 18);
        rate(home, 2, "corners", 20);                // fundasul nu bate cornerul (regula veche)
        rate(home, 2, "crossing", 20);
        rate(home, 10, "corners", 18);
        Sim s = sim(home, GoalRulesTest.team("Albii", 12));
        Team team = s.teams[0];
        check("id inexistent: penalty-ul il bate cel mai bun la penaltyTaking (la egalitate composure), nu portarul",
                team.penTaker == team.players[6]);
        check("id care nu e numar: lovitura libera o bate cel mai bun la freeKick, nu portarul",
                team.fkTaker == team.players[8]);
        check("fara executant de corner: null, se foloseste regula veche", team.cornerLeft == null && team.cornerRight == null);
        s.setRestart("corner", team, Geo.L, 0.0);
        check("regula veche: cel mai bun la corners + crossing, fara fundasi si portar", s.taker == team.players[10]);
        Sim plain = sim(withIds(GoalRulesTest.team("Rosii", 12)), GoalRulesTest.team("Albii", 12));
        check("fara tactica, la egalitate: primul jucator de camp", plain.teams[0].penTaker == plain.teams[0].players[1]
                && plain.teams[0].fkTaker == plain.teams[0].players[1]);
        check("jucatori fara id: id null", s.teams[1].players[3].id == null);
    }

    // ------------------------------------------------------------------ penalty si zid
    private static double penaltyChance(int taking, int keeper) {
        Sim s = sim();
        Team team = s.teams[0];
        Player tk = team.players[9];
        tk.n[A.penaltyTaking] = norm(taking);
        Player gk = s.teams[1].gk;
        gk.n[A.reflexes] = norm(keeper);
        gk.n[A.oneOnOnes] = norm(keeper);
        s.setRestart("penalty", team, Geo.L - Wt.pen_spot, Geo.MID_Y);
        double before = team.mXg;
        int shots = team.shots;
        SetPiece.takePenalty(s, tk);
        check("penalty-ul e un sut", team.shots == shots + 1 && s.ball.kind == Ball.SHOT && s.shot != null
                && s.shot.setPiece == 1);
        return team.mXg - before;          // sansa de gol, adunata la xG
    }

    private static void penaltyChanceRisesWithPenaltyTakingAndFallsWithTheKeeper() {
        double weak = penaltyChance(5, 10);
        double mid = penaltyChance(10, 10);
        double strong = penaltyChance(16, 10);
        check("penaltyTaking mare = sansa mai mare (" + weak + " < " + mid + " < " + strong + ")",
                weak < mid && mid < strong);
        double poorGk = penaltyChance(10, 4);
        double goodGk = penaltyChance(10, 17);
        check("portarul bun scade sansa (" + goodGk + " < " + mid + " < " + poorGk + ")", goodGk < mid && mid < poorGk);
        boolean range = true;
        for (int a : new int[] {1, 20}) {
            for (int g : new int[] {1, 20}) {
                double p = penaltyChance(a, g);
                range &= p > 0.0 && p < 1.0;
            }
        }
        check("sansa de gol din penalty e mereu intre 0 si 1", range);
        Sim s = sim();
        s.setRestart("penalty", s.teams[0], Geo.L - Wt.pen_spot, Geo.MID_Y);
        Map<String, Object> pen = s.events.get(s.events.size() - 1);
        SetPiece.takePenalty(s, s.taker);
        Map<String, Object> shot = s.events.get(s.events.size() - 1);
        check("PENALTY apoi SHOT cu penalty = true", "PENALTY".equals(pen.get("type")) && "SHOT".equals(shot.get("type"))
                && Boolean.TRUE.equals(shot.get("penalty")));
    }

    private static void wallStopsLessWithBetterFreeKick() {
        Sim s = sim();
        Player p = s.teams[0].players[7];
        double prev = 2.0;
        boolean order = true;
        boolean range = true;
        for (int r = 1; r <= 20; r++) {
            p.n[A.freeKick] = norm(r);
            double w = SetPiece.wallProb(p);
            range &= w >= 0.0 && w <= 0.95;
            order &= w <= prev;
            prev = w;
        }
        p.n[A.freeKick] = norm(3);
        double low = SetPiece.wallProb(p);
        p.n[A.freeKick] = norm(18);
        double high = SetPiece.wallProb(p);
        check("cu freeKick mare mingea ramane mai rar in zid", order && high < low);
        check("sansa zidului e intre 0 si 0.95", range);
    }
}
