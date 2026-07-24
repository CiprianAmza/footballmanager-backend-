package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.Mentality;
import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.shadow.CompartmentShadowObservation;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public final class CompartmentCalibrationAccumulator {
    private long sampleCount;
    private double legacyHomeGoals;
    private double legacyAwayGoals;
    private double canonicalHomeXg;
    private double canonicalAwayXg;
    private long legacyHomeWins;
    private long legacyDraws;
    private long legacyAwayWins;
    private double canonicalHomeWins;
    private double canonicalDraws;
    private double canonicalAwayWins;
    private double brier;
    private double logLoss;
    private long favoriteDecided;
    private long observedUpsets;
    private double expectedUpsets;
    private double totalDuration;
    private long maxDuration;
    private long[] legacyHistogram;
    private double[] canonicalHistogram;
    private SegmentTotals defensive = new SegmentTotals();
    private SegmentTotals nonDefensive = new SegmentTotals();
    private SegmentTotals stayForwardPresent = new SegmentTotals();
    private SegmentTotals stayForwardAbsent = new SegmentTotals();

    public synchronized void record(CompartmentShadowObservation observation) {
        if (observation == null) throw new NullPointerException("observation");
        CanonicalMatchEvaluation evaluation = observation.canonicalEvaluation();
        double[] homePmf = evaluation.probability().homeGoals().probabilities();
        double[] awayPmf = evaluation.probability().awayGoals().probabilities();
        int goalCap = evaluation.probability().homeGoals().cap();
        if (evaluation.probability().awayGoals().cap() != goalCap) {
            throw new IllegalArgumentException("home and away goal caps must match");
        }
        ensureHistogram(goalCap * 2 + 1);

        sampleCount++;
        legacyHomeGoals += observation.legacyHomeScore();
        legacyAwayGoals += observation.legacyAwayScore();
        canonicalHomeXg += evaluation.probability().homeXg();
        canonicalAwayXg += evaluation.probability().awayXg();
        switch (observation.legacyResult()) {
            case HOME_WIN -> legacyHomeWins++;
            case DRAW -> legacyDraws++;
            case AWAY_WIN -> legacyAwayWins++;
        }
        canonicalHomeWins += evaluation.outcome().homeWin();
        canonicalDraws += evaluation.outcome().draw();
        canonicalAwayWins += evaluation.outcome().awayWin();
        double yHome = observation.legacyResult() == CompartmentShadowObservation.LegacyResult.HOME_WIN ? 1.0 : 0.0;
        double yDraw = observation.legacyResult() == CompartmentShadowObservation.LegacyResult.DRAW ? 1.0 : 0.0;
        double yAway = observation.legacyResult() == CompartmentShadowObservation.LegacyResult.AWAY_WIN ? 1.0 : 0.0;
        brier += (square(evaluation.outcome().homeWin() - yHome)
                + square(evaluation.outcome().draw() - yDraw)
                + square(evaluation.outcome().awayWin() - yAway)) / 3.0;
        double assigned = switch (observation.legacyResult()) {
            case HOME_WIN -> evaluation.outcome().homeWin();
            case DRAW -> evaluation.outcome().draw();
            case AWAY_WIN -> evaluation.outcome().awayWin();
        };
        logLoss += -Math.log(Math.max(assigned, 1e-12));
        double homeProbability = evaluation.outcome().homeWin();
        double awayProbability = evaluation.outcome().awayWin();
        if (homeProbability - awayProbability >= 0.10 && observation.legacyResult() != CompartmentShadowObservation.LegacyResult.DRAW) {
            favoriteDecided++;
            if (observation.legacyResult() == CompartmentShadowObservation.LegacyResult.AWAY_WIN) observedUpsets++;
            expectedUpsets += awayProbability / (homeProbability + awayProbability);
        } else if (awayProbability - homeProbability >= 0.10
                && observation.legacyResult() != CompartmentShadowObservation.LegacyResult.DRAW) {
            favoriteDecided++;
            if (observation.legacyResult() == CompartmentShadowObservation.LegacyResult.HOME_WIN) observedUpsets++;
            expectedUpsets += homeProbability / (homeProbability + awayProbability);
        }

        int legacyTotal = observation.legacyHomeScore() + observation.legacyAwayScore();
        legacyHistogram[Math.min(legacyTotal, legacyHistogram.length - 1)]++;
        for (int homeGoals = 0; homeGoals < homePmf.length; homeGoals++) {
            for (int awayGoals = 0; awayGoals < awayPmf.length; awayGoals++) {
                canonicalHistogram[Math.min(homeGoals + awayGoals, canonicalHistogram.length - 1)]
                        += homePmf[homeGoals] * awayPmf[awayGoals];
            }
        }
        totalDuration += observation.totalDurationNanos();
        maxDuration = Math.max(maxDuration, observation.totalDurationNanos());

        recordTeam(evaluation.home(), observation.legacyHomeScore(), observation.legacyAwayScore(),
                evaluation.probability().homeXg(), evaluation.probability().awayXg(),
                isDefensive(evaluation.home().team().mentality()), hasStayForward(evaluation.home()));
        recordTeam(evaluation.away(), observation.legacyAwayScore(), observation.legacyHomeScore(),
                evaluation.probability().awayXg(), evaluation.probability().homeXg(),
                isDefensive(evaluation.away().team().mentality()), hasStayForward(evaluation.away()));
    }

    public synchronized CompartmentCalibrationSnapshot snapshot() {
        return new CompartmentCalibrationSnapshot(
                sampleCount,
                mean(legacyHomeGoals), mean(legacyAwayGoals), mean(legacyHomeGoals + legacyAwayGoals),
                mean(canonicalHomeXg), mean(canonicalAwayXg), mean(canonicalHomeXg + canonicalAwayXg),
                rate(legacyHomeWins), rate(legacyDraws), rate(legacyAwayWins),
                mean(canonicalHomeWins), mean(canonicalDraws), mean(canonicalAwayWins),
                mean(brier), mean(logLoss), favoriteDecided, observedUpsets,
                favoriteDecided == 0 ? 0.0 : (double) observedUpsets / favoriteDecided,
                favoriteDecided == 0 ? 0.0 : expectedUpsets / favoriteDecided,
                histogram(legacyHistogram), histogram(canonicalHistogram),
                segment(defensive), segment(nonDefensive),
                segment(stayForwardPresent), segment(stayForwardAbsent),
                sampleCount == 0 ? 0.0 : totalDuration / sampleCount, maxDuration);
    }

    public synchronized void reset() {
        sampleCount = 0;
        legacyHomeGoals = legacyAwayGoals = canonicalHomeXg = canonicalAwayXg = 0.0;
        legacyHomeWins = legacyDraws = legacyAwayWins = 0;
        canonicalHomeWins = canonicalDraws = canonicalAwayWins = brier = logLoss = 0.0;
        favoriteDecided = observedUpsets = 0;
        expectedUpsets = totalDuration = 0.0;
        maxDuration = 0;
        legacyHistogram = null;
        canonicalHistogram = null;
        defensive = new SegmentTotals();
        nonDefensive = new SegmentTotals();
        stayForwardPresent = new SegmentTotals();
        stayForwardAbsent = new SegmentTotals();
    }

    private void recordTeam(CanonicalTeamEvaluation team, int goalsFor, int goalsAgainst,
                            double xgFor, double xgAgainst, boolean defensiveMentality, boolean stayForward) {
        SegmentTotals mentalitySegment = defensiveMentality ? defensive : nonDefensive;
        mentalitySegment.add(team, goalsFor, goalsAgainst, xgFor, xgAgainst);
        (stayForward ? stayForwardPresent : stayForwardAbsent).add(team, goalsFor, goalsAgainst, xgFor, xgAgainst);
    }

    private static boolean isDefensive(Mentality mentality) {
        return mentality == Mentality.DEFENSIVE || mentality == Mentality.VERY_DEFENSIVE;
    }

    private static boolean hasStayForward(CanonicalTeamEvaluation team) {
        return team.team().players().stream().anyMatch(player -> player.instruction() == ForwardInstruction.STAY_FORWARD);
    }

    private void ensureHistogram(int size) {
        if (legacyHistogram == null) {
            legacyHistogram = new long[size];
            canonicalHistogram = new double[size];
        } else if (legacyHistogram.length != size) {
            throw new IllegalArgumentException("all observations must use the same goal cap");
        }
    }

    private Map<Integer, Long> histogram(long[] values) {
        if (values == null) return Map.of();
        LinkedHashMap<Integer, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) result.put(i, values[i]);
        return Collections.unmodifiableMap(result);
    }

    private Map<Integer, Double> histogram(double[] values) {
        if (values == null) return Map.of();
        LinkedHashMap<Integer, Double> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) result.put(i, values[i]);
        return Collections.unmodifiableMap(result);
    }

    private CalibrationSegmentSnapshot segment(SegmentTotals totals) {
        return new CalibrationSegmentSnapshot(totals.samples, mean(totals.goalsFor, totals.samples),
                mean(totals.goalsAgainst, totals.samples), mean(totals.xgFor, totals.samples),
                mean(totals.xgAgainst, totals.samples), mean(totals.attack, totals.samples),
                mean(totals.attackProtection, totals.samples));
    }

    private double rate(long value) {
        return sampleCount == 0 ? 0.0 : (double) value / sampleCount;
    }

    private double mean(double value) {
        return sampleCount == 0 ? 0.0 : value / sampleCount;
    }

    private static double mean(double value, long count) {
        return count == 0 ? 0.0 : value / count;
    }

    private static double square(double value) { return value * value; }

    private static final class SegmentTotals {
        private long samples;
        private double goalsFor, goalsAgainst, xgFor, xgAgainst, attack, attackProtection;

        private void add(CanonicalTeamEvaluation team, double goalsFor, double goalsAgainst,
                         double xgFor, double xgAgainst) {
            samples++;
            this.goalsFor += goalsFor;
            this.goalsAgainst += goalsAgainst;
            this.xgFor += xgFor;
            this.xgAgainst += xgAgainst;
            this.attack += team.team().attack();
            this.attackProtection += team.team().attackProtection();
        }
    }
}
