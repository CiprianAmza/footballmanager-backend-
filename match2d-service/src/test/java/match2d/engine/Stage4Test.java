package match2d.engine;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Teste pentru etapa 4 (aparare si atentie): positioning, marking, ballRecovery, teamwork,
 * concentration si leadership au fiecare un mecanism in joc. Fara biblioteci de test:
 *
 * <pre>   ./build.sh &amp;&amp; ./test.sh</pre>
 */
public final class Stage4Test {
    private static int checks;
    private static final List<String> FAILED = new ArrayList<>();

    private Stage4Test() {
    }

    public static void main(String[] args) {
        everyAttributeHasAWeightExceptTheOnesPlannedLater();
        captainIsThePlayerWithTheHighestLeadership();
        strongCaptainCalmsTeammatesNearHim();
        markerOfTheReceiverReactsFasterWithBetterMarking();
        ballRecoveryReactsFasterToALooseBall();
        lapseStopsThinkingAndSlowsTheReaction();
        concentrationAndTheEndOfTheHalfChangeHowOftenLapsesHappen();
        positioningClosesTheBallGoalLineNearOwnGoal();
        teamworkBringsSupportToAPressedCarrier();
        wholeMatchesCountLapsesAndLooseBalls();
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

    /** O echipa 4-4-2 la {@code level}, cu un atribut schimbat la un jucator. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> teamWith(String name, int level, int player, String attribute, int value) {
        Map<String, Object> t = GoalRulesTest.team(name, level);
        List<Map<String, Object>> players = (List<Map<String, Object>>) t.get("players");
        ((Map<String, Object>) players.get(player).get("ratings")).put(attribute, value);
        return t;
    }

    private static Sim sim() {
        return new Sim(GoalRulesTest.team("Rosii", 12), GoalRulesTest.team("Albii", 12), 1, 10.0, 10, false);
    }

    /** Ratingul 1..20 in forma folosita de motor (0.15..0.85). */
    private static double norm(int rating) {
        return 0.15 + 0.70 * (rating - 1) / 19.0;
    }

    // ------------------------------------------------------------------ tabelul de ponderi
    private static void everyAttributeHasAWeightExceptTheOnesPlannedLater() {
        // freeKick si penaltyTaking au intrat in joc la fazele fixe (fk_error_freeKick, pen_penaltyTaking)
        Set<String> later = Set.of("kicking", "throwing", "longThrows");
        List<String> missing = new ArrayList<>();
        List<String> early = new ArrayList<>();
        for (String attribute : A.NAMES) {
            boolean found = false;
            for (Field f : Wt.class.getFields()) {
                String n = f.getName();
                if (n.endsWith("_" + attribute) || n.contains("_" + attribute + "_")) {
                    found = true;
                    break;
                }
            }
            if (!found && !later.contains(attribute)) {
                missing.add(attribute);
            }
            if (found && later.contains(attribute)) {
                early.add(attribute);
            }
        }
        check("fiecare atribut citit de motor are o pondere in Wt (lipsesc: " + missing + ")", missing.isEmpty());
        check("cele 3 atribute ramase pentru mai tarziu nu au inca pondere (au: " + early + ")", early.isEmpty());
        check("teamwork, concentration si leadership au pondere dupa etapa 4",
                Wt.table().containsKey("help_teamwork") && Wt.table().containsKey("lapse_concentration")
                        && Wt.table().containsKey("captain_leadership"));
        // distanta fata de omul marcat nu mai depinde de marking: strans si lipit pierdea dueluri
        check("marking nu mai schimba distanta fata de om si nici cat se lasa tras de el",
                Wt.marking_marking_gap == 0.0 && Wt.marking_marking_gap_deep == 0.0 && Wt.marking_marking == 0.0);
    }

    // ------------------------------------------------------------------ leadership
    private static void captainIsThePlayerWithTheHighestLeadership() {
        Sim s = new Sim(teamWith("Rosii", 12, 6, "leadership", 18), GoalRulesTest.team("Albii", 12), 1, 10.0, 10, false);
        check("capitanul e cel cu cel mai mare leadership", s.teams[0].captain == s.teams[0].players[6]);
        check("la egalitate, capitanul e primul din lista", s.teams[1].captain == s.teams[1].players[0]);
    }

    private static void strongCaptainCalmsTeammatesNearHim() {
        Sim strong = new Sim(teamWith("Rosii", 12, 6, "leadership", 20), teamWith("Albii", 12, 6, "leadership", 13),
                1, 10.0, 10, false);
        Player cap = strong.teams[0].captain;
        Player mate = strong.teams[0].players[7];
        mate.x = cap.x + 5.0;
        mate.y = cap.y;
        double near = Decision.feltPressure(mate, 0.8);
        check("langa un capitan bun presiunea apasa mai putin", near < 0.8 - 0.1);
        mate.x = cap.x + 2.0 * Wt.captain_radius + 1.0;
        check("departe de capitan presiunea e cea reala", Decision.feltPressure(mate, 0.8) == 0.8);
        check("capitanul nu se ajuta singur", Decision.feltPressure(cap, 0.8) == 0.8);
        check("fara presiune nu e nimic de calmat", Decision.feltPressure(mate, 0.0) == 0.0);
        // echipa a doua: toti au 12, deci capitanul (13 la leadership) e aproape de medie
        Sim weak = new Sim(teamWith("Rosii", 12, 6, "leadership", 13), GoalRulesTest.team("Albii", 12), 1, 10.0, 10, false);
        for (Player p : weak.teams[0].players) {
            p.n[A.leadership] = norm(1);
        }
        Player weakCap = weak.teams[0].captain;
        Player other = weak.teams[0].players[7];
        other.x = weakCap.x + 5.0;
        other.y = weakCap.y;
        check("langa un capitan slab presiunea apasa mai tare", Decision.feltPressure(other, 0.5) > 0.5 + 0.05);
    }

    // ------------------------------------------------------------------ marking si ballRecovery
    private static void markerOfTheReceiverReactsFasterWithBetterMarking() {
        Sim s = sim();
        Player marker = s.teams[1].players[2];
        Player other = s.teams[1].players[3];
        s.ball.kind = Ball.PASS;
        s.pending = new Sim.Pending();
        s.pending.marker = marker;
        marker.n[A.marking] = norm(18);
        check("cine il tine pe primitor pleaca mai repede spre pasa", s.reactTo(marker) < marker.react - 0.05);
        marker.n[A.marking] = norm(3);
        check("cu marking slab pleaca mai tarziu", s.reactTo(marker) > marker.react + 0.05);
        check("ceilalti reactioneaza ca de obicei", s.reactTo(other) == other.react);
        marker.n[A.marking] = norm(20);
        double saved = Wt.set("mark_react.marking", 5.0);
        check("timpul de reactie nu coboara sub 0.05 s", s.reactTo(marker) == 0.05);
        Wt.set("mark_react.marking", saved);
    }

    private static void ballRecoveryReactsFasterToALooseBall() {
        Sim s = sim();
        Player quick = s.teams[0].players[6];
        Player slow = s.teams[1].players[6];
        quick.n[A.ballRecovery] = norm(18);
        slow.n[A.ballRecovery] = norm(3);
        BallPlay.looseBall(s, 52.0, 34.0, 0.3, 6.0, s.teams[0]);
        check("la mingea libera, ballRecovery mare = pleaca mai repede", s.reactTo(quick) < quick.react - 0.02);
        check("la mingea libera, ballRecovery mic = pleaca mai tarziu", s.reactTo(slow) > slow.react + 0.02);
        s.ball.kind = Ball.PASS;
        check("la o pasa, ballRecovery nu schimba reactia", s.reactTo(quick) == quick.react);
    }

    // ------------------------------------------------------------------ concentration
    private static void lapseStopsThinkingAndSlowsTheReaction() {
        Sim s = sim();
        Player p = s.teams[0].players[3];
        double base = Wt.set("lapse.base", 2.0);            // sansa peste 1: scaparea vine sigur
        s.t = 100.0;
        s.maybeLapse(p);
        Wt.set("lapse.base", base);
        check("scaparea tine lapse_time secunde", Math.abs(p.lapseUntil - (100.0 + Wt.lapse_time)) < 1e-9);
        check("cat tine scaparea nu-si mai corecteaza pozitia", p.thinkAt == p.lapseUntil);
        check("scaparea e numarata la echipa lui", s.teams[0].mLapses == 1 && s.teams[1].mLapses == 0);
        s.ball.kind = Ball.PASS;
        s.ball.kickT = 100.5;                               // pasa pleaca in timpul scaparii
        check("la o minge plecata in timpul scaparii reactioneaza mai tarziu",
                Math.abs(s.reactTo(p) - (p.react + Wt.lapse_react)) < 1e-9);
        s.ball.kickT = p.lapseUntil + 0.1;                  // pasa pleaca dupa ce si-a revenit
        check("dupa scapare reactioneaza normal", s.reactTo(p) == p.react);
    }

    private static void concentrationAndTheEndOfTheHalfChangeHowOftenLapsesHappen() {
        Sim s = sim();
        Player focused = s.teams[0].players[2];
        Player dreamy = s.teams[0].players[3];
        focused.n[A.concentration] = norm(19);
        dreamy.n[A.concentration] = norm(2);
        s.t = 60.0;
        int a = 0;
        int b = 0;
        for (int i = 0; i < 40000; i++) {
            focused.lapseUntil = -9.0;
            dreamy.lapseUntil = -9.0;
            s.maybeLapse(focused);
            s.maybeLapse(dreamy);
            a += focused.lapseUntil > 0.0 ? 1 : 0;
            b += dreamy.lapseUntil > 0.0 ? 1 : 0;
        }
        check("concentrare mare = mai putine scapari (" + a + " fata de " + b + ")", a > 0 && b > 2 * a);
        Player same = s.teams[1].players[2];
        int early = 0;
        int late = 0;
        for (int i = 0; i < 40000; i++) {
            same.lapseUntil = -9.0;
            s.t = 30.0;                                     // inceputul reprizei
            s.maybeLapse(same);
            early += same.lapseUntil > 0.0 ? 1 : 0;
            same.lapseUntil = -9.0;
            s.t = s.halfLen - 5.0;                          // finalul reprizei
            s.maybeLapse(same);
            late += same.lapseUntil > 0.0 ? 1 : 0;
        }
        check("spre finalul reprizei scaparile sunt mai dese (" + early + " fata de " + late + ")",
                late > early * 1.5);
    }

    // ------------------------------------------------------------------ positioning
    private static void positioningClosesTheBallGoalLineNearOwnGoal() {
        Sim s = sim();
        for (Player o : s.teams[1].players) {       // niciun adversar in zona: ramane doar acoperirea
            o.x = 95.0;
            o.y = 5.0;
        }
        Player def = s.teams[0].players[2];         // fundas central
        double fx = 10.0;
        double fy = 0.5 * (def.dbox[2] + def.dbox[3]);      // mijlocul zonei lui, ca sa aiba loc
        // mingea la 20 m de poarta, asezata asa incat linia minge - poarta sa treaca, la 10 m de
        // poarta, cu 10 m mai in lateral decat el
        double ballY = 2.0 * fy - 14.0;
        def.n[A.positioning] = norm(19);
        Positioning.markPoint(s, def, fx, fy, 20.0, ballY);
        double goodY = s.oy;
        double goodX = s.ox;
        def.n[A.positioning] = norm(4);
        Positioning.markPoint(s, def, fx, fy, 20.0, ballY);
        double poorY = s.oy;
        check("cu positioning bun se trage spre linia minge - poarta", goodY > fy + 3.0 && goodY < fy + 10.0);
        check("acoperirea il muta doar lateral", goodX == fx);
        check("cu positioning slab ramane pe locul lui", poorY == fy);
        def.n[A.positioning] = norm(19);
        Positioning.markPoint(s, def, fx, fy, 60.0, ballY);
        check("cu mingea departe de poarta nu se strange nimeni", s.oy == fy);
        Positioning.markPoint(s, def, 30.0, fy, 20.0, ballY);
        check("cine e in fata mingii nu acopera linia spre poarta", s.oy == fy);
        // cu un adversar in zona lui ramane cu omul: positioning nu-l mai muta
        Player man = s.teams[1].players[9];
        man.x = fx + 3.0;
        man.y = fy - 2.0;
        Positioning.markPoint(s, def, fx, fy, 20.0, ballY);
        double withMan = s.oy;
        def.n[A.positioning] = norm(4);
        Positioning.markPoint(s, def, fx, fy, 20.0, ballY);
        check("cine tine un om ramane cu el, oricat positioning ar avea", s.oy == withMan && withMan < fy);
    }

    // ------------------------------------------------------------------ teamwork
    private static void teamworkBringsSupportToAPressedCarrier() {
        Sim s = sim();
        Player helper = s.teams[0].players[6];
        Player loner = s.teams[0].players[7];
        helper.n[A.teamwork] = norm(19);
        loner.n[A.teamwork] = norm(2);
        // purtatorul e la (50, 34), presat; punctul lor de plecare e la 20 m in spatele mingii
        Positioning.helpPoint(helper, 30.0, 34.0, 50.0, 34.0, 1.0, s);
        double helperX = s.ox;
        Positioning.helpPoint(loner, 30.0, 34.0, 50.0, 34.0, 1.0, s);
        double lonerX = s.ox;
        check("cu teamwork mare vine mai aproape de purtatorul presat", helperX > lonerX + 2.0 && helperX <= 42.0);
        Positioning.helpPoint(helper, 30.0, 34.0, 50.0, 34.0, 0.0, s);
        check("daca purtatorul nu e presat, ramane pe locul lui", s.ox == 30.0 && s.oy == 34.0);
        Positioning.helpPoint(helper, 70.0, 34.0, 50.0, 34.0, 1.0, s);
        check("cine e cu mult in fata mingii isi pastreaza pozitia de atac", s.ox == 70.0);
        Positioning.helpPoint(helper, 44.0, 34.0, 50.0, 34.0, 1.0, s);
        check("cine e deja aproape nu se inghesuie peste purtator", s.ox == 44.0);
    }

    // ------------------------------------------------------------------ meciuri intregi
    @SuppressWarnings("unchecked")
    private static void wholeMatchesCountLapsesAndLooseBalls() {
        int focused = 0;
        int normal = 0;
        boolean keys = true;
        for (long seed = 21; seed <= 26; seed++) {
            Map<String, Object> home = GoalRulesTest.team("Atenti", 12);
            for (Map<String, Object> p : (List<Map<String, Object>>) home.get("players")) {
                ((Map<String, Object>) p.get("ratings")).put("concentration", 18);
            }
            Sim s = new Sim(home, GoalRulesTest.team("Obisnuiti", 12), seed, 90.0, 10, false);
            Map<String, Object> summary = (Map<String, Object>) s.run().get("summary");
            List<Map<String, Object>> teams = (List<Map<String, Object>>) summary.get("teams");
            keys &= teams.get(0).containsKey("lapses") && teams.get(0).containsKey("loose_won");
            focused += (Integer) teams.get(0).get("lapses");
            normal += (Integer) teams.get(1).get("lapses");
            keys &= (Integer) teams.get(0).get("loose_won") > 0;
        }
        check("rezumatul numara scaparile de atentie si mingile libere castigate", keys);
        check("in 6 meciuri, echipa cu concentrare 18 are mai putine scapari decat cea cu 12 ("
                + focused + " fata de " + normal + ")", focused > 0 && focused < normal * 0.85);
    }
}
