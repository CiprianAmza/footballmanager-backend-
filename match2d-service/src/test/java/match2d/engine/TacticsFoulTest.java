package match2d.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Teste pentru tactica pe drumul nou: mentalitatea si tempo-ul temperate (cam jumatate din efectul
 * motorului Python), plus campurile noi foulFrequency si foulHardness, cu valorile lipsa sau
 * necunoscute cazute pe Normal / Medium. Fara biblioteci de test:
 *
 * <pre>   ./build.sh &amp;&amp; ./test.sh TacticsFoulTest</pre>
 */
public final class TacticsFoulTest {
    private static int checks;
    private static final List<String> FAILED = new ArrayList<>();

    /** Valorile motorului Python (drumul vechi), fata de care drumul nou e temperat. */
    private static final String[] MENTALITY = {"Very Defensive", "Defensive", "Balanced", "Attacking", "Very Attacking"};
    private static final double[] LEGACY_BLOCK = {-0.07, -0.04, 0.0, 0.04, 0.07};
    private static final String[] TEMPO = {"Much Lower", "Lower", "Standard", "Higher", "Much Higher"};
    private static final double[] LEGACY_TEMPO_TIME = {1.5, 1.2, 1.0, 0.83, 0.68};
    private static final double[] LEGACY_TEMPO_MOVE = {0.90, 0.95, 1.0, 1.06, 1.12};

    private TacticsFoulTest() {
    }

    public static void main(String[] args) {
        newPathIsActive();
        mentalityIsHalvedAndKeepsItsOrder();
        tempoIsHalvedAndKeepsItsOrder();
        tempoErrorIsNotTempered();
        foulFrequencyMapping();
        foulHardnessMapping();
        missingOrUnknownFoulValuesFallBackToNormalAndMedium();
        foulFieldsAcceptAnySpelling();
        teamReadsTheFoulFieldsFromItsTactic();
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

    private static Tactics.Resolved resolve(Object... pairs) {
        Map<String, Object> t = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            t.put((String) pairs[i], pairs[i + 1]);
        }
        return Tactics.resolve(t);
    }

    private static double param(String key, Object... pairs) {
        return resolve(pairs).params.get(key);
    }

    /** Abaterea de acum fata de neutru e intre 40% si 60% din cea veche, cu acelasi semn. */
    private static boolean halved(double now, double legacy, double neutral) {
        double a = now - neutral;
        double b = legacy - neutral;
        if (b == 0.0) {
            return a == 0.0;
        }
        double ratio = a / b;
        return ratio >= 0.4 && ratio <= 0.6;
    }

    // ------------------------------------------------------------------ temperarea
    private static void newPathIsActive() {
        check("testele ruleaza pe drumul nou (fara -Dmatch2d.legacy)", !Sim.LEGACY);
    }

    private static void mentalityIsHalvedAndKeepsItsOrder() {
        double prev = Double.NEGATIVE_INFINITY;
        boolean order = true;
        for (int i = 0; i < MENTALITY.length; i++) {
            double block = param("block", "mentality", MENTALITY[i]);
            check("mentality " + MENTALITY[i] + ": block temperat la jumatate (" + block + " fata de "
                    + LEGACY_BLOCK[i] + ")", halved(block, LEGACY_BLOCK[i], 0.0));
            order &= block > prev;
            prev = block;
        }
        check("block creste de la Very Defensive la Very Attacking", order);
        check("Balanced nu muta blocul", param("block", "mentality", "Balanced") == 0.0);
        check("mentalitatea e simetrica", param("block", "mentality", "Very Attacking")
                == -param("block", "mentality", "Very Defensive")
                && param("block", "mentality", "Attacking") == -param("block", "mentality", "Defensive"));
    }

    private static void tempoIsHalvedAndKeepsItsOrder() {
        double prevTime = Double.POSITIVE_INFINITY;
        double prevMove = Double.NEGATIVE_INFINITY;
        boolean order = true;
        for (int i = 0; i < TEMPO.length; i++) {
            double time = param("tempo_time", "tempo", TEMPO[i]);
            double move = param("tempo_move", "tempo", TEMPO[i]);
            check("tempo " + TEMPO[i] + ": tempo_time temperat (" + time + " fata de " + LEGACY_TEMPO_TIME[i] + ")",
                    halved(time, LEGACY_TEMPO_TIME[i], 1.0));
            check("tempo " + TEMPO[i] + ": tempo_move temperat (" + move + " fata de " + LEGACY_TEMPO_MOVE[i] + ")",
                    halved(move, LEGACY_TEMPO_MOVE[i], 1.0));
            order &= time < prevTime && move > prevMove;
            prevTime = time;
            prevMove = move;
        }
        check("tempo mai mare = decizii mai rapide si miscare mai vie, in ordine", order);
        check("Standard e neutru", param("tempo_time", "tempo", "Standard") == 1.0
                && param("tempo_move", "tempo", "Standard") == 1.0);
    }

    private static void tempoErrorIsNotTempered() {
        double prev = 0.0;
        boolean order = true;
        for (String v : TEMPO) {
            double e = param("tempo_error", "tempo", v);
            order &= e > prev;
            prev = e;
        }
        check("tempo_error creste cu tempo-ul", order);
        check("tempo_error Standard e 1", param("tempo_error", "tempo", "Standard") == 1.0);
    }

