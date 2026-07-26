package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Single source of truth for pre-match shot volume.
 *
 * <p>The sampled score never drives the shot line.  The only score-related
 * input is the final invariant floor ({@code shots >= goals}).  When canonical
 * expected goals are available their relative share is used because it is the
 * same attack-v-protection matchup that produced the score distribution.
 */
@Component
public final class ShotVolumeModel {

    private final MatchEngineConfig engineConfig;

    @Autowired
    public ShotVolumeModel(MatchEngineConfig engineConfig) {
        this.engineConfig = engineConfig;
    }

    public ShotVolume plan(long competitionId, int season, int round,
                           long homeTeamId, long awayTeamId,
                           double homePower, double awayPower,
                           Double homeExpectedGoals, Double awayExpectedGoals,
                           int homeGoals, int awayGoals) {
        MatchEngineConfig.Stats stats = engineConfig.getStats();
        double controlShare = controlShare(
                homePower, awayPower, homeExpectedGoals, awayExpectedGoals);
        double exponent = Math.max(0.1, stats.getShotVolumeSplitExponent());
        double weightedHome = poweredShare(controlShare, exponent);

        Random random = new Random(seedFor(
                competitionId, season, round, homeTeamId, awayTeamId));
        double matchTempo = logNormalMeanOne(random, stats.getShotsTempoNoiseSigma());
        double totalMean = Math.max(1.0, stats.getShotVolumeTotalBase() * matchTempo);
        double homeMean = Math.max(0.2, totalMean * weightedHome
                * logNormalMeanOne(random, stats.getShotsTeamNoiseSigma()));
        double awayMean = Math.max(0.2, totalMean * (1.0 - weightedHome)
                * logNormalMeanOne(random, stats.getShotsTeamNoiseSigma()));

        int homeShots = Math.max(homeGoals, poisson(random, homeMean, stats.getShotsMax()));
        int awayShots = Math.max(awayGoals, poisson(random, awayMean, stats.getShotsMax()));
        return new ShotVolume(controlShare, weightedHome, homeMean, awayMean, homeShots, awayShots);
    }

    private static double controlShare(double homePower, double awayPower,
                                       Double homeExpectedGoals, Double awayExpectedGoals) {
        if (homeExpectedGoals != null && awayExpectedGoals != null
                && Double.isFinite(homeExpectedGoals) && homeExpectedGoals >= 0
                && Double.isFinite(awayExpectedGoals) && awayExpectedGoals >= 0
                && homeExpectedGoals + awayExpectedGoals > 0) {
            return clamp(homeExpectedGoals / (homeExpectedGoals + awayExpectedGoals), 0.01, 0.99);
        }
        double totalPower = homePower + awayPower;
        if (!Double.isFinite(totalPower) || totalPower <= 0) return 0.5;
        return clamp(homePower / totalPower, 0.01, 0.99);
    }

    private static double poweredShare(double share, double exponent) {
        double home = Math.pow(share, exponent);
        double away = Math.pow(1.0 - share, exponent);
        return home / (home + away);
    }

    private static double logNormalMeanOne(Random random, double sigma) {
        if (!Double.isFinite(sigma) || sigma <= 0) return 1.0;
        return Math.exp(random.nextGaussian() * sigma - 0.5 * sigma * sigma);
    }

    private static int poisson(Random random, double mean, int max) {
        if (mean <= 0) return 0;
        // Knuth is stable and cheap for the configured football range (< 40).
        double limit = Math.exp(-mean);
        double product = 1.0;
        int value = 0;
        do {
            value++;
            product *= random.nextDouble();
        } while (product > limit && value <= max + 1);
        return Math.min(max, value - 1);
    }

    static long seedFor(long competitionId, int season, int round,
                        long homeTeamId, long awayTeamId) {
        long hash = 0xcbf29ce484222325L;
        long[] values = {competitionId, season, round, homeTeamId, awayTeamId};
        for (long value : values) {
            for (int index = 0; index < Long.BYTES; index++) {
                hash ^= (value >>> (index * 8)) & 0xffL;
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record ShotVolume(
            double controlShare,
            double weightedHomeShare,
            double homeMean,
            double awayMean,
            int homeShots,
            int awayShots) {
    }
}
