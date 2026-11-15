package match2d.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Teste pentru echilibrul formatiilor (Runs): taria liniei de atac, alergarile in careu, omul in
 * plus tinut mai jos si coborarea atacantilor. Toate pe drumul nou; asteptarile vin din campurile
 * Wt, nu din numere scrise aici. Fara biblioteci de test:
 *
 * <pre>   ./build.sh &amp;&amp; ./test.sh RunsTest</pre>
 */
public final class RunsTest {
    private static int checks;
    private static final List<String> FAILED = new ArrayList<>();

    private RunsTest() {
    }

    public static void main(String[] args) {
        lineStrengthAndMissingMenFollowTheWeights();
        secondLineManIsTheAttackingMidfielder();
        secondLineManRunsFirst();
        withoutASecondLineManTheMostAdvancedMidfieldersRun();
        playersTooFarBehindTheBallDoNotRun();
        nobodyRunsWhenTheLineIsComplete();
        runsStopWhenTheBallIsLost();
        runsStopOnlyWhenPlayGoesBackFarEnough();
        extraMenOnTheLineAreHeldDeeper();
        onlyTheMostAdvancedStayUpWhenDefending();
        stayUpCountFollowsTheWeight();
        droppingForwardDefendsDeeperWithTheBallNearHisGoal();
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

    /** O echipa cu pozitiile date (in ordinea din lista), toate atributele la {@code level}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> teamOf(String name, int level, String formation, List<String> positions) {
        Map<String, Object> t = GoalRulesTest.team(name, level);
        List<Map<String, Object>> players = (List<Map<String, Object>>) t.get("players");
        for (int i = 0; i < 11; i++) {
            players.get(i).put("position", positions.get(i));
        }
        t.put("formation", formation);
        return t;
    }

    static Map<String, Object> teamOf(String name, int level, String formation) {
        return teamOf(name, level, formation, Zones.FORMATIONS.get(formation));
    }

    private static Sim simWith(Map<String, Object> home) {
        return new Sim(home, GoalRulesTest.team("Albii", 12), 1, 10.0, 10, false);
    }

    private static Sim sim(String formation) {
        return simWith(teamOf("Rosii", 12, formation));
    }

    private static int need(double missing) {
        return (int) Math.ceil(missing - 1e-9);
    }

    /**
     * Echipa {@code team} ataca, mingea e la {@code bcx} (canonic), pe ax. Jucatorii de camp sunt
     * asezati in urma mingii la distante diferite ({@code bcx - 4 - idx}), ca ordinea dupa cat de
     * avansati sunt sa fie clara; nimeni nu are rol.
     */
    private static void prepareAttack(Sim s, Team team, double bcx) {
        s.state = Sim.LIVE;
        s.poss = team;
        s.t = 10.0;
        Ball b = s.ball;
        b.holder = null;
        b.kind = Ball.PASS;
        b.x = Geo.cx(bcx, team.dir);
        b.y = Geo.MID_Y;
        for (Player p : team.players) {
            if (!p.isGk) {
                p.x = Geo.cx(bcx - 4.0 - p.idx, team.dir);
            }
            p.running = false;
            p.abox = p.abox0;
        }
        for (Player p : s.all) {
            p.role = Sim.R_NONE;
            p.stunUntil = -1.0;
        }
    }

    /** Pragul de la care pornesc alergarile echipei. */
    private static double runFrom(Team team) {
        return Wt.run_from - Wt.run_slope * team.runMissing;
    }

    /** Mingea, in canonic, unde alergarile sigur pornesc: aproape de poarta, peste prag. */
    private static double attackBall(Team team) {
        return Math.min(Geo.L - 3.0, Math.max(runFrom(team) + 1.0, Geo.L - 10.0));
    }

    private static List<Player> runners(Team team) {
        List<Player> out = new ArrayList<>();
        for (Player p : team.players) {
            if (p.running) {
                out.add(p);
            }
        }
        return out;
    }

    /** Cei care pot alerga (nu portar, nu fundas, nu om de atac de pe linie), in raza mingii. */
    private static List<Player> eligibleByAdvance(Team team, double bcx, boolean seconds) {
        List<Player> out = new ArrayList<>();
        for (Player p : team.players) {
            boolean second = p.forward && Runs.isSecond(p);
            if (p.isGk || p.isDef || (p.forward && !second) || second != seconds) {
                continue;
            }
            if (Geo.cx(p.x, team.dir) < bcx - Wt.run_reach) {
                continue;
            }
            out.add(p);
        }
        out.sort((a, c) -> Double.compare(Geo.cx(c.x, team.dir), Geo.cx(a.x, team.dir)));
        return out;
    }

    /** Alergatorii asteptati: intai oamenii din linia a doua, apoi cei mai avansati ceilalti. */
    private static Set<Player> expectedRunners(Team team, double bcx) {
        List<Player> order = new ArrayList<>(eligibleByAdvance(team, bcx, true));
        order.addAll(eligibleByAdvance(team, bcx, false));
        return new HashSet<>(order.subList(0, Math.min(team.runNeed, order.size())));
    }

    // ------------------------------------------------------------------ taria liniei
    private static void lineStrengthAndMissingMenFollowTheWeights() {
        Object[][] cases = {
            {"4-4-2", 2.0, 2},
            {"4-2-3-1", 1.0 + Wt.run_second + 2.0 * Wt.run_wide, 4},
            {"4-1-4-1", 1.0, 1},
            {"4-4-1-1", 1.0 + Wt.run_second, 2},
            {"4-3-3", 1.0 + 2.0 * Wt.run_wide, 3},
        };
        for (Object[] c : cases) {
            String f = (String) c[0];
            double strength = (Double) c[1];
            int forwards = (Integer) c[2];
            Team team = sim(f).teams[0];
            double missing = Math.max(0.0, Wt.run_target - strength);
            check(f + ": runMissing = run_target - taria liniei (" + team.runMissing + " fata de " + missing + ")",
                    Math.abs(team.runMissing - missing) < 1e-9);
            check(f + ": runNeed = ceil(runMissing) (" + team.runNeed + ")", team.runNeed == need(missing));
            int n = 0;
            for (Player p : team.players) {
                n += p.forward ? 1 : 0;
                check(f + ": forward e exact isForward la " + p.slot, p.forward == Runs.isForward(p));
            }
            check(f + ": " + forwards + " oameni de atac (are " + n + ")", n == forwards);
        }
    }

    private static void secondLineManIsTheAttackingMidfielder() {
        Team t4231 = sim("4-2-3-1").teams[0];
        Player aml = t4231.players[7];
        Player amc = t4231.players[8];
        Player st = t4231.players[10];
        check("4-2-3-1: AMC e om de atac din linia a doua", amc.forward && Runs.isSecond(amc));
        check("4-2-3-1: atacantul nu e din linia a doua", st.forward && !Runs.isSecond(st));
        check("4-2-3-1: extrema nu e din linia a doua", aml.forward && !Runs.isSecond(aml));
        Team t442 = sim("4-4-2").teams[0];
        check("4-4-2: niciun atacant nu e din linia a doua",
                !Runs.isSecond(t442.players[9]) && !Runs.isSecond(t442.players[10]));
        check("4-4-2: mijlocasii nu sunt oameni de atac", !t442.players[6].forward && !t442.players[5].forward);
        Team t4411 = sim("4-4-1-1").teams[0];
        check("4-4-1-1: AMC e om de atac din linia a doua", t4411.players[9].forward && Runs.isSecond(t4411.players[9]));
    }

    // ------------------------------------------------------------------ alergarile in careu
    private static void secondLineManRunsFirst() {
        for (String f : new String[] {"4-4-1-1", "4-2-3-1"}) {
            Sim s = sim(f);
            Team team = s.teams[0];
            double bcx = attackBall(team);
            prepareAttack(s, team, bcx);
            Player amc = null;
            for (Player p : team.players) {
                if (p.forward && Runs.isSecond(p)) {
                    amc = p;
                }
            }
            // omul din linia a doua e cel mai in urma dintre cei care pot alerga, dar tot in raza
            amc.x = Geo.cx(bcx - Wt.run_reach + 1.0, team.dir);
            check(f + ": are nevoie de alergari", team.runNeed > 0);
            Runs.update(s);
            List<Player> run = runners(team);
            check(f + ": alearga omul din linia a doua, desi e cel mai in urma", amc.running);
            check(f + ": alearga exact runNeed oameni (" + run.size() + " din " + team.runNeed + ")",
                    run.size() == team.runNeed);
            check(f + ": alergatorii sunt cei asteptati", new HashSet<>(run).equals(expectedRunners(team, bcx)));
            boolean target = true;
            for (Player p : run) {
                target &= p.runX == Geo.L - Wt.run_depth && p.abox == p.runBox && p.thinkAt == s.t
                        && !p.isDef && !p.isGk;
            }
            check(f + ": alergatorul tinteste careul (run_depth) si are zona de alergare", target);
        }
    }

    private static void withoutASecondLineManTheMostAdvancedMidfieldersRun() {
        for (String f : new String[] {"4-1-4-1", "4-4-2", "4-3-3"}) {
            Sim s = sim(f);
            Team team = s.teams[0];
            double bcx = attackBall(team);
            prepareAttack(s, team, bcx);
            Runs.update(s);
            List<Player> run = runners(team);
            check(f + ": alearga runNeed oameni (" + run.size() + " din " + team.runNeed + ")", run.size() == team.runNeed);
            check(f + ": alearga cei mai avansati care nu sunt fundasi si nici oameni de atac",
                    new HashSet<>(run).equals(expectedRunners(team, bcx)));
            boolean none = true;
            for (Player p : run) {
                none &= !p.isDef && !p.isGk && !p.forward;
            }
            check(f + ": niciun fundas, portar sau om de atac nu alearga", none);
        }
    }

    private static void playersTooFarBehindTheBallDoNotRun() {
        Sim s = sim("4-2-3-1");
        Team team = s.teams[0];
        double bcx = attackBall(team);
        prepareAttack(s, team, bcx);
        Player amc = team.players[8];
        amc.x = Geo.cx(bcx - Wt.run_reach - 3.0, team.dir);
        Runs.update(s);
        check("omul din linia a doua aflat prea departe in urma mingii nu alearga", !amc.running);
        check("in locul lui alearga cel mai avansat mijlocas", new HashSet<>(runners(team)).equals(expectedRunners(team, bcx))
                && runners(team).size() == team.runNeed);
        // si cine are mingea sau un rol nu alearga
        Sim s2 = sim("4-1-4-1");
        Team t2 = s2.teams[0];
        double b2 = attackBall(t2);
        prepareAttack(s2, t2, b2);
        Player first = expectedRunners(t2, b2).iterator().next();
        for (Player p : expectedRunners(t2, b2)) {
            if (Geo.cx(p.x, t2.dir) > Geo.cx(first.x, t2.dir)) {
                first = p;
            }
        }
        first.role = Sim.R_PRESS;
        Runs.update(s2);
        check("cine are un rol nu porneste in alergare", !first.running);
    }

    private static void nobodyRunsWhenTheLineIsComplete() {
        List<String> three = List.of("GK", "DL", "DC", "DC", "DR", "ML", "MC", "MR", "ST", "ST", "ST");
        double saved = Wt.run_target;
        Sim s;
        try {
            Wt.set("run.target", Math.min(Wt.run_target, 3.0));       // trei atacanti valoreaza 3
            s = simWith(teamOf("Rosii", 12, "4-3-3x", three));
        } finally {
            Wt.set("run.target", saved);
        }
        Team team = s.teams[0];
        check("cu trei atacanti nu lipseste nimeni (runNeed = " + team.runNeed + ")",
                team.runNeed == 0 && team.runMissing == 0.0);
        prepareAttack(s, team, Geo.L - 3.0);
        double from = Wt.run_from;
        try {
            Wt.set("run.from", 0.0);            // pragul nu mai opreste nimic: doar runNeed conteaza
            Runs.update(s);
        } finally {
            Wt.set("run.from", from);
        }
        check("cand runNeed e 0 nimeni nu alearga", runners(team).isEmpty());
    }

    private static void runsStopWhenTheBallIsLost() {
        Sim s = sim("4-1-4-1");
        Team team = s.teams[0];
        prepareAttack(s, team, attackBall(team));
        Runs.update(s);
        check("inainte de pierderea mingii sunt alergatori", !runners(team).isEmpty());
        BallPlay.gain(s, s.teams[1].players[6]);
        boolean back = true;
        for (Player p : team.players) {
            back &= p.abox == p.abox0;
        }
        check("dupa ce adversarul ia mingea nimeni nu mai alearga", runners(team).isEmpty());
        check("alergatorii isi recapata zona obisnuita", back);
        check("posesia e a adversarului", s.poss == s.teams[1]);
        Runs.update(s);
        check("cat adversarul are mingea alergarile nu pornesc iar", runners(team).isEmpty());
    }

    private static void runsStopOnlyWhenPlayGoesBackFarEnough() {
        Sim s = sim("4-1-4-1");
        Team team = s.teams[0];
        double from = runFrom(team);
        prepareAttack(s, team, attackBall(team));
        Runs.update(s);
        int n = runners(team).size();
        check("pornesc alergarile", n > 0);
        s.ball.x = Geo.cx(from - 0.5 * Wt.run_keep, team.dir);
        Runs.update(s);
        check("mingea putin sub prag: alergarea continua (run_keep)", runners(team).size() == n);
        s.ball.x = Geo.cx(from - Wt.run_keep - 1.0, team.dir);
        Runs.update(s);
        check("jocul s-a intors cu peste run_keep m: alergarea se opreste", runners(team).isEmpty());
        prepareAttack(s, team, attackBall(team));
        Runs.update(s);
        s.ball.kind = Ball.SHOT;
        Runs.update(s);
        check("la sut alergarile se opresc", runners(team).isEmpty());
        prepareAttack(s, team, attackBall(team));
        Runs.update(s);
        s.setRestart("throw", team, 50.0, 0.0);
        check("la repunere alergarile se opresc", runners(team).isEmpty());
    }

    // ------------------------------------------------------------------ omul tinut in spate
    private static void extraMenOnTheLineAreHeldDeeper() {
        for (String f : new String[] {"4-2-2-2", "4-4-2", "4-3-3"}) {
            Map<String, Object> data = teamOf("Rosii", 12, f);
            double saved = Wt.hold_line;
            Sim free;
            try {
                Wt.set("hold.line", 11.0);              // referinta: nimeni nu e tinut mai jos
                free = simWith(data);
            } finally {
                Wt.set("hold.line", saved);
            }
            Sim held = simWith(teamOf("Rosii", 12, f));
            Team a = free.teams[0];
            Team b = held.teams[0];
            int line = 0;
            double maxHigh = Double.NEGATIVE_INFINITY;
            for (Player p : a.players) {
                if (p.forward && !Runs.isSecond(p)) {
                    line++;
                    maxHigh = Math.max(maxHigh, p.high[0]);
                }
            }
            int most = (int) Math.round(Wt.hold_line);
            double lowered = 0.0;
            boolean onlyLine = true;
            boolean bounded = true;
            boolean topKept = true;
            boolean sameWidth = true;
            for (int i = 0; i < 11; i++) {
                Player pa = a.players[i];
                Player pb = b.players[i];
                double d = pa.high[0] - pb.high[0];
                lowered += d;
                if (d > 1e-9 && !(pa.forward && !Runs.isSecond(pa))) {
                    onlyLine = false;
                }
                bounded &= d >= -1e-9 && d <= Wt.hold_depth + 1e-9;
                sameWidth &= pa.high[1] == pb.high[1];
                if (pa.forward && pa.high[0] == maxHigh && line > 0 && most > 0 && d > 1e-9
                        && countAt(a, maxHigh) <= most) {
                    topKept = false;
                }
                check(f + ": dupa tinere, forward e isForward la " + pb.slot, pb.forward == Runs.isForward(pb));
            }
            double expected = Wt.hold_depth * Math.max(0, line - most);
            check(f + ": cei in plus pe linie (" + line + " fata de " + most + ") sunt tinuti in total cu hold_depth x surplus ("
                    + lowered + " fata de " + expected + ")", Math.abs(lowered - expected) < 1e-6);
            check(f + ": doar oamenii de pe linie sunt tinuti mai jos", onlyLine);
            check(f + ": nimeni nu e tinut cu mai mult de hold_depth", bounded);
            check(f + ": cei mai avansati raman sus", topKept);
            check(f + ": tinerea nu schimba latimea", sameWidth);
            check(f + ": tinerea nu schimba cati oameni lipsesc", a.runMissing == b.runMissing);
        }
    }

    private static int countAt(Team team, double high) {
        int n = 0;
        for (Player p : team.players) {
            if (Math.abs(p.high[0] - high) < 0.5) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ coborarea atacantilor
    private static void checkDropShares(String f, Team team, double stayUp) {
        List<Player> deep = new ArrayList<>();
        boolean range = true;
        boolean lowStay = true;
        for (Player p : team.players) {
            range &= p.dropK >= 0.0 && p.dropK <= 1.0;
            if (p.isGk) {
                lowStay &= p.dropK == 0.0;
                continue;
            }
            if (p.deep[0] > Wt.drop_deep) {
                deep.add(p);
            } else {
                lowStay &= p.dropK == 0.0;
            }
        }
        double up = 0.0;
        for (Player p : deep) {
            up += 1.0 - p.dropK;
        }
        double expected = Math.min(stayUp, deep.size());
        check(f + ": raman sus exact " + expected + " jucatori (suma " + up + ")", Math.abs(up - expected) < 1e-9);
        check(f + ": dropK e intre 0 si 1", range);
        check(f + ": cine sta deja jos (sub drop_deep) nu coboara", lowStay);
        boolean order = true;
        for (Player p : deep) {
            for (Player q : deep) {
                if (p.deep[0] > q.deep[0] + 0.5 && p.dropK > q.dropK + 1e-12) {
                    order = false;
                }
            }
        }
        check(f + ": cel mai avansat nu coboara mai mult decat unul mai retras", order);
    }

    private static void onlyTheMostAdvancedStayUpWhenDefending() {
        for (String f : new String[] {"4-2-3-1", "4-3-3", "4-1-4-1", "4-4-1-1", "4-4-2", "4-2-2-2"}) {
            checkDropShares(f, sim(f).teams[0], Wt.drop_stay_up);
        }
        Team t = sim("4-2-3-1").teams[0];
        check("4-2-3-1: cu drop_stay_up >= 1 atacantul ramane sus", Wt.drop_stay_up < 1.0 || t.players[10].dropK == 0.0);
    }

    private static void stayUpCountFollowsTheWeight() {
        double saved = Wt.drop_stay_up;
        try {
            for (double k : new double[] {1.0, 3.0}) {
                Wt.set("drop.stay_up", k);
                for (String f : new String[] {"4-2-3-1", "4-3-3", "4-4-1-1"}) {
                    checkDropShares(f + " (drop_stay_up " + k + ")", sim(f).teams[0], k);
                }
            }
            Wt.set("drop.stay_up", 1.0);
            Team t = sim("4-2-3-1").teams[0];
            check("cu drop_stay_up 1 sus ramane doar atacantul", t.players[10].dropK == 0.0
                    && t.players[7].dropK == 1.0 && t.players[8].dropK == 1.0 && t.players[9].dropK == 1.0);
        } finally {
            Wt.set("drop.stay_up", saved);
        }
    }

    private static void droppingForwardDefendsDeeperWithTheBallNearHisGoal() {
        Sim s = sim("4-2-3-1");
        Player p = null;
        for (Player q : s.teams[0].players) {
            if (q.dropK > 0.0 && (p == null || q.dropK > p.dropK)) {
                p = q;
            }
        }
        check("4-2-3-1 are un mijlocas ofensiv care coboara", p != null);
        if (p == null) {
            return;
        }
        double k = p.dropK;
        double nearBall = Math.max(0.0, Wt.drop_from - Wt.drop_blend - 5.0);
        Positioning.formTarget(s, p, nearBall, Geo.MID_Y, false);
        double dropX = s.ox;
        double dropLo = p.dlo;
        p.dropK = 0.0;
        Positioning.formTarget(s, p, nearBall, Geo.MID_Y, false);
        double stayX = s.ox;
        double stayLo = p.dlo;
        check("cu mingea langa poarta lui, cel care coboara sta mai jos (" + dropX + " fata de " + stayX + ")",
                dropX < stayX - 0.5);
        check("si zona lui de aparare coboara (dlo " + dropLo + " fata de " + stayLo + ")",
                dropLo < stayLo && stayLo == p.dbox[0] && dropLo >= Math.min(Wt.drop_box, p.dbox[0]) - 1e-9);
        double farBall = Wt.drop_from + 5.0;
        p.dropK = k;
        Positioning.formTarget(s, p, farBall, Geo.MID_Y, false);
        double a = s.ox;
        p.dropK = 0.0;
        Positioning.formTarget(s, p, farBall, Geo.MID_Y, false);
        check("cu mingea departe (peste drop_from) nu coboara", a == s.ox);
        p.dropK = k;
        Positioning.formTarget(s, p, nearBall, Geo.MID_Y, true);
        double att = s.ox;
        p.dropK = 0.0;
        Positioning.formTarget(s, p, nearBall, Geo.MID_Y, true);
        check("cand echipa ataca, coborarea nu conteaza", att == s.ox);
        p.dropK = k;
    }
}
