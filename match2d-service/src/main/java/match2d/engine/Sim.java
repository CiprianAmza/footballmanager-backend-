package match2d.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Motorul de meci 2D: joaca fazele din ratinguri, fara faze scrise dinainte.
 *
 * <p>Un pas de simulare = 0.1 s. La fiecare pas: (1) mingea zboara sau sta la purtator; se rezolva
 * preluari, interceptii, iesiri din teren; (2) purtatorul decide (sut, pasa la picior, pasa in
 * alergare, conducere pana in X) si poate fi deposedat; (3) ceilalti isi aleg punctul optim din
 * zona lor si merg spre el; (4) se inregistreaza un cadru in cronologie.
 *
 * <p>Tot ce e aleator vine dintr-un singur generator pornit din seed, deci acelasi seed da acelasi
 * meci. Motorul nu stie nimic despre afisaj: intoarce doar cronologia. Portat din motorul Python
 * (match-engine-2d/engine/sim.py), cu care da acelasi meci pas cu pas.
 */
public final class Sim {
    static final double DT = 0.1;            // pasul de simulare (s)
    static final double A_GROUND = 1.8;      // franarea mingii pe iarba (m/s^2)
    static final double CTRL_R = 1.1;        // raza in care un jucator poate prelua mingea (m)
    static final double TACKLE_R = 1.5;      // raza de deposedare (m)
    static final double TURNOVER = 0.008;    // cat "costa" pierderea posesiei, peste pericolul creat
    static final int ENERGY_EVERY = 5;       // la cati pasi se recalculeaza energia (0.5 s)
    static final int ENERGY_LOG = 600;       // la cati pasi se noteaza energia in cronologie (60 s)
    static final double FAST_SPEED = 5.5;    // de la ce viteza alergarea e "de mare intensitate"
    static final int DEAD = 0, LIVE = 1, OVER = 2;
    /**
     * {@code -Dmatch2d.legacy=true}: joaca exact ca motorul Python din care a fost portat (golul
     * fara sut ramane fara marcator, parada respinsa poate intra in poarta, pasa decisiva doar
     * dintr-o pasa primita direct). Folosit de uneltele care compara cele doua motoare.
     */
    static final boolean LEGACY = Boolean.getBoolean("match2d.legacy");

    /** {@link #LEGACY}, pentru serviciu (alt pachet). */
    public static boolean legacy() {
        return LEGACY;
    }

    /** versiunea motorului: cheia {@code version} a cronologiei (si {@code engineVersion} in serviciu). */
    public static final int VERSION = 2;
    private static final int PLAYS = 8;
    static final int R_NONE = 0, R_PRESS = 1, R_RECV = 2, R_INTERCEPT = 3, R_CHASE = 4, R_RESTART = 5, R_SAVE = 6;
    private static final char[] ROLE_CH = {'.', 'p', 'r', 'i', 'h', 's', 's'};

    /** Pasa aflata in zbor. */
    static final class Pending {
        Player kicker;
        Player recv;
        boolean through;
        boolean loft;
        boolean offside;
        double ox;
        double oy;
        String key;
        /** felul mingii inalte: 0 = pasa obisnuita, 1 = centrare, 2 = corner, 3 = lovitura libera centrata. */
        int sub;
        /** adversarul care il tinea pe primitor cand a plecat pasa (cel mai apropiat, in raza). */
        Player marker;
    }

    /** Sutul aflat in zbor (rezultatul e deja calculat la lovire). */
    static final class ShotState {
        static final int GOAL = 0, MISS = 1, BLOCK = 2, SAVE = 3;
        Player shooter;
        Player gk;
        Player blocker;
        Player assist;
        int outcome;
        double zEnd;
        double len;
        double frac;
        double gx;
        double gy;
        double tEnd;
        boolean caught;
        /** 0 = sut din joc, 1 = penalty, 2 = lovitura libera directa, 3 = lovitura de cap. */
        int setPiece;
        /** sutul din lovitura libera a ramas in zid. */
        boolean wall;
    }

    final long seed;
    final Mt rng;
    /** generator separat pentru estimari (sansa de gol a unui sut): nu atinge sirul meciului. */
    final Mt xrng;
    final double minutes;
    final Team[] teams;
    final Player[] all = new Player[22];
    final Ball ball = new Ball();
    double t = 0.0;
    int tickNo = 0;
    int half = 1;
    final double halfLen;
    int state = DEAD;
    Team poss;
    final Grid[] grids = {new Grid(), new Grid()};     // grids[i] = costul creat de jucatorii echipei i
    double gridAt = 0.0;
    double rolesAt = 0.0;
    double runsAt = 0.0;
    Pending pending;
    ShotState shot;
    boolean gkHold;
    double noTackleUntil = 0.0;
    String restartKind;         // tipul repunerii pentru prima decizie de dupa
    String rkind;
    Player taker;
    double restartAt = 0.0;
    /** felul loviturii libere de acum ("shot", "cross", "play"), sau null (doar pe drumul nou). */
    String fkKind;
    /** locurile jucatorilor la faza fixa de acum, sau null (vezi SetPiece). */
    SetPiece sp;
    /** cum s-a marcat golul care urmeaza ("penalty", "free_kick"), pus de finishShot. */
    String goalHow;
    /** felul si momentul ultimei faze fixe executate (pentru campul "after" al golului). */
    String lastSpKind;
    double lastSpT = -99.0;
    final int recEvery;
    final boolean keepFrames;
    int frameNo = 0;
    final List<int[]> frames = new ArrayList<>();
    final List<Integer> holders = new ArrayList<>();
    final List<String> rolesLog = new ArrayList<>();
    final List<Map<String, Object>> events = new ArrayList<>();
    final List<Map<String, Object>> halves = new ArrayList<>();
    long zoneOut = 0;
    long zoneN = 0;
    int dijkstraRuns = 0;
    int dijkstraDetours = 0;
    /** pase: tip -> [incercari, suma probabilitatilor estimate, reusite]. */
    final Map<String, double[]> calib = new TreeMap<>();
    final List<int[]> energyLog = new ArrayList<>();

    /** lungimea unei reprize de prelungiri (s): 15 minute, oricare ar fi {@code minutes}. */
    static final double ET_HALF = 900.0;
    /**
     * Meci eliminatoriu ({@link #knockout}): golurile cu care intra fiecare echipa din prima mansa,
     * in orientarea acestui meci; null = meci obisnuit (fara prelungiri, fara departajare).
     */
    int[] koAggregate;
    /** scorul dupa 90 de minute (pus la finalul timpului regulamentar, doar la meciul eliminatoriu). */
    int[] score90;
    boolean extraTime;
    /** dupa prelungiri e tot egalitate: departajarea se joaca dupa bucla, in {@link #run}. */
    boolean shootoutDue;
    /** loviturile de departajare (vezi {@link Shootout}), sau null daca nu s-a ajuns la ele. */
    List<Map<String, Object>> shootout;
    int[] penalties;

