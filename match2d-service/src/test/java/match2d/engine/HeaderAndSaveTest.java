package match2d.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Teste pentru parada si lovitura de cap: {@link Shooting#saveProb} ramane in [0, 1] pentru orice
 * portar si creste cu reflexes si handling; conditiile din {@link Shooting#canHeadAtGoal};
 * {@link Shooting#headerXg} in [0, 1]; lovitura de cap la poarta din mingea inalta. Fara
 * biblioteci de test:
 *
 * <pre>   ./build.sh &amp;&amp; ./test.sh HeaderAndSaveTest</pre>
 */
public final class HeaderAndSaveTest {
    private static int checks;
    private static final List<String> FAILED = new ArrayList<>();
    private static final int[] RATINGS = {1, 5, 10, 15, 20};

    private HeaderAndSaveTest() {
    }

    public static void main(String[] args) {
        saveProbStaysBetweenZeroAndOneForAnyKeeper();
        saveProbRisesWithReflexes();
        saveProbRisesWithHandling();
        canHeadAtGoalNeedsALoftedPassFromATeammateInFrontOfGoal();
        canHeadAtGoalWorksForBothDirections();
        headerXgStaysBetweenZeroAndOne();
        betterHeadingPutsMoreHeadersOnTarget();
        headerAtGoalIsAShotAfterASuccessfulCross();
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

    private static Sim sim() {
        return new Sim(GoalRulesTest.team("Rosii", 12), GoalRulesTest.team("Albii", 12), 5, 10.0, 10, false);
    }

    private static Shooting.Kick kick(double distG, double speed, double zEnd) {
        Shooting.Kick k = new Shooting.Kick();
        k.distG = distG;
        k.pathLen = distG;
        k.speed = speed;
        k.zEnd = zEnd;
        k.onTarget = true;
        k.ux = 1.0;
        k.uy = 0.0;
        k.ex = Geo.L;
        k.ey = Geo.MID_Y;
        return k;
    }

    /** Toate suturile de proba: distanta, viteza, inaltimea, pe unde trece de portar. */
    private static List<double[]> shots() {
        List<double[]> out = new ArrayList<>();
        for (double distG : new double[] {4.0, 9.0, 15.0, 25.0, 35.0}) {
            for (double speed : new double[] {8.0, 14.0, 25.0, 34.0}) {
                for (double zEnd : new double[] {0.3, 1.2, 2.2}) {
                    for (double gu : new double[] {0.05, 0.4, 1.0}) {
                        for (double dlat : new double[] {0.0, 1.0, 2.5, 4.0}) {
                            out.add(new double[] {distG, speed, zEnd, gu, dlat});
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void keeper(Player gk, int reflexes, int handling, int agility, int oneOnOnes) {
        gk.n[A.reflexes] = norm(reflexes);
        gk.n[A.handling] = norm(handling);
        gk.n[A.agility] = norm(agility);
        gk.n[A.oneOnOnes] = norm(oneOnOnes);
    }

    private static double save(Sim s, Player p, double[] c, boolean special, double late) {
        return Shooting.saveProb(s, p, kick(c[0], c[1], c[2]), special, c[3], c[4], late);
    }

    // ------------------------------------------------------------------ parada
    private static void saveProbStaysBetweenZeroAndOneForAnyKeeper() {
        Sim s = sim();
        Player p = s.teams[0].players[9];
        Player gk = s.teams[1].gk;
        List<double[]> shots = shots();
        boolean ok = true;
        double max = 0.0;
        String worst = "";
        for (int r : RATINGS) {
            for (int h : RATINGS) {
                for (int a : RATINGS) {
                    for (int o : RATINGS) {
                        keeper(gk, r, h, a, o);
                        for (double[] c : shots) {
                            for (boolean special : new boolean[] {false, true}) {
                                for (double late : new double[] {0.0, Wt.fk_gk_late}) {
                                    double v = save(s, p, c, special, late);
                                    if (!(v >= 0.0 && v <= 1.0)) {
                                        ok = false;
                                        worst = "portar " + r + "/" + h + "/" + a + "/" + o + " sut " + java.util.Arrays.toString(c)
                                                + " -> " + v;
                                    }
                                    max = Math.max(max, v);
                                }
                            }
                        }
                    }
                }
            }
        }
        check("saveProb e intre 0 si 1 pentru orice portar si orice sut " + worst, ok);
        check("portarul cel mai bun ajunge la sansa maxima (" + max + "), dar nu trece de 1", max <= 1.0 && max > 0.5);
    }

    private static boolean rises(int attribute, String name) {
        Sim s = sim();
        Player p = s.teams[0].players[9];
        Player gk = s.teams[1].gk;
        boolean monotone = true;
        boolean strict = false;
        for (double[] c : shots()) {
            for (boolean special : new boolean[] {false, true}) {
                double prev = -1.0;
                double first = 0.0;
                double last = 0.0;
                for (int r = 1; r <= 20; r++) {
                    keeper(gk, 10, 10, 10, 10);
                    gk.n[attribute] = norm(r);
                    double v = save(s, p, c, special, 0.0);
                    monotone &= v >= prev - 1e-12;
                    prev = v;
                    if (r == 1) {
                        first = v;
                    }
                    last = v;
                }
                strict |= last > first + 0.01;
            }
        }
        check("saveProb nu scade niciodata cand creste " + name, monotone);
        check("saveProb creste vizibil cu " + name + " (cel putin intr-un caz)", strict);
        return monotone && strict;
    }

    private static void saveProbRisesWithReflexes() {
        rises(A.reflexes, "reflexes");
    }

    private static void saveProbRisesWithHandling() {
        rises(A.handling, "handling");
    }

    // ------------------------------------------------------------------ lovitura de cap
    /** Mingea inalta a coechipierului {@code kicker} spre {@code q}, in zbor. */
    private static Sim.Pending loftedPass(Sim s, Player kicker, Player q) {
        Ball b = s.ball;
        b.holder = null;
        b.kind = Ball.PASS;
        b.lofted = true;
        b.kicker = kicker;
        Sim.Pending pend = new Sim.Pending();
        pend.kicker = kicker;
        pend.recv = q;
        pend.loft = true;
        pend.sub = 1;
        pend.key = "cross";
        s.pending = pend;
        return pend;
    }

    /** Pune {@code p} la ({@code cx}, {@code cy}) in canonicul echipei lui. */
    private static void at(Player p, double cx, double cy) {
        p.x = Geo.cx(cx, p.team.dir);
        p.y = Geo.cy(cy, p.team.dir);
    }

    private static void canHeadAtGoalNeedsALoftedPassFromATeammateInFrontOfGoal() {
        Sim s = sim();
        Team team = s.teams[0];
        Player q = team.players[9];
        Player kicker = team.players[5];
        at(q, Geo.L - 8.0, Geo.MID_Y + 2.0);
        Sim.Pending pend = loftedPass(s, kicker, q);
        check("minge inalta de la coechipier, in careu, aproape de poarta: da cu capul", Shooting.canHeadAtGoal(s, q));
        s.ball.lofted = false;
        check("pasa pe jos: nu", !Shooting.canHeadAtGoal(s, q));
        s.ball.lofted = true;
        s.ball.kind = Ball.LOOSE;
        check("minge libera: nu", !Shooting.canHeadAtGoal(s, q));
        s.ball.kind = Ball.PASS;
        s.pending = null;
        check("fara pasa in zbor: nu", !Shooting.canHeadAtGoal(s, q));
        s.pending = pend;
        pend.kicker = s.teams[1].players[5];
        check("pasa adversarului: nu", !Shooting.canHeadAtGoal(s, q));
        pend.kicker = kicker;
        pend.offside = true;
        check("primitorul in ofsaid: nu (se fluiera ofsaidul)", !Shooting.canHeadAtGoal(s, q));
        Player other = team.players[10];
        at(other, Geo.L - 9.0, Geo.MID_Y - 2.0);
        check("ofsaidul primitorului nu-l opreste pe alt coechipier", Shooting.canHeadAtGoal(s, other));
        pend.offside = false;
        at(q, Geo.L - 20.0, Geo.MID_Y);
        check("in afara careului: nu", !Shooting.canHeadAtGoal(s, q));
        if (Wt.header_range < 16.0) {
            at(q, Geo.L - 16.0, Geo.MID_Y + 3.0);
            check("in careu, dar mai departe de header_range: nu", !Shooting.canHeadAtGoal(s, q));
        }
        double r = Math.min(Wt.header_range - 0.5, 16.0);
        at(q, Geo.L - r, Geo.MID_Y);
        check("in careu, chiar sub header_range: da", Shooting.canHeadAtGoal(s, q));
        at(q, Geo.L - 5.0, Geo.MID_Y + 21.0);
        check("langa poarta dar in afara latimii careului: nu", !Shooting.canHeadAtGoal(s, q));
        Player keeper = team.gk;
        at(keeper, Geo.L - 6.0, Geo.MID_Y);
        check("portarul nu da cu capul la poarta", !Shooting.canHeadAtGoal(s, keeper));
        at(q, 6.0, Geo.MID_Y);
        check("in careul propriu: nu", !Shooting.canHeadAtGoal(s, q));
    }

    private static void canHeadAtGoalWorksForBothDirections() {
        Sim s = sim();
        Team away = s.teams[1];
        Player q = away.players[9];
        at(q, Geo.L - 7.0, Geo.MID_Y);
        loftedPass(s, away.players[5], q);
        check("echipa care ataca spre stanga: in careul advers (x mic in lume) da cu capul",
                q.x < 16.5 && Shooting.canHeadAtGoal(s, q));
        q.x = Geo.L - 7.0;
        check("echipa care ataca spre stanga: in careul propriu nu", !Shooting.canHeadAtGoal(s, q));
    }

    private static void headerXgStaysBetweenZeroAndOne() {
        Sim s = sim();
        Player q = s.teams[0].players[9];
        boolean ok = true;
        double best = 0.0;
        for (int h = 1; h <= 20; h += 3) {
            q.n[A.heading] = norm(h);
            for (double press : new double[] {0.0, 0.5, 1.0}) {
                for (double[] spot : new double[][] {{Geo.L - 5.0, Geo.MID_Y}, {Geo.L - 11.0, Geo.MID_Y + 6.0},
                    {Geo.L - 3.0, Geo.MID_Y - 8.0}}) {
                    at(q, spot[0], spot[1]);
                    double xg = Shooting.headerXg(s, q, press);
                    ok &= xg >= 0.0 && xg <= 1.0;
                    best = Math.max(best, xg);
                }
            }
        }
        check("headerXg e intre 0 si 1", ok);
        check("lovitura de cap de aproape are o sansa reala (" + best + ")", best > 0.0);
    }

    private static void betterHeadingPutsMoreHeadersOnTarget() {
        Sim s = sim();
        Player q = s.teams[0].players[9];
        at(q, Geo.L - 9.0, Geo.MID_Y + 3.0);
        int[] on = new int[2];
        int[] ratings = {3, 18};
        for (int i = 0; i < 2; i++) {
            q.n[A.heading] = norm(ratings[i]);
            Mt rng = new Mt(77);
            for (int k = 0; k < 4000; k++) {
                on[i] += Shooting.headerKick(s, q, 0.3, rng).onTarget ? 1 : 0;
            }
        }
        check("cu heading mare mai multe lovituri de cap pe poarta (" + on[0] + " fata de " + on[1] + ")",
                on[1] > on[0] * 1.1);
    }

    private static void headerAtGoalIsAShotAfterASuccessfulCross() {
        Sim s = sim();
        Team team = s.teams[0];
        Player q = team.players[9];
        Player kicker = team.players[8];
        at(q, Geo.L - 7.0, Geo.MID_Y);
        s.state = Sim.LIVE;
        s.poss = team;
        s.ball.x = q.x;
        s.ball.y = q.y;
        loftedPass(s, kicker, q);
        int shots = team.shots;
        int headers = team.headers;
        int ok = team.crossesOk;
        int passesOk = team.passesOk;
        int n = s.events.size();
        Shooting.doHeader(s, q, Wt.header_press_free);
        boolean passOk = false;
        Map<String, Object> shot = null;
        for (int i = n; i < s.events.size(); i++) {
            Map<String, Object> e = s.events.get(i);
            if ("PASS_OK".equals(e.get("type")) && Integer.valueOf(q.uid).equals(e.get("p"))
                    && Integer.valueOf(kicker.uid).equals(e.get("q"))) {
                passOk = true;
            }
            if ("SHOT".equals(e.get("type"))) {
                shot = e;
            }
        }
        check("centrarea ajunsa e o pasa reusita", passOk && team.passesOk == passesOk + 1 && team.crossesOk == ok + 1);
        check("lovitura de cap e un SHOT cu header = true", shot != null && Boolean.TRUE.equals(shot.get("header"))
                && Integer.valueOf(q.uid).equals(shot.get("p")) && Integer.valueOf(0).equals(shot.get("team")));
        check("e numarata ca sut si ca lovitura de cap", team.shots == shots + 1 && team.headers == headers + 1);
        check("mingea pleaca spre poarta ca un sut de cap", s.ball.kind == Ball.SHOT && s.shot != null
                && s.shot.setPiece == 3 && s.shot.shooter == q && s.pending == null);
    }
}
