package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.ForwardInstruction;
import com.footballmanagergamesimulator.compartment.Mentality;
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
        PreparedRecord prepared = prepare(observation);

        if (legacyHistogram == null) {
            legacyHistogram = new long[prepared.histogramSize];
            canonicalHistogram = new double[prepared.histogramSize];
        }
        legacyHomeGoals += prepared.legacyHomeGoals;
        legacyAwayGoals += prepared.legacyAwayGoals;
        canonicalHomeXg += prepared.canonicalHomeXg;
        canonicalAwayXg += prepared.canonicalAwayXg;
        legacyHomeWins += prepared.legacyHomeWins;
        legacyDraws += prepared.legacyDraws;
        legacyAwayWins += prepared.legacyAwayWins;
        canonicalHomeWins += prepared.canonicalHomeWins;
        canonicalDraws += prepared.canonicalDraws;
        canonicalAwayWins += prepared.canonicalAwayWins;
        brier += prepared.brier;
        logLoss += prepared.logLoss;
        favoriteDecided += prepared.favoriteDecided;
        observedUpsets += prepared.observedUpsets;
        expectedUpsets += prepared.expectedUpsets;
        totalDuration += prepared.durationNanos;
        maxDuration = Math.max(maxDuration, prepared.durationNanos);
        legacyHistogram[prepared.legacyBucket] = prepared.nextLegacyBucket;
        for (int i = 0; i < canonicalHistogram.length; i++) {
            canonicalHistogram[i] += prepared.canonicalBuckets[i];
        }
        commitSegment(defensive, prepared.homeDefensive);
        commitSegment(defensive, prepared.awayDefensive);
        commitSegment(nonDefensive, prepared.homeDefensive == null ? prepared.homeSegment : null);
        commitSegment(nonDefensive, prepared.awayDefensive == null ? prepared.awaySegment : null);
        commitSegment(stayForwardPresent, prepared.homeStayForward);
        commitSegment(stayForwardPresent, prepared.awayStayForward);
        commitSegment(stayForwardAbsent, prepared.homeStayForward == null ? prepared.homeSegment : null);
        commitSegment(stayForwardAbsent, prepared.awayStayForward == null ? prepared.awaySegment : null);
        sampleCount = prepared.nextSampleCount;
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
                segment(defensive), segment(nonDefensive), segment(stayForwardPresent), segment(stayForwardAbsent),
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

    private PreparedRecord prepare(CompartmentShadowObservation observation) {
        if (observation == null) throw new NullPointerException("observation");
        CanonicalMatchEvaluation evaluation = observation.canonicalEvaluation();
        if (evaluation == null || observation.legacyResult() == null) {
            throw new IllegalArgumentException("observation evaluation and result are required");
        }
        var probability = evaluation.probability();
        var homeGoals = probability.homeGoals();
        var awayGoals = probability.awayGoals();
        int cap = homeGoals.cap();
        if (cap < 0 || awayGoals.cap() != cap) throw new IllegalArgumentException("home and away goal caps must match");
        double[] homePmf = checkedPmf(homeGoals.probabilities(), cap, "home");
        double[] awayPmf = checkedPmf(awayGoals.probabilities(), cap, "away");
        if (!Double.isFinite(probability.homeXg()) || probability.homeXg() < 0
                || !Double.isFinite(probability.awayXg()) || probability.awayXg() < 0) {
            throw new IllegalArgumentException("expected goals must be finite and non-negative");
        }
        var outcome = evaluation.outcome();
        validateOutcome(outcome.homeWin(), outcome.draw(), outcome.awayWin());
        int legacyTotal = Math.addExact(observation.legacyHomeScore(), observation.legacyAwayScore());
        if (observation.totalDurationNanos() < 0) throw new IllegalArgumentException("duration must be non-negative");
        if (legacyHistogram != null && legacyHistogram.length != cap * 2 + 1) {
            throw new IllegalArgumentException("all observations must use the same goal cap");
        }

        long nextSampleCount = Math.addExact(sampleCount, 1);
        long homeWins = observation.legacyResult() == CompartmentShadowObservation.LegacyResult.HOME_WIN ? 1 : 0;
        long draws = observation.legacyResult() == CompartmentShadowObservation.LegacyResult.DRAW ? 1 : 0;
        long awayWins = observation.legacyResult() == CompartmentShadowObservation.LegacyResult.AWAY_WIN ? 1 : 0;
        long nextHomeWins = Math.addExact(legacyHomeWins, homeWins);
        long nextDraws = Math.addExact(legacyDraws, draws);
        long nextAwayWins = Math.addExact(legacyAwayWins, awayWins);
        int legacyBucket = Math.min(legacyTotal, cap * 2);
        long currentBucket = legacyHistogram == null ? 0 : legacyHistogram[legacyBucket];
        long nextLegacyBucket = Math.addExact(currentBucket, 1);
        double[] canonicalBuckets = convolution(homePmf, awayPmf, cap);

        double yHome = homeWins;
        double yDraw = draws;
        double yAway = awayWins;
        double oneBrier = (square(outcome.homeWin() - yHome) + square(outcome.draw() - yDraw)
                + square(outcome.awayWin() - yAway)) / 3.0;
        double assigned = switch (observation.legacyResult()) {
            case HOME_WIN -> outcome.homeWin(); case DRAW -> outcome.draw(); case AWAY_WIN -> outcome.awayWin();
        };
        double oneLogLoss = -Math.log(Math.max(assigned, 1e-12));
        double homeProbability = outcome.homeWin();
        double awayProbability = outcome.awayWin();
        long oneFavorite = 0;
        long oneUpset = 0;
        double oneExpectedUpset = 0.0;
        if (observation.legacyResult() != CompartmentShadowObservation.LegacyResult.DRAW) {
            if (homeProbability - awayProbability >= 0.10) {
                oneFavorite = 1;
                oneUpset = observation.legacyResult() == CompartmentShadowObservation.LegacyResult.AWAY_WIN ? 1 : 0;
                oneExpectedUpset = awayProbability / (homeProbability + awayProbability);
            } else if (awayProbability - homeProbability >= 0.10) {
                oneFavorite = 1;
                oneUpset = observation.legacyResult() == CompartmentShadowObservation.LegacyResult.HOME_WIN ? 1 : 0;
                oneExpectedUpset = homeProbability / (homeProbability + awayProbability);
            }
        }
        long nextFavorite = Math.addExact(favoriteDecided, oneFavorite);
        long nextObserved = Math.addExact(observedUpsets, oneUpset);
        SegmentValues homeSegment = segmentValues(evaluation.home(), observation.legacyHomeScore(),
                observation.legacyAwayScore(), probability.homeXg(), probability.awayXg());
        SegmentValues awaySegment = segmentValues(evaluation.away(), observation.legacyAwayScore(),
                observation.legacyHomeScore(), probability.awayXg(), probability.homeXg());
        Math.addExact(defensive.samples, count(homeSegment.defensive) + count(awaySegment.defensive));
        Math.addExact(nonDefensive.samples, count(!homeSegment.defensive) + count(!awaySegment.defensive));
        Math.addExact(stayForwardPresent.samples, count(homeSegment.stayForward) + count(awaySegment.stayForward));
        Math.addExact(stayForwardAbsent.samples, count(!homeSegment.stayForward) + count(!awaySegment.stayForward));
        return new PreparedRecord(nextSampleCount, observation.legacyHomeScore(), observation.legacyAwayScore(),
                probability.homeXg(), probability.awayXg(), homeWins, draws,
                awayWins, outcome.homeWin(), outcome.draw(), outcome.awayWin(), oneBrier, oneLogLoss,
                oneFavorite, oneUpset, oneExpectedUpset, observation.totalDurationNanos(), cap * 2 + 1,
                legacyBucket, nextLegacyBucket, canonicalBuckets, homeSegment, awaySegment,
                homeSegment.defensive ? homeSegment : null, awaySegment.defensive ? awaySegment : null,
                homeSegment.stayForward ? homeSegment : null, awaySegment.stayForward ? awaySegment : null,
                nextHomeWins, nextDraws, nextAwayWins, nextFavorite, nextObserved);
    }

    private static double[] checkedPmf(double[] values, int cap, String side) {
        if (values == null || values.length != cap + 1) throw new IllegalArgumentException(side + " PMF length mismatch");
        double sum = 0.0;
        for (double value : values) {
            if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException(side + " PMF contains invalid value");
            sum += value;
        }
        if (Math.abs(sum - 1.0) > 1e-9) throw new IllegalArgumentException(side + " PMF must sum to 1.0");
        return values;
    }

    private static void validateOutcome(double home, double draw, double away) {
        if (!Double.isFinite(home) || !Double.isFinite(draw) || !Double.isFinite(away)
                || home < 0 || draw < 0 || away < 0 || Math.abs(home + draw + away - 1.0) > 1e-9) {
            throw new IllegalArgumentException("invalid outcome probabilities");
        }
    }

    private static double[] convolution(double[] home, double[] away, int cap) {
        double[] result = new double[cap * 2 + 1];
        for (int h = 0; h <= cap; h++) for (int a = 0; a <= cap; a++) result[h + a] += home[h] * away[a];
        return result;
    }

    private static SegmentValues segmentValues(CanonicalTeamEvaluation evaluation, int goalsFor, int goalsAgainst,
                                               double xgFor, double xgAgainst) {
        if (evaluation == null || evaluation.team() == null) throw new IllegalArgumentException("team is required");
        var team = evaluation.team();
        double attack = team.attack();
        double protection = team.attackProtection();
        if (!Double.isFinite(attack) || !Double.isFinite(protection)) throw new IllegalArgumentException("team metrics invalid");
        boolean defensive = team.mentality() == Mentality.DEFENSIVE || team.mentality() == Mentality.VERY_DEFENSIVE;
        boolean stayForward = team.players().stream().anyMatch(player -> player.instruction() == ForwardInstruction.STAY_FORWARD);
        return new SegmentValues(goalsFor, goalsAgainst, xgFor, xgAgainst, attack, protection, defensive, stayForward);
    }

    private void commitSegment(SegmentTotals target, SegmentValues values) { if (values != null) target.add(values); }

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
        return new CalibrationSegmentSnapshot(totals.samples, mean(totals.goalsFor, totals.samples), mean(totals.goalsAgainst, totals.samples),
                mean(totals.xgFor, totals.samples), mean(totals.xgAgainst, totals.samples), mean(totals.attack, totals.samples),
                mean(totals.attackProtection, totals.samples));
    }
    private double rate(long value) { return sampleCount == 0 ? 0.0 : (double) value / sampleCount; }
    private double mean(double value) { return sampleCount == 0 ? 0.0 : value / sampleCount; }
    private static double mean(double value, long count) { return count == 0 ? 0.0 : value / count; }
    private static double square(double value) { return value * value; }
    private static long count(boolean value) { return value ? 1L : 0L; }

    private static final class SegmentTotals {
        private long samples;
        private double goalsFor, goalsAgainst, xgFor, xgAgainst, attack, attackProtection;
        private void add(SegmentValues value) {
            samples++;
            goalsFor += value.goalsFor; goalsAgainst += value.goalsAgainst; xgFor += value.xgFor;
            xgAgainst += value.xgAgainst; attack += value.attack; attackProtection += value.protection;
        }
    }
    private record SegmentValues(double goalsFor, double goalsAgainst, double xgFor, double xgAgainst,
                                 double attack, double protection, boolean defensive, boolean stayForward) {}
    private record PreparedRecord(long nextSampleCount, double legacyHomeGoals, double legacyAwayGoals,
                                  double canonicalHomeXg, double canonicalAwayXg,
                                  long legacyHomeWins, long legacyDraws, long legacyAwayWins,
                                  double canonicalHomeWins, double canonicalDraws, double canonicalAwayWins,
                                  double brier, double logLoss, long favoriteDecided, long observedUpsets,
                                  double expectedUpsets, long durationNanos, int histogramSize, int legacyBucket,
                                  long nextLegacyBucket, double[] canonicalBuckets, SegmentValues homeSegment,
                                  SegmentValues awaySegment, SegmentValues homeDefensive, SegmentValues awayDefensive,
                                  SegmentValues homeStayForward, SegmentValues awayStayForward,
                                  long nextHomeWins, long nextDraws, long nextAwayWins, long nextFavorite,
                                  long nextObserved) {}
}