    /** scorul tinta (modul condus, doar pe drumul nou), sau null: vezi {@link #guideGoal}. */
    Target target;
    /** incercarea nu a atins tinta (la finalul unei perioade lipseau goluri, sau departajarea n-a iesit). */
    boolean targetFailed;
    /** samanta generatorului departajarii in modul tinta (Long.MIN_VALUE = nu s-a jucat). */
    long shootoutSeed = Long.MIN_VALUE;
    // perioada condusa de acum: cele 90 de minute sau prelungirile
    private double perStart;
    private double perEnd;
    private final double[] perXg0 = new double[2];
    private final int[] perGoals0 = new int[2];
    /** ritmul de pornire al golurilor naturale, pe echipa, in 90 de minute (vezi {@link #guideGoal}). */
    static final double TARGET_PRIOR_GOALS = 2.3;
    /** cate secunde de joc cantareste ritmul de pornire fata de cel masurat. */
    static final double TARGET_PRIOR_T = 1200.0;

    // ultimii jucatori care au jucat mingea cu voie (control, pasa, sut, cap, deposedare), in ordine
    private final Player[] playWho = new Player[PLAYS];
    private int playN = 0;

    // locul in care functiile de pozitionare isi lasa rezultatul (x, y)
    double ox;
    double oy;

    public Sim(Map<String, Object> home, Map<String, Object> away, long seed, double minutes,
               int recordHz, boolean keepFrames) {
        this.seed = seed;
        this.rng = new Mt(seed);
        this.xrng = new Mt(seed * 7919 + 104729);
        this.minutes = minutes;
        this.teams = new Team[] {new Team(0, home), new Team(1, away)};
        for (int i = 0; i < 11; i++) {
            all[i] = teams[0].players[i];
            all[11 + i] = teams[1].players[i];
        }
        this.halfLen = minutes * 30.0;
        this.poss = teams[0];
        this.recEvery = Math.max(1, (int) Math.rint(1.0 / (recordHz * DT)));
        this.keepFrames = keepFrames;
        halves.add(halfEntry(0, 1, -1));
        placeKickoff(teams[0]);
        setRestart("kickoff", teams[0], Geo.L / 2, Geo.W / 2, 1.0);
    }

    /**
     * Face din meci unul eliminatoriu: {@code aggregate} = golurile din prima mansa ale gazdelor si
     * oaspetilor acestui meci ({0, 0} la mansa unica). Dupa 90 de minute, la egalitate (cu tot cu
     * {@code aggregate}) urmeaza prelungiri si, daca e tot egal, lovituri de departajare. Se
     * apeleaza inainte de {@link #run}; primele 90 de minute ies la fel ca fara el.
     */
    public void knockout(int[] aggregate) {
        if (aggregate == null || aggregate.length != 2 || aggregate[0] < 0 || aggregate[1] < 0) {
            throw new IllegalArgumentException("knockout.aggregate trebuie sa fie [gazde, oaspeti], numere >= 0");
        }
        koAggregate = aggregate.clone();
    }

