package match2d.league;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import match2d.engine.Sim;
import match2d.service.Json;

/**
 * Starea ligii si ceasul transmisiei, portate din clasa {@code League} a lui {@code league_tv.py}.
 *
 * <p>Fazele unui meci: 'lineups' (echipele pe ecran, cel putin {@code lineupSeconds} si pana e gata
 * simularea), 'live', 'fulltime' ({@code pauseSeconds}), apoi meciul urmator. Dupa ultimul meci
 * faza e 'finished'. In 'live' ceasul parcurge lista de bucati din {@link Playlist#playlist};
 * golurile, cartonasele, clasamentul, minutul si sfarsitul meciului se judeca dupa progres.
 */
public final class League {
    static final double LINEUP_SECONDS = 12.0;      // cat stau pe ecran cele doua echipe inainte de start
    static final double PAUSE_SECONDS = 30.0;       // pauza dupa fluierul final (cu fisa meciului pe ecran)
    static final List<Long> SPEEDS = List.of(1L, 2L, 4L, 8L, 16L, 32L);
    // statisticile de echipa pastrate in rezultatul salvat (lipsesc din rezumat -> None)
    static final List<String> TEAM_STATS = List.of("shots", "on_target", "poss_pct", "passes", "passes_ok",
            "energy_end", "xg", "corners", "fouls", "yellow_cards", "free_kicks", "penalties");
    // coloanele fisei de dupa meci: (cheie, a doua cheie, fel); etichetele vin din SeasonStats.COLUMNS
    static final String[][] SHEET_COLUMNS = {
        {"rating", null, "rating"}, {"goals", null, "n"}, {"assists", null, "n"},
        {"shots", "on_target", "paren"}, {"key_passes", null, "n"},
        {"passes_ok", "passes", "ratio"}, {"dribbles", null, "n"}, {"tackles", null, "n"},
        {"interceptions", null, "n"}, {"blocks", null, "n"}, {"saves", null, "n"},
        {"lost", null, "n"}, {"fouls", null, "n"}, {"yellow", "red", "card"},
    };
    static final int SEASON_TOP = 8;                // randuri in tabelele de sezon
    static final int REPLAY_CACHE = 3;              // meciuri rejucate tinute in memorie (pentru /replay/N)

    /** Joaca un meci si intoarce cronologia. */
    public interface Simulator {
        Map<String, Object> play(Map<String, Object> home, Map<String, Object> away, long seed, double minutes);
    }

    /** Un meci din program. */
    static final class Fixture {
        final int index;
        final int round;
        final String home;
        final String away;
        long seed;

        Fixture(int index, int round, String home, String away, long seed) {
            this.index = index;
            this.round = round;
            this.home = home;
            this.away = away;
            this.seed = seed;
        }
    }

    /** Un meci care intra in clasament: (meci, scor, goluri, in direct?). */
    private record Played(Fixture fx, List<?> score, List<?> goals, boolean live) {
    }

    /** Rezultatul unei cereri: cod HTTP si corp. */
    public record Reply(int code, Object body, String fileName) {
    }

