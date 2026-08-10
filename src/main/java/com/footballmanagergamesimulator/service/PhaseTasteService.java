package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.MatchPhaseData;
import com.footballmanagergamesimulator.frontend.MatchPhaseData.PhaseAction;
import com.footballmanagergamesimulator.model.PhaseRating;
import com.footballmanagergamesimulator.repository.PhaseRatingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Phase Lab's learning agent. Two cooperating learners, both online and
 * dependency-free:
 *
 * <ul>
 *   <li><b>Per-scenario bandit (UCB1)</b> — decides WHAT to show next and,
 *   after enough evidence, whether a scenario is KEPT or DROPPED. Dropped
 *   scenarios stop appearing in live matches (weight 0 in the engine's
 *   scenario choice).</li>
 *   <li><b>Online linear model (SGD)</b> — learns which phase FEATURES the
 *   user likes (length, dribbles, crosses, fouls, arcs, outcome…) from every
 *   rating, and scores candidate phases so /next proposes the candidate the
 *   model predicts the user will rate highest.</li>
 * </ul>
 *
 * All state is rebuilt from the persisted ratings at startup, so the agent
 * survives restarts and its decisions are reproducible.
 */
@Service
public class PhaseTasteService {

    /** Verdict thresholds: after this many ratings a scenario is judged. */
    static final int VERDICT_MIN_SAMPLES = 8;
    static final double DROP_BELOW = 2.5;
    static final double KEEP_ABOVE = 3.8;

    @Autowired
    PhaseRatingRepository repository;
    /** Lazy breaks the MatchPhaseEngine → taste → generator → engine cycle:
     *  the proxy resolves at first use (ApplicationReady), when every bean
     *  already exists. */
    @Autowired
    @org.springframework.context.annotation.Lazy
    PhaseLabGenerator generator;

    /** The durable memory. The game DB is deliberately disposable
     *  (in-memory H2 + create-drop), so what the agent has learned lives in
     *  this CSV instead and survives every backend restart. */
    static final java.nio.file.Path STORE =
            java.nio.file.Paths.get("data", "phase-ratings.csv");

    private static class Stats {
        int n;
        double sum;
        double mean() { return n == 0 ? 0 : sum / n; }
    }

    private final Map<String, Stats> byScenario = new HashMap<>();
    private int totalRatings = 0;

    /** SGD weights for the feature model (see features()). */
    private final double[] weights = new double[FEATURE_COUNT];
    private static final int FEATURE_COUNT = 12;
    private static final double LEARNING_RATE = 0.04;

    /** Runs AFTER the full context is up (not @PostConstruct) — regenerating
     *  phases for the feature model needs the whole engine chain alive. */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public synchronized void rebuild() {
        byScenario.clear();
        totalRatings = 0;
        java.util.Arrays.fill(weights, 0);
        weights[0] = 3.0; // bias starts at the scale midpoint
        if (!java.nio.file.Files.exists(STORE)) return;
        try {
            for (String line : java.nio.file.Files.readAllLines(STORE)) {
                String[] parts = line.split(",");
                if (parts.length < 5) continue;
                PhaseRating rating = new PhaseRating();
                rating.setStrategy(parts[0]);
                rating.setScenario(parts[1]);
                rating.setOutcome(parts[2]);
                rating.setSeed(Long.parseLong(parts[3]));
                rating.setRating(Integer.parseInt(parts[4]));
                // seed 0 marks bootstrap rows whose exact phase is unknown —
                // they train the bandit only; real rows regenerate their
                // phase deterministically and retrain the feature model too.
                MatchPhaseData phase = rating.getSeed() != 0
                        ? generator.buildPhase(rating.getStrategy(), rating.getScenario(),
                                rating.getOutcome(), rating.getSeed())
                        : null;
                absorb(rating, phase);
            }
        } catch (Exception e) {
            // A corrupt store must never block boot; the agent just starts fresh.
        }
    }

