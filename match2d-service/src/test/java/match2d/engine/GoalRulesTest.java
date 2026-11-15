package match2d.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Teste pentru regulile de gol ale motorului Java: cine e marcatorul, cine da pasa decisiva, cand
 * e autogol si incotro pleaca mingea respinsa de portar. Fara biblioteci de test, ca tot proiectul:
 *
 * <pre>   ./build.sh &amp;&amp; ./test.sh</pre>
 */
public final class GoalRulesTest {
    private static int checks;
    private static final List<String> FAILED = new ArrayList<>();

    private GoalRulesTest() {
    }

    public static void main(String[] args) {
        newRulesAreTheDefault();
        assistIsTheLastTeammateBeforeTheScorer();
        opponentInControlBreaksTheChain();
        reboundAfterASaveKeepsTheFirstShooter();
        scorerOfHisOwnReboundGetsTheEarlierPasser();
        restartStartsANewChain();
        samePlayerManyTimesDoesNotPushTheOthersOut();
        reboundTowardsTheGoalIsTurnedBackIntoPlay();
        reboundAwayOrWideKeepsItsDirection();
        reboundFromBehindTheLineStillLeavesTheGoal();
        ballPlayedInByAnAttackerIsHisGoalWithAnAssist();
        ballPutInByADefenderIsANamedOwnGoal();
        wholeMatchesFollowTheRules();
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

    /** O echipa 4-4-2 cu toate atributele la {@code level}. */
    static Map<String, Object> team(String name, int level) {
        String[] positions = {"GK", "DL", "DC", "DC", "DR", "ML", "MC", "MC", "MR", "ST", "ST"};
        List<Object> players = new ArrayList<>();
        for (int i = 0; i < positions.length; i++) {
            Map<String, Object> ratings = new LinkedHashMap<>();
            for (String attribute : A.NAMES) {
                ratings.put(attribute, level);
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", name + " " + (i + 1));
            p.put("number", i + 1);
            p.put("position", positions[i]);
            p.put("ratings", ratings);
            players.add(p);
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("formation", "4-4-2");
        t.put("color", "#d62828");
        t.put("gkColor", "#f4d35e");
        t.put("players", players);
        return t;
    }

    private static Sim sim(long seed, double minutes) {
        return new Sim(team("Rosii", 12), team("Albii", 12), seed, minutes, 10, false);
    }

    private static Sim sim() {
        return sim(1, 10.0);
    }

    private static Map<String, Object> lastEvent(Sim s) {
        return s.events.get(s.events.size() - 1);
    }

    /** Duce mingea peste linia portii aparate de echipa 0 (x = 0), prin mijlocul portii. */
    private static void crossGoalLineOfTeam0(Sim s) {
        Ball b = s.ball;
        b.px = 1.0;
        b.py = Geo.MID_Y;
        b.x = -0.3;
        b.y = Geo.MID_Y;
        b.z = 0.0;
        BallPlay.outOfPlay(s);
    }

    // ------------------------------------------------------------------ pasa decisiva
    private static void newRulesAreTheDefault() {
        check("fara -Dmatch2d.legacy motorul joaca dupa regulile noi", !Sim.LEGACY);
    }

    private static void assistIsTheLastTeammateBeforeTheScorer() {
        Sim s = sim();
        Player passer = s.teams[0].players[6];
        Player scorer = s.teams[0].players[9];
        s.played(s.teams[0].players[2]);
        s.played(passer);
        s.played(scorer);
        check("pasa decisiva e a ultimului coechipier dinaintea marcatorului", s.assistFor(scorer) == passer);
    }

    private static void opponentInControlBreaksTheChain() {
        Sim s = sim();
        Player scorer = s.teams[0].players[9];
        s.played(s.teams[0].players[6]);
        s.played(s.teams[1].players[3]);        // adversarul a avut mingea
        s.played(scorer);                       // marcatorul i-a luat-o
        check("mingea luata de la adversar nu are pasa decisiva", s.assistFor(scorer) == null);
    }

    private static void reboundAfterASaveKeepsTheFirstShooter() {
        Sim s = sim();
        Player first = s.teams[0].players[9];
        Player second = s.teams[0].players[10];
        Player keeper = s.teams[1].gk;
        s.played(first);                                                        // suteaza
        BallPlay.looseBall(s, 99.0, 34.0, Math.PI, 6.0, s.teams[1], keeper);    // portarul respinge
        check("mingea respinsa tine minte cine a atins-o", s.ball.toucher == keeper);
        s.played(second);                                                       // preia
        s.played(second);                                                       // si suteaza
        check("golul din respingere are pasa de la primul sutor", s.assistFor(second) == first);
    }

    private static void scorerOfHisOwnReboundGetsTheEarlierPasser() {
        Sim s = sim();
        Player passer = s.teams[0].players[7];
        Player scorer = s.teams[0].players[9];
        s.played(passer);
        s.played(scorer);
        BallPlay.looseBall(s, 99.0, 34.0, Math.PI, 6.0, s.teams[1], s.teams[1].gk);
        s.played(scorer);
        check("cine isi marcheaza propria respingere are pasa de la cel care i-a dat mingea",
                s.assistFor(scorer) == passer);
    }

    private static void restartStartsANewChain() {
        Sim s = sim();
        Player taker = s.teams[0].players[6];
        s.played(s.teams[0].players[7]);
        s.setRestart("free_kick", s.teams[0], 80.0, 30.0);
        check("repunerea uita cine a atins mingea", s.ball.toucher == null);
        s.played(taker);
        check("golul direct din lovitura libera nu are pasa decisiva", s.assistFor(taker) == null);
    }

    private static void samePlayerManyTimesDoesNotPushTheOthersOut() {
        Sim s = sim();
        Player passer = s.teams[0].players[6];
        Player scorer = s.teams[0].players[9];
        s.played(passer);
        for (int i = 0; i < 40; i++) {
            s.played(scorer);
        }
        check("atingerile repetate ale marcatorului nu sterg pasatorul", s.assistFor(scorer) == passer);
        for (int i = 0; i < 40; i++) {
            s.played(s.all[i % 22]);            // un sir lung nu depaseste memoria tinuta
        }
        check("sirul tine minte ultimul jucator", s.ball.toucher == s.all[39 % 22]);
    }

    // ------------------------------------------------------------------ mingea respinsa
    private static void reboundTowardsTheGoalIsTurnedBackIntoPlay() {
        Sim s = sim();
        Team defending = s.teams[0];            // apara poarta de la x = 0
        double ang = BallPlay.awayFromGoal(3.0, Geo.MID_Y + 1.0, Math.PI, defending);
        check("respingerea care ar intra in poarta e intoarsa spre teren", PMath.cos(ang) > 0.9);
        Team other = s.teams[1];                // apara poarta de la x = L
        double ang2 = BallPlay.awayFromGoal(Geo.L - 3.0, Geo.MID_Y, 0.2, other);
        check("la fel la poarta cealalta", PMath.cos(ang2) < -0.9);
        check("intoarcerea pastreaza miscarea laterala", Math.abs(PMath.sin(ang2) - PMath.sin(0.2)) < 1e-9);
    }

    private static void reboundAwayOrWideKeepsItsDirection() {
        Sim s = sim();
        Team defending = s.teams[0];
        check("respingerea spre teren ramane cum e",
                BallPlay.awayFromGoal(3.0, Geo.MID_Y, 0.3, defending) == 0.3);
        double wide = Math.PI / 2 + 0.2;        // aproape paralel cu linia: iese departe de bare
        check("respingerea care iese pe langa poarta ramane cum e",
                BallPlay.awayFromGoal(3.0, Geo.MID_Y, wide, defending) == wide);
    }

    private static void reboundFromBehindTheLineStillLeavesTheGoal() {
        Sim s = sim();
        // sutul puternic a dus mingea dincolo de linie in pasul paradei
        double ang = BallPlay.awayFromGoal(-0.6, Geo.MID_Y, Math.PI - 0.4, s.teams[0]);
        check("parada prinsa dincolo de linie tot spre teren respinge", PMath.cos(ang) > 0.0);
        double ang2 = BallPlay.awayFromGoal(Geo.L + 0.6, Geo.MID_Y, 0.4, s.teams[1]);
        check("la fel la poarta cealalta, dincolo de linie", PMath.cos(ang2) < 0.0);
    }

    // ------------------------------------------------------------------ cine a marcat
    private static void ballPlayedInByAnAttackerIsHisGoalWithAnAssist() {
        Sim s = sim();
        Player passer = s.teams[1].players[7];
        Player crosser = s.teams[1].players[8];
        s.played(passer);
        s.played(crosser);                      // centrarea lui intra direct in poarta
        crossGoalLineOfTeam0(s);
        Map<String, Object> e = lastEvent(s);
        check("mingea intrata in poarta e gol", "GOAL".equals(e.get("type")));
        check("golul e al echipei care ataca", Integer.valueOf(1).equals(e.get("team")) && s.teams[1].goals == 1);
        check("marcatorul e cel care a jucat ultimul mingea", Integer.valueOf(crosser.uid).equals(e.get("p")));
        check("pasa decisiva e a coechipierului dinainte", Integer.valueOf(passer.uid).equals(e.get("assist")));
        check("nu e autogol", !e.containsKey("own"));
        check("dupa gol urmeaza lovitura de la centru a echipei care a primit golul", s.poss == s.teams[0]);
    }

    private static void ballPutInByADefenderIsANamedOwnGoal() {
        Sim s = sim();
        Player defender = s.teams[0].players[2];
        s.played(s.teams[1].players[8]);                                        // centrarea
        BallPlay.looseBall(s, 2.0, Geo.MID_Y, Math.PI, 6.0, s.teams[0], defender);  // fundasul o deviaza
        crossGoalLineOfTeam0(s);
        Map<String, Object> e = lastEvent(s);
        check("devierea in propria poarta e gol pentru adversar",
                "GOAL".equals(e.get("type")) && Integer.valueOf(1).equals(e.get("team")) && s.teams[1].goals == 1
                        && s.teams[0].goals == 0);
        check("autogolul nu are marcator si nici pasa decisiva", !e.containsKey("p") && !e.containsKey("assist"));
        check("autogolul spune cine l-a dat", Integer.valueOf(defender.uid).equals(e.get("own")));
        check("textul numeste autogolul", String.valueOf(e.get("text")).contains("autogol " + defender.shortName));
    }

    // ------------------------------------------------------------------ meciuri intregi
    @SuppressWarnings("unchecked")
    private static void wholeMatchesFollowTheRules() {
        int goals = 0;
        int assisted = 0;
        boolean oneOrOther = true;
        boolean sides = true;
        boolean noSaveGoal = true;
        boolean scoreMatches = true;
        for (long seed = 11; seed <= 18; seed++) {
            Sim s = sim(seed, 90.0);
            Map<String, Object> timeline = s.run();
            Map<Integer, Player> byUid = new HashMap<>();
            for (Player p : s.all) {
                byUid.put(p.uid, p);
            }
            int[] counted = new int[2];
            List<Map<String, Object>> events = (List<Map<String, Object>>) timeline.get("events");
            Map<String, Object> previous = null;
            for (Map<String, Object> e : events) {
                String type = (String) e.get("type");
                if ("GOAL".equals(type)) {
                    goals++;
                    int team = (Integer) e.get("team");
                    counted[team]++;
                    Player scorer = byUid.get(e.get("p"));
                    Player own = byUid.get(e.get("own"));
                    Player assist = byUid.get(e.get("assist"));
                    oneOrOther &= (scorer != null) != (own != null);
                    if (scorer != null) {
                        sides &= scorer.team.index == team;
                    }
                    if (own != null) {
                        sides &= own.team.index != team && assist == null;
                    }
                    if (assist != null) {
                        assisted++;
                        sides &= assist != scorer && assist.team.index == team;
                    }
                    if (own != null && previous != null && "SAVE".equals(previous.get("type"))
                            && ((Number) e.get("t")).doubleValue() - ((Number) previous.get("t")).doubleValue() < 0.35) {
                        noSaveGoal = false;
                    }
                }
                if (!"CARRY".equals(type) && !"PASS".equals(type)) {
                    previous = e;
                }
            }
            int[] score = s.score();
            scoreMatches &= score[0] == counted[0] && score[1] == counted[1];
        }
        check("in 8 meciuri se marcheaza", goals >= 8);
        check("fiecare gol are fie marcator, fie autorul autogolului", oneOrOther);
        check("marcatorul si pasatorul sunt din echipa care a marcat, autorul autogolului din cealalta", sides);
        check("parada respinsa nu se mai termina cu gol", noSaveGoal);
        check("scorul e numarul de goluri din cronologie", scoreMatches);
        check("cel putin jumatate din goluri au pasa decisiva (" + assisted + " din " + goals + ")",
                assisted * 2 >= goals);
    }
}
