package match2d.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import match2d.engine.A;
import match2d.engine.Zones;

/**
 * Aduce o echipa primita din afara (backend-ul jocului, un fisier) la forma pe care o joaca
 * motorul. Backend-ul trimite ce are in baza: codul de formatie ({@code 442}), culori ca nume CSS
 * ({@code black}), jucatorii in ordinea lui. Aici le normalizam o singura data, cu erori clare
 * daca lipseste ceva.
 */
public final class TeamIo {
    /** Echipa primita nu poate fi jucata; mesajul spune exact de ce. */
    public static final class TeamError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public TeamError(String message) {
            super(message);
        }
    }

    // codul de formatie din backend -> numele din motor (doar pentru afisare; zonele vin din pozitii)
    private static final Map<String, String> FORMATION_CODES = Map.of("442", "4-4-2", "433", "4-3-3",
            "4231", "4-2-3-1", "352", "3-5-2", "4141", "4-1-4-1", "4411", "4-4-1-1", "4222", "4-2-2-2",
            "3511", "3-5-1-1", "31411", "3-1-4-1-1");
    private static final Map<String, String> CSS = Map.ofEntries(Map.entry("black", "#1f2933"),
            Map.entry("grey", "#9aa5b1"), Map.entry("gray", "#9aa5b1"), Map.entry("white", "#f5f7fa"),
            Map.entry("blue", "#1d4ed8"), Map.entry("darkblue", "#1e3a8a"), Map.entry("green", "#16a34a"),
            Map.entry("darkgreen", "#166534"), Map.entry("yellow", "#facc15"), Map.entry("orange", "#f97316"),
            Map.entry("red", "#dc2626"), Map.entry("brown", "#92400e"), Map.entry("purple", "#7c3aed"),
            Map.entry("pink", "#ec4899"), Map.entry("cyan", "#06b6d4"));
    // ordinea in care asezam jucatorii: portar, aparare, mijloc, atac, de la stanga la dreapta
    private static final List<String> ORDER = List.of("GK", "DL", "WBL", "DC", "DR", "WBR", "DM", "ML",
            "MC", "MR", "AML", "AMC", "AMR", "ST");
    private static final String[] KEEP = {"id", "rating", "specialRole", "naturalPosition", "role", "duty",
        "instructions", "shadow"};

    private TeamIo() {
    }

    private static String color(Object value, String fallback) {
        if (value == null || String.valueOf(value).isEmpty()) {
            return fallback;
        }
        String v = String.valueOf(value).trim();
        if (v.startsWith("#") && v.length() == 7) {
            return v;
        }
        return CSS.getOrDefault(v.toLowerCase(), fallback);
    }

    private static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        return !String.valueOf(v).isEmpty();
    }

    /** Intoarce o copie a echipei, gata de dat motorului. Nu modifica originalul. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalize(Object raw, String fallbackColor) {
        if (!(raw instanceof Map)) {
            throw new TeamError("echipa trebuie sa fie un obiect JSON");
        }
        Map<String, Object> team = (Map<String, Object>) raw;
        String name = truthy(team.get("name")) ? String.valueOf(team.get("name")) : "Echipa";
        Object playersRaw = team.get("players");
        if (!(playersRaw instanceof List) || ((List<?>) playersRaw).size() != 11) {
            throw new TeamError(name + ": trebuie exact 11 jucatori, am primit "
                    + (playersRaw instanceof List ? String.valueOf(((List<?>) playersRaw).size()) : "altceva"));
        }
        List<Object> players = (List<Object>) playersRaw;
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        for (int i = 0; i < players.size(); i++) {
            if (!(players.get(i) instanceof Map)) {
                throw new TeamError(name + ": jucatorul " + (i + 1) + " trebuie sa fie un obiect JSON");
            }
            Map<String, Object> p = (Map<String, Object>) players.get(i);
            Object pos = p.get("position");
            if (!(pos instanceof String) || !Zones.POS.containsKey(pos)) {
                throw new TeamError(name + ": pozitie necunoscuta \"" + pos + "\" la " + p.get("name"));
            }
            Map<String, Object> ratings = p.get("ratings") instanceof Map
                    ? (Map<String, Object>) p.get("ratings") : Map.of();
            List<String> missing = new ArrayList<>();
            for (String a : A.NAMES) {
                if (!ratings.containsKey(a)) {
                    missing.add(a);
                }
            }
            if (!missing.isEmpty()) {
                throw new TeamError(name + ": lui " + p.get("name") + " ii lipsesc ratingurile: "
                        + String.join(", ", missing));
            }
            Map<String, Object> clean = new LinkedHashMap<>();
            for (String a : A.NAMES) {
                Object v = ratings.get(a);
                double d;
                if (v instanceof Number num) {
                    d = num.doubleValue();
                } else {
                    try {
                        d = Double.parseDouble(String.valueOf(v));
                    } catch (NumberFormatException e) {
                        throw new TeamError(name + ": ratingul " + a + " al lui " + p.get("name") + " nu e numar");
                    }
                }
                clean.put(a, (long) Math.max(1, Math.min(25, Math.rint(d))));
            }
            long number = truthy(p.get("number")) && p.get("number") instanceof Number
                    ? ((Number) p.get("number")).longValue() : i + 1;
            while (used.contains(number)) {
                number++;
            }
            used.add(number);
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("name", truthy(p.get("name")) ? String.valueOf(p.get("name")) : "Jucator " + (i + 1));
            q.put("number", number);
            q.put("position", pos);
            q.put("ratings", clean);
            q.put("stayForward", truthy(p.get("stayForward")));
            for (String k : KEEP) {
                if (p.containsKey(k)) {
                    q.put(k, p.get(k));
                }
            }
            out.add(q);
        }
        int keepers = 0;
        Map<String, Integer> perPos = new LinkedHashMap<>();
        for (Map<String, Object> p : out) {
            String pos = (String) p.get("position");
            perPos.merge(pos, 1, Integer::sum);
            if (pos.equals("GK")) {
                keepers++;
            }
        }
        if (keepers != 1) {
            throw new TeamError(name + ": trebuie exact un portar");
        }
        for (String pos : Zones.POS.keySet()) {
            if (perPos.getOrDefault(pos, 0) > 4) {
                throw new TeamError(name + ": prea multi jucatori pe pozitia " + pos);
            }
        }
        // sortare stabila: portarul ajunge primul
        out.sort((a, b) -> Integer.compare(ORDER.indexOf(a.get("position")), ORDER.indexOf(b.get("position"))));
        List<String> positions = new ArrayList<>();
        for (Map<String, Object> p : out) {
            positions.add((String) p.get("position"));
        }
        String code = team.get("formation") == null ? "" : String.valueOf(team.get("formation"));
        String formation = FORMATION_CODES.getOrDefault(code.replace("-", ""), code);
        if (Zones.FORMATIONS.containsKey(formation)) {
            List<String> want = new ArrayList<>(Zones.FORMATIONS.get(formation));
            List<String> got = new ArrayList<>(positions);
            want.sort(null);
            got.sort(null);
            if (!want.equals(got)) {
                formation = "";     // numele nu se potriveste cu pozitiile reale: nu-l mai afisam
            }
        }
        if (!Zones.FORMATIONS.containsKey(formation)) {
            formation = describe(positions);
        }
        String c1 = color(team.get("color"), fallbackColor);
        String c2 = color(team.get("gkColor"), "#f97316");
        if (c2.equals(c1)) {
            c2 = !c1.equals("#f97316") ? "#f97316" : "#facc15";
        }
        Map<String, Object> tactic = new LinkedHashMap<>();
        if (team.get("tactic") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) team.get("tactic")).entrySet()) {
                tactic.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("name", name);
        res.put("formation", formation);
        res.put("color", c1);
        res.put("gkColor", c2);
        res.put("tactic", tactic);
        res.put("players", out);
        if (team.containsKey("id")) {
            res.put("id", team.get("id"));
        }
        return res;
    }

    /** Numele formatiei dedus din pozitii, de exemplu 4-3-3. */
    static String describe(List<String> positions) {
        List<Set<String>> lines = List.of(Set.of("DL", "DC", "DR", "WBL", "WBR"), Set.of("DM"),
                Set.of("ML", "MC", "MR"), Set.of("AML", "AMC", "AMR"), Set.of("ST"));
        List<String> parts = new ArrayList<>();
        for (Set<String> line : lines) {
            long count = positions.stream().filter(line::contains).count();
            if (count > 0) {
                parts.add(String.valueOf(count));
            }
        }
        return parts.isEmpty() ? "?" : String.join("-", parts);
    }
}