    // ------------------------------------------------------------------ faulturile
    private static void foulFrequencyMapping() {
        check("foulFrequency e un camp de tactica pe drumul nou", Tactics.EFFECTS.containsKey("foulFrequency"));
        double rarely = param("foul_freq", "foulFrequency", "Rarely");
        double normal = param("foul_freq", "foulFrequency", "Normal");
        double often = param("foul_freq", "foulFrequency", "Often");
        check("Rarely -> foul_freq 0.7 (" + rarely + ")", rarely == 0.7);
        check("Normal -> foul_freq 1.0 (" + normal + ")", normal == 1.0);
        check("Often -> foul_freq 1.4 (" + often + ")", often == 1.4);
        check("foulFrequency nu atinge duritatea", resolve("foulFrequency", "Often").params.get("foul_hard") == 1.0
                && resolve("foulFrequency", "Often").params.get("tackle_edge") == 0.0);
    }

    private static void foulHardnessMapping() {
        check("foulHardness e un camp de tactica pe drumul nou", Tactics.EFFECTS.containsKey("foulHardness"));
        String[] v = {"Soft", "Medium", "Hard"};
        double[] hard = {0.6, 1.0, 1.6};
        double[] edge = {-0.10, 0.0, 0.10};
        for (int i = 0; i < v.length; i++) {
            Map<String, Double> p = resolve("foulHardness", v[i]).params;
            check(v[i] + " -> foul_hard " + hard[i] + " (" + p.get("foul_hard") + ")", p.get("foul_hard") == hard[i]);
            check(v[i] + " -> tackle_edge " + edge[i] + " (" + p.get("tackle_edge") + ")",
                    Math.abs(p.get("tackle_edge") - edge[i]) < 1e-12);
            check(v[i] + " nu atinge frecventa", p.get("foul_freq") == 1.0);
        }
    }

    private static void missingOrUnknownFoulValuesFallBackToNormalAndMedium() {
        Tactics.Resolved none = Tactics.resolve(null);
        check("fara tactica: Normal si Medium", "Normal".equals(none.chosen.get("foulFrequency"))
                && "Medium".equals(none.chosen.get("foulHardness")));
        check("fara tactica: foul_freq 1, foul_hard 1, tackle_edge 0", none.params.get("foul_freq") == 1.0
                && none.params.get("foul_hard") == 1.0 && none.params.get("tackle_edge") == 0.0);
        Tactics.Resolved empty = Tactics.resolve(new HashMap<>());
        check("tactica goala: Normal si Medium", "Normal".equals(empty.chosen.get("foulFrequency"))
                && "Medium".equals(empty.chosen.get("foulHardness")) && empty.unknown.isEmpty());
        Tactics.Resolved unknown = resolve("foulFrequency", "Sometimes", "foulHardness", "Brutal");
        check("valori necunoscute: Normal si Medium", "Normal".equals(unknown.chosen.get("foulFrequency"))
                && "Medium".equals(unknown.chosen.get("foulHardness")));
        check("valori necunoscute: parametrii neutri", unknown.params.get("foul_freq") == 1.0
                && unknown.params.get("foul_hard") == 1.0 && unknown.params.get("tackle_edge") == 0.0);
        check("valorile necunoscute sunt raportate (" + unknown.unknown + ")",
                unknown.unknown.contains("foulFrequency=Sometimes") && unknown.unknown.contains("foulHardness=Brutal"));
        Tactics.Resolved blank = resolve("foulFrequency", "  ", "foulHardness", null);
        check("valori goale sau null: Normal si Medium, fara raportare", "Normal".equals(blank.chosen.get("foulFrequency"))
                && "Medium".equals(blank.chosen.get("foulHardness")) && blank.unknown.isEmpty());
        Tactics.Resolved other = resolve("mentality", "Attacking");
        check("alt camp ales nu schimba faulturile", other.params.get("foul_freq") == 1.0
                && other.params.get("foul_hard") == 1.0);
    }

    private static void foulFieldsAcceptAnySpelling() {
        Tactics.Resolved r = resolve("foul_frequency", "often", "FOUL-HARDNESS", "hard");
        check("cheile si valorile in orice scriere", "Often".equals(r.chosen.get("foulFrequency"))
                && "Hard".equals(r.chosen.get("foulHardness")) && r.params.get("foul_freq") == 1.4
                && r.params.get("foul_hard") == 1.6);
    }

    private static void teamReadsTheFoulFieldsFromItsTactic() {
        Map<String, Object> home = GoalRulesTest.team("Rosii", 12);
        Map<String, Object> tactic = new LinkedHashMap<>();
        tactic.put("foulFrequency", "Rarely");
        tactic.put("foulHardness", "Soft");
        home.put("tactic", tactic);
        Map<String, Object> away = GoalRulesTest.team("Albii", 12);
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("foulFrequency", "Nope");
        away.put("tactic", bad);
        Sim s = new Sim(home, away, 1, 10.0, 10, false);
        Team a = s.teams[0];
        Team b = s.teams[1];
        check("echipa cu Rarely / Soft", a.foulFreq == 0.7 && a.foulHard == 0.6 && a.tackleEdge < 0.0);
        check("echipa cu valoare necunoscuta: Normal / Medium", b.foulFreq == 1.0 && b.foulHard == 1.0
                && b.tackleEdge == 0.0 && b.tacticUnknown.contains("foulFrequency=Nope"));
        check("tactica folosita apare in cronologie", "Rarely".equals(a.tactic.get("foulFrequency"))
                && "Soft".equals(a.tactic.get("foulHardness")));
    }
}
