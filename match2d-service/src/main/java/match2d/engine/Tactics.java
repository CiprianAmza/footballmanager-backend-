package match2d.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tactica echipei -> parametri de comportament.
 *
 * <p>Fiecare optiune tactica din joc (campurile din {@code PersonalizedTactic}) schimba unul sau
 * doi parametri ai motorului: unde stau jucatorii, cand paseaza, cat risca. Niciuna nu da un bonus
 * direct la scor. Valoarea implicita a fiecarui camp lasa motorul exact cum e fara tactica.
 */
public final class Tactics {
    /** camp -> valoarea implicita. */
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    /** camp -> valoare -> (parametru -> numar). */
    public static final Map<String, Map<String, Map<String, Double>>> EFFECTS = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> ALIASES = new HashMap<>();
    private static final Map<String, String> FIELDS = new HashMap<>();
    private static final Map<String, Map<String, String>> VALUES = new HashMap<>();

    static {
        // tot blocul urca sau coboara (la recalibrarea nivelului de goluri efectul a fost
        // injumatatit: "Defensive" la ambele echipe taia un sfert din goluri)
        field("mentality", "Balanced");
        value("mentality", "Very Defensive", "block", was(-0.035, -0.07));
        value("mentality", "Defensive", "block", was(-0.02, -0.04));
        value("mentality", "Balanced", "block", 0.0);
        value("mentality", "Attacking", "block", was(0.02, 0.04));
        value("mentality", "Very Attacking", "block", was(0.035, 0.07));
        // cati ies la minge, de la ce distanta de zona lor, cat de des ataca mingea;
        // cine preseaza mai putin isi tine mai bine forma (marcajul trage mai tare)
        field("pressing", "Normal");
        value("pressing", "Very Easy", "press_extra", 0, "press_margin", 2.5, "press_eager", 0.65, "shape", 1.12);
        value("pressing", "Easy", "press_extra", 0, "press_margin", 3.5, "press_eager", 0.8, "shape", 1.06);
        value("pressing", "Normal", "press_extra", 0, "press_margin", 5.0, "press_eager", 1.0, "shape", 1.0);
        value("pressing", "Aggressive", "press_extra", 1, "press_margin", 8.0, "press_eager", 1.25, "shape", 0.96);
        value("pressing", "Very Aggressive", "press_extra", 1, "press_margin", 11.0, "press_eager", 1.5, "shape", 0.92);
        // cat de sus sta linia de fund (doar fundasii)
        field("defensiveLine", "Standard");
        value("defensiveLine", "Deep", "line_shift", -0.05);
        value("defensiveLine", "Standard", "line_shift", 0.0);
        value("defensiveLine", "High", "line_shift", 0.05);
        // cat de lat sta echipa: in atac si in aparare
        field("width", "Balanced");
        value("width", "Narrow", "width_att", 0.85, "width_def", 0.70);
        value("width", "Balanced", "width_att", 1.0, "width_def", 0.80);
        value("width", "Wide", "width_att", 1.12, "width_def", 0.90);
        // cat tine mingea pana decide; tempo mare = decizii rapide, dar executie mai imprecisa;
        // tempo_move = cat de repede se misca echipa fara minge cand ataca (costa energie);
        // si aici timpul si miscarea au fost injumatatite la recalibrare
        field("tempo", "Standard");
        value("tempo", "Much Lower", "tempo_time", was(1.25, 1.5), "tempo_error", 0.92, "tempo_move", was(0.95, 0.90));
        value("tempo", "Lower", "tempo_time", was(1.1, 1.2), "tempo_error", 0.96, "tempo_move", was(0.975, 0.95));
        value("tempo", "Standard", "tempo_time", 1.0, "tempo_error", 1.0, "tempo_move", 1.0);
        value("tempo", "Higher", "tempo_time", was(0.91, 0.83), "tempo_error", 1.05, "tempo_move", was(1.03, 1.06));
        value("tempo", "Much Higher", "tempo_time", was(0.84, 0.68), "tempo_error", 1.10, "tempo_move", was(1.06, 1.12));
        // ce fel de pasa prefera: valoarea variantei se inmulteste cu factorul ei
        field("passingType", "Normal");
        value("passingType", "Short", "pass_short", 1.12, "pass_long", 0.80, "pass_through", 0.95);
        value("passingType", "Normal", "pass_short", 1.0, "pass_long", 1.0, "pass_through", 1.0);
        value("passingType", "Long", "pass_short", 0.94, "pass_long", 1.20, "pass_through", 1.05);
        // cat risca: costul pierderii mingii si preferinta pentru pasa inainte
        field("inPossession", "Standard");
        value("inPossession", "Standard", "risk", 1.0, "forward", 1.0);
        value("inPossession", "Keep Ball", "risk", 1.35, "forward", 0.97);
        value("inPossession", "Free Ball Early", "risk", 0.9, "forward", 1.08);
        // cat de des aleg sa conduca mingea
        field("dribbling", "Standard");
        value("dribbling", "Less", "dribble", 0.9);
        value("dribbling", "Standard", "dribble", 1.0);
        value("dribbling", "More", "dribble", 1.1);
        // faulturi (faze fixe, etapa A): cat de des faulteaza si cat de dur. "Hard" castiga ceva
        // mai multe dueluri (tackle_edge in logitul duelului), dar ia mai multe cartonase.
        // Doar pe drumul nou: cu -Dmatch2d.legacy=true tactica ramane cea a motorului Python.
        if (!Boolean.getBoolean("match2d.legacy")) {
            field("foulFrequency", "Normal");
            value("foulFrequency", "Rarely", "foul_freq", 0.7);
            value("foulFrequency", "Normal", "foul_freq", 1.0);
            value("foulFrequency", "Often", "foul_freq", 1.4);
            field("foulHardness", "Medium");
            value("foulHardness", "Soft", "foul_hard", 0.6, "tackle_edge", -0.10);
            value("foulHardness", "Medium", "foul_hard", 1.0, "tackle_edge", 0.0);
            value("foulHardness", "Hard", "foul_hard", 1.6, "tackle_edge", 0.10);
        }
        // valori vechi pe care backend-ul le mai poate avea salvate
        ALIASES.put("pressing", Map.of("low", "Easy", "standard", "Normal", "high", "Aggressive"));
        for (String f : EFFECTS.keySet()) {
            FIELDS.put(norm(f), f);
            Map<String, String> vals = new HashMap<>();
            for (String v : EFFECTS.get(f).keySet()) {
                vals.put(norm(v), v);
            }
            VALUES.put(f, vals);
        }
    }

