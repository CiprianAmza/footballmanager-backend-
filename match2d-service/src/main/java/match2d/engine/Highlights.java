package match2d.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fazele importante ale unui meci (rezumatul), scoase din evenimente: lista {@code highlights} a
 * cronologiei, {@code [{from, to, label, kind}]} in secunde de joc, ordonate si unite. Post-procesare
 * pura (nu trage din generatorul de numere). Ceasul transmisiei (reluari, taieturi) ramane in Python.
 */
public final class Highlights {
    // ferestrele fazelor (secunde de joc fata de momentul evenimentului)
    public static final double GOAL_BEFORE = 10.0;
    public static final double GOAL_AFTER = 4.0;
    public static final double SAVE_BEFORE = 7.0;        // inainte de sutul aparat
    public static final double SAVE_AFTER = 2.5;         // dupa parada
    public static final double SHOT_LOOKBACK = 6.0;      // sutul de dinaintea paradei, daca e la cel mult atat
    public static final double PEN_BEFORE = 5.0;         // inainte de penalty
    public static final double PEN_AFTER = 4.0;          // dupa deznodamant
    public static final double FK_BEFORE = 2.0;          // lovitura libera direct la poarta
    public static final double FK_AFTER = 2.5;
    public static final double OUTCOME_WINDOW = 15.0;    // deznodamantul (GOAL/SAVE/MISS) vine in cel mult atat
    public static final double EDGE = 4.0;               // startul [0, 4] si finalul [durata - 4, durata]
    public static final double MERGE_GAP = 3.0;          // fazele mai apropiate de atat se unesc
    /** eticheta fazei unite e a celei mai importante. */
    public static final Map<String, Integer> PRIORITY = Map.of("shootout", 6, "goal", 5, "penalty", 4,
            "free_kick", 3, "save", 2, "edge", 1);

    private Highlights() {
    }

    private record Raw(double from, double to, String label, String kind) {
    }

    /** Durata meciului (secunde de joc): din numarul de cadre, altfel din ultimul eveniment. */
    static double duration(Sim s) {
        double hz = 1.0 / (s.recEvery * Sim.DT);
        if (s.frameNo > 0) {
            return (s.frameNo - 1) / hz;
        }
        double max = 0.0;
        for (Map<String, Object> e : s.events) {
            if (e.get("t") instanceof Number t) {
                max = Math.max(max, t.doubleValue());
            }
        }
        return max;
    }

    private static double outcome(List<Map<String, Object>> events, int k, double limit) {
        double t0 = ((Number) events.get(k).get("t")).doubleValue();
        for (int j = k + 1; j < events.size(); j++) {
            Map<String, Object> e = events.get(j);
            double t = ((Number) e.get("t")).doubleValue();
            if (t - t0 > limit) {
                break;
            }
            Object typ = e.get("type");
            if ("GOAL".equals(typ) || "SAVE".equals(typ) || "MISS".equals(typ)) {
                return t;
            }
        }
        return t0;
    }

    private static String name(Map<Integer, String> names, Integer uid) {
        String n = uid != null ? names.get(uid) : null;
        return n == null || n.isEmpty() ? "?" : n;
    }

    /** Fazele importante, ordonate si unite: [{from, to, label, kind}] (secunde de joc). */
    static List<Map<String, Object>> segments(Sim s) {
        double dur = duration(s);
        Map<Integer, String> names = new HashMap<>();
        for (Team team : s.teams) {
            for (Player p : team.players) {
                names.put(p.uid, p.name);
            }
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> e : s.events) {
            if (e.get("t") instanceof Number) {
                events.add(e);
            }
        }
        List<Raw> raw = new ArrayList<>();
        raw.add(new Raw(0.0, EDGE, "Start", "edge"));
        raw.add(new Raw(dur - EDGE, dur, "Final", "edge"));
        Double lastShot = null;
        for (int k = 0; k < events.size(); k++) {
            Map<String, Object> e = events.get(k);
            Object typ = e.get("type");
            double t = ((Number) e.get("t")).doubleValue();
            if ("SHOT".equals(typ)) {
                lastShot = t;
            } else if ("GOAL".equals(typ)) {
                Integer p = MatchStats.num(e.get("p"));
                Integer own = MatchStats.num(e.get("own"));
                String label = e.get("p") == null || (e.get("own") != null && own != null && own.equals(p))
                        ? "Autogol: " + name(names, own) : "Gol: " + name(names, p);
                raw.add(new Raw(t - GOAL_BEFORE, t + GOAL_AFTER, label, "goal"));
            } else if ("SAVE".equals(typ)) {
                double shot = lastShot != null && t - lastShot <= SHOT_LOOKBACK ? lastShot : t;
                raw.add(new Raw(shot - SAVE_BEFORE, t + SAVE_AFTER, "Șut apărat", "save"));
            } else if ("PENALTY".equals(typ)) {
                raw.add(new Raw(t - PEN_BEFORE, outcome(events, k, OUTCOME_WINDOW) + PEN_AFTER, "Penalty",
                        "penalty"));
            } else if ("SHOOTOUT".equals(typ)) {
                // toata departajarea, de la asezare pana la final (doar la meciul eliminatoriu)
                raw.add(new Raw(t, dur, "Lovituri de departajare", "shootout"));
            } else if ("FREE_KICK".equals(typ) && "shot".equals(e.get("kind"))) {
                raw.add(new Raw(t - FK_BEFORE, outcome(events, k, OUTCOME_WINDOW) + FK_AFTER, "Lovitură liberă",
                        "free_kick"));
            }
        }
        List<Raw> clipped = new ArrayList<>();
        for (Raw r : raw) {
            Raw c = new Raw(Math.max(0.0, r.from), Math.min(dur, r.to), r.label, r.kind);
            if (c.to > c.from) {
                clipped.add(c);
            }
        }
        clipped.sort(Comparator.comparingDouble(Raw::from).thenComparingDouble(Raw::to));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Raw r : clipped) {
            Map<String, Object> last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && r.from - (double) last.get("to") < MERGE_GAP) {
                last.put("to", Math.max((double) last.get("to"), r.to));
                if (PRIORITY.get(r.kind) > PRIORITY.get((String) last.get("kind"))) {
                    last.put("label", r.label);
                    last.put("kind", r.kind);
                }
            } else {
                Map<String, Object> seg = new LinkedHashMap<>();
                seg.put("from", r.from);
                seg.put("to", r.to);
                seg.put("label", r.label);
                seg.put("kind", r.kind);
                out.add(seg);
            }
        }
        return out;
    }
}
