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
        activateFallbackConsumer(override.key(), baseline, tested);
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
                fingerprints.configFingerprint(tested.compartment(), tested.match()), paired,
                b.attack() / matches, t.attack() / matches,
                b.midfield() / matches, t.midfield() / matches,
                b.defense() / matches, t.defense() / matches,
                b.protection() / matches, t.protection() / matches,
                b.homeXg() / matches, t.homeXg() / matches,
                b.awayXg() / matches, t.awayXg() / matches,
                b.homeWin() / matches, t.homeWin() / matches,
                b.drawProbability() / matches, t.drawProbability() / matches,
                b.awayWin() / matches, t.awayWin() / matches,
                pmfL1(b, t), b.wideChannelAttack() / matches, t.wideChannelAttack() / matches, false, true);
    }

    private static void activateFallbackConsumer(String key, CanonicalScoringWeightSet baseline,
                                                  CanonicalScoringWeightSet tested) {
        if (key.endsWith("default-position-multiplier")) {
            baseline.compartment().getPositions().remove("AMC");
            tested.compartment().getPositions().remove("AMC");
        } else if (key.endsWith("default-role-multiplier")) {
            baseline.compartment().getRoles().remove(com.footballmanagergamesimulator.compartment.PlayerRole.WINGER);
            tested.compartment().getRoles().remove(com.footballmanagergamesimulator.compartment.PlayerRole.WINGER);
        }
    }

    private List<SeasonStats> runSeasons(ScoringSensitivityScenario scenario,
                                         CanonicalMatchEvaluationAdapter adapter,
                                         CanonicalRuntimeTeamInput homeTeam,
                                         CanonicalRuntimeTeamInput awayTeam) {
        List<SeasonStats> seasons = new ArrayList<>();
        CanonicalMatchEvaluation candidateHomeEvaluation = adapter.evaluate(
                homeTeam, awayTeam, MatchVenue.HOME);
        CanonicalMatchEvaluation candidateAwayEvaluation = adapter.evaluate(
                awayTeam, homeTeam, MatchVenue.HOME);
        for (int season = 0; season < scenario.seasons(); season++) {
            SeasonStats stats = new SeasonStats();
            for (int match = 0; match < 38; match++) {
                boolean homeMatch = scenario.isHomeMatch(match);
                CanonicalMatchEvaluation evaluation = homeMatch
                        ? candidateHomeEvaluation : candidateAwayEvaluation;
                CanonicalScoreSampler.GoalSample sample = sampler.sample(evaluation, scenario.seed() + season * 38L + match);
                int own = homeMatch ? sample.homeGoals() : sample.awayGoals();
                int opponent = homeMatch ? sample.awayGoals() : sample.homeGoals();
                stats.add(own, opponent, evaluation,
                        homeMatch ? evaluation.home() : evaluation.away(), homeMatch);
            }
            seasons.add(stats);
        }
        return seasons;
    }

    private static double pmfL1(SeasonStats baseline, SeasonStats tested) {
        java.util.Set<Integer> goals = new java.util.HashSet<>();
        goals.addAll(baseline.homePmf().keySet()); goals.addAll(tested.homePmf().keySet());
        goals.addAll(baseline.awayPmf().keySet()); goals.addAll(tested.awayPmf().keySet());
        double l1 = 0.0;
        for (int goal : goals) {
            l1 += Math.abs(baseline.homePmf().getOrDefault(goal, 0.0) - tested.homePmf().getOrDefault(goal, 0.0));
            l1 += Math.abs(baseline.awayPmf().getOrDefault(goal, 0.0) - tested.awayPmf().getOrDefault(goal, 0.0));
        }
        return l1;
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
        private double homeXg, awayXg, homeWin, drawProbability, awayWin, wideChannelAttack;
        private final java.util.Map<Integer, Double> homePmf = new java.util.HashMap<>();
        private final java.util.Map<Integer, Double> awayPmf = new java.util.HashMap<>();
        void add(int own, int opponent, com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation evaluation,
                 CanonicalTeamEvaluation evaluatedTeam, boolean evaluatedHome) {
            gf += own; ga += opponent;
            xgf += evaluatedHome ? evaluation.probability().homeXg() : evaluation.probability().awayXg();
            xga += evaluatedHome ? evaluation.probability().awayXg() : evaluation.probability().homeXg();
            homeXg += evaluation.probability().homeXg(); awayXg += evaluation.probability().awayXg();
            homeWin += evaluation.outcome().homeWin(); drawProbability += evaluation.outcome().draw(); awayWin += evaluation.outcome().awayWin();
            double[] homeProbabilities = evaluation.probability().homeGoals().probabilities();
            double[] awayProbabilities = evaluation.probability().awayGoals().probabilities();
            for (int i = 0; i < homeProbabilities.length; i++) homePmf.merge(i, homeProbabilities[i], Double::sum);
            for (int i = 0; i < awayProbabilities.length; i++) awayPmf.merge(i, awayProbabilities[i], Double::sum);
            if (own > opponent) { points += 3; wins++; } else if (own == opponent) { points++; draws++; } else losses++;
            attack += evaluatedTeam.team().rawTotals().attack(); midfield += evaluatedTeam.team().rawTotals().midfield();
            defense += evaluatedTeam.team().rawTotals().defense(); protection += evaluatedTeam.team().attackProtection();
            wideChannelAttack += evaluatedTeam.team().channelBreakdown().values().stream()
                    .filter(channel -> channel.channel() != com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.WideChannel.CENTRAL)
                    .mapToDouble(com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator.ChannelBreakdown::attack)
                    .sum();
        }
        int points() { return points; }
        int gf() { return gf; } int ga() { return ga; } int wins() { return wins; } int draws() { return draws; } int losses() { return losses; }
        double xgf() { return xgf; } double xga() { return xga; } double attack() { return attack; } double midfield() { return midfield; }
        double defense() { return defense; } double protection() { return protection; }
        double homeXg() { return homeXg; } double awayXg() { return awayXg; }
        double homeWin() { return homeWin; } double drawProbability() { return drawProbability; } double awayWin() { return awayWin; }
        double wideChannelAttack() { return wideChannelAttack; }
        java.util.Map<Integer, Double> homePmf() { return java.util.Map.copyOf(homePmf); }
        java.util.Map<Integer, Double> awayPmf() { return java.util.Map.copyOf(awayPmf); }
        static SeasonStats sum(List<SeasonStats> values) { SeasonStats out = new SeasonStats(); values.forEach(v -> { out.points += v.points; out.gf += v.gf; out.ga += v.ga; out.wins += v.wins; out.draws += v.draws; out.losses += v.losses; out.xgf += v.xgf; out.xga += v.xga; out.attack += v.attack; out.midfield += v.midfield; out.defense += v.defense; out.protection += v.protection; out.homeXg += v.homeXg; out.awayXg += v.awayXg; out.homeWin += v.homeWin; out.drawProbability += v.drawProbability; out.awayWin += v.awayWin; out.wideChannelAttack += v.wideChannelAttack; v.homePmf.forEach((goal, probability) -> out.homePmf.merge(goal, probability, Double::sum)); v.awayPmf.forEach((goal, probability) -> out.awayPmf.merge(goal, probability, Double::sum)); }); return out; }
    }
}
