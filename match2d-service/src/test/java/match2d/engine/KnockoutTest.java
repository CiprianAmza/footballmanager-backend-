package match2d.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Meciul eliminatoriu (campul {@code knockout}): fara el meciul e cel de pana acum; cu el primele 90
 * de minute sunt aceleasi, prelungirile (2 x 15 minute) se joaca doar la egalitate cu tot cu
 * aggregate-ul, iar departajarea (5 + cate una, alternativ, oprire cand e decisa, executanti diferiti
 * pana trag toti 11) da mereu un castigator, fara sa intre in scor. Fara biblioteci de test:
 *
 * <pre>   ./build.sh &amp;&amp; ./test.sh KnockoutTest</pre>
 */
public final class KnockoutTest {
    private static int checks;
    private static final List<String> FAILED = new ArrayList<>();
    private static final double MINUTES = 90.0;
    private static final int HZ = 5;
    private static final Set<String> NEW_TYPES = Set.of("EXTRA_TIME", "EXTRA_HALF_TIME", "SHOOTOUT", "SHOOTOUT_KICK");

    private KnockoutTest() {
    }

    public static void main(String[] args) {
        parseKnockoutReadsTheRequestField();
        takerOrderFollowsTheRules();
        List<Map<String, Object>> shootouts = new ArrayList<>();
        int extra = 0;
        for (long seed = 1; seed <= 14 && (shootouts.size() < 3 || extra < 4); seed++) {
            Map<String, Object> base = sim(seed, null).run();
            withoutKnockoutNothingChanges(seed, base);
            int[] s90 = score(base);
            // aggregate care face egalitate la general: urmeaza prelungiri
            int[] tie = {Math.max(0, s90[1] - s90[0]), Math.max(0, s90[0] - s90[1])};
            Map<String, Object> ko = sim(seed, tie).run();
            first90AreIdentical(seed, base, ko);
            extraTimeIsPlayed(seed, s90, ko);
            coherent(seed, tie, ko);
            extra++;
            if (ko(ko).get("penalties") != null) {
                shootoutRules(seed, ko);
                shootouts.add(ko);
            }
            if (seed <= 3) {
                // fara egalitate la general: nici prelungiri, nici departajare
                int[] noTie = s90[0] == s90[1] ? new int[] {1, 0} : new int[] {0, 0};
                Map<String, Object> plain = sim(seed, noTie).run();
                noExtraTimeWithoutATie(seed, s90, noTie, base, plain);
                coherent(seed, noTie, plain);
            }
        }
        check("s-au gasit meciuri cu departajare (" + shootouts.size() + ")", shootouts.size() >= 2);
        sameRequestSameResult();
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

    private static Sim sim(long seed, int[] knockout) {
        Sim s = new Sim(GoalRulesTest.team("Rosii", 12), GoalRulesTest.team("Albii", 12), seed, MINUTES, HZ, true);
        if (knockout != null) {
            s.knockout(knockout);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(Map<String, Object> tl) {
        return (List<Map<String, Object>>) tl.get("events");
    }

    @SuppressWarnings("unchecked")
    private static List<int[]> frames(Map<String, Object> tl) {
        return (List<int[]>) tl.get("frames");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> halves(Map<String, Object> tl) {
        return (List<Map<String, Object>>) tl.get("halves");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ko(Map<String, Object> tl) {
        return (Map<String, Object>) tl.get("knockout");
    }

    @SuppressWarnings("unchecked")
    private static int[] score(Map<String, Object> tl) {
        List<Integer> sc = (List<Integer>) ((Map<String, Object>) tl.get("summary")).get("score");
        return new int[] {sc.get(0), sc.get(1)};
    }

    @SuppressWarnings("unchecked")
    private static int[] pair(Object v) {
        List<Integer> l = (List<Integer>) v;
        return new int[] {l.get(0), l.get(1)};
    }

    private static double t(Map<String, Object> e) {
        return ((Number) e.get("t")).doubleValue();
    }

    private static int indexOf(List<Map<String, Object>> evs, String type) {
        for (int i = 0; i < evs.size(); i++) {
            if (type.equals(evs.get(i).get("type"))) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ cererea
    private static void parseKnockoutReadsTheRequestField() {
        check("knockout lipsa -> null", Sim.parseKnockout(null) == null);
        check("knockout fara aggregate -> [0, 0]", Arrays.equals(Sim.parseKnockout(Map.of()), new int[] {0, 0}));
        check("knockout cu aggregate [2, 1]", Arrays.equals(Sim.parseKnockout(Map.of("aggregate", List.of(2L, 1L))),
                new int[] {2, 1}));
        for (Object bad : new Object[] {"da", Map.of("aggregate", List.of(1L)), Map.of("aggregate", List.of(-1L, 0L)),
            Map.of("aggregate", List.of(1.5, 0L)), Map.of("aggregate", "1-0")}) {
            boolean thrown = false;
            try {
                Sim.parseKnockout(bad);
            } catch (IllegalArgumentException e) {
                thrown = true;
            }
            check("knockout gresit e refuzat: " + bad, thrown);
        }
    }

    // ------------------------------------------------------------------ executantii
    private static void takerOrderFollowsTheRules() {
        Map<String, Object> home = GoalRulesTest.team("Rosii", 12);
        Map<String, Object> away = GoalRulesTest.team("Albii", 12);
        int[] pens = {20, 3, 9, 15, 9, 4, 17, 11, 6, 13, 9};     // portarul (0) are cel mai mare penaltyTaking
        int[] comp = {10, 10, 5, 10, 14, 10, 10, 10, 10, 10, 8};
        for (Map<String, Object> t : List.of(home, away)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ps = (List<Map<String, Object>>) t.get("players");
            for (int i = 0; i < ps.size(); i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) ps.get(i).get("ratings");
                r.put("penaltyTaking", pens[i]);
                r.put("composure", comp[i]);
                ps.get(i).put("id", 500L + i);
            }
        }
        Map<String, Object> tactic = new LinkedHashMap<>();
        tactic.put("penaltyTakerId", 505L);         // jucatorul 5, slab la penalty-uri, ales din tactica
        away.put("tactic", tactic);
        Sim s = new Sim(home, away, 1, MINUTES, HZ, false);
        for (Team team : s.teams) {
            List<Player> ord = Shootout.order(team);
            check("ordinea are toti 11 o singura data (" + team.name + ")",
                    ord.size() == 11 && new HashSet<>(ord).size() == 11);
            check("primul e executantul de penalty (" + team.name + ")", ord.get(0) == team.penTaker);
            check("portarul e ultimul (" + team.name + ")", ord.get(10).isGk);
            boolean sorted = true;
            for (int i = 2; i < 10; i++) {
                Player a = ord.get(i - 1);
                Player b = ord.get(i);
                if (a.r[A.penaltyTaking] < b.r[A.penaltyTaking] || (a.r[A.penaltyTaking] == b.r[A.penaltyTaking]
                        && a.r[A.composure] < b.r[A.composure])) {
                    sorted = false;
                }
            }
            check("dupa primul: penaltyTaking descrescator, la egalitate composure (" + team.name + ")", sorted);
        }
        check("fara tactica primul e cel mai bun la penaltyTaking dintre jucatorii de camp",
                Shootout.order(s.teams[0]).get(0).r[A.penaltyTaking] == 17);
        check("executantul din tactica trage primul", Shootout.order(s.teams[1]).get(0).r[A.penaltyTaking] == 4);
        List<Player> o0 = Shootout.order(s.teams[0]);
        check("apoi 15, 13, 11 (penaltyTaking descrescator)", o0.get(1).r[A.penaltyTaking] == 15
                && o0.get(2).r[A.penaltyTaking] == 13 && o0.get(3).r[A.penaltyTaking] == 11);
        check("intre cei trei cu penaltyTaking 9, composure mai mare primul",
                o0.get(4).r[A.composure] == 14 && o0.get(5).r[A.composure] == 8 && o0.get(6).r[A.composure] == 5);
        check("portarul e ultimul desi are cel mai mare penaltyTaking", o0.get(10).r[A.penaltyTaking] == 20);
    }

    // ------------------------------------------------------------------ fara knockout
    private static void withoutKnockoutNothingChanges(long seed, Map<String, Object> base) {
        String tag = " (samanta " + seed + ")";
        check("fara knockout nu exista cheia knockout" + tag, !base.containsKey("knockout"));
        check("fara knockout doua reprize, fara cheia period" + tag, halves(base).size() == 2
                && !halves(base).get(0).containsKey("period") && !halves(base).get(1).containsKey("period"));
        boolean none = true;
        for (Map<String, Object> e : events(base)) {
            if (NEW_TYPES.contains(e.get("type"))) {
                none = false;
            }
        }
        check("fara knockout niciun eveniment nou" + tag, none);
        List<Map<String, Object>> evs = events(base);
        check("fara knockout ultimul eveniment e FULL_TIME" + tag,
                "FULL_TIME".equals(evs.get(evs.size() - 1).get("type")));
        double end = (frames(base).size() - 1) / (double) HZ;
        check("fara knockout meciul are 90 de minute" + tag, end >= 5400.0 && end < 5460.0);
    }

    // ------------------------------------------------------------------ primele 90 de minute
    private static void first90AreIdentical(long seed, Map<String, Object> base, Map<String, Object> ko) {
        String tag = " (samanta " + seed + ")";
        List<Map<String, Object>> be = events(base);
        List<Map<String, Object>> ke = events(ko);
        int cut = indexOf(ke, "EXTRA_TIME");
        check("cu egalitate la general exista EXTRA_TIME" + tag, cut > 0);
        if (cut <= 0) {
            return;
        }
        // fara knockout, dupa ultimul eveniment comun vine doar FULL_TIME
        check("aceleasi evenimente pana la minutul 90" + tag, be.size() == cut + 1
                && be.subList(0, cut).equals(ke.subList(0, cut)) && "FULL_TIME".equals(be.get(cut).get("type")));
        int f = ((Number) halves(ko).get(2).get("frame")).intValue();
        List<int[]> bf = frames(base);
        List<int[]> kf = frames(ko);
        boolean same = bf.size() >= f && kf.size() > f;
        for (int i = 0; same && i < f; i++) {
            same = Arrays.equals(bf.get(i), kf.get(i));
        }
        check("aceleasi cadre pana la minutul 90" + tag, same);
        check("aceiasi purtatori si roluri pana la minutul 90" + tag,
                ((List<?>) base.get("holders")).subList(0, f).equals(((List<?>) ko.get("holders")).subList(0, f))
                && ((List<?>) base.get("roles")).subList(0, f).equals(((List<?>) ko.get("roles")).subList(0, f)));
        check("primele doua reprize la fel" + tag, halves(base).equals(halves(ko).subList(0, 2)));
        check("score90 e scorul meciului fara knockout" + tag,
                Arrays.equals(pair(ko(ko).get("score90")), score(base)));
    }

    // ------------------------------------------------------------------ fara prelungiri
    private static void noExtraTimeWithoutATie(long seed, int[] s90, int[] agg, Map<String, Object> base,
                                               Map<String, Object> tl) {
        String tag = " (samanta " + seed + ", aggregate " + Arrays.toString(agg) + ")";
        Map<String, Object> k = ko(tl);
        check("fara egalitate: extraTime false" + tag, Boolean.FALSE.equals(k.get("extraTime")));
        check("fara egalitate: penalties si shootout null" + tag, k.get("penalties") == null && k.get("shootout") == null);
        check("fara egalitate: extraTimeGoals [0, 0]" + tag, Arrays.equals(pair(k.get("extraTimeGoals")), new int[] {0, 0}));
        check("fara egalitate: decidedBy regular" + tag, "regular".equals(k.get("decidedBy")));
        check("fara egalitate: score = score90 = scorul de la 90" + tag, Arrays.equals(score(tl), s90)
                && Arrays.equals(pair(k.get("score90")), s90));
        check("fara egalitate: doua reprize" + tag, halves(tl).size() == 2);
        check("fara egalitate: aggregate intors neschimbat" + tag, Arrays.equals(pair(k.get("aggregate")), agg));
        // meciul e chiar cel fara knockout: aceleasi evenimente si cadre, acelasi sumar
        check("fara egalitate: acelasi meci ca fara knockout" + tag, events(tl).equals(events(base))
                && tl.get("summary").equals(base.get("summary")) && frames(tl).size() == frames(base).size()
                && tl.get("players").equals(base.get("players")) && tl.get("highlights").equals(base.get("highlights")));
        boolean same = true;
        for (int i = 0; same && i < frames(tl).size(); i++) {
            same = Arrays.equals(frames(tl).get(i), frames(base).get(i));
        }
        check("fara egalitate: aceleasi cadre" + tag, same);
    }

    // ------------------------------------------------------------------ prelungirile
    private static void extraTimeIsPlayed(long seed, int[] s90, Map<String, Object> tl) {
        String tag = " (samanta " + seed + ")";
        Map<String, Object> k = ko(tl);
        List<Map<String, Object>> evs = events(tl);
        check("egalitate la general: extraTime true" + tag, Boolean.TRUE.equals(k.get("extraTime")));
        int et = indexOf(evs, "EXTRA_TIME");
        int ht = indexOf(evs, "EXTRA_HALF_TIME");
        check("EXTRA_TIME inainte de EXTRA_HALF_TIME" + tag, et >= 0 && ht > et);
        if (et < 0 || ht < 0) {
            return;
        }
        double t0 = t(evs.get(et));
        double t1 = t(evs.get(ht));
        int endIdx = k.get("penalties") != null ? indexOf(evs, "SHOOTOUT") : evs.size() - 1;
        double t2 = t(evs.get(endIdx));
        check("prelungirile incep la minutul 90" + tag, t0 >= 5400.0 && t0 < 5430.0);
        check("prima repriza de prelungiri are 15 minute" + tag, t1 >= 6300.0 && t1 < 6330.0);
        check("prelungirile se termina la minutul 120 (30 de minute)" + tag, t2 >= 7200.0 && t2 < 7230.0);
        List<Map<String, Object>> hv = halves(tl);
        check("cronologia are perioadele extra1 si extra2" + tag, hv.size() >= 4
                && "extra1".equals(hv.get(2).get("period")) && "extra2".equals(hv.get(3).get("period")));
        check("schimbarea terenului la fiecare repriza de prelungiri" + tag, hv.size() >= 4
                && !hv.get(2).get("dirs").equals(hv.get(1).get("dirs")) && hv.get(3).get("dirs").equals(hv.get(1).get("dirs")));
        int[] etGoals = new int[2];
        boolean late = true;
        for (int i = et + 1; i < evs.size(); i++) {
            Map<String, Object> e = evs.get(i);
            if ("GOAL".equals(e.get("type"))) {
                etGoals[(Integer) e.get("team")]++;
                late &= t(e) > 5400.0;
            }
        }
        check("golurile din prelungiri au t peste 5400" + tag, late);
        check("extraTimeGoals = golurile de dupa EXTRA_TIME" + tag, Arrays.equals(pair(k.get("extraTimeGoals")), etGoals));
        int[] sc = score(tl);
        check("score = score90 + golurile din prelungiri" + tag,
                sc[0] == s90[0] + etGoals[0] && sc[1] == s90[1] + etGoals[1]);
        int goals = 0;
        for (Map<String, Object> e : evs) {
            if ("GOAL".equals(e.get("type"))) {
                goals++;
            }
        }
        check("scorul are exact golurile GOAL (fara departajare)" + tag, goals == sc[0] + sc[1]);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players = (List<Map<String, Object>>) tl.get("players");
        int statGoals = 0;
        for (Map<String, Object> p : players) {
            @SuppressWarnings("unchecked")
            Map<String, Object> st = (Map<String, Object>) p.get("stats");
            statGoals += (Integer) st.get("goals") + (Integer) st.get("own_goals");
        }
        check("statisticile jucatorilor au golurile cu tot cu prelungirile, fara departajare" + tag,
                statGoals == sc[0] + sc[1]);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hl = (List<Map<String, Object>>) tl.get("highlights");
        boolean all = true;
        for (int i = et + 1; i < evs.size(); i++) {
            Map<String, Object> e = evs.get(i);
            if ("GOAL".equals(e.get("type"))) {
                boolean in = false;
                for (Map<String, Object> h : hl) {
                    in |= (Double) h.get("from") <= t(e) && t(e) <= (Double) h.get("to");
                }
                all &= in;
            }
        }
        check("golurile din prelungiri sunt in highlights" + tag, all);
    }

    // ------------------------------------------------------------------ rezultatul
    private static void coherent(long seed, int[] agg, Map<String, Object> tl) {
        String tag = " (samanta " + seed + ", aggregate " + Arrays.toString(agg) + ")";
        Map<String, Object> k = ko(tl);
        int[] sc = score(tl);
        int g0 = sc[0] + agg[0];
        int g1 = sc[1] + agg[1];
        int winner = (Integer) k.get("winner");
        Object pens = k.get("penalties");
        String by = (String) k.get("decidedBy");
        if (g0 != g1) {
            check("castigatorul e cel cu mai multe goluri la general" + tag, winner == (g0 > g1 ? 0 : 1));
            check("decidedBy regular / extra_time" + tag, pens == null
                    && by.equals(Boolean.TRUE.equals(k.get("extraTime")) ? "extra_time" : "regular"));
        } else {
            int[] p = pair(pens);
            check("egal dupa prelungiri: departajarea are castigator" + tag, p[0] != p[1]
                    && winner == (p[0] > p[1] ? 0 : 1) && "penalties".equals(by));
            check("egal dupa prelungiri: s-au jucat prelungiri" + tag, Boolean.TRUE.equals(k.get("extraTime")));
        }
        List<Map<String, Object>> evs = events(tl);
        check("ultimul eveniment e FULL_TIME" + tag, "FULL_TIME".equals(evs.get(evs.size() - 1).get("type")));
    }

    // ------------------------------------------------------------------ departajarea
    @SuppressWarnings("unchecked")
    private static void shootoutRules(long seed, Map<String, Object> tl) {
        String tag = " (samanta " + seed + ")";
        Map<String, Object> k = ko(tl);
        List<Map<String, Object>> log = (List<Map<String, Object>>) k.get("shootout");
        List<Map<String, Object>> evs = events(tl);
        int start = indexOf(evs, "SHOOTOUT");
        check("departajarea are evenimentul SHOOTOUT" + tag, start > 0);
        int first = (Integer) evs.get(start).get("first");
        List<Map<String, Object>> kicks = new ArrayList<>();
        for (Map<String, Object> e : evs) {
            if ("SHOOTOUT_KICK".equals(e.get("type"))) {
                kicks.add(e);
            }
        }
        check("cate un SHOOTOUT_KICK pe lovitura din shootout" + tag, kicks.size() == log.size() && !log.isEmpty());
        boolean match = kicks.size() == log.size();
        for (int i = 0; match && i < kicks.size(); i++) {
            Map<String, Object> e = kicks.get(i);
            Map<String, Object> r = log.get(i);
            match = e.get("team").equals(r.get("team")) && e.get("p").equals(r.get("uid")) && e.get("scored").equals(r.get("scored"))
                    && ((Integer) e.get("kick")) == i + 1 && r.containsKey("playerId") && r.get("player") instanceof String;
        }
        check("evenimentele si lista shootout spun acelasi lucru" + tag, match);
        // alternanta, de la echipa trasa la sorti
        boolean alt = true;
        for (int i = 0; i < log.size(); i++) {
            alt &= (Integer) log.get(i).get("team") == (i % 2 == 0 ? first : 1 - first);
        }
        check("loviturile alterneaza, incepe echipa trasa la sorti" + tag, alt);
        // oprirea: nedecis inainte de ultima lovitura, decis dupa ea (regula scrisa aici din nou)
        int[] taken = new int[2];
        int[] scored = new int[2];
        boolean early = false;
        for (int i = 0; i < log.size(); i++) {
            int ti = (Integer) log.get(i).get("team");
            taken[ti]++;
            if ((Boolean) log.get(i).get("scored")) {
                scored[ti]++;
            }
            boolean done = taken[0] <= 5 && taken[1] <= 5
                    ? scored[0] + 5 - taken[0] < scored[1] || scored[1] + 5 - taken[1] < scored[0]
                    : taken[0] == taken[1] && scored[0] != scored[1];
            if (done && i < log.size() - 1) {
                early = true;
            }
            if (i == log.size() - 1) {
                check("departajarea e decisa la ultima lovitura" + tag, done);
            }
        }
        check("departajarea nu continua dupa ce e decisa" + tag, !early);
        check("penalties = loviturile transformate" + tag, Arrays.equals(pair(k.get("penalties")), scored));
        check("cel putin 3 lovituri de fiecare (sau 5 daca nu e decis mai devreme)" + tag, taken[0] >= 3 && taken[1] >= 3);
        // executanti diferiti pana trag toti 11, apoi ordinea se reia; primul e executantul de penalty
        Sim ref = sim(seed, null);
        for (int ti = 0; ti < 2; ti++) {
            List<Integer> uids = new ArrayList<>();
            for (Map<String, Object> r : log) {
                if ((Integer) r.get("team") == ti) {
                    uids.add((Integer) r.get("uid"));
                }
            }
            boolean distinct = new HashSet<>(uids.subList(0, Math.min(11, uids.size()))).size() == Math.min(11, uids.size());
            check("executanti diferiti pana trag toti 11 (echipa " + ti + ")" + tag, distinct);
            boolean cycle = true;
            for (int i = 11; i < uids.size(); i++) {
                cycle &= uids.get(i).equals(uids.get(i - 11));
            }
            check("dupa 11 lovituri ordinea se reia (echipa " + ti + ")" + tag, cycle);
            check("primul executant e executantul de penalty (echipa " + ti + ")" + tag,
                    uids.get(0) == ref.teams[ti].penTaker.uid);
            if (uids.size() >= 11) {
                check("portarul trage al 11-lea (echipa " + ti + ")" + tag, uids.get(10) == ref.teams[ti].gk.uid);
            }
        }
        List<Map<String, Object>> hv = halves(tl);
        check("cronologia are perioada penalties" + tag, "penalties".equals(hv.get(hv.size() - 1).get("period"))
                && ((Number) hv.get(hv.size() - 1).get("frame")).intValue() < frames(tl).size());
        List<Map<String, Object>> hl = (List<Map<String, Object>>) tl.get("highlights");
        boolean covered = false;
        double from = t(evs.get(start));
        double to = t(kicks.get(kicks.size() - 1));
        for (Map<String, Object> h : hl) {
            covered |= "Lovituri de departajare".equals(h.get("label")) && (Double) h.get("from") <= from
                    && (Double) h.get("to") >= to;
        }
        check("highlights are segmentul Lovituri de departajare peste toata departajarea" + tag, covered);
        boolean afterEnd = true;
        for (Map<String, Object> e : kicks) {
            afterEnd &= t(e) > 7200.0;
        }
        check("loviturile vin dupa minutul 120" + tag, afterEnd);
    }

    // ------------------------------------------------------------------ determinismul
    private static void sameRequestSameResult() {
        Map<String, Object> a = sim(5, new int[] {2, 2}).run();
        Map<String, Object> b = sim(5, new int[] {2, 2}).run();
        check("aceeasi cerere: acelasi rezultat knockout", ko(a).equals(ko(b)));
        check("aceeasi cerere: aceleasi evenimente si acelasi sumar", events(a).equals(events(b))
                && a.get("summary").equals(b.get("summary")));
        boolean same = frames(a).size() == frames(b).size();
        for (int i = 0; same && i < frames(a).size(); i++) {
            same = Arrays.equals(frames(a).get(i), frames(b).get(i));
        }
        check("aceeasi cerere: aceleasi cadre", same);
    }
}