    /**
     * Citeste campul {@code knockout} al unei cereri: null daca lipseste, altfel aggregate-ul
     * ({0, 0} daca {@code aggregate} lipseste). Forma gresita: IllegalArgumentException.
     */
    public static int[] parseKnockout(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("\"knockout\" trebuie sa fie un obiect {\"aggregate\": [gazde, oaspeti]}");
        }
        Object agg = map.get("aggregate");
        if (agg == null) {
            return new int[] {0, 0};
        }
        if (!(agg instanceof List<?> list) || list.size() != 2 || !(list.get(0) instanceof Number a)
                || !(list.get(1) instanceof Number b) || a.doubleValue() < 0 || b.doubleValue() < 0
                || a.doubleValue() != Math.rint(a.doubleValue()) || b.doubleValue() != Math.rint(b.doubleValue())) {
            throw new IllegalArgumentException("\"knockout.aggregate\" trebuie sa fie [gazde, oaspeti], intregi >= 0");
        }
        return new int[] {a.intValue(), b.intValue()};
    }

    // ------------------------------------------------------------------ scorul tinta
    /**
     * Face din meci unul condus spre {@code tg} (inainte de {@link #run}). Prelungirile se joaca daca
     * si numai daca tinta le are, departajarea la fel; fara {@link #knockout} meciul cu prelungiri e
     * socotit mansa unica (aggregate 0-0) pentru blocul {@code knockout} al rezultatului.
     */
    public void target(Target tg) {
        if (LEGACY) {
            throw new IllegalArgumentException("\"target\" nu exista in modul legacy");
        }
        target = tg;
        if (tg.extraTime != null && koAggregate == null) {
            koAggregate = new int[] {0, 0};
        }
        startPeriod(0.0, 2 * halfLen);
    }

    private void startPeriod(double start, double len) {
        perStart = start;
        perEnd = start + len;
        for (Team team : teams) {
            perXg0[team.index] = team.mXg;
            perGoals0[team.index] = team.goals;
        }
    }

    private int[] quota() {
        return half <= 2 ? target.score : target.extraTime;
    }

    /** Cate goluri mai are voie {@code team} sa dea in perioada de acum (fara tinta: oricate). */
    int goalsLeft(Team team) {
        if (target == null) {
            return Integer.MAX_VALUE;
        }
        return quota()[team.index] - (team.goals - perGoals0[team.index]);
    }

    /**
     * Sansa de gol condusa a unei ocazii cu sansa naturala {@code p} a echipei {@code team}: fara
     * tinta, chiar {@code p}. Cu tinta: 0 daca cota perioadei e atinsa, altfel
     * {@code clamp(lambda * p, 0, 1)}, cu {@code lambda = ramase / asteptate}: golurile ramase din cota
     * impartite la golurile naturale asteptate in timpul ramas al perioadei, din ritmul xG-ului natural
     * de pana acum amestecat cu un ritm de pornire ({@link #TARGET_PRIOR_GOALS} pe 90 de minute,
     * cantarit cat {@link #TARGET_PRIOR_T} secunde de joc). Asa golurile pastrate se intind pe toata
     * perioada si raman legate de calitatea ocaziei; spre final, cu goluri lipsa, lambda creste.
     */
    double guideGoal(Team team, double p) {
        if (target == null) {
            return p;
        }
        int left = goalsLeft(team);
        if (left <= 0) {
            return 0.0;
        }
        double elapsed = Math.max(0.0, t - perStart);
        double remain = Math.max(1.0, perEnd - t);
        double prior = TARGET_PRIOR_GOALS / (2 * halfLen);
        double rate = (team.mXg - perXg0[team.index] + prior * TARGET_PRIOR_T) / (elapsed + TARGET_PRIOR_T);
        double expected = Math.max(1e-6, rate * remain);
        return Geo.clamp(left / expected * p, 0.0, 1.0);
    }

    /** Finalul celor 90 de minute in modul tinta: scorul trebuie sa fie exact cel cerut. */
    private void targetEndOfRegularTime() {
        if (teams[0].goals != target.score[0] || teams[1].goals != target.score[1]) {
            targetFailed = true;
            state = OVER;
            return;
        }
        score90 = score();
        if (target.extraTime != null) {
            extraTime = true;
            ev("EXTRA_TIME", null, null, null, "Prelungiri: " + scoreText());
            startPeriod(2 * halfLen, 2 * ET_HALF);
            extraPeriod(3, teams[0], "extra1");
        } else if (koAggregate != null) {
            finish();
        } else {
            state = OVER;
            ev("FULL_TIME", null, null, null, "Final: " + scoreText());
        }
    }

    private void targetEndOfExtraTime() {
        if (teams[0].goals - score90[0] != target.extraTime[0] || teams[1].goals - score90[1] != target.extraTime[1]) {
            targetFailed = true;
            state = OVER;
            return;
        }
        if (target.penalties != null) {
            shootoutDue = true;
            state = OVER;
        } else {
            finish();
        }
    }

    /** Samanta incercarii {@code k} (k = 0: chiar samanta ceruta); amestec splitmix64. */
    static long mix(long seed, long k) {
        if (k == 0) {
            return seed;
        }
        long z = seed + k * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Meciul cu scor tinta acceptat: cronologia, cate incercari, samanta incercarii acceptate. */
    public static final class TargetRun {
        public final Map<String, Object> timeline;
        public final int tries;
        public final long seed;
        public final int[] score;

        TargetRun(Map<String, Object> timeline, int tries, long seed, int[] score) {
            this.timeline = timeline;
            this.tries = tries;
            this.seed = seed;
            this.score = score;
        }
    }

    /** cate nuclee are masina: marimea bazinului de incercari si plafonul simularilor pornite deodata. */
    private static final int CORES = Math.max(1, Runtime.getRuntime().availableProcessors());
    /** bazinul comun (pe proces, marime fixa) al incercarilor speculative ale meciurilor cu tinta. */
    private static final ExecutorService TRY_POOL = Executors.newFixedThreadPool(CORES, r -> {
        Thread th = new Thread(r, "target-try");
        th.setDaemon(true);
        return th;
    });
    /** cate incercari de meci cu tinta se joaca acum in proces (pe orice fir). */
    private static final AtomicInteger RUNNING = new AtomicInteger();
    /** cate meciuri cu tinta asteapta acum incercari dupa prima (isi impart nucleele libere). */
    private static final AtomicInteger WAITING = new AtomicInteger();
    /** marimea primului val de incercari speculative (dupa incercarea 0, jucata singura). */
    static final int FIRST_WAVE = 3;

    /** opreste incercarea (o incercare cu k mai mic a reusit deja), verificat din cand in cand in bucla. */
    private BooleanSupplier stop;

    /** Joaca incercarea k; null daca n-a atins tinta (sau a fost oprita). */
    private static TargetRun oneTry(Map<String, Object> home, Map<String, Object> away, long seed, double minutes,
                                    int recordHz, boolean keepFrames, int[] aggregate, Target tg, int k,
                                    BooleanSupplier stop) {
        long sd = mix(seed, k);
        RUNNING.incrementAndGet();
        try {
            Sim sim = new Sim(home, away, sd, minutes, recordHz, keepFrames);
            if (aggregate != null) {
                sim.knockout(aggregate);
            }
            sim.target(tg);
            sim.stop = stop;
            Map<String, Object> tl = sim.run();
            return sim.targetFailed ? null : new TargetRun(tl, k + 1, sd, sim.score());
        } finally {
            RUNNING.decrementAndGet();
        }
    }

    /**
     * Joaca meciul condus spre {@code tg}: incercarea k foloseste samanta {@link #mix}(seed, k), pana
     * la {@code tg.maxTries}; meciul e cel al celui mai mic k care atinge tinta. null = tinta neatinsa.
     *
     * <p>Incercarea 0 se joaca singura, pe firul apelantului. Daca rateaza, urmatoarele se joaca in
     * valuri speculative pe bazinul comun {@link #TRY_POOL}: primul val are {@link #FIRST_WAVE}
     * incercari, urmatoarele cate nuclee sunt libere, impartite la meciurile cu tinta care asteapta
     * acum in proces ({@link #WAITING}). Nucleele libere se socotesc din simularile pornite deja
     * ({@link #RUNNING}), deci cand masina e plina incercarile merg una dupa alta, pe firul
     * apelantului, ca inainte. Rezultatul e acelasi ca al buclei secventiale: se accepta tot cel mai
     * mic k reusit; o incercare cu k mai mare se opreste doar ca sa nu arda procesor. Incercarile
     * se joaca fara cadre; cea acceptata se rejoaca (aceeasi samanta, acelasi meci) cu cadre daca
     * {@code keepFrames}.
     */
    public static TargetRun playTarget(Map<String, Object> home, Map<String, Object> away, long seed, double minutes,
                                       int recordHz, boolean keepFrames, int[] aggregate, Target tg) {
        TargetRun won = oneTry(home, away, seed, minutes, recordHz, false, aggregate, tg, 0, null);
        if (won == null && tg.maxTries > 1) {
            WAITING.incrementAndGet();
            try {
                int next = 1;
                while (won == null && next < tg.maxTries) {
                    int free = CORES - RUNNING.get();
                    int share = free / Math.max(1, WAITING.get());
                    int size = Math.min(Math.min(next == 1 ? FIRST_WAVE : CORES, share), tg.maxTries - next);
                    if (size <= 1) {
                        won = oneTry(home, away, seed, minutes, recordHz, false, aggregate, tg, next, null);
                        next++;
                    } else {
                        won = wave(home, away, seed, minutes, recordHz, aggregate, tg, next, size);
                        next += size;
                    }
                }
            } finally {
                WAITING.decrementAndGet();
            }
        }
        if (won != null && keepFrames) {
            int k = won.tries - 1;
            TargetRun framed = oneTry(home, away, seed, minutes, recordHz, true, aggregate, tg, k, null);
            if (framed == null || framed.score[0] != won.score[0] || framed.score[1] != won.score[1]) {
                throw new IllegalStateException("meciul cu tinta nu s-a putut rejuca cu cadre (k = " + k + ")");
            }
            won = framed;
        }
        return won;
    }

    /** Un val: incercarile next .. next + size - 1 in paralel; intoarce cea reusita cu k minim, sau null. */
    private static TargetRun wave(Map<String, Object> home, Map<String, Object> away, long seed, double minutes,
                                  int recordHz, int[] aggregate, Target tg, int next, int size) {
        AtomicInteger best = new AtomicInteger(Integer.MAX_VALUE);
        List<Future<TargetRun>> wf = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int k = next + i;
            wf.add(TRY_POOL.submit(() -> {
                TargetRun r = oneTry(home, away, seed, minutes, recordHz, false, aggregate, tg, k,
                        () -> best.get() < k);
                if (r != null) {
                    best.accumulateAndGet(k, Math::min);
                }
                return r;
            }));
        }
        // in ordinea lui k: primul reusit e cel acceptat (o incercare se opreste doar daca una cu k
        // mai mic a reusit, deci toate cele dinaintea lui s-au jucat pana la capat si au ratat)
        for (Future<TargetRun> f : wf) {
            TargetRun r;
            try {
                r = f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("meciul cu tinta a fost intrerupt", e);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
            }
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private static Map<String, Object> halfEntry(int frame, int d0, int d1) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("frame", frame);
        h.put("dirs", List.of(d0, d1));
        return h;
    }

    // ------------------------------------------------------------------ utilitare
    Team opp(Team team) {
        return teams[1 - team.index];
    }

    /** {@code p} a jucat mingea cu voie: intra in sirul din care se citeste pasa decisiva. */
    void played(Player p) {
        ball.toucher = p;
        if (playN > 0 && playWho[playN - 1] == p) {
            return;                     // tot el: sirul tine minte jucatori diferiti, in ordine
        }
        if (playN == PLAYS) {
            System.arraycopy(playWho, 1, playWho, 0, PLAYS - 1);
            playN--;
        }
        playWho[playN] = p;
        playN++;
    }

    /**
     * Pasa decisiva: ultimul coechipier care a jucat mingea inaintea marcatorului, daca intre ei
     * nu a avut-o un adversar sub control. O parada respinsa sau o deviere nu rup sirul, deci
     * golul din respingere are pasa de la cel care a sutat primul. Sirul incepe de la zero la
     * fiecare repunere, deci un gol direct din lovitura libera nu are pasa decisiva.
     */
    Player assistFor(Player scorer) {
        for (int i = playN - 1; i >= 0; i--) {
            Player who = playWho[i];
            if (who != scorer) {
                return who.team == scorer.team ? who : null;
            }
        }
        return null;
    }

    /**
     * In cat timp reactioneaza {@code q} la mingea aflata acum in zbor. Peste timpul lui obisnuit:
     * cel care il tinea pe primitor pleaca mai repede (marking), la mingea libera conteaza
     * ballRecovery, iar cine e intr-o scapare de atentie pleaca tarziu.
     */
    double reactTo(Player q) {
        double r = q.react;
        if (LEGACY) {
            return r;
        }
        Pending pend = pending;
        if (pend != null && pend.marker == q) {
            r -= Wt.mark_react_marking * (q.n[A.marking] - Wt.mark_react_marking_from);
        } else if (ball.kind == Ball.LOOSE) {
            r -= Wt.loose_react_ballRecovery * (q.n[A.ballRecovery] - Wt.loose_react_ballRecovery_from);
        }
        if (q.lapseUntil > ball.kickT) {
            r += Wt.lapse_react;
        }
        return r < 0.05 ? 0.05 : r;
    }

    /** Cat de presat e purtatorul mingii (0..1), dupa cel mai apropiat adversar. */
    double holderPress() {
        Player c = ball.holder;
        if (c == null) {
            return 0.0;
        }
        double nd = 99.0;
        for (Player o : opp(c.team).players) {
            double dd = hyp(o.x - c.x, o.y - c.y);
            if (dd < nd) {
                nd = dd;
            }
        }
        return Geo.clamp((6.0 - nd) / 4.5, 0.0, 1.0);
    }

    /**
     * Scaparea de atentie (concentration): din cand in cand un jucator de camp fara rol ramane cu
     * gandul in urma: nu-si mai corecteaza pozitia {@code lapse_time} secunde si reactioneaza
     * tarziu la minge. Mai des cu concentrare mica si spre finalul reprizei.
     */
    void maybeLapse(Player p) {
        double phase = half <= 2 ? (half == 1 ? t : t - halfLen) / halfLen
                : (t - 2 * halfLen - (half - 3) * ET_HALF) / ET_HALF;      // repriza de prelungiri
        double chance = (Wt.lapse_base + Wt.lapse_concentration * (1.0 - p.n[A.concentration]))
                * (1.0 + Wt.lapse_late * phase * phase);
        if (rng.random() < chance) {
            p.lapseUntil = t + Wt.lapse_time;
            p.thinkAt = p.lapseUntil;
            p.team.mLapses++;
        }
    }

    /** {@code round(v, digits)} din Python: rotunjirea corecta a valorii, cu jumatatile spre par. */
    static double round(double v, int digits) {
        return new BigDecimal(v).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    static double hyp(double x, double y) {
        return Math.sqrt(x * x + y * y);
    }

    static double sigmoid(double x) {
        if (x < -30.0) {
            return 0.0;
        }
        if (x > 30.0) {
            return 1.0;
        }
        return 1.0 / (1.0 + PMath.exp(-x));
    }

    /** Adauga un eveniment in cronologie si il intoarce, ca apelantul sa mai poata pune campuri. */
    Map<String, Object> ev(String typ, Team team, Player p, Player q, String text,
                           double x, double y, double tx, double ty) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("t", round(t, 1));
        e.put("f", frameNo);
        e.put("type", typ);
        if (team != null) {
            e.put("team", team.index);
        }
        if (p != null) {
            e.put("p", p.uid);
            if (Double.isNaN(x)) {
                x = p.x;
                y = p.y;
            }
        }
        if (q != null) {
            e.put("q", q.uid);
        }
        if (!Double.isNaN(x)) {
            e.put("x", round(x / Geo.L * 100, 1));
            e.put("y", round(y / Geo.W * 100, 1));
        }
        if (!Double.isNaN(tx)) {
            e.put("tx", round(tx / Geo.L * 100, 1));
            e.put("ty", round(ty / Geo.W * 100, 1));
        }
        if (text != null && !text.isEmpty()) {
            e.put("text", text);
        }
        events.add(e);
        return e;
    }

    Map<String, Object> ev(String typ, Team team, Player p, Player q, String text) {
        return ev(typ, team, p, q, text, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    boolean inOwnBox(Player p) {
        double cx = Geo.cx(p.x, p.team.dir);
        double cy = Geo.cy(p.y, p.team.dir);
        return cx <= 16.5 && Math.abs(cy - Geo.MID_Y) <= 20.16;
    }

    boolean inZone(Player p, double x, double y, double margin) {
        double cx = Geo.cx(x, p.team.dir);
        double cy = Geo.cy(y, p.team.dir);
        boolean has = poss == p.team;
        double[] b = has ? p.abox : p.dbox;
        double lo = has || LEGACY ? b[0] : p.dlo;       // atacantul coborat apara si mai jos de zona lui
        return (lo - margin <= cx && cx <= b[1] + margin) && (b[2] - margin <= cy && cy <= b[3] + margin);
    }

    /** Linia de ofsaid pentru echipa {@code team} (in canonicul ei). */
    double offsideLine(Team team) {
        int d = team.dir;
        double a = -1.0;
        double b = -1.0;
        for (Player o : opp(team).players) {
            double cx = d == 1 ? o.x : Geo.L - o.x;
            if (cx > a) {
                b = a;
                a = cx;
            } else if (cx > b) {
                b = cx;
            }
        }
        return b > 52.5 ? b : 52.5;
    }

    /**
     * Eroarea unei centrari (sub 1: crossing / passing / technique), a unui corner (sub 2: corners
     * si crossing) sau a unei lovituri libere centrate (sub 3: freeKick si crossing). Aceeasi forma
     * ca mingea inalta din {@link #passSigma}: cu amestecul 0.5 jucatorul mediu are eroarea mediei.
     */
    /** Abilitatea executantului la felul de centrare {@code sub} (amestecul din {@link #crossSigma}), 0.15..0.85. */
    static double crossSkill(Player p, int sub) {
        double[] n = p.n;
        if (sub == 2) {
            return Wt.corner_error_corners * n[A.corners] + Wt.corner_error_crossing * n[A.crossing];
        }
        if (sub == 3) {
            return Wt.fk_cross_error_freeKick * n[A.freeKick] + Wt.fk_cross_error_crossing * n[A.crossing];
        }
        return Wt.cross_error_crossing * n[A.crossing] + Wt.cross_error_passing * n[A.passing]
                + Wt.cross_error_technique * n[A.technique];
    }

    double crossSigma(Player p, double press, int sub) {
        double[] n = p.n;
        double skill = crossSkill(p, sub);
        double k = sub == 2 ? Wt.corner_error_k : sub == 3 ? Wt.fk_cross_error_k : Wt.cross_error_k;
        // (1 - skill) = 0.5 + (0.5 - skill): la corner panta e marita cu corner_skill_slope, centrat
        // pe jucatorul mediu (cu 1 e exact forma mingii inalte)
        double spread = sub == 2 ? Wt.corner_skill_slope : 1.0;
        double base = Math.max(Wt.pass_error_floor, Wt.loft_error_slope * (0.5 + spread * (0.5 - skill))
                * Wt.loft_error_factor * k);
        return base * (1.0 + Wt.pass_error_pressure * press * (1.0 - Wt.pass_error_composure * n[A.composure]))
                * p.team.tempoError;
    }

    double passSigma(Player p, double press, boolean loft) {
        double[] n = p.n;
        double base;
        if (loft) {
            double skill = Wt.loft_error_passing * n[A.passing] + Wt.loft_error_technique * n[A.technique]
                    + Wt.loft_error_crossing * n[A.crossing];
            base = Wt.loft_error_slope * (1.0 - skill) * Wt.loft_error_factor;
        } else {
            base = Wt.pass_error_passing * (1.0 - n[A.passing]) - Wt.pass_error_offset;
        }
        if (base < Wt.pass_error_floor) {
            base = Wt.pass_error_floor;
        }
        return (base * (1.0 + Wt.pass_error_pressure * press * (1.0 - Wt.pass_error_composure * n[A.composure]))
                * p.team.tempoError);
    }

    // ------------------------------------------------------------------ repuneri
    /** Unde sta jucatorul la lovitura de incepere (canonic); rezultatul e in {@code ox, oy}. */
    void kickoffPoint(Player p, boolean kicking) {
        Positioning.formTarget(this, p, Geo.L / 2, Geo.MID_Y, kicking);
        double fx = ox;
        double fy = oy;
        if (fx > 51.0) {
            fx = 51.0;
        }
        if (!kicking && hyp(fx - Geo.L / 2, fy - Geo.MID_Y) < 10.0) {
            fx = Geo.L / 2 - 10.5;
        }
        ox = fx;
        oy = fy;
    }

    void placeKickoff(Team kickingTeam) {
        for (Team team : teams) {
            for (Player p : team.players) {
                kickoffPoint(p, team == kickingTeam);
                p.x = Geo.cx(ox, team.dir);
                p.y = Geo.cy(oy, team.dir);
                p.vx = 0.0;
                p.vy = 0.0;
                p.tx = p.x;
                p.ty = p.y;
                p.path = new ArrayList<>();
                p.pg = null;
                p.role = R_NONE;
                p.carryTo = null;
                p.stunUntil = -1.0;
            }
        }
    }

    Player pickTaker(String kind, Team team, double x, double y) {
        if (kind.equals("goal_kick")) {
            return team.gk;
        }
        if (!LEGACY) {
            // executantii din tactica (sau cei mai buni la fazele fixe, vezi Team.resolveTakers)
            if (kind.equals("penalty")) {
                return team.penTaker;
            }
            if (kind.equals("free_kick") && ("shot".equals(fkKind) || "cross".equals(fkKind))) {
                return team.fkTaker;
            }
            if (kind.equals("corner")) {
                Player side = Geo.cy(y, team.dir) < Geo.MID_Y ? team.cornerLeft : team.cornerRight;
                if (side != null) {
                    return side;
                }
            }
        }
        if (kind.equals("kickoff")) {
            Player best = null;
            for (Player p : team.players) {      // cel mai avansat; la egalitate, primul din lista
                if (best == null || p.deep[0] > best.deep[0]) {
                    best = p;
                }
            }
            return best;
        }
        if (kind.equals("corner")) {
            Player best = null;
            double bestKey = 0.0;
            for (Player p : team.players) {
                if (p.isGk || p.isDef) {
                    continue;
                }
                double key = Wt.corner_taker_corners * p.r[A.corners] + Wt.corner_taker_crossing * p.r[A.crossing];
                if (best == null || key > bestKey) {
                    best = p;
                    bestKey = key;
                }
            }
            return best;
        }
        if (kind.equals("free_kick")) {
            double cx = Geo.cx(x, team.dir);
            double cy = Geo.cy(y, team.dir);
            if (cx <= 16.5 && Math.abs(cy - Geo.MID_Y) <= 20.16) {
                return team.gk;
            }
        }
        Player best = null;
        double bd = 0.0;
        for (Player p : team.players) {
            if (p.isGk) {
                continue;
            }
            double dd = hyp(p.x - x, p.y - y);
            if (best == null || dd < bd) {
                best = p;
                bd = dd;
            }
        }
        return best;
    }

    void setRestart(String kind, Team team, double x, double y, double wait) {
        Ball b = ball;
        state = DEAD;
        rkind = kind;
        restartKind = null;
        pending = null;
        shot = null;
        gkHold = false;
        poss = team;
        b.holder = null;
        b.kind = Ball.DEAD;
        b.v = 0.0;
        b.lofted = false;
        b.z = 0.0;
        b.x = Geo.clamp(x, 0.3, Geo.L - 0.3);
        b.y = Geo.clamp(y, 0.3, Geo.W - 0.3);
        b.px = b.x;
        b.py = b.y;
        b.receiver = null;
        b.kicker = null;
        b.lastTeam = team;
        b.toucher = null;
        playN = 0;
        if (!LEGACY) {
            sp = null;
            fkKind = kind.equals("free_kick") ? SetPiece.fkKind(team, b.x, b.y) : null;
        }
        taker = pickTaker(kind, team, b.x, b.y);
        if (Double.isNaN(wait)) {
            wait = switch (kind) {
                case "throw" -> 2.0;
                case "goal_kick" -> 3.5;
                case "corner" -> 4.0;
                case "free_kick" -> 2.5;
                default -> 3.0;
            };
        }
        restartAt = t + wait;
        if (!LEGACY) {
            Runs.stop(teams[0]);
            Runs.stop(teams[1]);
        }
        for (Player p : all) {
            p.carryTo = null;
            p.thinkAt = t + 0.05 * (p.uid % 5);
        }
        if (!LEGACY) {
            SetPiece.onRestart(this, kind, team);       // evenimentul si locurile de la faza fixa
        }
        Roles.assignRoles(this);
    }

    void setRestart(String kind, Team team, double x, double y) {
        setRestart(kind, team, x, y, Double.NaN);
    }

    void tickDead() {
        Player tk = taker;
        Ball b = ball;
        boolean ready = t >= restartAt && hyp(tk.x - b.x, tk.y - b.y) < 1.4;
        if (ready && rkind.equals("kickoff") && t < restartAt + 14.0) {
            for (Player p : all) {
                double cx = p.team.dir == 1 ? p.x : Geo.L - p.x;
                if (cx > 53.5) {
                    ready = false;
                    break;
                }
            }
        }
        if (!LEGACY && ready && sp != null && t < restartAt + Wt.setpiece_wait_max && !sp.placed(this)) {
            ready = false;              // la faza fixa se asteapta asezarea (cel mult setpiece_wait_max)
        }
        if (t > restartAt + 16.0) {
            tk.x = b.x;
            tk.y = b.y;
            tk.vx = 0.0;
            tk.vy = 0.0;
            ready = true;
        }
        if (!ready) {
            return;
        }
        String kind = rkind;
        if (!LEGACY) {
            SetPiece plan = sp;
            sp = null;
            if (kind.equals("corner") || kind.equals("free_kick") || kind.equals("penalty")) {
                lastSpKind = kind;
                lastSpT = t;
            }
            if (kind.equals("penalty")) {
                SetPiece.takePenalty(this, tk);
                return;
            }
            if (kind.equals("free_kick") && "shot".equals(fkKind) && plan != null) {
                SetPiece.takeFreeKick(this, tk, plan);
                return;
            }
            if (plan != null && plan.targets.length > 0
                    && (kind.equals("corner") || (kind.equals("free_kick") && "cross".equals(fkKind)))) {
                if (kind.equals("corner")) {
                    SetPiece.takeCorner(this, tk, plan);
                } else {
                    SetPiece.takeFkCross(this, tk, plan);
                }
                Roles.assignRoles(this);
                return;
            }
        }
        state = LIVE;
        restartKind = kind;
        poss = tk.team;
        b.holder = tk;
        b.kind = Ball.HELD;
        b.lastTeam = tk.team;
        played(tk);
        tk.canActAt = t + 0.4;
        tk.decideAt = t + 0.4;
        tk.gainT = Double.NaN;
        tk.assistFrom = null;
        tk.carryTo = null;
        tk.path = new ArrayList<>();
        tk.tx = tk.x;
        tk.ty = tk.y;
        gkHold = kind.equals("goal_kick");
        noTackleUntil = t + 1.5;
        Roles.assignRoles(this);
    }

    // ------------------------------------------------------------------ oboseala
    /**
     * Pune energia jucatorului; sub prag ii scade ritmul intre sprinturi si, mai putin, viteza si
     * acceleratia (iesirea la presing citeste direct {@code tired}).
     */
    void setEnergy(Player p, double e) {
        p.energy = e;
        double thr = Wt.tired_threshold;
        double tired = e < thr ? (thr - e) / thr : 0.0;
        if (tired != p.tired) {
            p.tired = tired;
            p.vmax = p.vmax0 * (1.0 - Wt.tired_speed * tired);
            p.acc = p.acc0 * (1.0 - Wt.tired_accel * tired);
            p.jog = p.jog0 * p.effort * (1.0 - Wt.tired_jog * tired);
        }
    }

    /**
     * O data la 0.5 s: energia scade cu timpul petrecut pe teren (flat) plus patratul vitezei, si
     * revine cat mingea e afara din joc. Cine alearga mult (presing, tempo mare, workRate mare)
     * oboseste mai repede.
     */
    void updateEnergy() {
        double ref = Wt.fatigue_ref_speed;
        double flat = Wt.fatigue_flat;
        boolean dead = state == DEAD;
        double step = ENERGY_EVERY * DT;
        for (Player p : all) {
            double v = hyp(p.vx, p.vy);
            p.dist += v * step;
            if (v > FAST_SPEED) {
                p.fast += v * step;
            }
            double u = v / ref;
            double de = -p.drainK * (flat + u * u);
            if (dead) {
                de += p.restK;
            }
            if (de != 0.0) {
                double e = p.energy + de * step;
                if (e > 1.0) {
                    e = 1.0;
                } else if (e < 0.0) {
                    e = 0.0;
                }
                if (e != p.energy) {
                    setEnergy(p, e);
                }
            }
        }
    }

    /**
     * Dupa fiecare gol: in echipa condusa, cine are determinare mare ridica ritmul intre
     * sprinturi, iar cine are determinare mica il lasa sa scada.
     */
    void updateEffort() {
        double kJog = Wt.tired_jog;
        for (Team team : teams) {
            boolean behind = opp(team).goals > team.goals;
            for (Player p : team.players) {
                p.effort = behind ? 1.0 + p.trailK : 1.0;
                p.jog = p.jog0 * p.effort * (1.0 - kJog * p.tired);
            }
        }
    }

    /** Energia medie a jucatorilor de camp, in procente. */
    double teamEnergy(Team team) {
        double sum = 0.0;
        int count = 0;
        for (Player p : team.players) {
            if (!p.isGk) {
                sum += p.energy;
                count++;
            }
        }
        return 100.0 * sum / count;
    }

    void halfTime() {
        ev("HALF_TIME", null, null, null, "Pauză: " + teams[0].name + " " + teams[0].goals + " - "
                + teams[1].goals + " " + teams[1].name);
        for (Team team : teams) {
            team.mEnergyHalf = teamEnergy(team);
            for (Player p : team.players) {
                // la pauza recupereaza o parte din ce a pierdut (mai mult cu naturalFitness mare)
                setEnergy(p, p.energy + (1.0 - p.energy) * p.halfK);
            }
        }
        half = 2;
        for (Team team : teams) {
            team.dir = -team.dir;
        }
        halves.add(halfEntry(frameNo, teams[0].dir, teams[1].dir));
        placeKickoff(teams[1]);
        setRestart("kickoff", teams[1], Geo.L / 2, Geo.W / 2, 1.5);
    }

    // ------------------------------------------------------------------ meciul eliminatoriu
    private String scoreText() {
        return teams[0].name + " " + teams[0].goals + " - " + teams[1].goals + " " + teams[1].name;
    }

    /** Egalitate la general: scorul acestui meci plus golurile din prima mansa. */
    boolean tiedOnAggregate() {
        return teams[0].goals + koAggregate[0] == teams[1].goals + koAggregate[1];
    }

    /** Inceputul unei reprize de prelungiri: schimbarea terenului si lovitura de incepere (fara odihna). */
    private void extraPeriod(int newHalf, Team kicking, String period) {
        half = newHalf;
        for (Team team : teams) {
            team.dir = -team.dir;
        }
        Map<String, Object> h = halfEntry(frameNo, teams[0].dir, teams[1].dir);
        h.put("period", period);
        halves.add(h);
        placeKickoff(kicking);
        setRestart("kickoff", kicking, Geo.L / 2, Geo.W / 2, 1.5);
    }

    /** Finalul celor 90 de minute la meciul eliminatoriu: prelungiri la egalitate, altfel gata. */
    private void endOfRegularTime() {
        score90 = score();
        if (tiedOnAggregate()) {
            extraTime = true;
            ev("EXTRA_TIME", null, null, null, "Prelungiri: " + scoreText());
            extraPeriod(3, teams[0], "extra1");
        } else {
            finish();
        }
    }

    private void endOfExtraTime() {
        if (tiedOnAggregate()) {
            shootoutDue = true;         // departajarea se joaca in run(), dupa bucla
            state = OVER;
        } else {
            finish();
        }
    }

    /** Fluierul final al meciului eliminatoriu (dupa 90 de minute, dupa prelungiri sau dupa departajare). */
    void finish() {
        state = OVER;
        String how = penalties != null ? " (pen. " + penalties[0] + " - " + penalties[1] + ")"
                : extraTime ? ", după prelungiri" : "";
        ev("FULL_TIME", null, null, null, "Final: " + scoreText() + how);
    }

    /** Rezultatul meciului eliminatoriu (cheia {@code knockout} a cronologiei). */
    Map<String, Object> knockoutResult() {
        int[] sc = score();
        int[] s90 = score90 != null ? score90 : sc;
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("aggregate", List.of(koAggregate[0], koAggregate[1]));
        k.put("score90", List.of(s90[0], s90[1]));
        k.put("extraTime", extraTime);
        k.put("extraTimeGoals", List.of(sc[0] - s90[0], sc[1] - s90[1]));
        k.put("penalties", penalties != null ? List.of(penalties[0], penalties[1]) : null);
        k.put("shootout", shootout);
        int t0 = sc[0] + koAggregate[0];
        int t1 = sc[1] + koAggregate[1];
        int winner = t0 != t1 ? (t0 > t1 ? 0 : 1) : (penalties != null && penalties[0] > penalties[1] ? 0 : 1);
        if (target != null && t0 == t1 && penalties == null) {
            // tinta fara departajare la egalitate la general: meciul ramane nedecis
            k.put("winner", null);
            k.put("decidedBy", "none");
            return k;
        }
        k.put("winner", winner);
        k.put("decidedBy", t0 != t1 ? (extraTime ? "extra_time" : "regular") : "penalties");
        return k;
    }

    // ------------------------------------------------------------------ bucla
    void tick() {
        t += DT;
        tickNo++;
        Ball b = ball;
        if (state == DEAD) {
            tickDead();
        } else if (state == LIVE) {
            BallPlay.updateBall(this);
            if (state == LIVE && b.holder != null) {
                BallPlay.carrierLogic(this);
            }
        }
        if (t >= rolesAt) {
            Roles.assignRoles(this);
        }
        if (t >= gridAt) {
            Movement.refreshGrids(this);
        }
        if (!LEGACY && t >= runsAt) {
            Runs.update(this);
        }
        for (Player p : all) {
            if (t >= p.thinkAt) {
                Movement.think(this, p);
                p.thinkAt = t + (p.role != R_NONE ? 0.2 : 0.5) + 0.01 * (p.uid % 7);
                if (!LEGACY && state == LIVE && p.role == R_NONE && !p.isGk && p != b.holder) {
                    maybeLapse(p);
                }
            }
        }
        Player holder = b.holder;
        for (Player p : all) {
            Movement.move(this, p, holder);
        }
        if (tickNo % ENERGY_EVERY == 0) {
            updateEnergy();
            if (tickNo % ENERGY_LOG == 0) {
                int[] row = new int[22];
                for (int i = 0; i < 22; i++) {
                    row[i] = (int) Math.rint(all[i].energy * 100);
                }
                energyLog.add(row);
            }
        }
        if (holder != null) {
            double sp = hyp(holder.vx, holder.vy);
            if (sp > 0.5) {
                b.x = holder.x + holder.vx / sp * 0.6;
                b.y = holder.y + holder.vy / sp * 0.6;
            } else {
                b.x = holder.x + 0.4 * holder.team.dir;
                b.y = holder.y;
            }
            b.x = Geo.clamp(b.x, 0.1, Geo.L - 0.1);
            b.y = Geo.clamp(b.y, 0.1, Geo.W - 0.1);
            b.px = b.x;
            b.py = b.y;
        }
        if (state == LIVE) {
            poss.poss++;
        }
        // penalty-ul acordat se bate si daca s-a terminat timpul reprizei (doar pe drumul nou)
        boolean penPending = !LEGACY && state == DEAD && "penalty".equals(rkind);
        if (b.kind != Ball.SHOT && !penPending) {
            if (half == 1 && t >= halfLen) {
                halfTime();
            } else if (half == 2 && t >= 2 * halfLen) {
                if (target != null) {
                    targetEndOfRegularTime();
                } else if (koAggregate != null) {
                    endOfRegularTime();
                } else {
                    state = OVER;
                    ev("FULL_TIME", null, null, null, "Final: " + teams[0].name + " " + teams[0].goals
                            + " - " + teams[1].goals + " " + teams[1].name);
                }
            } else if (half == 3 && t >= 2 * halfLen + ET_HALF) {
                ev("EXTRA_HALF_TIME", null, null, null, "Pauză în prelungiri: " + scoreText());
                extraPeriod(4, teams[1], "extra2");
            } else if (half == 4 && t >= 2 * halfLen + 2 * ET_HALF) {
                if (target != null) {
                    targetEndOfExtraTime();
                } else {
                    endOfExtraTime();
                }
            }
        }
        if (tickNo % recEvery == 0) {
            record();
        }
    }

    void record() {
        Ball b = ball;
        Player holder = b.holder;
        boolean live = state == LIVE;
        if (live) {
            for (Player p : all) {
                if (p != holder && p.role == R_NONE && !p.isGk) {
                    // jucator pozitional: trebuie sa fie in zona lui (aparare sau atac)
                    double cx = Geo.cx(p.x, p.team.dir);
                    double cy = Geo.cy(p.y, p.team.dir);
                    double[] a = p.abox;
                    double[] d = p.dbox;
                    zoneN++;
                    if (!(Math.min(a[0], Math.min(d[0], p.dlo)) - 1.5 <= cx && cx <= Math.max(a[1], d[1]) + 1.5
                            && Math.min(a[2], d[2]) - 1.5 <= cy && cy <= Math.max(a[3], d[3]) + 1.5)) {
                        zoneOut++;
                    }
                }
            }
            for (Team team : teams) {
                if (team == poss) {
                    double lo = Double.POSITIVE_INFINITY;
                    double hi = Double.NEGATIVE_INFINITY;
                    for (Player p : team.players) {
                        if (!p.isGk) {
                            lo = Math.min(lo, p.y);
                            hi = Math.max(hi, p.y);
                        }
                    }
                    team.mWidth += hi - lo;
                    team.mWidthN++;
                } else {
                    int d = team.dir;
                    double sum = 0.0;
                    int count = 0;
                    for (Player p : team.players) {
                        if (p.isDef) {
                            sum += d == 1 ? p.x : Geo.L - p.x;
                            count++;
                        }
                    }
                    if (count > 0) {
                        team.mLine += sum / count;
                        team.mLineN++;
                    }
                }
            }
        }
        frameNo++;
        if (!keepFrames) {
            return;
        }
        double kx = 1000.0 / Geo.L;
        double ky = 1000.0 / Geo.W;
        int[] row = new int[3 + 44];
        row[0] = (int) Math.rint(b.x * kx);
        row[1] = (int) Math.rint(b.y * ky);
        row[2] = (int) Math.rint(b.z * 10);
        char[] roles = new char[22];
        for (int i = 0; i < 22; i++) {
            Player p = all[i];
            row[3 + 2 * i] = (int) Math.rint(p.x * kx);
            row[4 + 2 * i] = (int) Math.rint(p.y * ky);
            roles[i] = p == holder ? 'c' : ROLE_CH[p.role];
        }
        frames.add(row);
        holders.add(holder != null ? holder.uid : -1);
        rolesLog.add(new String(roles));
    }

    /** Joaca meciul pana la final si intoarce cronologia. */
    public Map<String, Object> run() {
        Movement.refreshGrids(this);
        record();
        while (state != OVER) {
            tick();
            if (stop != null && (tickNo & 255) == 0 && stop.getAsBoolean()) {
                targetFailed = true;        // incercare speculativa de prisos: o alta cu k mai mic a reusit
                return null;
            }
        }
        if (shootoutDue) {
            shootoutDue = false;
            if (target != null) {
                // departajarea are generatorul ei: se cauta samanta care da exact scorul tintei
                // (doar trageri, fara a rejuca meciul), apoi se joaca cu ea
                for (int j = 0; j < Target.SHOOTOUT_SEEDS && shootoutSeed == Long.MIN_VALUE; j++) {
                    long sd = mix(seed ^ 0x5DEECE66DL, j + 1);
                    int[] sc = Shootout.kicks(this, new Mt(sd));
                    if (sc[0] == target.penalties[0] && sc[1] == target.penalties[1]) {
                        shootoutSeed = sd;
                    }
                }
                if (shootoutSeed == Long.MIN_VALUE) {
                    targetFailed = true;
                    return timeline();
                }
                Shootout.play(this, new Mt(shootoutSeed));
            } else {
                Shootout.play(this);
            }
            finish();
        }
        return timeline();
    }

    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", List.of(teams[0].goals, teams[1].goals));
        List<Map<String, Object>> list = new ArrayList<>();
        int tot = teams[0].poss + teams[1].poss;
        if (tot == 0) {
            tot = 1;
        }
        for (Team team : teams) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("passes", team.passes);
            s.put("passes_ok", team.passesOk);
            s.put("through", team.through);
            s.put("through_ok", team.throughOk);
            s.put("long", team.longBalls);
            s.put("long_ok", team.longOk);
            s.put("dribbles_won", team.dribblesWon);
            s.put("dribbles_lost", team.dribblesLost);
            s.put("tackles", team.tackles);
            s.put("interceptions", team.interceptions);
            s.put("aerials_won", team.aerialsWon);
            s.put("shots", team.shots);
            s.put("on_target", team.onTarget);
            s.put("blocked", team.blocked);
            s.put("off_target", team.offTarget);
            s.put("saves", team.saves);
            s.put("offsides", team.offsides);
            s.put("corners", team.corners);
            s.put("carries", team.carries);
            s.put("poss_pct", round(100.0 * team.poss / tot, 1));
            int passes = Math.max(1, team.passes);
            s.put("avg_pass_len", round(team.mPassLen / passes, 1));
            s.put("forward_pct", round(100.0 * team.mFwd / passes, 1));
            s.put("loft_pct", round(100.0 * team.longBalls / passes, 1));
            s.put("width", round(team.mWidth / Math.max(1, team.mWidthN), 1));
            s.put("line_height", round(team.mLine / Math.max(1, team.mLineN), 1));
            s.put("time_on_ball", round(team.mBallTime / Math.max(1, team.mBallN), 2));
            double minEnergy = Double.POSITIVE_INFINITY;
            double dist = 0.0;
            double fast = 0.0;
            int field = 0;
            for (Player p : team.players) {
                if (!p.isGk) {
                    minEnergy = Math.min(minEnergy, p.energy);
                    dist += p.dist;
                    fast += p.fast;
                    field++;
                }
            }
            s.put("energy_half", round(team.mEnergyHalf, 1));
            s.put("energy_end", round(teamEnergy(team), 1));
            s.put("energy_min", round(100.0 * minEnergy, 1));
            s.put("distance_km", round(dist / field / 1000.0, 2));
            s.put("fast_km", round(fast / field / 1000.0, 2));
            s.put("regains", team.mRegains);
            s.put("chase_regains", team.mChaseRegains);
            s.put("goals_late", team.mGoalsLate);
            s.put("shots_late", team.mShotsLate);
            s.put("xg", round(team.mXg, 3));        // suma sanselor de gol ale suturilor
            if (!LEGACY) {
                s.put("lapses", team.mLapses);          // scapari de atentie
                s.put("loose_won", team.mLooseWon);     // mingi libere castigate
                s.put("fouls", team.fouls);             // faulturi comise
                s.put("yellow_cards", team.yellowCards);
                s.put("free_kicks", team.freeKicks);    // lovituri libere primite
                s.put("penalties", team.penalties);     // penalty-uri primite
                s.put("penalty_goals", team.penaltyGoals);
                s.put("fk_shots", team.fkShots);        // suturi din lovitura libera directa
                s.put("fk_goals", team.fkGoals);
                s.put("crosses", team.crosses);         // centrari: din joc, corner, lovitura libera
                s.put("crosses_ok", team.crossesOk);    // ajunse la un coechipier
                s.put("headers", team.headers);         // lovituri de cap la poarta
                s.put("header_goals", team.headerGoals);
                s.put("corner_goals", team.cornerGoals); // goluri la cel mult 10 s dupa un corner
            }
            list.add(s);
        }
        out.put("teams", list);
        out.put("zone_samples", zoneN);
        out.put("zone_outside", zoneOut);
        out.put("dijkstra_runs", dijkstraRuns);
        out.put("dijkstra_detours", dijkstraDetours);
        Map<String, Object> cal = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : calib.entrySet()) {
            double[] v = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("n", (int) v[0]);
            row.put("estimated", round(v[1] / Math.max(1, v[0]), 3));
            row.put("actual", round(v[2] / Math.max(1, v[0]), 3));
            cal.put(e.getKey(), row);
        }
        out.put("pass_calibration", cal);
        return out;
    }

    public Map<String, Object> timeline() {
        List<Map<String, Object>> teamList = new ArrayList<>();
        for (Team team : teams) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("name", team.name);
            tm.put("color", team.color);
            tm.put("gkColor", team.gkColor);
            tm.put("formation", team.formation);
            tm.put("tactic", team.tactic);
            tm.put("tacticIgnored", team.tacticUnknown);
            List<Map<String, Object>> players = new ArrayList<>();
            for (Player p : team.players) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("uid", p.uid);
                pm.put("number", p.num);
                pm.put("name", p.name);
                pm.put("pos", p.pos);
                pm.put("slot", p.slot);
                Map<String, Object> ratings = new LinkedHashMap<>();
                for (int k = 0; k < A.COUNT; k++) {
                    ratings.put(A.NAMES[k], p.r[k]);
                }
                pm.put("ratings", ratings);
                pm.put("zoneDef", p.dbox);
                pm.put("zoneAtt", p.abox0);
                players.add(pm);
            }
            tm.put("players", players);
            teamList.add(tm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", VERSION);
        out.put("seed", seed);
        out.put("minutes", minutes);
        out.put("hz", 1.0 / (recEvery * DT));
        Map<String, Object> pitch = new LinkedHashMap<>();
        pitch.put("length", Geo.L);
        pitch.put("width", Geo.W);
        out.put("pitch", pitch);
        out.put("coords", "x si y normalizate 0-1000 (x pe lungime, y pe latime); "
                + "cadru = [mingeX, mingeY, inaltime_dm, j0x, j0y, ... j21x, j21y]");
        out.put("teams", teamList);
        out.put("halves", halves);
        out.put("frames", frames);
        out.put("holders", holders);
        out.put("roles", rolesLog);
        // energia celor 22 (procente), cate un rand pe minut de joc
        Map<String, Object> energy = new LinkedHashMap<>();
        energy.put("every", ENERGY_LOG * DT);
        energy.put("rows", energyLog);
        out.put("energy", energy);
        out.put("events", events);
        out.put("summary", summary());
        if (koAggregate != null) {
            out.put("knockout", knockoutResult());
        }
        if (!LEGACY) {
            // post-procesare peste evenimente (fara tragere din generator): notele si fazele importante
            out.put("players", MatchStats.players(this));
            out.put("highlights", Highlights.segments(this));
        }
        return out;
    }

    public int[] score() {
        return new int[] {teams[0].goals, teams[1].goals};
    }
}