    /** Record one verdict (with the regenerated phase for its features). */
    public synchronized void rate(PhaseRating rating, MatchPhaseData phase) {
        repository.save(rating);
        absorb(rating, phase);
        try {
            java.nio.file.Files.createDirectories(STORE.getParent());
            String line = String.join(",", rating.getStrategy(), rating.getScenario(),
                    rating.getOutcome(), String.valueOf(rating.getSeed()),
                    String.valueOf(rating.getRating()),
                    String.valueOf(rating.getCreatedAt())) + "\n";
            java.nio.file.Files.writeString(STORE, line,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            // Disk hiccup: the in-memory state still has the rating.
        }
    }

    private void absorb(PhaseRating rating, MatchPhaseData phase) {
        byScenario.computeIfAbsent(rating.getScenario(), k -> new Stats());
        Stats stats = byScenario.get(rating.getScenario());
        stats.n++;
        stats.sum += rating.getRating();
        totalRatings++;
        if (phase != null) {
            double[] f = features(phase);
            double predicted = dot(f);
            double error = rating.getRating() - predicted;
            for (int i = 0; i < FEATURE_COUNT; i++) {
                weights[i] += LEARNING_RATE * error * f[i];
            }
        }
    }

    // ------------------------------------------------------------ engine hooks

    /** Live-match weight of one scenario: 0 = dropped (never plays), around
     *  1 while still learning, up to 2 for loved scenarios. */
    public synchronized double scenarioWeight(String scenario) {
        Stats stats = byScenario.get(scenario);
        if (stats == null || stats.n < 3) return 1.0;
        double mean = stats.mean();
        if (stats.n >= VERDICT_MIN_SAMPLES && mean < DROP_BELOW) return 0.0;
        return Math.max(0.4, Math.min(2.0, mean / 3.0));
    }

    /** Family weight = average of its scenarios' weights. */
    public synchronized double strategyWeight(String strategy) {
        List<String> pool = MatchPhaseEngine.SCENARIOS.get(strategy);
        if (pool == null || pool.isEmpty()) return 1.0;
        double sum = 0;
        for (String scenario : pool) sum += scenarioWeight(scenario);
        return sum / pool.size();
    }

    // ------------------------------------------------------------ lab hooks

    public record ScenarioStat(String scenario, int ratings, double avgRating,
                               String status, double ucbScore) {}

    public synchronized ScenarioStat statOf(String scenario) {
        Stats stats = byScenario.getOrDefault(scenario, new Stats());
        double mean = stats.mean();
        String status = stats.n >= VERDICT_MIN_SAMPLES
                ? (mean < DROP_BELOW ? "DROPPED" : mean >= KEEP_ABOVE ? "KEPT" : "TRIAL")
                : "EXPLORING";
        // UCB1: optimistic score — untried scenarios look attractive, so the
        // agent explores the whole library before it starts exploiting.
        double exploration = Math.sqrt(2 * Math.log(totalRatings + 2) / (stats.n + 1));
        double ucb = (stats.n == 0 ? 3.0 : mean) + 1.1 * exploration;
        return new ScenarioStat(scenario, stats.n, Math.round(mean * 100) / 100.0, status, ucb);
    }

    /** The bandit's pick: highest-UCB scenario that is not dropped. */
    public synchronized String[] pickScenario() {
        String bestStrategy = null;
        String bestScenario = null;
        double bestScore = -1;
        for (Map.Entry<String, List<String>> family : MatchPhaseEngine.SCENARIOS.entrySet()) {
            for (String scenario : family.getValue()) {
                ScenarioStat stat = statOf(scenario);
                if ("DROPPED".equals(stat.status())) continue;
                if (stat.ucbScore() > bestScore) {
                    bestScore = stat.ucbScore();
                    bestStrategy = family.getKey();
                    bestScenario = scenario;
                }
            }
        }
        return new String[] { bestStrategy == null ? "POSSESSION" : bestStrategy,
                              bestScenario == null ? "TIKI_TAKA" : bestScenario };
    }

    /** Model prediction (1..5) for a generated phase. */
    public synchronized double predict(MatchPhaseData phase) {
        return Math.max(1, Math.min(5, dot(features(phase))));
    }

    private double dot(double[] f) {
        double sum = 0;
        for (int i = 0; i < FEATURE_COUNT; i++) sum += weights[i] * f[i];
        return sum;
    }

    /** Normalised description of what a phase LOOKS like — the model learns
     *  the user's taste over these. */
    static double[] features(MatchPhaseData phase) {
        List<PhaseAction> actions = phase.getActions();
        int passes = 0, dribbles = 0, skills = 0, crosses = 0, headers = 0;
        int fouls = 0, longBalls = 0;
        double totalMs = 0;
        for (PhaseAction a : actions) {
            totalMs += a.getDurationMs();
            switch (a.getType()) {
                case "PASS", "BACK_PASS", "LATERAL_PASS" -> passes++;
                case "THROUGH_BALL" -> { passes++; longBalls++; }
                case "DRIBBLE", "SPRINT_DRIBBLE" -> dribbles++;
                case "STEPOVER", "CUT_LEFT", "CUT_RIGHT", "ROULETTE", "SKILL_MOVE" -> skills++;
                case "CROSS", "RABONA_CROSS" -> crosses++;
                case "HEADER" -> headers++;
                case "FOUL" -> fouls++;
                default -> { }
            }
            if (a.getPeakHeight() > 2.0) longBalls++;
        }
        String end = phase.getEndEvent() == null ? "" : phase.getEndEvent();
        return new double[] {
                1.0,
                actions.size() / 20.0,
                passes / 15.0,
                dribbles / 6.0,
                skills / 4.0,
                crosses / 2.0,
                headers / 2.0,
                fouls,
                longBalls / 3.0,
                totalMs / 12000.0,
                "goal".equals(end) ? 1 : 0,
                end.startsWith("shot") ? 1 : 0
        };
    }
}
