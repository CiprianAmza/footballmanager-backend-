package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoringFingerprintService;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure paired 38-match/season harness. No Spring, repository, clock, or domain entity. */
public final class ScoringSensitivityHarness {
    private final CompartmentEngineConfig baselineCompartment;
    private final MatchEngineConfig baselineMatch;
    private final CanonicalScoreSampler sampler;
    private final CalibrationInputFactory inputFactory = new CalibrationInputFactory();
    private final CanonicalScoringFingerprintService fingerprints = new CanonicalScoringFingerprintService();

    public ScoringSensitivityHarness(CompartmentEngineConfig compartment, MatchEngineConfig match,
                                     CanonicalScoreSampler sampler) {
        baselineCompartment = Objects.requireNonNull(compartment, "compartment");
        baselineMatch = Objects.requireNonNull(match, "match");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
    }

    /** Runs baseline and tested pipelines from the same raw fixture and paired seeds. */
    public ScoringSensitivityResult run(ScoringSensitivityScenario scenario,
                                        CanonicalScoringWeightCatalog catalog,
                                        CanonicalScoringWeightOverride override) {
        Objects.requireNonNull(scenario, "scenario");
        CanonicalScoringWeightSet baseline = CanonicalScoringWeightSet.baseline(baselineCompartment, baselineMatch);
        CanonicalScoringWeightSet tested = baseline.override(catalog, override);
        CanonicalMatchEvaluationAdapter baselineAdapter = new CanonicalMatchEvaluationAdapter(
                baseline.compartment(), baseline.match());
        CanonicalMatchEvaluationAdapter testedAdapter = new CanonicalMatchEvaluationAdapter(
                tested.compartment(), tested.match());
        CanonicalRuntimeTeamInput baselineHome = inputFactory.build(scenario.baselineTeam(), baseline);
        CanonicalRuntimeTeamInput baselineAway = inputFactory.build(scenario.opponent(), baseline);
        CanonicalRuntimeTeamInput testedHome = inputFactory.build(scenario.baselineTeam(), tested);
        CanonicalRuntimeTeamInput testedAway = inputFactory.build(scenario.opponent(), tested);
        List<SeasonStats> baseSeasons = runSeasons(scenario, baselineAdapter, baselineHome, baselineAway);
        List<SeasonStats> testedSeasons = runSeasons(scenario, testedAdapter, testedHome, testedAway);
        double baselineAverage = baseSeasons.stream().mapToDouble(SeasonStats::points).average().orElseThrow();
        double testedAverage = testedSeasons.stream().mapToDouble(SeasonStats::points).average().orElseThrow();
        List<Double> paired = new ArrayList<>();
        for (int i = 0; i < baseSeasons.size(); i++) paired.add((double) testedSeasons.get(i).points() - baseSeasons.get(i).points());
        double ci = confidence95(paired);
        SeasonStats b = SeasonStats.sum(baseSeasons);
        SeasonStats t = SeasonStats.sum(testedSeasons);
        int matches = scenario.seasons() * 38;
        return new ScoringSensitivityResult(scenario.name(), scenario.seed(), scenario.seasons(), matches,
                override.key(), catalog.require(override.key()).baselineValue() instanceof Number n ? n.doubleValue() : override.value(),
                override.value(), baselineAverage, testedAverage, testedAverage, testedAverage - baselineAverage,
                b.gf(), t.gf(), b.ga(), t.ga(), b.xgf(), t.xgf(), b.xga(), t.xga(),
                t.gf(), t.ga(), t.xgf(), t.xga(), t.wins(), t.draws(), t.losses(),
                t.attack() / matches, t.midfield() / matches, t.defense() / matches,
                t.protection() / matches, ci,
                matches, fingerprints.configFingerprint(baseline.compartment(), baseline.match()),
                fingerprints.configFingerprint(tested.compartment(), tested.match()), paired);
    }

    private List<SeasonStats> runSeasons(ScoringSensitivityScenario scenario,
                                         CanonicalMatchEvaluationAdapter adapter,
                                         CanonicalRuntimeTeamInput homeTeam,
                                         CanonicalRuntimeTeamInput awayTeam) {
        List<SeasonStats> seasons = new ArrayList<>();
        for (int season = 0; season < scenario.seasons(); season++) {
            SeasonStats stats = new SeasonStats();
            for (int match = 0; match < 38; match++) {
                boolean homeMatch = scenario.isHomeMatch(match);
                CanonicalRuntimeTeamInput home = homeMatch ? homeTeam : awayTeam;
                CanonicalRuntimeTeamInput away = homeMatch ? awayTeam : homeTeam;
                CanonicalMatchEvaluation evaluation = adapter.evaluate(home, away, MatchVenue.HOME);
                CanonicalScoreSampler.GoalSample sample = sampler.sample(evaluation, scenario.seed() + season * 38L + match);
                int own = home == homeTeam ? sample.homeGoals() : sample.awayGoals();
                int opponent = home == homeTeam ? sample.awayGoals() : sample.homeGoals();
                stats.add(own, opponent, home == homeTeam ? evaluation.probability().homeXg() : evaluation.probability().awayXg(),
                        home == homeTeam ? evaluation.probability().awayXg() : evaluation.probability().homeXg(),
                        home == homeTeam ? evaluation.home() : evaluation.away());
            }
            seasons.add(stats);
        }
        return seasons;
    }

    private static double confidence95(List<Double> deltas) {
        if (deltas.size() < 2) return 0.0;
        double mean = deltas.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = deltas.stream().mapToDouble(d -> (d - mean) * (d - mean)).sum() / (deltas.size() - 1);
        return 1.96 * Math.sqrt(variance / deltas.size());
    }

    private static final class SeasonStats {
        private int points, gf, ga, wins, draws, losses;
        private double xgf, xga, attack, midfield, defense, protection;
        void add(int own, int opponent, double ownXg, double opponentXg, CanonicalTeamEvaluation evaluation) {
            gf += own; ga += opponent; xgf += ownXg; xga += opponentXg;
            if (own > opponent) { points += 3; wins++; } else if (own == opponent) { points++; draws++; } else losses++;
            attack += evaluation.team().rawTotals().attack(); midfield += evaluation.team().rawTotals().midfield();
            defense += evaluation.team().rawTotals().defense(); protection += evaluation.team().attackProtection();
        }
        int points() { return points; }
        int gf() { return gf; } int ga() { return ga; } int wins() { return wins; } int draws() { return draws; } int losses() { return losses; }
        double xgf() { return xgf; } double xga() { return xga; } double attack() { return attack; } double midfield() { return midfield; }
        double defense() { return defense; } double protection() { return protection; }
        static SeasonStats sum(List<SeasonStats> values) { SeasonStats out = new SeasonStats(); values.forEach(v -> { out.points += v.points; out.gf += v.gf; out.ga += v.ga; out.wins += v.wins; out.draws += v.draws; out.losses += v.losses; out.xgf += v.xgf; out.xga += v.xga; out.attack += v.attack; out.midfield += v.midfield; out.defense += v.defense; out.protection += v.protection; }); return out; }
    }
}
