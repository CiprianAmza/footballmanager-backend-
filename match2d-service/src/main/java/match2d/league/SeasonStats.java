package match2d.league;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Statisticile pe jucator in forma ligii, portate din {@code match_stats.py}: asezarea notelor
 * motorului ({@link match2d.engine.MatchStats}, cheia {@code players} a cronologiei) pe echipe,
 * forma compacta din fisierul de stare ({@code pack} / {@code unpack}) si tabelele de sezon.
 */
final class SeasonStats {
    private SeasonStats() {
    }

    static final Map<String, String> GROUP_OF = new LinkedHashMap<>();
    static final Map<String, String> GROUP_NAMES = new LinkedHashMap<>();

    static {
        GROUP_OF.put("GK", "GK");
        for (String p : List.of("DL", "DC", "DR", "WBL", "WBR", "SW", "D")) {
            GROUP_OF.put(p, "DEF");
        }
        for (String p : List.of("DM", "ML", "MC", "MR", "AMC", "M")) {
            GROUP_OF.put(p, "MID");
        }
        for (String p : List.of("AML", "AMR", "ST", "FC", "F")) {
            GROUP_OF.put(p, "ATT");
        }
        GROUP_NAMES.put("GK", "portari");
        GROUP_NAMES.put("DEF", "fundași");
        GROUP_NAMES.put("MID", "mijlocași");
        GROUP_NAMES.put("ATT", "atacanți");
    }

    /** (cheie, eticheta scurta, eticheta lunga): ordinea coloanelor la afisare si in forma pack. */
    static final String[][] COLUMNS = {
        {"rating", "Notă", "Nota meciului"},
        {"goals", "G", "Goluri"},
        {"assists", "PD", "Pase decisive"},
        {"shots", "Ș", "Șuturi"},
        {"on_target", "ȘP", "Șuturi pe poartă"},
        {"key_passes", "PC", "Pase-cheie"},
        {"passes", "P", "Pase"},
        {"passes_ok", "PR", "Pase reușite"},
        {"dribbles", "Dr", "Driblinguri reușite"},
        {"tackles", "Dep", "Deposedări"},
        {"interceptions", "Int", "Intercepții"},
        {"blocks", "Bl", "Șuturi blocate"},
        {"aerials", "Aer", "Dueluri aeriene câștigate"},
        {"saves", "Par", "Parade"},
        {"lost", "MP", "Mingi pierdute"},
        {"fouls", "F", "Faulturi comise"},
        {"fouled", "FS", "Faulturi suferite"},
        {"offsides", "Ofs", "Ofsaiduri"},
        {"yellow", "CG", "Cartonașe galbene"},
        {"red", "CR", "Cartonașe roșii"},
        {"own_goals", "AG", "Autogoluri"},
        {"pen_goals", "PT", "Penalty-uri transformate"},
        {"pen_missed", "PRt", "Penalty-uri ratate"},
        {"pen_saved", "PA", "Penalty-uri apărate"},
        {"conceded", "GP", "Goluri primite"},
        {"clean_sheet", "FGP", "Fără gol primit"},
        {"motm", "OM", "Omul meciului"},
    };
    static final List<String> COLUMN_KEYS;
    static final Set<String> BOOL_KEYS = Set.of("clean_sheet", "motm");
    static final List<String> COUNT_KEYS;
    static final int PACK_VERSION = 1;

    static {
        List<String> keys = new ArrayList<>();
        List<String> counts = new ArrayList<>();
        for (String[] c : COLUMNS) {
            keys.add(c[0]);
            if (!c[0].equals("rating") && !BOOL_KEYS.contains(c[0])) {
                counts.add(c[0]);
            }
        }
        COLUMN_KEYS = List.copyOf(keys);
        COUNT_KEYS = List.copyOf(counts);
    }

    /** Grupa unui post: 'GK', 'DEF', 'MID' sau 'ATT' (necunoscut -> 'MID'). */
    static String groupOf(Object pos) {
        String s = Py.truthy(pos) ? Py.str(pos) : "";
        return GROUP_OF.getOrDefault(s.toUpperCase(Locale.ROOT), "MID");
    }

    private static Object num(Object x) {
        return (x instanceof Long || x instanceof Integer || x instanceof Short || x instanceof Byte
                || x instanceof java.math.BigInteger) ? x : null;
    }

    private static Map<?, ?> dict(Object o) {
        return o instanceof Map<?, ?> m ? m : Map.of();
    }

    private static List<?> list(Object o) {
        return Py.truthy(o) ? (List<?>) o : List.of();
    }