    /** Coloanele fisei, cu etichetele din COLUMNS (scurta si lunga, pentru legenda). */
    static List<Map<String, Object>> sheetColumns() {
        Map<String, String[]> labels = new HashMap<>();
        for (String[] c : SeasonStats.COLUMNS) {
            labels.put(c[0], new String[] {c[1], c[2]});
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] sc : SHEET_COLUMNS) {
            String[] l = labels.getOrDefault(sc[0], new String[] {sc[0], sc[0]});
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("key", sc[0]);
            col.put("kind", sc[2]);
            col.put("short", l[0]);
            col.put("long", l[1]);
            if (sc[1] != null) {
                String[] l2 = labels.getOrDefault(sc[1], new String[] {sc[1], sc[1]});
                col.put("key2", sc[1]);
                col.put("short2", l2[0]);
                col.put("long2", l2[1]);
            }
            out.add(col);
        }
        return out;
    }

    // ---------------------------------------------------------------------- program
    /**
     * Etapele ligii: {@code meetings} tururi complete, fiecare echipa o data pe etapa (metoda
     * cercului pe lista amestecata din {@code seed}); turul al doilea si al patrulea inverseaza gazdele.
     */
    static List<List<String[]>> drawSchedule(List<String> names, int meetings, long seed) {
        PyRandom rng = new PyRandom(seed);
        List<String> order = new ArrayList<>(names);
        rng.shuffle(order);
        if (order.size() % 2 != 0) {
            order.add(null);            // numar impar: cine pica langa null sta in etapa aceea
        }
        int n = order.size();
        List<List<String[]>> single = new ArrayList<>();
        for (int r = 0; r < n - 1; r++) {
            List<String[]> pairs = new ArrayList<>();
            for (int i = 0; i < n / 2; i++) {
                String a = order.get(i);
                String b = order.get(n - 1 - i);
                if (a == null || b == null) {
                    continue;
                }
                if (i == 0 ? (r % 2 != 0) : (i % 2 != 0)) {
                    String t = a;           // alterneaza cine e gazda
                    a = b;
                    b = t;
                }
                pairs.add(new String[] {a, b});
            }
            single.add(pairs);
            List<String> next = new ArrayList<>();
            next.add(order.get(0));
            next.add(order.get(n - 1));
            next.addAll(order.subList(1, n - 1));
            order = next;
        }
        List<List<String[]>> rounds = new ArrayList<>();
        for (int leg = 0; leg < meetings; leg++) {
            for (List<String[]> pairs : single) {
                List<String[]> p = new ArrayList<>();
                for (String[] ab : pairs) {
                    p.add(leg % 2 != 0 ? new String[] {ab[1], ab[0]} : ab);
                }
                rng.shuffle(p);
                rounds.add(p);
            }
        }
        return rounds;
    }

    /** Copia unei valori JSON, cum ar trece printr-un {@code json.dumps} / {@code json.loads}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> viaJson(Map<String, Object> v) {
        return (Map<String, Object>) Json.parse(Json.write(v));
    }

    /**
     * Joaca un meci in proces si intoarce cronologia, cu aceeasi regula a culorilor ca
     * {@code simulate_fixture}. Echipele si cronologia trec prin JSON, ca in Python (unde meciul il
     * juca {@code java -jar match2d.jar run}): aceleasi tipuri de numere, nimic partajat cu motorul.
     */
    static Map<String, Object> simulateFixture(Map<String, Object> home, Map<String, Object> away, long seed,
                                               double minutes) {
        if (Py.eq(away.get("color"), home.get("color"))) {
            Map<String, Object> copy = new LinkedHashMap<>(away);
            copy.put("color", !Py.eq(home.get("color"), "#f5f7fa") ? "#f5f7fa" : "#1f2933");
            away = copy;
        }
        int hz = minutes <= 20 ? 10 : 5;
        Sim sim = new Sim(viaJson(home), viaJson(away), seed, minutes, hz, true);
        return viaJson(sim.run());
    }

    private static Map<Object, Map<?, ?>> playersByUid(Map<String, Object> timeline) {
        Map<Object, Map<?, ?>> players = new HashMap<>();
        for (Object t : (List<?>) timeline.get("teams")) {
            for (Object p : (List<?>) ((Map<?, ?>) t).get("players")) {
                players.put(key(((Map<?, ?>) p).get("uid")), (Map<?, ?>) p);
            }
        }
        return players;
    }

    /** Cheia unui dictionar Python: numerele egale (3 si 3.0) dau aceeasi cheie. */
    private static Object key(Object v) {
        if (Py.isNum(v)) {
            double d = Py.num(v);
            return d == Math.rint(d) && Math.abs(d) < 9e15 ? (Object) (long) d : (Object) d;
        }
        return v;
    }

    private static Object nameOf(Map<?, ?> p) {
        return p != null ? p.get("name") : null;
    }

    /** Golurile unui meci, in ordine: t, team (0 = gazde), scorer, assist, own [, how]. */
    static List<Map<String, Object>> matchGoals(Map<String, Object> timeline) {
        Map<Object, Map<?, ?>> players = playersByUid(timeline);
        List<Map<String, Object>> goals = new ArrayList<>();
        for (Object o : (List<?>) timeline.get("events")) {
            Map<?, ?> e = (Map<?, ?>) o;
            if (!"GOAL".equals(e.get("type"))) {
                continue;
            }
            Map<String, Object> goal = new LinkedHashMap<>();
            goal.put("t", e.get("t"));
            goal.put("team", e.get("team"));
            goal.put("scorer", nameOf(players.get(key(e.get("p")))));
            goal.put("assist", nameOf(players.get(key(e.get("assist")))));
            goal.put("own", nameOf(players.get(key(e.get("own")))));
            if (Py.truthy(e.get("how"))) {   // 'penalty' / 'free_kick' / 'header'; lipseste la cele din joc
                goal.put("how", e.get("how"));
            }
            goals.add(goal);
        }
        return goals;
    }

    /** Cartonasele unui meci, in ordine: t, team (0 = gazde), name, card. */
    static List<Map<String, Object>> matchCards(Map<String, Object> timeline) {
        Map<Object, Map<?, ?>> players = playersByUid(timeline);
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Object o : (List<?>) timeline.get("events")) {
            Map<?, ?> e = (Map<?, ?>) o;
            if (!"CARD".equals(e.get("type"))) {
                continue;
            }
            Map<?, ?> who = players.get(key(e.get("p")));
            Object team = e.get("team");
            if (who == null || !(Py.eq(team, 0L) || Py.eq(team, 1L))) {
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("t", e.get("t"));
            c.put("team", team);
            c.put("name", who.get("name"));
            c.put("card", Py.truthy(e.get("card")) ? e.get("card") : "yellow");
            cards.add(c);
        }
        return cards;
    }

    // ---------------------------------------------------------------------- liga
    final String name;
    final Map<String, Map<String, Object>> teams = new LinkedHashMap<>();
    final List<String> order = new ArrayList<>();
    final int meetings;
    final double minutes;
    double speed;                   // viteza meciului intreg
    double hlSpeed;                 // viteza rezumatului
    String mode;
    boolean replays;
    private final ExecutorService executor;
    private final DoubleSupplier clock;
    private final Simulator simulate;
    private final Path statePath;
    final double lineupSeconds;
    final double pauseSeconds;
    private final Map<List<Object>, Map<String, Object>> replayCache = new LinkedHashMap<>();
    int season;
    long seed;
    List<List<String[]>> rounds;
    List<Fixture> fixtures;
    List<Object> results;
    int cur;
    private Map<Integer, Map<String, Object>> timelines;
    private Map<Integer, List<Map<String, Object>>> goalCache;
    private Map<Integer, List<Map<String, Object>>> cardCache;
    private Map<Integer, Map<String, Object>> statsCache;
    private Object[] seasonCache;   // (meciuri incheiate, tabelele de sezon)
    private Map<Integer, Object[]> pages;
    private Map<Integer, Future<Map<String, Object>>> pending;
    String phase;
    double phaseAt;
    private List<Playlist.Piece> pieces;
    private int anchorPiece;        // (bucata, secunde reale trecute din ea, moment)
    private double anchorOffset;
    private double anchorWall;

    public League(List<Map<String, Object>> teamList, long seed, int meetings, double minutes, double speed,
                  ExecutorService executor, DoubleSupplier clock, Simulator simulate, Path statePath,
                  double lineupSeconds, double pauseSeconds, String name, String mode, boolean replays,
                  double hlSpeed) {
        this.name = name;
        for (Map<String, Object> t : teamList) {
            teams.put((String) t.get("name"), t);
            order.add((String) t.get("name"));
        }
        this.meetings = meetings;
        this.minutes = minutes;
        this.speed = speed;
        this.hlSpeed = hlSpeed;
        this.mode = Playlist.MODES.contains(mode) ? mode : "full";
        this.replays = replays;
        this.executor = executor;
        this.clock = clock;
        this.simulate = simulate;
        this.statePath = statePath;
        this.lineupSeconds = lineupSeconds;
        this.pauseSeconds = pauseSeconds;
        this.season = 1;
        startSeason(seed);
        load();
        prepare(cur);
    }

    private void startSeason(long newSeed) {
        this.seed = newSeed;
        this.rounds = drawSchedule(order, meetings, seed);
        this.fixtures = new ArrayList<>();
        for (int r = 0; r < rounds.size(); r++) {
            for (String[] pair : rounds.get(r)) {
                int k = fixtures.size();
                fixtures.add(new Fixture(k, r + 1, pair[0], pair[1], seed * 1000 + k + 1));
            }
        }
        this.results = new ArrayList<>();
        for (int i = 0; i < fixtures.size(); i++) {
            results.add(null);
        }
        this.cur = 0;
        this.timelines = new HashMap<>();       // doar meciul curent si cel pregatit
        this.goalCache = new HashMap<>();
        this.cardCache = new HashMap<>();
        this.statsCache = new HashMap<>();
        this.seasonCache = null;
        this.pages = new HashMap<>();
        this.pending = new HashMap<>();
        this.phase = fixtures.isEmpty() ? "finished" : "lineups";
        this.phaseAt = clock.getAsDouble();
        this.pieces = new ArrayList<>();
        this.anchorPiece = 0;
        this.anchorOffset = 0.0;
        this.anchorWall = phaseAt;
    }

    // ------------------------------------------------------------------ salvare
    private Map<String, Object> identity() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seed", seed);
        out.put("meetings", (long) meetings);
        out.put("minutes", minutes);
        out.put("teams", new ArrayList<Object>(order));
        return out;
    }

    synchronized void save() {
        if (statePath == null) {
            return;
        }
        try {
            Path abs = statePath.toAbsolutePath();
            if (abs.getParent() != null) {
                Files.createDirectories(abs.getParent());
            }
            Map<String, Object> out = identity();
            out.put("season", (long) season);
            out.put("speed", speed);
            out.put("mode", mode);
            out.put("replays", replays);
            out.put("hl_speed", hlSpeed);
            out.put("results", results);
            Path tmp = Path.of(statePath.toString() + ".tmp");
            Files.writeString(tmp, Py.dumps(out, false), StandardCharsets.UTF_8);
            Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static long toLong(Object v) {
        if (v instanceof String s) {
            java.math.BigInteger b = Py.parseInt(s);
            if (b == null) {
                throw new IllegalArgumentException("invalid literal for int(): " + s);
            }
            return b.longValue();
        }
        if (v instanceof Double d) {
            return (long) (double) d;
        }
        return (long) Py.num(v);
    }

    private static double toDouble(Object v) {
        Double d = Py.toFloat(v);
        if (d == null) {
            throw new IllegalArgumentException("float() argument must be a string or a real number");
        }
        return d;
    }

    /** Reia sezonul salvat, daca e al aceleiasi ligi (aceleasi echipe, acelasi format). */
    @SuppressWarnings("unchecked")
    boolean load() {
        if (statePath == null || !Files.exists(statePath)) {
            return false;
        }
        Map<String, Object> saved;
        try {
            saved = (Map<String, Object>) Json.parse(Files.readString(statePath, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
        for (Map.Entry<String, Object> e : identity().entrySet()) {
            if (!e.getKey().equals("seed") && !Py.eq(saved.get(e.getKey()), e.getValue())) {
                return false;
            }
        }
        Object res = Py.get(saved, "results", List.of());
        if (((List<?>) res).size() != fixtures.size()) {
            return false;
        }
        if (!Py.eq(saved.get("seed"), seed)) {
            startSeason(toLong(saved.get("seed")));
        }
        season = (int) toLong(Py.get(saved, "season", 1L));
        speed = toDouble(Py.get(saved, "speed", speed));
        if (Playlist.MODES.contains(saved.get("mode"))) {
            mode = (String) saved.get("mode");
        }
        if (saved.get("replays") instanceof Boolean b) {
            replays = b;
        }
        hlSpeed = toDouble(Py.get(saved, "hl_speed", hlSpeed));
        results = new ArrayList<>((List<Object>) saved.get("results"));
        seasonCache = null;
        cur = results.size();
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) == null) {
                cur = i;
                break;
            }
        }
        if (cur >= fixtures.size()) {
            phase = "finished";
        }
        return true;
    }

    // ------------------------------------------------------------------ meciuri
    /** Porneste simularea meciului {@code index}, daca nu e deja facuta sau in lucru. */
    private void prepare(int index) {
        if (index >= fixtures.size() || timelines.containsKey(index) || pending.containsKey(index)
                || results.get(index) != null) {
            return;
        }
        Fixture fx = fixtures.get(index);
        Map<String, Object> home = teams.get(fx.home);
        Map<String, Object> away = teams.get(fx.away);
        long s = fx.seed;
        double m = minutes;
        pending.put(index, executor.submit(() -> simulate.play(home, away, s, m)));
    }

    private boolean ready(int index) {
        if (timelines.containsKey(index)) {
            return true;
        }
        Future<Map<String, Object>> future = pending.get(index);
        if (future == null || !future.isDone()) {
            return false;
        }
        pending.remove(index);
        try {
            timelines.put(index, future.get());
        } catch (ExecutionException | RuntimeException | InterruptedException exc) {
            // un meci stricat nu opreste liga: se rejoaca
            Throwable cause = exc instanceof ExecutionException && exc.getCause() != null ? exc.getCause() : exc;
            System.out.println(String.format("meciul %d nu s-a putut juca (%s); il rejoc", index + 1, message(cause)));
            System.out.flush();
            fixtures.get(index).seed += 7919;
            prepare(index);
            return false;
        }
        return true;
    }

    static String message(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private double duration(int index) {
        Map<String, Object> tl = timelines.get(index);
        return (((List<?>) tl.get("frames")).size() - 1) / Py.num(tl.get("hz"));
    }

    /** Viteza modului activ (meciul intreg si rezumatul au viteze separate). */
    double activeSpeed() {
        return mode.equals("highlights") ? hlSpeed : speed;
    }

    private List<Playlist.Piece> buildPieces() {
        return Playlist.playlist(timelines.get(cur), mode, replays, speed, hlSpeed);
    }

    /** (bucata curenta, pozitia afisata, progresul) ale meciului curent la momentul {@code now}. */
    private Playlist.Loc clockAt(double now) {
        if (phase.equals("fulltime")) {
            double end = timelines.containsKey(cur) ? duration(cur) : 0.0;
            return new Playlist.Loc(pieces.size(), end, end);
        }
        if (!phase.equals("live")) {
            return new Playlist.Loc(0, 0.0, 0.0);
        }
        return Playlist.locate(pieces, anchorPiece, anchorOffset, now - anchorWall);
    }

    /** Cea mai departata secunda de joc atinsa de transmisie (nu merge inapoi). */
    private double progress(double now) {
        return clockAt(now).progress();
    }

    /** Reface bucatile (alt mod, alta viteza, reluari) si continua de la progresul curent. */
    private void reanchor(double now) {
        if (!phase.equals("live")) {
            return;
        }
        double progress = progress(now);
        pieces = buildPieces();
        Playlist.Resume r = Playlist.resume(pieces, progress);
        anchorPiece = r.index();
        anchorOffset = r.offset();
        anchorWall = now;
    }

    synchronized void advance() {
        advance(clock.getAsDouble());
    }

    /** Muta transmisia la momentul {@code now}: porneste meciul, il incheie, trece la urmatorul. */
    synchronized void advance(double now) {
        while (true) {
            if (phase.equals("lineups")) {
                prepare(cur);
                if (!ready(cur) || now - phaseAt < lineupSeconds) {
                    return;
                }
                phase = "live";
                phaseAt = onTime(phaseAt + lineupSeconds, now);
                pieces = buildPieces();
                anchorPiece = 0;
                anchorOffset = 0.0;
                anchorWall = phaseAt;
                prepare(cur + 1);           // urmatorul se joaca cat se vede acesta
            } else if (phase.equals("live")) {
                double end = anchorWall + Playlist.totalSeconds(pieces, anchorPiece, anchorOffset);   // fluierul final
                if (end > now) {
                    return;
                }
                results.set(cur, resultOf(cur));
                phase = "fulltime";
                phaseAt = end;
                save();
            } else if (phase.equals("fulltime")) {
                if (now - phaseAt < pauseSeconds) {
                    return;
                }
                timelines.remove(cur);
                pages.remove(cur);
                goalCache.remove(cur);
                cardCache.remove(cur);
                statsCache.remove(cur);
                cur += 1;
                phaseAt = onTime(phaseAt + pauseSeconds, now);
                phase = cur < fixtures.size() ? "lineups" : "finished";
            } else {
                return;
            }
        }
    }

    /** Momentul la care incepe faza urmatoare: cel planificat, sau acum daca serverul a ramas in urma. */
    static double onTime(double planned, double now) {
        return now - planned <= 1.0 ? planned : now;
    }

    private List<Map<String, Object>> goalsOf(int index) {
        return goalCache.computeIfAbsent(index, i -> matchGoals(timelines.get(i)));
    }

    private List<Map<String, Object>> cardsOf(int index) {
        return cardCache.computeIfAbsent(index, i -> matchCards(timelines.get(i)));
    }

    /** Statisticile si notele celor 22, o data pe meci. */
    private Map<String, Object> statsOf(int index) {
        return statsCache.computeIfAbsent(index, i -> SeasonStats.fromTimeline(timelines.get(i)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resultOf(int index) {
        Map<String, Object> tl = timelines.get(index);
        Map<String, Object> summary = (Map<String, Object>) tl.get("summary");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", new ArrayList<Object>((List<Object>) summary.get("score")));
        out.put("goals", goalsOf(index));
        out.put("cards", cardsOf(index));
        List<Object> stats = new ArrayList<>();
        for (Object t : (List<?>) summary.get("teams")) {
            Map<String, Object> s = new LinkedHashMap<>();
            for (String k : TEAM_STATS) {
                s.put(k, ((Map<?, ?>) t).get(k));
            }
            stats.add(s);
        }
        out.put("stats", stats);
        out.put("players", SeasonStats.pack(statsOf(index)));
        return out;
    }

    private static Map<?, ?> res(Object r) {
        return (Map<?, ?>) r;
    }

    private Object colorOf(String team) {
        return Py.get(teams.get(team), "color", "#888888");
    }

    // ------------------------------------------------------------------ fisa meciului
    /** Fisa unui meci incheiat (din rezultatul salvat), sau null daca nu e incheiat ori nu are date. */
    synchronized Map<String, Object> matchSheet(int index, Double endsIn) {
        Map<?, ?> r = 0 <= index && index < results.size() ? res(results.get(index)) : null;
        if (!Py.truthy(r) || !Py.truthy(r.get("players"))) {
            return null;
        }
        Fixture fx = fixtures.get(index);
        Map<?, ?> stats = (Map<?, ?>) SeasonStats.unpack(r.get("players"), null);
        List<?> sides = Py.truthy(stats.get("teams")) ? (List<?>) stats.get("teams") : List.of();
        List<?> teamStats = Py.truthy(r.get("stats")) ? (List<?>) r.get("stats") : List.of();
        List<Map<String, Object>> teamsOut = new ArrayList<>();
        String[] names = {fx.home, fx.away};
        for (int side = 0; side < 2; side++) {
            Object ts = side < teamStats.size() && Py.truthy(teamStats.get(side)) ? teamStats.get(side) : Map.of();
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", names[side]);
            t.put("color", colorOf(names[side]));
            t.put("stats", new LinkedHashMap<>((Map<?, ?>) ts));
            t.put("players", side < sides.size() ? ((Map<?, ?>) sides.get(side)).get("players") : List.of());
            teamsOut.add(t);
        }
        Map<String, Object> motm = null;
        for (int side = 0; side < teamsOut.size(); side++) {
            Map<String, Object> team = teamsOut.get(side);
            for (Object lo : (List<?>) team.get("players")) {
                Map<?, ?> line = (Map<?, ?>) lo;
                if (Py.truthy(line.get("motm")) && motm == null) {
                    motm = new LinkedHashMap<>();
                    motm.put("uid", line.get("uid"));
                    motm.put("name", line.get("name"));
                    motm.put("team", (long) side);
                    motm.put("teamName", team.get("name"));
                    motm.put("rating", line.get("rating"));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index", (long) index);
        out.put("number", (long) index + 1);
        out.put("round", (long) fx.round);
        out.put("score", new ArrayList<Object>((List<?>) r.get("score")));
        out.put("goals", describeGoals(Py.truthy(r.get("goals")) ? (List<?>) r.get("goals") : List.of()));
        out.put("teams", teamsOut);
        out.put("motm", motm);
        out.put("columns", sheetColumns());
        out.put("endsIn", endsIn == null ? null : Py.round(endsIn, 1));
        return out;
    }

    private static Map<String, Object> error(String message, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", message);
        out.put("reason", reason);
        return out;
    }

    /** Raspunsul adresei /api/sheet/N. */
    synchronized Reply sheetResponse(int index) {
        advance();
        if (!(0 <= index && index < fixtures.size())) {
            return new Reply(404, error("Meci necunoscut.", "unknown"), null);
        }
        if (results.get(index) == null) {
            return new Reply(404, error("Meciul nu s-a jucat încă.", "not-played"), null);
        }
        Map<String, Object> sheet = matchSheet(index, null);
        if (sheet == null) {
            return new Reply(404, error("Pentru meciul acesta nu există date pe jucători "
                    + "(a fost jucat înainte de fișele de meci).", "no-data"), null);
        }
        return new Reply(200, sheet, null);
    }

    /** Tabelele de sezon din meciurile incheiate care au fisa, primii SEASON_TOP. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> seasonTables() {
        int done = 0;
        for (Object r : results) {
            if (r != null) {
                done++;
            }
        }
        if (seasonCache == null || (Integer) seasonCache[0] != done) {
            List<Map<String, Object>> matches = new ArrayList<>();
            for (int i = 0; i < Math.min(fixtures.size(), results.size()); i++) {
                Map<?, ?> r = res(results.get(i));
                if (r != null && Py.truthy(r.get("players"))) {
                    Fixture fx = fixtures.get(i);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("home", fx.home);
                    m.put("away", fx.away);
                    m.put("round", (long) fx.round);
                    m.put("stats", r.get("players"));
                    matches.add(m);
                }
            }
            Map<String, List<Map<String, Object>>> tables = SeasonStats.seasonTables(matches);
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : List.of("avg_rating", "max_rating", "clean_sheets", "motm")) {
                List<Map<String, Object>> all = tables.getOrDefault(key, List.of());
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Map<String, Object> row : all.subList(0, Math.min(SEASON_TOP, all.size()))) {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    Object team = row.get("team");
                    Map<String, Object> t = team instanceof String s ? teams.get(s) : null;
                    copy.put("color", Py.get(Py.truthy(t) ? t : Map.of(), "color", "#888888"));
                    rows.add(copy);
                }
                out.put(key, rows);
            }
            seasonCache = new Object[] {done, out};
        }
        return (Map<String, Object>) seasonCache[1];
    }

    // ------------------------------------------------------------------ comenzi
    synchronized void setSpeed(double value) {
        double now = clock.getAsDouble();
        advance(now);
        double s = Py.max(0.25, Py.min(64.0, value));
        if (mode.equals("highlights")) {
            hlSpeed = s;
        } else {
            speed = s;
        }
        reanchor(now);
        save();
    }

    /** 'full' (meciul intreg) sau 'highlights' (rezumatul); meciul continua de la progres. */
    synchronized void setMode(Object value) {
        if (!(value instanceof String s) || !Playlist.MODES.contains(s)) {
            throw new IllegalArgumentException(String.valueOf(value));
        }
        double now = clock.getAsDouble();
        advance(now);
        mode = s;
        reanchor(now);
        save();
    }

    synchronized void setReplays(boolean on) {
        double now = clock.getAsDouble();
        advance(now);
        replays = on;
        reanchor(now);
        save();
    }

    /** Sare la sfarsitul fazei curente: porneste meciul, il duce la final sau trece de pauza. */
    synchronized void skip() {
        double now = clock.getAsDouble();
        advance(now);
        if (phase.equals("live")) {
            anchorPiece = pieces.size();
            anchorOffset = 0.0;
            anchorWall = now;
        } else if (phase.equals("lineups")) {
            phaseAt = now - lineupSeconds;
        } else if (phase.equals("fulltime")) {
            phaseAt = now - pauseSeconds;
        }
        advance(now);
    }

    synchronized void newSeason() {
        season += 1;
        startSeason(seed + 1);
        save();
        prepare(cur);
    }

    // ------------------------------------------------------------------ ce se vede
    /** Minutul afisat (1..90), oricat de lung ar fi meciul simulat. */
    long minute(double seconds) {
        double v = seconds / (minutes * 60.0) * 90.0;
        long m = (long) v + 1;
        return Math.max(1, Math.min(90, m));
    }

    /** Golurile meciului curent pe care transmisia le-a aratat deja. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> visibleGoals(double now) {
        if (phase.equals("finished") || phase.equals("lineups")) {
            return List.of();
        }
        if (phase.equals("fulltime")) {
            return (List<Map<String, Object>>) res(results.get(cur)).get("goals");
        }
        double progress = progress(now);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : goalsOf(cur)) {
            if (Py.num(g.get("t")) <= progress) {
                out.add(g);
            }
        }
        return out;
    }

    /** Cartonasele meciului curent aratate deja (meciurile salvate inainte de cartonase: []). */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> visibleCards(double now) {
        if (phase.equals("finished") || phase.equals("lineups")) {
            return List.of();
        }
        if (phase.equals("fulltime")) {
            Map<?, ?> r = res(results.get(cur));
            Object cards = r == null ? null : r.get("cards");
            return Py.truthy(cards) ? (List<Map<String, Object>>) cards : List.of();
        }
        double progress = progress(now);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : cardsOf(cur)) {
            if (Py.num(c.get("t")) <= progress) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean isHome(Object team) {
        return Py.eq(team, 0L);
    }

    private List<Map<String, Object>> top(Map<List<Object>, Long> count, Map<List<Object>, Long> pens, int limit) {
        List<Map.Entry<List<Object>, Long>> items = new ArrayList<>(count.entrySet());
        items.sort((x, y) -> {
            int c = Long.compare(y.getValue(), x.getValue());
            return c != 0 ? c : Py.cmp((String) x.getKey().get(0), (String) y.getKey().get(0));
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<List<Object>, Long> e : items.subList(0, Math.min(Math.max(0, limit), items.size()))) {
            String team = (String) e.getKey().get(1);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.getKey().get(0));
            row.put("team", team);
            row.put("color", Py.get(teams.get(team), "color", "#888888"));
            row.put("count", e.getValue());
            if (pens != null) {
                row.put("pens", pens.getOrDefault(e.getKey(), 0L));
            }
            out.add(row);
        }
        return out;
    }

    /** Jucatorii cu cele mai multe cartonase de culoarea {@code card}, cu meciul in direct inclus. */
    private List<Map<String, Object>> cardLeaders(double now, String card, int limit) {
        Map<List<Object>, Long> count = new LinkedHashMap<>();
        List<Object[]> lists = new ArrayList<>();
        for (int i = 0; i < Math.min(fixtures.size(), results.size()); i++) {
            Map<?, ?> r = res(results.get(i));
            if (r != null) {
                lists.add(new Object[] {fixtures.get(i), Py.truthy(r.get("cards")) ? r.get("cards") : List.of()});
            }
        }
        if (phase.equals("live")) {
            lists.add(new Object[] {fixtures.get(cur), visibleCards(now)});
        }
        for (Object[] fc : lists) {
            Fixture fx = (Fixture) fc[0];
            for (Object co : (List<?>) fc[1]) {
                Map<?, ?> c = (Map<?, ?>) co;
                if (Py.eq(Py.get(c, "card", "yellow"), card) && Py.truthy(c.get("name"))) {
                    List<Object> k = Arrays.asList(c.get("name"), isHome(c.get("team")) ? fx.home : fx.away);
                    count.merge(k, 1L, Long::sum);
                }
            }
        }
        return top(count, null, limit);
    }

    /** Meciurile care intra in clasament: (meci, scor, goluri, in direct?). */
    private List<Played> played(double now) {
        List<Played> out = new ArrayList<>();
        for (int i = 0; i < Math.min(fixtures.size(), results.size()); i++) {
            Map<?, ?> r = res(results.get(i));
            if (r != null) {
                out.add(new Played(fixtures.get(i), (List<?>) r.get("score"), (List<?>) r.get("goals"), false));
            }
        }
        if (phase.equals("live")) {
            List<Map<String, Object>> goals = visibleGoals(now);
            out.add(new Played(fixtures.get(cur), liveScore(goals), goals, true));
        }
        return out;
    }

    private static List<Object> liveScore(List<?> goals) {
        long h = 0;
        long a = 0;
        for (Object g : goals) {
            Object team = ((Map<?, ?>) g).get("team");
            if (Py.eq(team, 0L)) {
                h++;
            }
            if (Py.eq(team, 1L)) {
                a++;
            }
        }
        return new ArrayList<>(List.of(h, a));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> standings(double now) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (String n : order) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("team", n);
            row.put("color", colorOf(n));
            for (String k : List.of("p", "w", "d", "l", "gf", "ga", "pts")) {
                row.put(k, 0L);
            }
            row.put("form", new ArrayList<Object>());
            row.put("live", false);
            rows.put(n, row);
        }
        for (Played pl : played(now)) {
            long s0 = (long) Py.num(pl.score().get(0));
            long s1 = (long) Py.num(pl.score().get(1));
            Object[][] sides = {{pl.fx().home, s0, s1}, {pl.fx().away, s1, s0}};
            for (Object[] side : sides) {
                Map<String, Object> row = rows.get((String) side[0]);
                long gf = (Long) side[1];
                long ga = (Long) side[2];
                row.put("p", (Long) row.get("p") + 1);
                row.put("gf", (Long) row.get("gf") + gf);
                row.put("ga", (Long) row.get("ga") + ga);
                String outcome = gf > ga ? "w" : gf == ga ? "d" : "l";
                row.put(outcome, (Long) row.get(outcome) + 1);
                row.put("pts", (Long) row.get("pts") + (outcome.equals("w") ? 3 : outcome.equals("d") ? 1 : 0));
                ((List<Object>) row.get("form")).add(outcome.toUpperCase());
                row.put("live", (Boolean) row.get("live") || pl.live());
            }
        }
        List<Map<String, Object>> table = new ArrayList<>(rows.values());
        table.sort((x, y) -> {
            long px = (Long) x.get("pts");
            long py = (Long) y.get("pts");
            if (px != py) {
                return Long.compare(py, px);
            }
            long dx = (Long) x.get("gf") - (Long) x.get("ga");
            long dy = (Long) y.get("gf") - (Long) y.get("ga");
            if (dx != dy) {
                return Long.compare(dy, dx);
            }
            long fx = (Long) x.get("gf");
            long fy = (Long) y.get("gf");
            if (fx != fy) {
                return Long.compare(fy, fx);
            }
            return Py.cmp((String) x.get("team"), (String) y.get("team"));
        });
        for (int i = 0; i < table.size(); i++) {
            Map<String, Object> row = table.get(i);
            row.put("pos", (long) i + 1);
            row.put("gd", (Long) row.get("gf") - (Long) row.get("ga"));
            List<Object> form = (List<Object>) row.get("form");
            row.put("form", new ArrayList<>(form.subList(Math.max(0, form.size() - 5), form.size())));
        }
        return table;
    }

    /** Primii la goluri ({@code key}='scorer') sau la pase decisive ({@code key}='assist'). */
    private List<Map<String, Object>> leaders(double now, String key, int limit) {
        Map<List<Object>, Long> count = new LinkedHashMap<>();
        Map<List<Object>, Long> pens = new HashMap<>();     // dintre ele, golurile din penalty
        for (Played pl : played(now)) {
            for (Object go : pl.goals()) {
                Map<?, ?> g = (Map<?, ?>) go;
                if (Py.truthy(g.get(key))) {
                    List<Object> who = Arrays.asList(g.get(key), isHome(g.get("team")) ? pl.fx().home : pl.fx().away);
                    count.merge(who, 1L, Long::sum);
                    if (key.equals("scorer") && "penalty".equals(g.get("how"))) {
                        pens.merge(who, 1L, Long::sum);
                    }
                }
            }
        }
        return top(count, pens, limit);
    }

    private Map<String, Object> sheet(String teamName) {
        Map<String, Object> team = teams.get(teamName);
        List<?> players = (List<?>) team.get("players");
        // capitanul, dupa regula motorului: cel mai mare leadership, la egalitate primul din lista
        int captain = -1;
        double best = 0;
        for (int i = 0; i < players.size(); i++) {
            Map<?, ?> p = (Map<?, ?>) players.get(i);
            Object ratings = Py.get(p, "ratings", Map.of());
            double lead = Py.num(Py.get((Map<?, ?>) ratings, "leadership", 0L));
            if (captain < 0 || lead > best) {
                captain = i;
                best = lead;
            }
        }
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            Map<?, ?> p = (Map<?, ?>) players.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("number", p.get("number"));
            row.put("name", p.get("name"));
            row.put("position", p.get("position"));
            row.put("captain", i == captain);
            list.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", teamName);
        out.put("color", Py.get(team, "color", "#888888"));
        out.put("formation", team.get("formation"));
        out.put("coach", team.get("coach"));
        out.put("players", list);
        return out;
    }

    private List<Map<String, Object>> describeGoals(List<?> goals) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object go : goals) {
            Map<?, ?> g = (Map<?, ?>) go;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("minute", minute(Py.num(g.get("t"))));
            d.put("team", g.get("team"));
            d.put("scorer", g.get("scorer"));
            d.put("assist", g.get("assist"));
            d.put("own", g.get("own"));
            d.put("how", g.get("how"));
            out.add(d);
        }
        return out;
    }

    synchronized Map<String, Object> state() {
        return state(clock.getAsDouble());
    }

    /** Tot ce arata paginile, la momentul {@code now} (fara nimic din viitorul meciului). */
    @SuppressWarnings("unchecked")
    synchronized Map<String, Object> state(double now) {
        advance(now);
        long done = 0;
        for (Object r : results) {
            if (r != null) {
                done++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("league", name);
        out.put("season", (long) season);
        out.put("phase", phase);
        out.put("speed", activeSpeed());
        out.put("speeds", new ArrayList<Object>(SPEEDS));
        out.put("played", done);
        out.put("mode", mode);
        out.put("modes", new ArrayList<Object>(Playlist.MODES));
        out.put("replays", replays);
        out.put("total", (long) fixtures.size());
        out.put("rounds", (long) rounds.size());
        out.put("pauseSeconds", pauseSeconds);
        Map<String, Object> match = null;
        if (!phase.equals("finished")) {
            Fixture fx = fixtures.get(cur);
            List<Map<String, Object>> goals = visibleGoals(now);
            boolean ready = timelines.containsKey(cur);
            Playlist.Loc loc = clockAt(now);
            int cut = loc.index();
            Playlist.Piece piece = phase.equals("live") && cut < pieces.size() ? pieces.get(cut) : null;
            double elapsed = now - phaseAt;
            Double ends = null;
            if (phase.equals("lineups") && ready) {
                ends = Py.max(0.0, lineupSeconds - elapsed);
            } else if (phase.equals("fulltime")) {
                ends = Py.max(0.0, pauseSeconds - elapsed);
            }
            match = new LinkedHashMap<>();
            match.put("index", (long) fx.index);
            match.put("number", (long) fx.index + 1);
            match.put("round", (long) fx.round);
            match.put("home", sheet(fx.home));
            match.put("away", sheet(fx.away));
            match.put("stadium", teams.get(fx.home).get("stadium"));
            match.put("ready", ready);
            match.put("pos", Py.round(loc.pos(), 2));
            match.put("duration", ready ? Py.round(duration(cur), 2) : null);
            match.put("live", Py.round(loc.progress(), 2));
            match.put("replay", piece != null && piece.kind.equals("replay"));
            match.put("label", piece != null ? piece.label : null);
            match.put("cut", (long) cut);
            match.put("rate", piece != null ? piece.rate : activeSpeed());
            match.put("minute", !phase.equals("lineups") ? minute(loc.progress()) : 0L);
            match.put("score", liveScore(goals));
            match.put("goals", describeGoals(goals));
            match.put("endsIn", ends == null ? null : Py.round(ends, 1));
            if (mode.equals("highlights") && piece != null && piece.segment != null) {
                int total = -1;
                for (Playlist.Piece p : pieces) {
                    if (p.segment != null) {
                        total = Math.max(total, p.segment);
                    }
                }
                Map<String, Object> seg = new LinkedHashMap<>();
                seg.put("index", (long) piece.segment);
                seg.put("total", (long) total + 1);
                match.put("segment", seg);
            }
            out.put("match", match);
            int nxt = cur + 1;
            if (nxt < fixtures.size()) {
                Fixture n = fixtures.get(nxt);
                Map<String, Object> next = new LinkedHashMap<>();
                next.put("round", (long) n.round);
                next.put("home", n.home);
                next.put("away", n.away);
                out.put("next", next);
            }
        }
        List<Map<String, Object>> table = standings(now);
        out.put("standings", table);
        out.put("scorers", leaders(now, "scorer", 10));
        out.put("assists", leaders(now, "assist", 10));
        out.put("yellowCards", cardLeaders(now, "yellow", 8));
        out.put("seasonTables", seasonTables());
        if (phase.equals("fulltime")) {
            Object e = match.get("endsIn");
            Map<String, Object> sheet = matchSheet(cur, e == null ? null : (Double) e);
            if (sheet != null) {
                out.put("sheet", sheet);
            }
        }
        Integer live = phase.equals("live") ? cur : null;
        List<Object> fixturesOut = new ArrayList<>();
        for (int r = 0; r < rounds.size(); r++) {
            List<Object> matches = new ArrayList<>();
            for (Fixture fx : fixtures) {
                if (fx.round != r + 1) {
                    continue;
                }
                Map<?, ?> rr = res(results.get(fx.index));
                Object score = Py.truthy(rr) ? rr.get("score") : null;
                boolean isLive = live != null && fx.index == live;
                if (isLive) {
                    score = match.get("score");
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", (long) fx.index);
                m.put("home", fx.home);
                m.put("away", fx.away);
                m.put("score", score);
                m.put("hasSheet", Py.truthy(rr) && Py.truthy(rr.get("players")));
                m.put("live", isLive);
                m.put("current", !phase.equals("finished") && fx.index == cur);
                matches.add(m);
            }
            Map<String, Object> round = new LinkedHashMap<>();
            round.put("round", (long) r + 1);
            round.put("matches", matches);
            fixturesOut.add(round);
        }
        out.put("fixtures", fixturesOut);
        if (phase.equals("finished") && !table.isEmpty()) {
            out.put("champion", table.get(0).get("team"));
        }
        return out;
    }

    // ------------------------------------------------------------------ meci de revazut / salvat
    /**
     * Cronologia unui meci incheiat, pentru revazut sau salvat: (cod HTTP, cronologie sau mesaj).
     * Meciul curent dupa fluierul final vine din memorie; unul mai vechi se rejoaca cu aceeasi
     * samanta (in afara lacatului) si se tine printre ultimele REPLAY_CACHE.
     */
    Reply replayTimeline(int index) {
        List<Object> key;
        Fixture fx;
        Map<String, Object> home;
        Map<String, Object> away;
        Object resultScore;
        synchronized (this) {
            advance();
            if (!(0 <= index && index < fixtures.size())) {
                return new Reply(404, "Meci necunoscut.", null);
            }
            if (index == cur && (phase.equals("lineups") || phase.equals("live"))) {
                return new Reply(409, "Meciul e în curs: se poate revedea după fluierul final.", null);
            }
            Map<?, ?> result = res(results.get(index));
            if (result == null) {
                return new Reply(409, "Meciul nu s-a jucat încă.", null);
            }
            if (index == cur && phase.equals("fulltime") && timelines.containsKey(index)) {
                return new Reply(200, timelines.get(index), null);
            }
            key = Arrays.asList((long) season, seed, (long) index);
            if (replayCache.containsKey(key)) {
                Map<String, Object> tl = replayCache.remove(key);
                replayCache.put(key, tl);
                return new Reply(200, tl, null);
            }
            Fixture f = fixtures.get(index);
            fx = new Fixture(f.index, f.round, f.home, f.away, f.seed);
            home = teams.get(fx.home);
            away = teams.get(fx.away);
            resultScore = result.get("score");
        }
        Map<String, Object> timeline;
        try {
            timeline = simulate.play(home, away, fx.seed, minutes);
        } catch (RuntimeException exc) {
            return new Reply(409, "Meciul nu a putut fi refăcut (" + message(exc) + ").", null);
        }
        Object score = ((Map<?, ?>) timeline.get("summary")).get("score");
        if (!Py.eq(new ArrayList<Object>((List<?>) score), new ArrayList<Object>((List<?>) resultScore))) {
            return new Reply(409, "Meciul nu mai poate fi refăcut: motorul s-a schimbat de când a fost jucat.", null);
        }
        synchronized (this) {
            replayCache.put(key, timeline);
            Iterator<List<Object>> it = replayCache.keySet().iterator();
            while (replayCache.size() > REPLAY_CACHE && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        return new Reply(200, timeline, null);
    }

    /** Pagina de afisaj de sine statatoare a meciului jucat {@code index}, pornita pe rezumat. */
    @SuppressWarnings("unchecked")
    Reply replayPage(int index, Function<Map<String, Object>, String> viewerHtml) {
        Reply r = replayTimeline(index);
        if (r.code() != 200) {
            return r;
        }
        Fixture fx;
        List<?> score;
        synchronized (this) {
            fx = fixtures.get(index);
            score = (List<?>) res(results.get(index)).get("score");
        }
        String file = String.format("etapa-%02d_%s_%d-%d_%s.html", fx.round, asciiSlug(fx.home),
                (long) Py.num(score.get(0)), (long) Py.num(score.get(1)), asciiSlug(fx.away));
        Map<String, Object> tl = new LinkedHashMap<>((Map<String, Object>) r.body());
        tl.put("start", "highlights");
        return new Reply(200, viewerHtml.apply(tl), file);
    }

    /** Pagina de afisaj a meciului curent (doar el poate fi cerut); se construieste o data. */
    synchronized byte[] matchPage(int index, Function<Map<String, Object>, String> render, Object stamp) {
        if (phase.equals("finished") || index != cur || !timelines.containsKey(index)) {
            return null;
        }
        Object[] cached = pages.get(index);
        if (cached == null || !java.util.Objects.equals(cached[0], stamp)) {
            cached = new Object[] {stamp, render.apply(timelines.get(index)).getBytes(StandardCharsets.UTF_8)};
            pages.put(index, cached);
        }
        return (byte[]) cached[1];
    }

    /** Numele unei echipe pentru un nume de fisier: ASCII, fara diacritice si fara spatii. */
    static String asciiSlug(String text) {
        String norm = java.text.Normalizer.normalize(String.valueOf(text), java.text.Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder();
        boolean dash = false;
        for (int i = 0; i < norm.length(); i++) {
            char ch = norm.charAt(i);
            if (ch >= 128) {
                continue;
            }
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) {
                out.append(ch);
                dash = false;
            } else if (!dash && out.length() > 0) {
                out.append('-');
                dash = true;
            }
        }
        int a = 0;
        int b = out.length();
        while (a < b && out.charAt(a) == '-') {
            a++;
        }
        while (b > a && out.charAt(b - 1) == '-') {
            b--;
        }
        String s = out.substring(a, b);
        return s.isEmpty() ? "echipa" : s;
    }

    // accesorii pentru server
    synchronized String phase() {
        return phase;
    }

    synchronized int cur() {
        return cur;
    }

    synchronized int total() {
        return fixtures.size();
    }
}
