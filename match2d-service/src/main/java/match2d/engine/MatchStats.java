package match2d.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Statisticile si nota (1-10) fiecarui jucator, scoase din evenimentele unui meci jucat. Post-procesare
 * pura: nu trage din generatorul de numere si nu schimba nimic in simulare. Rezultatul intra in
 * cronologie ca lista {@code players} (doar pe drumul nou, vezi {@link Sim#timeline()}).
 *
 * <p>Nota porneste de la {@link #START}, primeste o baza pe grupa de post si sumele ponderate de mai
 * jos; capetele scarii sunt comprimate lin ({@link #soften}); se limiteaza la [3,0; 10,0], cu o zecimala.
 * Omul meciului: nota cea mai mare; la egalitate castigatoarea, apoi golurile, apoi nota nerotunjita.
 */
public final class MatchStats {
    // -----------------------------------------------------------------------------------------
    // Ponderile notei: tot ce conteaza, intr-un singur loc. Valorile sunt puncte de nota pe unitate.
    // Tablourile pe grupa sunt in ordinea GK (portar), DEF, MID, ATT (vezi GROUP_NAMES).
    // -----------------------------------------------------------------------------------------
    public static final double START = 6.0;            // nota de pornire
    // baza pe grupa: compenseaza ca fiecare post aduna altfel de actiuni (calibrata pe liga celor 8)
    public static final double[] BASE = {0.00, 0.28, 0.02, -0.12};
    // --- atac ---
    public static final double GOAL = 1.00;            // gol din joc (si cu capul, si din lovitura libera)
    public static final double GOAL_PEN = 0.60;        // penalty transformat
    public static final double BRACE = 0.40;           // bonus o singura data pentru 2+ goluri
    public static final double GOAL_WINNER = 0.30;     // golul victoriei (nr. goluri ale invinsei + 1)
    public static final double ASSIST = 0.55;          // pasa decisiva
    public static final double ON_TARGET = 0.12;       // sut pe poarta (inclusiv cele care intra)
    public static final double SHOT_OFF = -0.05;       // sut pe langa poarta
    public static final double KEY_PASS = 0.12;        // pasa dupa care primitorul suteaza
    public static final double DRIBBLE = 0.05;         // dribling reusit
    public static final double OFFSIDE = -0.05;        // prins in ofsaid
    // --- pase: calitate fata de volum ---
    public static final double PASS_OK = 0.004;        // fiecare pasa reusita, ...
    public static final int PASS_CAP = 80;             // ... numarate pana la atatea
    public static final double PASS_ACC = 0.25;        // pe unitate de (reusite - asteptat*pase) / sqrt(pase + 10)
    public static final double PASS_EXP = 0.75;        //   asteptat = procentul mediu al ligii
    public static final double DISPOSSESSED = -0.06;   // deposedat
    // --- aparare ---
    public static final double TACKLE = 0.12;
    public static final double INTERCEPTION = 0.035;
    public static final double BLOCK = 0.22;           // sut blocat
    public static final double AERIAL_WON = 0.03;
    public static final double AERIAL_LOST = -0.02;
    public static final double DRIBBLED_PAST = -0.05;
    public static final double SAVE = 0.22;            // parada (portar)
    public static final double PEN_SAVED = 0.80;       // penalty aparat (portar), pe langa parada
    // --- goluri primite / meci fara gol primit, pe grupa ---
    public static final double[] CONCEDED = {-0.33, -0.24, -0.12, -0.04};   // pe gol primit
    public static final double[] CLEAN_SHEET = {0.0, 0.50, 0.20, 0.0};      // portarul: mai jos
    public static final double CLEAN_SHEET_GK_BASE = 0.30;      // fara nicio parada
    public static final double CLEAN_SHEET_GK_PER_SAVE = 0.10;  // + atat pentru fiecare parada
    public static final double CLEAN_SHEET_GK_MAX = 0.70;       // cel mult atat
    // --- disciplina si greseli ---
    public static final double FOUL = -0.10;
    public static final double FOULED = 0.05;
    public static final double YELLOW = -0.40;
    public static final double RED = -1.50;
    public static final double OWN_GOAL = -1.00;
    public static final double PEN_MISSED = -0.80;     // penalty ratat sau aparat (pentru executant)
    public static final double PEN_CONCEDED = -0.50;   // faultul care a dus la penalty
    public static final double PEN_WON = 0.25;         // a provocat penalty-ul
    // --- rezultatul echipei ---
    public static final double WIN = 0.20;
    public static final double LOSS = -0.20;
    // --- capetele scarii ---
    public static final double SOFT_HI = 8.5;
    public static final double SOFT_K = 1.1;           // forma comprimarii de sus: mai mic = 10,0 mai rar
    public static final double SOFT_LO = 5.0;
    public static final double MIN = 3.0;
    public static final double MAX = 10.0;
    /** secunde: sutul primitorului trebuie sa vina la cel mult atat dupa pasa (pasa-cheie). */
    public static final double KEY_PASS_WINDOW = 12.0;

    public static final String[] GROUP_NAMES = {"GK", "DEF", "MID", "ATT"};
    private static final int GK = 0;
    private static final Map<String, Integer> GROUP_OF = new HashMap<>();

    static {
        String[][] groups = {{"GK"}, {"DL", "DC", "DR", "WBL", "WBR", "SW", "D"},
            {"DM", "ML", "MC", "MR", "AMC", "M"}, {"AML", "AMR", "ST", "FC", "F"}};
        for (int g = 0; g < groups.length; g++) {
            for (String pos : groups[g]) {
                GROUP_OF.put(pos, g);
            }
        }
    }

    // contoarele: primele 24 sunt coloanele fisei (in ordinea din match_stats.COLUMNS), restul ajutatoare
    static final String[] KEYS = {"goals", "assists", "shots", "on_target", "key_passes", "passes",
        "passes_ok", "dribbles", "tackles", "interceptions", "blocks", "aerials", "saves", "lost", "fouls",
        "fouled", "offsides", "yellow", "red", "own_goals", "pen_goals", "pen_missed", "pen_saved", "conceded",
        "aerials_lost", "dribbled_past", "shots_off", "open_goals", "dispossessed", "pen_conceded", "pen_won",
        "winning_goal"};
    private static final int GOALS = 0;
    private static final int ASSISTS = 1;
    private static final int SHOTS = 2;
    private static final int ON_TGT = 3;
    private static final int KEY_PASSES = 4;
    private static final int PASSES = 5;
    private static final int PASSES_OK = 6;
    private static final int DRIBBLES = 7;
    private static final int TACKLES = 8;
    private static final int INTERCEPTIONS = 9;
    private static final int BLOCKS = 10;
    private static final int AERIALS = 11;
    private static final int SAVES = 12;
    private static final int LOST = 13;
    private static final int FOULS = 14;
    private static final int FOULED_N = 15;
    private static final int OFFSIDES = 16;
    private static final int YELLOWS = 17;
    private static final int REDS = 18;
    private static final int OWN_GOALS = 19;
    private static final int PEN_GOALS = 20;
    private static final int PEN_MISSED_N = 21;
    private static final int PEN_SAVED_N = 22;
    private static final int CONCEDED_N = 23;
    private static final int AERIALS_LOST = 24;
    private static final int DRIBBLED_PAST_N = 25;
    private static final int SHOTS_OFF = 26;
    private static final int OPEN_GOALS = 27;
    private static final int DISPOSSESSED_N = 28;
    private static final int PEN_CONCEDED_N = 29;
    private static final int PEN_WON_N = 30;
    private static final int WINNING_GOAL = 31;

    // evenimente dupa care mingea nu mai e la primitorul unei pase (inchid o posibila pasa-cheie)
    private static final Set<String> BREAKS = Set.of("PASS", "PASS_FAIL", "INTERCEPT", "TACKLE", "HEADER",
            "OFFSIDE", "CORNER", "GOAL_KICK", "THROW_IN", "FOUL", "FREE_KICK", "PENALTY", "HALF_TIME",
            "FULL_TIME", "GOAL", "MISS", "SAVE", "BLOCK", "CARD", "EXTRA_TIME", "EXTRA_HALF_TIME", "SHOOTOUT");

    private MatchStats() {
    }

    /** Grupa unui post (indice in GROUP_NAMES); necunoscut = MID. */
    static int groupOf(String pos) {
        Integer g = GROUP_OF.get(pos == null ? "" : pos.toUpperCase(Locale.ROOT));
        return g != null ? g : 2;
    }

    static Integer num(Object v) {
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Long l) {
            return (int) (long) l;
        }
        return null;
    }

    static boolean truthy(Object v) {
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

    private static boolean sameT(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return Objects.equals(a, b);
    }

    static double round1(double x) {
        return Math.floor(x * 10.0 + 0.5) / 10.0;
    }

    /** Lista {@code players} a cronologiei: cate un element pe jucator, gazdele intai. */
    static List<Map<String, Object>> players(Sim s) {
        List<Player> roster = new ArrayList<>();
        for (Team team : s.teams) {
            for (Player p : team.players) {
                roster.add(p);
            }
        }
        int[] sc = {s.teams[0].goals, s.teams[1].goals};
        Map<Integer, Integer> teamOf = new HashMap<>();
        Map<Integer, int[]> counts = new HashMap<>();
        for (Player p : roster) {
            teamOf.put(p.uid, p.team.index);
            counts.put(p.uid, new int[KEYS.length]);
        }
        count(s.events, counts);
        Integer winner = winningGoal(s.events, teamOf, sc);
        if (winner != null && counts.containsKey(winner) && teamOf.get(winner) == (sc[0] > sc[1] ? 0 : 1)) {
            counts.get(winner)[WINNING_GOAL] = 1;
        }
        int n = roster.size();
        double[] raws = new double[n];
        double[] ratings = new double[n];
        for (int k = 0; k < n; k++) {
            Player p = roster.get(k);
            int i = p.team.index;
            int[] c = counts.get(p.uid);
            c[CONCEDED_N] = sc[1 - i];
            raws[k] = rate(c, groupOf(p.pos), sc[1 - i], sc[i] - sc[1 - i]);
            ratings[k] = round1(Math.min(MAX, Math.max(MIN, raws[k])));
        }
        Integer winTeam = sc[0] > sc[1] ? Integer.valueOf(0) : (sc[1] > sc[0] ? Integer.valueOf(1) : null);
        int best = -1;
        Comparator<Integer> order = Comparator.<Integer>comparingDouble(k -> -ratings[k])
                .thenComparingInt(k -> winTeam != null && roster.get(k).team.index == winTeam ? 0 : 1)
                .thenComparingInt(k -> -counts.get(roster.get(k).uid)[GOALS])
                .thenComparingDouble(k -> -raws[k])
                .thenComparingInt(k -> roster.get(k).uid);
        for (int k = 0; k < n; k++) {
            if (best < 0 || order.compare(k, best) < 0) {
                best = k;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            Player p = roster.get(k);
            int[] c = counts.get(p.uid);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team", p.team.index);
            m.put("uid", p.uid);
            m.put("id", p.id);
            m.put("name", p.name);
            m.put("number", p.num);
            m.put("position", p.pos);
            m.put("rating", ratings[k]);
            m.put("motm", k == best);
            Map<String, Object> st = new LinkedHashMap<>();
            for (int j = 0; j < KEYS.length; j++) {
                st.put(KEYS[j], c[j]);
                if (j == CONCEDED_N) {
                    st.put("clean_sheet", c[j] == 0);
                }
            }
            m.put("stats", st);
            out.add(m);
        }
        return out;
    }

    private static void add(Map<Integer, int[]> c, Integer uid, int key) {
        if (uid != null) {
            int[] row = c.get(uid);
            if (row != null) {
                row[key]++;
            }
        }
    }

    /** Numara actiunile fiecarui jucator (cheile din {@code c} = uid-urile cunoscute). */
    static void count(List<Map<String, Object>> events, Map<Integer, int[]> c) {
        boolean shotOn = false;     // sutul in curs, pana la deznodamant
        Integer shotP = null;
        boolean shotPen = false;
        Integer shotKp = null;
        boolean recvOn = false;     // ultima pasa reusita, cat timp primitorul are mingea
        Integer recvP = null;       // primitorul
        Integer recvQ = null;       // pasatorul
        Double recvT = null;
        Integer penTaker = null;    // executantul anuntat de PENALTY
        boolean foulOn = false;     // ultimul fault: (t, cine a faultat, cine a fost faultat)
        Object foulT = null;
        Integer foulP = null;
        Integer foulQ = null;
        for (Map<String, Object> e : events) {
            Object typObj = e.get("type");
            String typ = typObj instanceof String str ? str : null;
            Integer p = num(e.get("p"));
            Integer q = num(e.get("q"));
            if (typ == null) {
                typ = "";
            }
            switch (typ) {
                case "PASS" -> add(c, p, PASSES);
                case "PASS_OK" -> add(c, q, PASSES_OK);
                case "PASS_FAIL" -> add(c, p, LOST);
                case "INTERCEPT" -> {
                    add(c, p, INTERCEPTIONS);
                    add(c, q, LOST);
                }
                case "TACKLE" -> {
                    add(c, p, TACKLES);
                    add(c, q, LOST);
                    add(c, q, DISPOSSESSED_N);
                }
                case "DRIBBLE" -> {
                    add(c, p, DRIBBLES);
                    add(c, q, DRIBBLED_PAST_N);
                }
                case "HEADER" -> {
                    add(c, p, AERIALS);
                    add(c, q, AERIALS_LOST);
                }
                case "OFFSIDE" -> add(c, p, OFFSIDES);
                case "FOUL" -> {
                    add(c, p, FOULS);
                    add(c, q, FOULED_N);
                    foulOn = true;
                    foulT = e.get("t");
                    foulP = p;
                    foulQ = q;
                }
                case "CARD" -> {
                    Object cv = e.get("card");
                    String card = (truthy(cv) ? String.valueOf(cv) : "yellow").toLowerCase(Locale.ROOT);
                    add(c, p, card.equals("red") || card.equals("second_yellow") ? REDS : YELLOWS);
                }
                case "PENALTY" -> {
                    penTaker = p;
                    // penalty-ul vine imediat dupa faultul din careu (acelasi moment)
                    if (foulOn && sameT(foulT, e.get("t"))) {
                        add(c, foulP, PEN_CONCEDED_N);
                        add(c, foulQ, PEN_WON_N);
                    }
                    foulOn = false;
                }
                case "SHOT" -> {
                    if (p != null) {
                        add(c, p, SHOTS);
                        boolean pen = truthy(e.get("penalty")) || (penTaker != null && penTaker.equals(p));
                        Object t = e.get("t");
                        boolean fresh = recvOn && (!(t instanceof Number) || recvT == null
                                || ((Number) t).doubleValue() - recvT <= KEY_PASS_WINDOW);
                        Integer kp = fresh && p.equals(recvP) && !pen ? recvQ : null;
                        add(c, kp, KEY_PASSES);
                        shotOn = true;
                        shotP = p;
                        shotPen = pen;
                        shotKp = kp;
                    }
                    penTaker = null;
                }
                case "SAVE" -> {
                    add(c, p, SAVES);
                    if (shotOn) {
                        add(c, shotP, ON_TGT);
                        if (shotPen) {
                            add(c, shotP, PEN_MISSED_N);
                            add(c, p, PEN_SAVED_N);
                        }
                    }
                    shotOn = false;
                }
                case "MISS" -> {
                    if (shotOn) {
                        add(c, shotP, SHOTS_OFF);
                        if (shotPen) {
                            add(c, shotP, PEN_MISSED_N);
                        }
                    }
                    shotOn = false;
                }
                case "BLOCK" -> {
                    add(c, p, BLOCKS);
                    shotOn = false;
                }
                case "GOAL" -> {
                    Integer own = num(e.get("own"));
                    if (p == null || (own != null && own.equals(p))) {
                        add(c, own, OWN_GOALS);
                    } else {
                        Object hv = e.get("how");
                        String how = truthy(hv) ? String.valueOf(hv) : "";
                        boolean fromShot = shotOn && p.equals(shotP);
                        boolean pen = how.equals("penalty") || (fromShot && shotPen);
                        add(c, p, GOALS);
                        add(c, p, pen ? PEN_GOALS : OPEN_GOALS);
                        // golul e mereu un sut pe poarta; fara SHOT al marcatorului il numaram aici
                        if (!fromShot) {
                            add(c, p, SHOTS);
                        }
                        add(c, p, ON_TGT);
                        Integer a = num(e.get("assist"));
                        if (a != null && !a.equals(p)) {
                            add(c, a, ASSISTS);
                            if (!(fromShot && Objects.equals(shotKp, a))) {
                                add(c, a, KEY_PASSES);     // pasa decisiva e si pasa-cheie
                            }
                        }
                    }
                    shotOn = false;
                    penTaker = null;
                }
                default -> {
                }
            }
            // cine are mingea dupa o pasa reusita (pentru pasele-cheie)
            if (typ.equals("PASS_OK")) {
                Object t = e.get("t");
                recvOn = p != null && q != null && !p.equals(q);
                recvP = recvOn ? p : null;
                recvQ = recvOn ? q : null;
                recvT = recvOn && t instanceof Number tn ? tn.doubleValue() : null;
            } else if (BREAKS.contains(typ) || (typ.equals("CARRY") && recvOn && !Objects.equals(p, recvP))
                    || (typ.equals("DRIBBLE") && recvOn && !Objects.equals(p, recvP))) {
                recvOn = false;
            }
            if (typ.equals("SHOT")) {
                recvOn = false;
            }
        }
    }

    /** uid-ul marcatorului golului victoriei, sau null (egal, autogol, gol lipsa). */
    static Integer winningGoal(List<Map<String, Object>> events, Map<Integer, Integer> teamOf, int[] score) {
        if (score[0] == score[1]) {
            return null;
        }
        int w = score[0] > score[1] ? 0 : 1;
        int need = score[1 - w] + 1;
        int n = 0;
        for (Map<String, Object> e : events) {
            if (!"GOAL".equals(e.get("type"))) {
                continue;
            }
            Integer p = num(e.get("p"));
            Integer own = num(e.get("own"));
            boolean isOwn = p == null || (own != null && own.equals(p));
            Integer team = num(e.get("team"));
            if (team == null || (team != 0 && team != 1)) {
                if (!isOwn && teamOf.containsKey(p)) {
                    team = teamOf.get(p);
                } else if (isOwn && own != null && teamOf.containsKey(own)) {
                    team = 1 - teamOf.get(own);
                } else {
                    continue;
                }
            }
            if (team != w) {
                continue;
            }
            n++;
            if (n == need) {
                return isOwn ? null : p;
            }
        }
        return null;
    }

    /** Nota bruta (nerotunjita, nelimitata). */
    static double rate(int[] c, int group, int conceded, int result) {
        double r = START + BASE[group];
        r += GOAL * c[OPEN_GOALS] + GOAL_PEN * c[PEN_GOALS];
        if (c[GOALS] >= 2) {
            r += BRACE;
        }
        r += GOAL_WINNER * c[WINNING_GOAL];
        r += ASSIST * c[ASSISTS];
        r += ON_TARGET * c[ON_TGT] + SHOT_OFF * c[SHOTS_OFF];
        r += KEY_PASS * c[KEY_PASSES] + DRIBBLE * c[DRIBBLES] + OFFSIDE * c[OFFSIDES];
        int n = c[PASSES];
        int ok = Math.min(c[PASSES_OK], n);
        r += PASS_OK * Math.min(ok, PASS_CAP);
        r += PASS_ACC * (ok - PASS_EXP * n) / Math.sqrt(n + 10.0);
        r += DISPOSSESSED * c[DISPOSSESSED_N];
        r += TACKLE * c[TACKLES] + INTERCEPTION * c[INTERCEPTIONS] + BLOCK * c[BLOCKS];
        r += AERIAL_WON * c[AERIALS] + AERIAL_LOST * c[AERIALS_LOST];
        r += DRIBBLED_PAST * c[DRIBBLED_PAST_N];
        r += SAVE * c[SAVES] + PEN_SAVED * c[PEN_SAVED_N];
        r += CONCEDED[group] * conceded;
        if (conceded == 0) {
            if (group == GK) {
                r += Math.min(CLEAN_SHEET_GK_MAX, CLEAN_SHEET_GK_BASE + CLEAN_SHEET_GK_PER_SAVE * c[SAVES]);
            } else {
                r += CLEAN_SHEET[group];
            }
        }
        r += FOUL * c[FOULS] + FOULED * c[FOULED_N];
        r += YELLOW * c[YELLOWS] + RED * c[REDS];
        r += OWN_GOAL * c[OWN_GOALS] + PEN_MISSED * c[PEN_MISSED_N];
        r += PEN_CONCEDED * c[PEN_CONCEDED_N] + PEN_WON * c[PEN_WON_N];
        r += result > 0 ? WIN : (result < 0 ? LOSS : 0.0);
        return soften(r);
    }

    /** Capetele scarii: peste SOFT_HI nota tinde lin spre MAX, sub SOFT_LO spre MIN. */
    static double soften(double r) {
        if (r > SOFT_HI) {
            // x * s / (x^k + s^k)^(1/k): panta 1 la SOFT_HI, tinde spre MAX cu atat mai incet cu cat k e mai mic
            double span = MAX - SOFT_HI;
            double x = r - SOFT_HI;
            return SOFT_HI + span * x / Math.pow(Math.pow(x, SOFT_K) + Math.pow(span, SOFT_K), 1.0 / SOFT_K);
        }
        if (r < SOFT_LO) {
            double span = SOFT_LO - MIN;
            return SOFT_LO - span * Math.tanh((SOFT_LO - r) / span);
        }
        return r;
    }
}