    /** [(echipa, uid, number, name, pos)] din forma cronologiei sau din fisierul de echipe. */
    private static List<Object[]> roster(List<?> teams) {
        List<Object[]> out = new ArrayList<>();
        List<?> two = teams == null ? List.of() : teams.subList(0, Math.min(2, teams.size()));
        for (int i = 0; i < two.size(); i++) {
            List<?> players = list(dict(two.get(i)).get("players"));
            for (int k = 0; k < players.size(); k++) {
                Map<?, ?> p = dict(players.get(k));
                Object uid = num(p.get("uid"));
                Object pos = Py.truthy(p.get("pos")) ? p.get("pos") : p.get("position");
                out.add(new Object[] {i, uid != null ? uid : (Object) (i * 11 + k), p.get("number"), p.get("name"), pos});
            }
        }
        return out;
    }

    /** Lista 'players' a motorului -> {teams: [{name, players: [linie, ...]}, x2], motm: uid}. */
    static Map<String, Object> fromPlayers(List<?> players, List<?> names) {
        if (players == null) {
            throw new IllegalStateException("arhiva motorului e prea veche: rezultatul nu are \"players\" "
                    + "(recompileaza match-engine-java cu build.sh; motorul Python nu le calculeaza)");
        }
        List<Object> nm = new ArrayList<>();
        if (names != null) {
            nm.addAll(names.subList(0, Math.min(2, names.size())));
        }
        while (nm.size() < 2) {
            nm.add(null);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", nm.get(i));
            t.put("players", new ArrayList<Object>());
            out.add(t);
        }
        Object motm = null;
        for (Object o : players) {
            Map<?, ?> p = (Map<?, ?>) o;
            Map<?, ?> stats = Py.truthy(p.get("stats")) ? (Map<?, ?>) p.get("stats") : Map.of();
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("uid", p.get("uid"));
            line.put("number", p.get("number"));
            line.put("name", p.get("name"));
            line.put("pos", p.get("position"));
            line.put("rating", p.get("rating"));
            for (String k : COUNT_KEYS) {
                line.put(k, Py.get(stats, k, 0L));
            }
            line.put("clean_sheet", Py.truthy(stats.get("clean_sheet")));
            line.put("motm", Py.truthy(p.get("motm")));
            if ((Boolean) line.get("motm")) {
                motm = line.get("uid");
            }
            Object team = p.get("team");
            int side = Py.eq(team, 1L) ? 1 : 0;
            @SuppressWarnings("unchecked")
            List<Object> lines = (List<Object>) out.get(side).get("players");
            lines.add(line);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("teams", out);
        res.put("motm", motm);
        return res;
    }

    /** Statisticile din cronologia intreaga (cu 'players' si 'teams'), calculate de motor. */
    static Map<String, Object> fromTimeline(Map<String, Object> timeline) {
        Map<?, ?> tl = timeline == null ? Map.of() : timeline;
        List<Object> names = new ArrayList<>();
        for (Object t : list(tl.get("teams"))) {
            names.add(dict(t).get("name"));
        }
        return fromPlayers((List<?>) tl.get("players"), names);
    }

    // ------------------------------------------------------------------ forma compacta
    /** {v: 1, keys: [...], motm: uid, teams: [{name, ids: [[uid, number, name, pos]], rows: [[...]]}]}. */
    static Map<String, Object> pack(Map<String, Object> stats) {
        Map<?, ?> st = stats == null ? Map.of() : stats;
        List<Object> teams = new ArrayList<>();
        for (Object to : list(st.get("teams"))) {
            Map<?, ?> t = (Map<?, ?>) to;
            List<Object> ids = new ArrayList<>();
            List<Object> rows = new ArrayList<>();
            for (Object lo : list(t.get("players"))) {
                Map<?, ?> l = (Map<?, ?>) lo;
                ids.add(Arrays.asList(l.get("uid"), l.get("number"), l.get("name"), l.get("pos")));
                List<Object> row = new ArrayList<>();
                for (String k : COLUMN_KEYS) {
                    Object v = Py.get(l, k, 0L);
                    row.add(BOOL_KEYS.contains(k) ? (Object) (Py.truthy(v) ? 1L : 0L) : (v != null ? v : (Object) 0L));
                }
                rows.add(row);
            }
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("name", t.get("name"));
            tm.put("ids", ids);
            tm.put("rows", rows);
            teams.add(tm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v", (long) PACK_VERSION);
        out.put("keys", new ArrayList<Object>(COLUMN_KEYS));
        out.put("motm", st.get("motm"));
        out.put("teams", teams);
        return out;
    }

    static boolean isPacked(Object stats) {
        return stats instanceof Map<?, ?> m && m.containsKey("v") && m.containsKey("keys");
    }

    /** Inapoi la forma lui from_players; coloanele absente din 'keys' devin 0. */
    @SuppressWarnings("unchecked")
    static Object unpack(Object packedObj, List<?> teams) {
        if (!isPacked(packedObj)) {
            return packedObj;
        }
        Map<?, ?> packed = (Map<?, ?>) packedObj;
        List<?> keys = Py.truthy(packed.get("keys")) ? (List<?>) packed.get("keys") : COLUMN_KEYS;
        List<Object[]> roster = Py.truthy(teams) ? roster(teams) : List.of();
        List<Object> out = new ArrayList<>();
        List<?> ts = list(packed.get("teams"));
        for (int i = 0; i < ts.size(); i++) {
            Map<?, ?> t = (Map<?, ?>) ts.get(i);
            List<?> ids = list(t.get("ids"));
            List<Object[]> mine = new ArrayList<>();
            for (Object[] r : roster) {
                if (((Integer) r[0]) == i) {
                    mine.add(r);
                }
            }
            List<Object> lines = new ArrayList<>();
            List<?> rows = list(t.get("rows"));
            for (int k = 0; k < rows.size(); k++) {
                List<?> row = (List<?>) rows.get(k);
                Object uid;
                Object number;
                Object name;
                Object pos;
                if (k < ids.size() && Py.truthy(ids.get(k))) {
                    List<Object> id = new ArrayList<>((List<Object>) ids.get(k));
                    while (id.size() < 4) {
                        id.add(null);
                    }
                    uid = id.get(0);
                    number = id.get(1);
                    name = id.get(2);
                    pos = id.get(3);
                } else if (k < mine.size()) {
                    Object[] m = mine.get(k);
                    uid = m[1];
                    number = m[2];
                    name = m[3];
                    pos = m[4];
                } else {
                    uid = (long) (i * 11 + k);
                    number = null;
                    name = null;
                    pos = null;
                }
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("uid", uid);
                line.put("number", number);
                line.put("name", name);
                line.put("pos", pos);
                Map<Object, Object> vals = new LinkedHashMap<>();
                for (int c = 0; c < Math.min(keys.size(), row.size()); c++) {
                    vals.put(keys.get(c), row.get(c));
                }
                for (String key : COLUMN_KEYS) {
                    Object v = Py.get(vals, key, 0L);
                    line.put(key, BOOL_KEYS.contains(key) ? (Object) Py.truthy(v) : v);
                }
                lines.add(line);
            }
            Object name = t.get("name");
            if (name == null && Py.truthy(teams) && i < teams.size()) {
                name = dict(teams.get(i)).get("name");
            }
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("name", name);
            tm.put("players", lines);
            out.add(tm);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("teams", out);
        res.put("motm", packed.get("motm"));
        return res;
    }

    // ------------------------------------------------------------------ tabelele de sezon
    private static final class Agg {
        int n;
        double sum;
        int motm;
        final Map<Object, Integer> pos = new LinkedHashMap<>();
    }

    private static String sname(Object x) {
        return Py.str(x != null ? x : "");
    }

    private static Object mainPos(Agg a) {
        if (a.pos.isEmpty()) {
            return null;
        }
        List<Map.Entry<Object, Integer>> items = new ArrayList<>(a.pos.entrySet());
        items.sort((x, y) -> Py.cmp(Py.str(x.getKey()), Py.str(y.getKey())));
        Map.Entry<Object, Integer> best = items.get(0);
        for (Map.Entry<Object, Integer> e : items) {
            if (e.getValue() > best.getValue()) {
                best = e;
            }
        }
        return best.getKey();
    }

    private static double d(Map<String, Object> r, String k) {
        return Py.num(r.get(k));
    }

    private static Comparator<Map<String, Object>> byNames() {
        return (x, y) -> {
            int c = Py.cmp(sname(x.get("team")), sname(y.get("team")));
            return c != 0 ? c : Py.cmp(sname(x.get("name")), sname(y.get("name")));
        };
    }

    /**
     * matches = [{home, away, round, stats}]; intoarce {clean_sheets, avg_rating, max_rating, motm},
     * fiecare lista sortata si cu toate randurile (vezi {@code match_stats.season_tables}).
     */
    static Map<String, List<Map<String, Object>>> seasonTables(List<Map<String, Object>> matches) {
        Map<List<Object>, Agg> players = new LinkedHashMap<>();
        Map<List<Object>, int[]> gks = new LinkedHashMap<>();       // {cs, n}
        List<Map<String, Object>> singles = new ArrayList<>();
        for (Map<String, Object> m : matches == null ? List.<Map<String, Object>>of() : matches) {
            if (m == null || !Py.truthy(m.get("stats"))) {
                continue;
            }
            Map<?, ?> st = (Map<?, ?>) unpack(m.get("stats"), null);
            List<?> sides = list(st.get("teams"));
            Object[] names = {m.get("home"), m.get("away")};
            for (int i = 0; i < Math.min(2, sides.size()); i++) {
                Map<?, ?> t = (Map<?, ?>) sides.get(i);
                Object team = names[i] != null ? names[i] : t.get("name");
                Object opp = names[1 - i] != null ? names[1 - i]
                        : (sides.size() > 1 ? ((Map<?, ?>) sides.get(1 - i)).get("name") : null);
                for (Object lo : list(t.get("players"))) {
                    Map<?, ?> l = (Map<?, ?>) lo;
                    List<Object> key = Arrays.asList(team, l.get("name"));
                    Agg a = players.computeIfAbsent(key, x -> new Agg());
                    double rating = Py.truthy(l.get("rating")) ? Py.num(l.get("rating")) : 0.0;
                    a.n += 1;
                    a.sum += rating;
                    a.motm += Py.truthy(l.get("motm")) ? 1 : 0;
                    a.pos.merge(l.get("pos"), 1, Integer::sum);
                    Map<String, Object> single = new LinkedHashMap<>();
                    single.put("team", team);
                    single.put("name", l.get("name"));
                    single.put("pos", l.get("pos"));
                    single.put("rating", rating);
                    single.put("opponent", opp);
                    single.put("round", m.get("round"));
                    singles.add(single);
                    if (groupOf(l.get("pos")).equals("GK")) {
                        int[] g = gks.computeIfAbsent(key, x -> new int[2]);
                        g[1] += 1;
                        g[0] += Py.truthy(l.get("clean_sheet")) ? 1 : 0;
                    }
                }
            }
        }
        List<Map<String, Object>> clean = new ArrayList<>();
        for (Map.Entry<List<Object>, int[]> e : gks.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("team", e.getKey().get(0));
            r.put("name", e.getKey().get(1));
            r.put("clean_sheets", (long) e.getValue()[0]);
            r.put("matches", (long) e.getValue()[1]);
            clean.add(r);
        }
        clean.sort(((Comparator<Map<String, Object>>) (x, y) -> Py.cmp(-d(x, "clean_sheets"), -d(y, "clean_sheets")))
                .thenComparing((x, y) -> Py.cmp(d(x, "matches"), d(y, "matches")))
                .thenComparing(byNames()));
        int most = 0;
        for (Agg a : players.values()) {
            most = Math.max(most, a.n);
        }
        List<Map<String, Object>> avg = new ArrayList<>();
        List<Map<String, Object>> motm = new ArrayList<>();
        for (Map.Entry<List<Object>, Agg> e : players.entrySet()) {
            Agg a = e.getValue();
            if (a.n * 3 >= most) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("team", e.getKey().get(0));
                r.put("name", e.getKey().get(1));
                r.put("pos", mainPos(a));
                r.put("matches", (long) a.n);
                r.put("avg_rating", Py.round(a.sum / a.n, 2));
                avg.add(r);
            }
        }
        avg.sort(((Comparator<Map<String, Object>>) (x, y) -> Py.cmp(-d(x, "avg_rating"), -d(y, "avg_rating")))
                .thenComparing((x, y) -> Py.cmp(-d(x, "matches"), -d(y, "matches")))
                .thenComparing(byNames()));
        singles.sort(((Comparator<Map<String, Object>>) (x, y) -> Py.cmp(-d(x, "rating"), -d(y, "rating")))
                .thenComparing((x, y) -> Py.cmp(roundKey(x.get("round")), roundKey(y.get("round"))))
                .thenComparing(byNames()));
        for (Map.Entry<List<Object>, Agg> e : players.entrySet()) {
            Agg a = e.getValue();
            if (a.motm > 0) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("team", e.getKey().get(0));
                r.put("name", e.getKey().get(1));
                r.put("pos", mainPos(a));
                r.put("motm", (long) a.motm);
                r.put("matches", (long) a.n);
                r.put("avg_rating", Py.round(a.sum / a.n, 2));
                motm.add(r);
            }
        }
        motm.sort(((Comparator<Map<String, Object>>) (x, y) -> Py.cmp(-d(x, "motm"), -d(y, "motm")))
                .thenComparing((x, y) -> Py.cmp(-d(x, "avg_rating"), -d(y, "avg_rating")))
                .thenComparing(byNames()));
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("clean_sheets", clean);
        out.put("avg_rating", avg);
        out.put("max_rating", singles);
        out.put("motm", motm);
        return out;
    }

    private static double roundKey(Object r) {
        return Py.isNum(r) ? Py.num(r) : 0.0;
    }
}