    private Tactics() {
    }

    /** Valoarea de acum; cu {@code -Dmatch2d.legacy=true}, cea veche, a motorului Python. */
    private static double was(double now, double legacy) {
        return Boolean.getBoolean("match2d.legacy") ? legacy : now;
    }

    private static void field(String name, String defaultValue) {
        DEFAULTS.put(name, defaultValue);
        EFFECTS.put(name, new LinkedHashMap<>());
    }

    private static void value(String field, String value, Object... pairs) {
        Map<String, Double> params = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        EFFECTS.get(field).put(value, params);
    }

    static String norm(Object value) {
        StringBuilder sb = new StringBuilder();
        for (char ch : String.valueOf(value).toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(ch)) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** Rezultatul citirii unei tactici: parametrii, valorile folosite si ce a fost ignorat. */
    public static final class Resolved {
        public final Map<String, Double> params = new HashMap<>();
        public final Map<String, String> chosen = new LinkedHashMap<>();
        public final List<String> unknown = new ArrayList<>();
    }

    /**
     * Din tactica primita (orice scriere a cheilor: passingType, passing_type) intoarce parametrii,
     * valorile folosite si lista de valori necunoscute, care au fost ignorate.
     */
    public static Resolved resolve(Map<String, Object> tactic) {
        Resolved out = new Resolved();
        out.chosen.putAll(DEFAULTS);
        if (tactic != null) {
            for (Map.Entry<String, Object> e : tactic.entrySet()) {
                String field = FIELDS.get(norm(e.getKey()));
                Object value = e.getValue();
                if (field == null || value == null || String.valueOf(value).trim().isEmpty()) {
                    continue;
                }
                String v = norm(value);
                String name = VALUES.get(field).get(v);
                if (name == null && ALIASES.containsKey(field)) {
                    name = ALIASES.get(field).get(v);
                }
                if (name == null) {
                    out.unknown.add(field + "=" + value);
                    continue;
                }
                out.chosen.put(field, name);
            }
        }
        for (Map.Entry<String, String> e : out.chosen.entrySet()) {
            out.params.putAll(EFFECTS.get(e.getKey()).get(e.getValue()));
        }
        return out;
    }

    /** Sarcina jucatorului: cat muta ancorele slotului lui (metri, pe lungime): {high, deep}. */
    public static double[] dutyShift(Object duty) {
        String d = norm(duty == null ? "" : duty);
        if (d.equals("attack")) {
            return new double[] {4.0, 3.0};
        }
        if (d.equals("defend")) {
            return new double[] {-6.0, 0.0};
        }
        return new double[] {0.0, 0.0};
    }
}
