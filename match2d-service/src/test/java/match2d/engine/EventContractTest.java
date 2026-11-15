package match2d.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contractul evenimentelor pe meciuri intregi (drumul nou, in proces, cu seminte fixe): acelasi
 * meci la aceeasi samanta, faultul urmat de repunere, doar galben si cel mult unul pe jucator,
 * valorile permise pentru kind / sub / how / after si cheile din sumar potrivite cu numarul de
 * evenimente. Fara biblioteci de test:
 *
 * <pre>   ./build.sh &amp;&amp; ./test.sh EventContractTest</pre>
 */
public final class EventContractTest {
    private static int checks;
    private static final List<String> FAILED = new ArrayList<>();
    private static final double MINUTES = 45.0;
    private static final long FIRST_SEED = 41;
    private static final long LAST_SEED = 48;

    private static final Set<String> TYPES = Set.of("PASS", "PASS_OK", "PASS_FAIL", "CARRY", "INTERCEPT", "TACKLE",
            "DRIBBLE", "HEADER", "SHOT", "SAVE", "BLOCK", "MISS", "GOAL", "GOAL_KICK", "CORNER", "THROW_IN", "OFFSIDE",
            "FOUL", "CARD", "FREE_KICK", "PENALTY", "HALF_TIME", "FULL_TIME");
    private static final Set<String> KINDS = Set.of("shot", "cross", "play");
    private static final Set<String> SUBS = Set.of("feet", "through", "cross", "corner", "free_kick");
    private static final Set<String> CROSS_SUBS = Set.of("cross", "corner", "free_kick");
    private static final Set<String> HOWS = Set.of("penalty", "free_kick", "header");
    private static final Set<String> AFTERS = Set.of("corner", "free_kick", "penalty");
    private static final String[] SUMMARY_KEYS = {"fouls", "yellow_cards", "free_kicks", "penalties", "penalty_goals",
        "fk_shots", "fk_goals", "crosses", "crosses_ok", "headers", "header_goals", "corner_goals"};

    private static final List<Map<String, Object>> TIMELINES = new ArrayList<>();

    private EventContractTest() {
    }

    public static void main(String[] args) {
        newPathIsActive();
        sameSeedGivesTheSameMatch();
        for (long seed = FIRST_SEED; seed <= LAST_SEED; seed++) {
            TIMELINES.add(match(seed).run());
        }
        onlyKnownEventTypes();
        everyFoulIsFollowedByAFreeKickOrPenalty();
        cardsAreYellowAndAtMostOnePerPlayer();
        valuesComeFromTheAllowedSets();
        setPieceEventsComeInOrder();
        headerEventsNameTheWinnerAndTheLoser();
        summaryKeysExistAndMatchTheEvents();
        goalsInTheSummaryAreTheGoalEvents();
        matchesHaveFouls();
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

    private static Map<String, Object> withTactic(Map<String, Object> team, String frequency, String hardness) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("foulFrequency", frequency);
        t.put("foulHardness", hardness);
        team.put("tactic", t);
        return team;
    }

