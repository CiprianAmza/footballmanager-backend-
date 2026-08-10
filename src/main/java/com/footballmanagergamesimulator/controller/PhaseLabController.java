package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.LiveMatchData.LiveMatchMinute;
import com.footballmanagergamesimulator.frontend.MatchPhaseData;
import com.footballmanagergamesimulator.model.PhaseRating;
import com.footballmanagergamesimulator.service.MatchPhaseEngine;
import com.footballmanagergamesimulator.service.PhaseLabGenerator;
import com.footballmanagergamesimulator.service.PhaseTasteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Phase Lab (dev tool): generate presentation phases from the scenario
 * library, watch them on a pitch, rate them 1-5, and let the taste agent
 * ({@link PhaseTasteService}) learn which scenarios stay in the game.
 *
 * <p>Dev-only, mirroring the Face Lab pattern: reachable while
 * {@code phaselab.enabled} is true (the default in this test project).
 */
@RestController
@RequestMapping("/api/dev/phaselab")
@ConditionalOnProperty(name = "phaselab.enabled", havingValue = "true", matchIfMissing = true)
public class PhaseLabController {

    /** Every lab phase ends with a SHOT — this system strictly simulates
     *  goal/miss presentations layered over the backend's decision. Corners
     *  and fouls appear as flavour INSIDE chains, never as endings. */
    private static final List<String> OUTCOMES = List.of(
            "goal", "shot_saved", "shot_wide", "shot_blocked");

    @Autowired
    PhaseLabGenerator generator;
    @Autowired
    PhaseTasteService taste;

    // ------------------------------------------------------------ DTOs

    public record PlayerDto(long id, String name, String role, long teamId,
                            int shirtNumber, boolean goalkeeper) {}

    public record PreviewDto(String strategy, String scenario, String outcome, long seed,
                             double predictedRating, MatchPhaseData phase,
                             List<PlayerDto> players, long homeTeamId, long awayTeamId) {}

    public record ScenarioDto(String name, int ratings, double avgRating, String status) {}

    public record StrategyDto(String name, List<ScenarioDto> scenarios) {}

    public record CatalogDto(List<StrategyDto> strategies, List<String> outcomes,
                             int totalCombos, int totalRatings) {}

    public record RateRequest(String strategy, String scenario, String outcome,
                              long seed, int rating) {}

    // ------------------------------------------------------------ endpoints

    @GetMapping("/catalog")
    public CatalogDto catalog() {
        List<StrategyDto> strategies = new ArrayList<>();
        int totalRatings = 0;
        for (Map.Entry<String, List<String>> family : MatchPhaseEngine.SCENARIOS.entrySet()) {
            List<ScenarioDto> scenarios = new ArrayList<>();
            for (String scenario : family.getValue()) {
                PhaseTasteService.ScenarioStat stat = taste.statOf(scenario);
                totalRatings += stat.ratings();
                scenarios.add(new ScenarioDto(scenario, stat.ratings(), stat.avgRating(), stat.status()));
            }
            strategies.add(new StrategyDto(family.getKey(), scenarios));
        }
        int combos = MatchPhaseEngine.SCENARIOS.values().stream().mapToInt(List::size).sum()
                * OUTCOMES.size();
        return new CatalogDto(strategies, OUTCOMES, combos, totalRatings);
    }

    /** Deterministic generation: same tuple → the exact same phase. */
    @GetMapping("/generate")
    public PreviewDto generate(@RequestParam String strategy, @RequestParam String scenario,
                               @RequestParam String outcome, @RequestParam long seed) {
        MatchPhaseData phase = buildPhase(strategy, scenario, outcome, seed);
        double predicted = phase == null ? 0 : taste.predict(phase);
        return new PreviewDto(strategy, scenario, outcome, seed,
                Math.round(predicted * 100) / 100.0, phase, roster(), 1, 2);
    }

    /** The agent's proposal: UCB bandit picks the scenario that needs
     *  judging most, then the linear model picks (from a handful of candidate
     *  seeds) the variant it predicts the user will rate highest — with a
     *  slice of pure exploration so the model keeps seeing fresh shapes. */
    @GetMapping("/next")
    public PreviewDto next() {
        String[] pick = taste.pickScenario();
        Random rng = new Random();
        String outcome = OUTCOMES.get(rng.nextInt(OUTCOMES.size()));
        long bestSeed = rng.nextLong(1_000_000);
        MatchPhaseData best = buildPhase(pick[0], pick[1], outcome, bestSeed);
        double bestPredicted = best == null ? 0 : taste.predict(best);
        // Candidate ranking — skipped 25% of the time (exploration).
        if (rng.nextDouble() > 0.25) {
            for (int i = 0; i < 5; i++) {
                long seed = rng.nextLong(1_000_000);
                MatchPhaseData candidate = buildPhase(pick[0], pick[1], outcome, seed);
                double predicted = candidate == null ? 0 : taste.predict(candidate);
                if (predicted > bestPredicted) {
                    bestPredicted = predicted;
                    bestSeed = seed;
                    best = candidate;
                }
            }
        }
        return new PreviewDto(pick[0], pick[1], outcome, bestSeed,
                Math.round(bestPredicted * 100) / 100.0, best, roster(), 1, 2);
    }

    /** One verdict: persists, updates the bandit + the feature model, and
     *  returns the scenario's refreshed stats (so the UI shows KEPT/DROPPED
     *  the moment the threshold is crossed). */
    @PostMapping("/rate")
    public ScenarioDto rate(@RequestBody RateRequest request) {
        PhaseRating rating = new PhaseRating();
        rating.setStrategy(request.strategy());
        rating.setScenario(request.scenario());
        rating.setOutcome(request.outcome());
        rating.setSeed(request.seed());
        rating.setRating(Math.max(1, Math.min(5, request.rating())));
        rating.setCreatedAt(System.currentTimeMillis());
        MatchPhaseData phase = buildPhase(request.strategy(), request.scenario(),
                request.outcome(), request.seed());
        taste.rate(rating, phase);
        PhaseTasteService.ScenarioStat stat = taste.statOf(request.scenario());
        return new ScenarioDto(stat.scenario(), stat.ratings(), stat.avgRating(), stat.status());
    }

    // ------------------------------------------------------------ generation

    private MatchPhaseData buildPhase(String strategy, String scenario, String outcome, long seed) {
        return generator.buildPhase(strategy, scenario, outcome, seed);
    }

    /** Roster the frontend needs to draw the two elevens. */
    private List<PlayerDto> roster() {
        List<PlayerDto> players = new ArrayList<>();
        // The roster must match the ids generated in buildPhase — same roles,
        // same base ids; names differ per seed but the frontend takes names
        // from the phase actions' playerName fields, so stable ids suffice.
        for (int team = 0; team < 2; team++) {
            long base = team == 0 ? 100 : 200;
            String[] roles = PhaseLabGenerator.ROLES;
            for (int i = 0; i < roles.length; i++) {
                players.add(new PlayerDto(base + i + 1, roles[i] + " " + (i + 1), roles[i],
                        team + 1, i + 1, i == 0));
            }
        }
        return players;
    }
}