    /** Gazdele faulteaza des si dur (ca sa fie faulturi si cartonase), oaspetii normal. */
    private static Sim match(long seed) {
        Map<String, Object> home = withTactic(GoalRulesTest.team("Rosii", 12), "Often", "Hard");
        Map<String, Object> away = GoalRulesTest.team("Albii", 12);
        if (seed % 2 == 0) {
            away = withTactic(away, "Rarely", "Soft");
        }
        return new Sim(home, away, seed, MINUTES, 10, false);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(Map<String, Object> timeline) {
        return (List<Map<String, Object>>) timeline.get("events");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> summaryTeams(Map<String, Object> timeline) {
        return (List<Map<String, Object>>) ((Map<String, Object>) timeline.get("summary")).get("teams");
    }

    private static long seedOf(Map<String, Object> timeline) {
        return ((Number) timeline.get("seed")).longValue();
    }

    private static String type(Map<String, Object> e) {
        return (String) e.get("type");
    }

    private static int team(Map<String, Object> e) {
        Object t = e.get("team");
        return t == null ? -1 : (Integer) t;
    }

    private static boolean flag(Map<String, Object> e, String key) {
        return Boolean.TRUE.equals(e.get(key));
    }

    /** Cate evenimente de tipul dat are echipa, care trec si de filtrul dat. */
    private static int count(List<Map<String, Object>> events, String type, int team,
                             java.util.function.Predicate<Map<String, Object>> filter) {
        int n = 0;
        for (Map<String, Object> e : events) {
            if (type.equals(type(e)) && team(e) == team && filter.test(e)) {
                n++;
            }
        }
        return n;
    }

    private static int count(List<Map<String, Object>> events, String type, int team) {
        return count(events, type, team, e -> true);
    }

    // ------------------------------------------------------------------ determinismul
    private static void newPathIsActive() {
        check("testele ruleaza pe drumul nou (fara -Dmatch2d.legacy)", !Sim.LEGACY);
    }

    private static void sameSeedGivesTheSameMatch() {
        Map<String, Object> a = match(FIRST_SEED).run();
        Map<String, Object> b = match(FIRST_SEED).run();
        check("aceeasi samanta: aceleasi evenimente (" + events(a).size() + " si " + events(b).size() + ")",
                events(a).equals(events(b)));
        check("aceeasi samanta: acelasi sumar", a.get("summary").equals(b.get("summary")));
        Map<String, Object> c = match(FIRST_SEED + 100).run();
        check("alta samanta: alt meci", !events(a).equals(events(c)));
    }

    // ------------------------------------------------------------------ structura
    private static void onlyKnownEventTypes() {
        Set<String> unknown = new HashSet<>();
        for (Map<String, Object> tl : TIMELINES) {
            for (Map<String, Object> e : events(tl)) {
                if (!TYPES.contains(type(e))) {
                    unknown.add(type(e));
                }
            }
        }
        check("doar tipuri de evenimente cunoscute, fara eliminari (necunoscute: " + unknown + ")", unknown.isEmpty());
    }

    private static void everyFoulIsFollowedByAFreeKickOrPenalty() {
        boolean ok = true;
        boolean fields = true;
        String where = "";
        for (Map<String, Object> tl : TIMELINES) {
            List<Map<String, Object>> ev = events(tl);
            for (int i = 0; i < ev.size(); i++) {
                Map<String, Object> e = ev.get(i);
                if (!"FOUL".equals(type(e))) {
                    continue;
                }
                int foulTeam = team(e);
                fields &= (foulTeam == 0 || foulTeam == 1) && e.get("p") instanceof Integer && e.get("q") instanceof Integer
                        && (Integer) e.get("p") / 11 == foulTeam && (Integer) e.get("q") / 11 == 1 - foulTeam
                        && e.get("behind") instanceof Boolean
                        && (!e.containsKey("aerial") || Boolean.TRUE.equals(e.get("aerial")));
                int j = i + 1;
                if (j < ev.size() && "CARD".equals(type(ev.get(j)))) {
                    j++;
                }
                boolean next = j < ev.size() && ("FREE_KICK".equals(type(ev.get(j))) || "PENALTY".equals(type(ev.get(j))))
                        && team(ev.get(j)) == 1 - foulTeam;
                if (!next) {
                    ok = false;
                    where = "samanta " + seedOf(tl) + ", evenimentul " + i;
                }
            }
        }
        check("fiecare FOUL e urmat (dupa eventualul CARD) de FREE_KICK sau PENALTY pentru cealalta echipa " + where, ok);
        check("FOUL: team = cine faulteaza, p din echipa lui, q din cealalta, behind boolean, aerial doar true", fields);
    }

    private static void cardsAreYellowAndAtMostOnePerPlayer() {
        boolean yellow = true;
        boolean once = true;
        boolean afterFoul = true;
        int cards = 0;
        for (Map<String, Object> tl : TIMELINES) {
            List<Map<String, Object>> ev = events(tl);
            Set<Object> booked = new HashSet<>();
            for (int i = 0; i < ev.size(); i++) {
                Map<String, Object> e = ev.get(i);
                if (!"CARD".equals(type(e))) {
                    continue;
                }
                cards++;
                yellow &= "yellow".equals(e.get("card"));
                once &= booked.add(e.get("p"));
                Map<String, Object> prev = i > 0 ? ev.get(i - 1) : null;
                afterFoul &= prev != null && "FOUL".equals(type(prev)) && e.get("p").equals(prev.get("p"))
                        && team(e) == team(prev);
            }
        }
        check("CARD e doar galben (" + cards + " cartonase)", yellow);
        check("cel mult un galben pe jucator intr-un meci", once);
        check("cartonasul vine imediat dupa faultul aceluiasi jucator", afterFoul);
    }

    private static void valuesComeFromTheAllowedSets() {
        Set<Object> kinds = new HashSet<>();
        Set<Object> subs = new HashSet<>();
        Set<Object> hows = new HashSet<>();
        Set<Object> afters = new HashSet<>();
        boolean directHasNoAfter = true;
        for (Map<String, Object> tl : TIMELINES) {
            for (Map<String, Object> e : events(tl)) {
                String t = type(e);
                if ("FREE_KICK".equals(t)) {
                    kinds.add(e.get("kind"));
                } else if ("PASS".equals(t)) {
                    subs.add(e.get("sub"));
                } else if ("GOAL".equals(t)) {
                    if (e.containsKey("how")) {
                        hows.add(e.get("how"));
                    }
                    if (e.containsKey("after")) {
                        afters.add(e.get("after"));
                    }
                    if (("penalty".equals(e.get("how")) || "free_kick".equals(e.get("how"))) && e.containsKey("after")) {
                        directHasNoAfter = false;
                    }
                }
            }
        }
        check("FREE_KICK kind din " + KINDS + " (are " + kinds + ")", KINDS.containsAll(kinds) && !kinds.isEmpty());
        check("PASS sub din " + SUBS + " (are " + subs + ")", SUBS.containsAll(subs) && !subs.isEmpty());
        check("GOAL how din " + HOWS + " (are " + hows + ")", HOWS.containsAll(hows));
        check("GOAL after din " + AFTERS + " (are " + afters + ")", AFTERS.containsAll(afters));
        check("golul direct din penalty sau lovitura libera are doar how, nu si after", directHasNoAfter);
    }

    private static void setPieceEventsComeInOrder() {
        boolean penalty = true;
        boolean fkShot = true;
        boolean corner = true;
        boolean fkCross = true;
        boolean penGoal = true;
        String where = "";
        for (Map<String, Object> tl : TIMELINES) {
            List<Map<String, Object>> ev = events(tl);
            for (int i = 0; i < ev.size(); i++) {
                Map<String, Object> e = ev.get(i);
                String t = type(e);
                Map<String, Object> prev = i > 0 ? ev.get(i - 1) : null;
                if ("PENALTY".equals(t)) {
                    Map<String, Object> next = i + 1 < ev.size() ? ev.get(i + 1) : null;
                    if (!(next != null && "SHOT".equals(type(next)) && flag(next, "penalty") && team(next) == team(e))) {
                        penalty = false;
                        where = "samanta " + seedOf(tl) + ", evenimentul " + i;
                    }
                }
                if ("SHOT".equals(t) && flag(e, "freeKick")) {
                    fkShot &= prev != null && "FREE_KICK".equals(type(prev)) && "shot".equals(prev.get("kind"))
                            && team(prev) == team(e);
                }
                if ("PASS".equals(t) && "corner".equals(e.get("sub"))) {
                    corner &= prev != null && "CORNER".equals(type(prev)) && team(prev) == team(e);
                }
                if ("PASS".equals(t) && "free_kick".equals(e.get("sub"))) {
                    fkCross &= prev != null && "FREE_KICK".equals(type(prev)) && "cross".equals(prev.get("kind"))
                            && team(prev) == team(e);
                }
                if ("GOAL".equals(t) && "penalty".equals(e.get("how"))) {
                    boolean found = false;
                    for (int k = i - 1; k >= 0; k--) {
                        if ("SHOT".equals(type(ev.get(k)))) {
                            found = flag(ev.get(k), "penalty") && team(ev.get(k)) == team(e)
                                    && ev.get(k).get("p").equals(e.get("p"));
                            break;
                        }
                    }
                    penGoal &= found;
                }
            }
        }
        check("dupa PENALTY urmeaza direct SHOT cu penalty = true al aceleiasi echipe " + where, penalty);
        check("SHOT cu freeKick = true vine direct dupa FREE_KICK kind = shot", fkShot);
        check("PASS sub = corner vine direct dupa CORNER", corner);
        check("PASS sub = free_kick vine direct dupa FREE_KICK kind = cross", fkCross);
        check("golul din penalty e al executantului, dupa sutul din penalty", penGoal);
    }

    private static void headerEventsNameTheWinnerAndTheLoser() {
        boolean ok = true;
        int n = 0;
        for (Map<String, Object> tl : TIMELINES) {
            for (Map<String, Object> e : events(tl)) {
                if (!"HEADER".equals(type(e))) {
                    continue;
                }
                n++;
                int t = team(e);
                Object p = e.get("p");
                Object q = e.get("q");
                ok &= p instanceof Integer && (Integer) p / 11 == t
                        && (q == null || (q instanceof Integer && (Integer) q / 11 == 1 - t));
            }
        }
        check("HEADER: castigatorul (p) e din echipa evenimentului, adversarul (q) din cealalta (" + n + " dueluri)",
                ok && n > 0);
    }

    // ------------------------------------------------------------------ sumarul
    private static void summaryKeysExistAndMatchTheEvents() {
        boolean keys = true;
        List<String> wrong = new ArrayList<>();
        for (Map<String, Object> tl : TIMELINES) {
            List<Map<String, Object>> ev = events(tl);
            List<Map<String, Object>> teams = summaryTeams(tl);
            for (int t = 0; t < 2; t++) {
                Map<String, Object> s = teams.get(t);
                for (String k : SUMMARY_KEYS) {
                    keys &= s.get(k) instanceof Integer && (Integer) s.get(k) >= 0;
                }
                if (!keys) {
                    continue;
                }
                int[][] pairs = {
                    {(Integer) s.get("fouls"), count(ev, "FOUL", t)},
                    {(Integer) s.get("yellow_cards"), count(ev, "CARD", t)},
                    {(Integer) s.get("free_kicks"), count(ev, "FREE_KICK", t)},
                    {(Integer) s.get("penalties"), count(ev, "PENALTY", t)},
                    {(Integer) s.get("penalty_goals"), count(ev, "GOAL", t, e -> "penalty".equals(e.get("how")))},
                    {(Integer) s.get("fk_shots"), count(ev, "SHOT", t, e -> flag(e, "freeKick"))},
                    {(Integer) s.get("fk_goals"), count(ev, "GOAL", t, e -> "free_kick".equals(e.get("how")))},
                    {(Integer) s.get("crosses"), count(ev, "PASS", t, e -> CROSS_SUBS.contains(e.get("sub")))},
                    {(Integer) s.get("headers"), count(ev, "SHOT", t, e -> flag(e, "header"))},
                    {(Integer) s.get("header_goals"), count(ev, "GOAL", t, e -> "header".equals(e.get("how")))},
                    {(Integer) s.get("corner_goals"), count(ev, "GOAL", t, e -> "corner".equals(e.get("after")))},
                    {(Integer) s.get("corners"), count(ev, "CORNER", t)},
                    {(Integer) s.get("shots"), count(ev, "SHOT", t)},
                };
                String[] names = {"fouls", "yellow_cards", "free_kicks", "penalties", "penalty_goals", "fk_shots",
                    "fk_goals", "crosses", "headers", "header_goals", "corner_goals", "corners", "shots"};
                for (int i = 0; i < pairs.length; i++) {
                    if (pairs[i][0] != pairs[i][1]) {
                        wrong.add("samanta " + seedOf(tl) + " echipa " + t + " " + names[i] + ": " + pairs[i][0]
                                + " in sumar, " + pairs[i][1] + " evenimente");
                    }
                }
                int crossesOk = (Integer) s.get("crosses_ok");
                if (crossesOk > (Integer) s.get("crosses")) {
                    wrong.add("samanta " + seedOf(tl) + " echipa " + t + " crosses_ok > crosses");
                }
                if ((Integer) s.get("fk_shots") > count(ev, "FREE_KICK", t, e -> "shot".equals(e.get("kind")))) {
                    wrong.add("samanta " + seedOf(tl) + " echipa " + t + " fk_shots > lovituri libere directe");
                }
            }
        }
        check("sumarul are cheile fazelor fixe, numere intregi", keys);
        check("sumarul se potriveste cu evenimentele " + wrong, wrong.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static void goalsInTheSummaryAreTheGoalEvents() {
        boolean ok = true;
        int goals = 0;
        for (Map<String, Object> tl : TIMELINES) {
            List<Map<String, Object>> ev = events(tl);
            List<Object> score = (List<Object>) ((Map<String, Object>) tl.get("summary")).get("score");
            for (int t = 0; t < 2; t++) {
                int n = count(ev, "GOAL", t);
                goals += n;
                ok &= ((Number) score.get(t)).intValue() == n;
            }
        }
        check("golurile din sumar sunt evenimentele GOAL (" + goals + " goluri)", ok);
    }

    private static void matchesHaveFouls() {
        int fouls = 0;
        int freeKicks = 0;
        int penalties = 0;
        for (Map<String, Object> tl : TIMELINES) {
            for (Map<String, Object> e : events(tl)) {
                fouls += "FOUL".equals(type(e)) ? 1 : 0;
                freeKicks += "FREE_KICK".equals(type(e)) ? 1 : 0;
                penalties += "PENALTY".equals(type(e)) ? 1 : 0;
            }
        }
        check("in " + TIMELINES.size() + " meciuri sunt faulturi (" + fouls + "), lovituri libere (" + freeKicks
                + ") si penalty-uri (" + penalties + ")", fouls >= 8 && freeKicks + penalties >= fouls);
    }
}
