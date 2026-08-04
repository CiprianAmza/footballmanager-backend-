package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.compartment.effects.CanonicalMatchEffectsInput;
import com.footballmanagergamesimulator.compartment.effects.CanonicalMatchStatsProfileV1;
import com.footballmanagergamesimulator.compartment.effects.CanonicalMatchStatsSeed;
import com.footballmanagergamesimulator.compartment.effects.CanonicalMatchStatsValidator;
import com.footballmanagergamesimulator.frontend.LiveMatchData;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Match statistics generation, persistence, and aggregation. Split out of
 * {@link MatchSimulationService} so the "events / utility" part of the engine
 * stays focused on simulation logic while the stat-line plumbing (xG, possession,
 * tackles, ratings aggregates) lives behind one boundary.
 *
 * <p>All methods here are stateless and only depend on {@link MatchStatsRepository}.
 */
@Service
public class MatchStatsService {

    @Autowired
    private MatchStatsRepository matchStatsRepository;
    @Autowired
    private MatchEngineConfig engineConfig;
    @Autowired
    private ShotVolumeModel shotVolumeModel;
    @Autowired(required = false)
    private ShotEventService shotEventService;

    /**
     * Shared RNG used by stat generators. Held as a field so determinism IT
     * (seed → reproducible stat line) can swap in a seeded {@link Random} via
     * {@link #setRandomForTesting(Random)}.
     */
    private Random random = new Random();

    /** Test-only seam: swap the RNG for determinism / fuzz tests. */
    public void setRandomForTesting(Random random) {
        this.random = random;
    }

    // ==================== MATCH STATS GENERATION ====================

    /**
     * Generate realistic match statistics based on team powers, final score, and tactical settings.
     * Uses real-world football averages (Premier League / Champions League data) as baselines:
     * - Avg shots per team: 11-13, shots on target: 35-45% of total
     * - Avg possession: 50%, range 30-70%
     * - Avg passes: 400-500, pass accuracy: 80-88%
     * - Avg corners: 5-6, fouls: 10-12, offsides: 2-3
     * - Avg tackles: 18-20, interceptions: 10-12
     * - xG per goal: ~0.11 per shot, ~0.35 per big chance, 0.76 per penalty
     */
    public MatchStats generateMatchStats(
            long competitionId, int season, int round,
            long team1Id, long team2Id,
            int homeGoals, int awayGoals,
            double homePower, double awayPower,
            PersonalizedTactic homeTactic, PersonalizedTactic awayTactic) {
        return generateMatchStats(competitionId, season, round, team1Id, team2Id,
                homeGoals, awayGoals, homePower, awayPower, homeTactic, awayTactic,
                this.random, null, null);
    }

    private MatchStats generateMatchStats(
            long competitionId, int season, int round,
            long team1Id, long team2Id,
            int homeGoals, int awayGoals,
            double homePower, double awayPower,
            PersonalizedTactic homeTactic, PersonalizedTactic awayTactic,
            Random rng, Double homeExpectedGoals, Double awayExpectedGoals) {

        // Team power drives the central tendency, while match-control, tempo and
        // team-specific noise create the long tails seen in real matches.
        MatchStats stats = new MatchStats();
        stats.setCompetitionId(competitionId);
        stats.setSeasonNumber(season);
        stats.setRoundNumber(round);
        stats.setTeam1Id(team1Id);
        stats.setTeam2Id(team2Id);
        stats.setHomeGoals(homeGoals);
        stats.setAwayGoals(awayGoals);

        MatchEngineConfig.Stats sc = engineConfig.getStats();
        double totalPower = homePower + awayPower;
        double homeRatio = totalPower > 0 ? homePower / totalPower : 0.5;
        // Edge factor amplifies "ratio - 0.5" so big mismatches read in the stats
        // instead of being smoothed away by noise.
        double edge = (homeRatio - 0.5);

        // --- POSSESSION ---
        double basePoss = sc.getPossessionBase() + edge * sc.getPossessionEdgeScale();
        basePoss += sc.getHomePossessionBoost();
        basePoss += getTacticalPossessionBonus(homeTactic) - getTacticalPossessionBonus(awayTactic);
        basePoss = Math.max(25, Math.min(75, basePoss + rng.nextGaussian() * sc.getPossessionNoiseSigma()));
        int homePoss = (int) Math.round(basePoss);
        stats.setHomePossession(homePoss);
        stats.setAwayPossession(100 - homePoss);

        // --- PASSES ---
        double possRatioHome = homePoss / 50.0;
        double possRatioAway = (100 - homePoss) / 50.0;
        int homePasses = (int) (sc.getPassesBase() * possRatioHome + rng.nextGaussian() * sc.getPassesNoiseSigma());
        int awayPasses = (int) (sc.getPassesBase() * possRatioAway + rng.nextGaussian() * sc.getPassesNoiseSigma());
        stats.setHomePasses(Math.max(200, Math.min(750, homePasses)));
        stats.setAwayPasses(Math.max(200, Math.min(750, awayPasses)));

        // --- PASS ACCURACY ---
        double homePassAcc = sc.getPassAccuracyBase() + edge * sc.getPassAccuracyEdgeScale()
                + rng.nextGaussian() * sc.getPassAccuracyNoiseSigma();
        double awayPassAcc = sc.getPassAccuracyBase() - edge * sc.getPassAccuracyEdgeScale()
                + rng.nextGaussian() * sc.getPassAccuracyNoiseSigma();
        if (homeTactic != null && "Keep Ball".equals(homeTactic.getInPossession())) homePassAcc += sc.getPassAccuracyKeepBallBonus();
        if (awayTactic != null && "Keep Ball".equals(awayTactic.getInPossession())) awayPassAcc += sc.getPassAccuracyKeepBallBonus();
        if (homeTactic != null && "Long Ball".equals(homeTactic.getPassingType())) homePassAcc += sc.getPassAccuracyLongBallPenalty();
        if (awayTactic != null && "Long Ball".equals(awayTactic.getPassingType())) awayPassAcc += sc.getPassAccuracyLongBallPenalty();
        stats.setHomePassAccuracy(clamp((int) homePassAcc, 55, 96));
        stats.setAwayPassAccuracy(clamp((int) awayPassAcc, 55, 96));

        // --- SHOTS / CHANCE QUALITY ---
        // The complete shot budget is decided before kickoff from matchup strength.
        // It never feeds back from the sampled score (apart from the football floor).
        ShotVolumeModel.ShotVolume shotVolume = shotVolumeModel.plan(
                competitionId, season, round, team1Id, team2Id,
                homePower, awayPower, homeExpectedGoals, awayExpectedGoals,
                homeGoals, awayGoals);
        int homeShots = shotVolume.homeShots();
        int awayShots = shotVolume.awayShots();
        stats.setHomeShots(homeShots);
        stats.setAwayShots(awayShots);

        ChanceLine homeChanceLine = generatePreMatchChanceLine(
                homeShots, homeGoals, shotVolume.controlShare(), null, rng);
        ChanceLine awayChanceLine = generatePreMatchChanceLine(
                awayShots, awayGoals, 1 - shotVolume.controlShare(), null, rng);
        int homeSoT = homeChanceLine.shotsOnTarget();
        int awaySoT = awayChanceLine.shotsOnTarget();
        stats.setHomeShotsOnTarget(homeSoT);
        stats.setAwayShotsOnTarget(awaySoT);

        // Blocked shots
        int homeBlocked = clamp((int) (homeShots * (sc.getBlockedShotsBase() + rng.nextDouble() * sc.getBlockedShotsNoiseSpan())), 0, homeShots - homeSoT);
        int awayBlocked = clamp((int) (awayShots * (sc.getBlockedShotsBase() + rng.nextDouble() * sc.getBlockedShotsNoiseSpan())), 0, awayShots - awaySoT);
        stats.setHomeShotsBlocked(homeBlocked);
        stats.setAwayShotsBlocked(awayBlocked);

        // --- CORNERS ---
        int homeCorners = shotVolume.homeCorners();
        int awayCorners = shotVolume.awayCorners();
        stats.setHomeCorners(homeCorners);
        stats.setAwayCorners(awayCorners);

        // --- FOULS ---
        double homeFoulBase = sc.getFoulsBase() - edge * sc.getFoulsEdgeSpan() + rng.nextGaussian() * 1.0;
        double awayFoulBase = sc.getFoulsBase() + edge * sc.getFoulsEdgeSpan() + rng.nextGaussian() * 1.0;
        if (homeTactic != null && "Very Defensive".equals(homeTactic.getMentality())) homeFoulBase += sc.getVeryDefensiveFoulBonus();
        if (awayTactic != null && "Very Defensive".equals(awayTactic.getMentality())) awayFoulBase += sc.getVeryDefensiveFoulBonus();
        int homeFouls = clamp((int) homeFoulBase, 4, 22);
        int awayFouls = clamp((int) awayFoulBase, 4, 22);
        stats.setHomeFouls(homeFouls);
        stats.setAwayFouls(awayFouls);

        stats.setHomeFreeKicks(awayFouls);
        stats.setAwayFreeKicks(homeFouls);

        // --- CARDS ---
        MatchEngineConfig.Fouls fc = engineConfig.getFouls();
        int homeYellow = clamp((int) (homeFouls * (fc.getLiveYellowCardRateMin() + rng.nextDouble() * fc.getLiveYellowCardRateSpread())), 0, 6);
        int awayYellow = clamp((int) (awayFouls * (fc.getLiveYellowCardRateMin() + rng.nextDouble() * fc.getLiveYellowCardRateSpread())), 0, 6);
        stats.setHomeYellowCards(homeYellow);
        stats.setAwayYellowCards(awayYellow);

        double homeRedChance = fc.getLiveRedCardBase() + homeFouls * fc.getLiveRedCardPerFoul();
        double awayRedChance = fc.getLiveRedCardBase() + awayFouls * fc.getLiveRedCardPerFoul();
        stats.setHomeRedCards(rng.nextDouble() < homeRedChance ? 1 : 0);
        stats.setAwayRedCards(rng.nextDouble() < awayRedChance ? 1 : 0);

        // --- OFFSIDES ---
        double homeOffsBase = sc.getOffsidesBase() + (homeRatio - sc.getOffsidesPivotRatio()) * sc.getOffsidesScale() + rng.nextGaussian() * 0.7;
        double awayOffsBase = sc.getOffsidesBase() + ((1 - homeRatio) - sc.getOffsidesPivotRatio()) * sc.getOffsidesScale() + rng.nextGaussian() * 0.7;
        stats.setHomeOffsides(clamp((int) homeOffsBase, 0, 8));
        stats.setAwayOffsides(clamp((int) awayOffsBase, 0, 8));

        // --- TACKLES ---
        double homeTackleBase = sc.getTacklesBase() + (50 - homePoss) * sc.getTacklesPossessionCoefficient() + rng.nextGaussian() * 1.5;
        double awayTackleBase = sc.getTacklesBase() + (homePoss - 50) * sc.getTacklesPossessionCoefficient() + rng.nextGaussian() * 1.5;
        stats.setHomeTackles(clamp((int) homeTackleBase, 8, 35));
        stats.setAwayTackles(clamp((int) awayTackleBase, 8, 35));

        // --- INTERCEPTIONS ---
        int homeInterceptions = clamp((int) (sc.getInterceptionsBase() + (50 - homePoss) * sc.getInterceptionsPossessionCoefficient() + rng.nextGaussian()), 3, 22);
        int awayInterceptions = clamp((int) (sc.getInterceptionsBase() + (homePoss - 50) * sc.getInterceptionsPossessionCoefficient() + rng.nextGaussian()), 3, 22);
        stats.setHomeInterceptions(homeInterceptions);
        stats.setAwayInterceptions(awayInterceptions);

        // --- CLEARANCES ---
        int homeClearances = clamp((int) (sc.getClearancesBase() + (50 - homePoss) * sc.getClearancesPossessionCoefficient() + awayShots * sc.getClearancesShotBonus() + rng.nextGaussian() * 1.5), 5, 40);
        int awayClearances = clamp((int) (sc.getClearancesBase() + (homePoss - 50) * sc.getClearancesPossessionCoefficient() + homeShots * sc.getClearancesShotBonus() + rng.nextGaussian() * 1.5), 5, 40);
        stats.setHomeClearances(homeClearances);
        stats.setAwayClearances(awayClearances);

        stats.setHomeSaves(Math.max(0, awaySoT - awayGoals));
        stats.setAwaySaves(Math.max(0, homeSoT - homeGoals));

        // --- BIG CHANCES / xG ---
        stats.setHomeBigChances(homeChanceLine.bigChances());
        stats.setAwayBigChances(awayChanceLine.bigChances());
        stats.setHomeBigChancesMissed(homeChanceLine.bigChancesMissed());
        stats.setAwayBigChancesMissed(awayChanceLine.bigChancesMissed());
        stats.setHomeXg(homeChanceLine.xgHundredths());
        stats.setAwayXg(awayChanceLine.xgHundredths());

        // --- CROSSES ---
        int homeCrosses = clamp((int) (18 + rng.nextGaussian() * 2 + homeCorners * 0.5), 5, 40);
        int awayCrosses = clamp((int) (18 + rng.nextGaussian() * 2 + awayCorners * 0.5), 5, 40);
        double homeCrossRate = sc.getCrossAccuracyBase() + edge * sc.getCrossAccuracyEdgeScale() + rng.nextDouble() * sc.getCrossAccuracyNoise();
        double awayCrossRate = sc.getCrossAccuracyBase() - edge * sc.getCrossAccuracyEdgeScale() + rng.nextDouble() * sc.getCrossAccuracyNoise();
        int homeCrossAcc = clamp((int) (homeCrosses * homeCrossRate), 0, homeCrosses);
        int awayCrossAcc = clamp((int) (awayCrosses * awayCrossRate), 0, awayCrosses);
        stats.setHomeCrosses(homeCrosses);
        stats.setAwayCrosses(awayCrosses);
        stats.setHomeCrossesAccurate(homeCrossAcc);
        stats.setAwayCrossesAccurate(awayCrossAcc);

        // --- DUELS ---
        int homeDuels = clamp((int) (sc.getDuelsBase() + rng.nextGaussian() * sc.getDuelsNoise()), 35, 85);
        int awayDuels = clamp((int) (sc.getDuelsBase() + rng.nextGaussian() * sc.getDuelsNoise()), 35, 85);
        double homeDuelWinPct = sc.getDuelWinBase() + edge * sc.getDuelWinEdgeScale() + rng.nextGaussian() * sc.getDuelWinNoise();
        homeDuelWinPct = Math.max(0.30, Math.min(0.70, homeDuelWinPct));
        stats.setHomeDuelsWon(clamp((int) (homeDuels * homeDuelWinPct), 15, 60));
        stats.setAwayDuelsWon(clamp((int) (awayDuels * (1 - homeDuelWinPct)), 15, 60));

        // Aerial duels
        int homeAerial = clamp((int) (sc.getAerialDuelsBase() + edge * sc.getAerialDuelsEdgeScale() + rng.nextGaussian() * sc.getAerialDuelsNoise()), 5, 25);
        int awayAerial = clamp((int) (sc.getAerialDuelsBase() - edge * sc.getAerialDuelsEdgeScale() + rng.nextGaussian() * sc.getAerialDuelsNoise()), 5, 25);
        stats.setHomeAerialDuelsWon(homeAerial);
        stats.setAwayAerialDuelsWon(awayAerial);

        return stats;
    }

    /**
     * Generate and persist match stats. Returns the saved entity.
     */
    public MatchStats generateAndSaveMatchStats(
            long competitionId, int season, int round,
            long team1Id, long team2Id,
            int homeGoals, int awayGoals,
            double homePower, double awayPower,
            PersonalizedTactic homeTactic, PersonalizedTactic awayTactic) {

        MatchStats stats = generateMatchStats(competitionId, season, round,
                team1Id, team2Id, homeGoals, awayGoals, homePower, awayPower, homeTactic, awayTactic);
        return saveWithShotEvents(stats);
    }

    /** Replace fallback chance-derived xG with the canonical pre-match means. */
    public MatchStats applyCanonicalExpectedGoals(
            MatchStats stats, double homeExpectedGoals, double awayExpectedGoals) {
        if (stats == null) throw new IllegalArgumentException("stats must not be null");
        stats.setHomeXg(toHundredths(homeExpectedGoals));
        stats.setAwayXg(toHundredths(awayExpectedGoals));
        return saveWithShotEvents(stats);
    }

    /**
     * Canonical runtime variant for a human/instant fixture.  Supplying the
     * expected-goal pair makes its shot split identical to watched playback.
     */
    public MatchStats generateAndSaveCanonicalRuntimeMatchStats(
            long competitionId, int season, int round,
            long team1Id, long team2Id,
            int homeGoals, int awayGoals,
            double homePower, double awayPower,
            PersonalizedTactic homeTactic, PersonalizedTactic awayTactic,
            double homeExpectedGoals, double awayExpectedGoals) {
        return generateAndSaveCanonicalRuntimeMatchStats(
                competitionId, season, round, team1Id, team2Id,
                homeGoals, awayGoals, homePower, awayPower, homeTactic, awayTactic,
                homeExpectedGoals, awayExpectedGoals, 0, 0, 0, 0);
    }

    public MatchStats generateAndSaveCanonicalRuntimeMatchStats(
            long competitionId, int season, int round,
            long team1Id, long team2Id,
            int homeGoals, int awayGoals,
            double homePower, double awayPower,
            PersonalizedTactic homeTactic, PersonalizedTactic awayTactic,
            double homeExpectedGoals, double awayExpectedGoals,
            int homeShooterShots, int awayShooterShots,
            int homeShooterGoals, int awayShooterGoals) {
        MatchStats stats = generateMatchStats(
                competitionId, season, round, team1Id, team2Id,
                homeGoals, awayGoals, homePower, awayPower, homeTactic, awayTactic,
                this.random, homeExpectedGoals, awayExpectedGoals);
        includeShooterAttempts(stats, homeShooterShots, awayShooterShots,
                homeShooterGoals, awayShooterGoals, this.random);
        stats.setHomeXg(toHundredths(homeExpectedGoals));
        stats.setAwayXg(toHundredths(awayExpectedGoals));
        return saveWithShotEvents(stats);
    }

    /**
     * Generate a deterministic, persistence-free projection of a canonical decision.
     *
     * <p>This is the reporting/simulation seam for callers that need the exact same
     * statistics as a real match without manufacturing a competition fixture or
     * filling {@code match_stats} with synthetic rows. The persisted production path
     * below delegates here, so the two projections cannot drift.</p>
     */
    public MatchStats projectCanonicalMatchStats(
            CanonicalMatchEffectsInput input,
            long competitionId, int season, int round) {
        CanonicalMatchStatsValidator.validate(input);
        MatchStats stats = generateCanonicalMatchStats(input, competitionId, season, round,
                CanonicalMatchStatsProfileV1.v1(), new Random(CanonicalMatchStatsSeed.derive(input)));
        if (input.decision().scoreEngine() == com.footballmanagergamesimulator.matchplan.ScoreEngineKind.COMPARTMENT_V1
                && input.decision().homeXg() != null) {
            stats.setHomeXg(toHundredths(input.decision().homeXg()));
            stats.setAwayXg(toHundredths(input.decision().awayXg()));
        }
        validateGeneratedCanonicalStats(stats);
        return stats;
    }

    /** Generate and persist a deterministic projection of a persisted canonical decision. */
    public MatchStats generateAndSaveCanonicalMatchStats(
            CanonicalMatchEffectsInput input,
            long competitionId, int season, int round) {
        return saveWithShotEvents(projectCanonicalMatchStats(input, competitionId, season, round));
    }

    private MatchStats generateCanonicalMatchStats(
            CanonicalMatchEffectsInput input, long competitionId, int season, int round,
            CanonicalMatchStatsProfileV1 profile, Random rng) {
        int homeGoals = CanonicalMatchStatsValidator.footballGoals(
                input.split().score90Home(), input.split().etHome());
        int awayGoals = CanonicalMatchStatsValidator.footballGoals(
                input.split().score90Away(), input.split().etAway());
        double totalPower = input.decision().homePower() + input.decision().awayPower();
        double homeShare = totalPower > 0 ? input.decision().homePower() / totalPower : 0.5;
        double edge = homeShare - 0.5;

        MatchStats stats = new MatchStats();
        stats.setCompetitionId(competitionId);
        stats.setSeasonNumber(season);
        stats.setRoundNumber(round);
        stats.setTeam1Id(input.homeTeamId());
        stats.setTeam2Id(input.awayTeamId());
        stats.setHomeGoals(homeGoals);
        stats.setAwayGoals(awayGoals);

        double possessionNoise = rng.nextGaussian() * profile.possessionNoise();
        double baselineHomePossession = profile.possessionBase()
                + edge * profile.possessionPowerScale() + possessionNoise;
        int homePossession = canonicalHomePossession(
                input.decision().homePassingControl(), input.decision().awayPassingControl(),
                baselineHomePossession, possessionNoise);
        int awayPossession = 100 - homePossession;
        stats.setHomePossession(homePossession);
        stats.setAwayPossession(awayPossession);

        stats.setHomePasses(clamp((int) Math.round(profile.passesBase() * homePossession / 50.0
                + rng.nextGaussian() * profile.passesNoise()), 200, 750));
        stats.setAwayPasses(clamp((int) Math.round(profile.passesBase() * awayPossession / 50.0
                + rng.nextGaussian() * profile.passesNoise()), 200, 750));
        stats.setHomePassAccuracy(clamp((int) Math.round(profile.passAccuracyBase()
                + edge * profile.passAccuracyPowerScale() + rng.nextGaussian() * profile.passAccuracyNoise()), 55, 96));
        stats.setAwayPassAccuracy(clamp((int) Math.round(profile.passAccuracyBase()
                - edge * profile.passAccuracyPowerScale() + rng.nextGaussian() * profile.passAccuracyNoise()), 55, 96));

        ShotVolumeModel.ShotVolume shotVolume = shotVolumeModel.plan(
                competitionId, season, round, input.homeTeamId(), input.awayTeamId(),
                input.decision().homePower(), input.decision().awayPower(),
                input.decision().homeXg(), input.decision().awayXg(),
                homeGoals, awayGoals);
        int homeShots = shotVolume.homeShots();
        int awayShots = shotVolume.awayShots();
        stats.setHomeShots(homeShots);
        stats.setAwayShots(awayShots);
        double homeOnTargetRate = clamp(engineConfig.getStats().getShotsOnTargetBase()
                + (shotVolume.controlShare() - 0.5)
                * engineConfig.getStats().getShotsOnTargetEdgeSpan(), 0.12, 0.70);
        double awayOnTargetRate = clamp(engineConfig.getStats().getShotsOnTargetBase()
                + (0.5 - shotVolume.controlShare())
                * engineConfig.getStats().getShotsOnTargetEdgeSpan(), 0.12, 0.70);
        int homeShotsOnTarget = homeGoals + randomBounded(
                rng, homeShots - homeGoals, homeOnTargetRate);
        int awayShotsOnTarget = awayGoals + randomBounded(
                rng, awayShots - awayGoals, awayOnTargetRate);
        stats.setHomeShotsOnTarget(homeShotsOnTarget);
        stats.setAwayShotsOnTarget(awayShotsOnTarget);
        stats.setHomeShotsBlocked(clamp((int) Math.round(homeShots
                * (profile.blockedRate() + rng.nextDouble() * profile.blockedNoise())), 0,
                homeShots - homeShotsOnTarget));
        stats.setAwayShotsBlocked(clamp((int) Math.round(awayShots
                * (profile.blockedRate() + rng.nextDouble() * profile.blockedNoise())), 0,
                awayShots - awayShotsOnTarget));

        stats.setHomeCorners(shotVolume.homeCorners());
        stats.setAwayCorners(shotVolume.awayCorners());
        int homeFouls = clamp((int) Math.round(profile.foulsBase() - edge * 2 + rng.nextGaussian() * profile.foulNoise()), 4, 22);
        int awayFouls = clamp((int) Math.round(profile.foulsBase() + edge * 2 + rng.nextGaussian() * profile.foulNoise()), 4, 22);
        stats.setHomeFouls(homeFouls);
        stats.setAwayFouls(awayFouls);
        stats.setHomeFreeKicks(awayFouls);
        stats.setAwayFreeKicks(homeFouls);
        stats.setHomeYellowCards(clamp((int) Math.round(homeFouls * 0.16 + rng.nextDouble()), 0, 6));
        stats.setAwayYellowCards(clamp((int) Math.round(awayFouls * 0.16 + rng.nextDouble()), 0, 6));
        stats.setHomeRedCards(rng.nextDouble() < 0.03 ? 1 : 0);
        stats.setAwayRedCards(rng.nextDouble() < 0.03 ? 1 : 0);
        stats.setHomeOffsides(clamp((int) Math.round(1.5 + edge * 2 + rng.nextGaussian() * 0.7), 0, 8));
        stats.setAwayOffsides(clamp((int) Math.round(1.5 - edge * 2 + rng.nextGaussian() * 0.7), 0, 8));

        stats.setHomeTackles(clamp((int) Math.round(profile.tacklesBase()
                + (50 - homePossession) * 0.15 + rng.nextGaussian() * profile.tackleNoise()), 8, 35));
        stats.setAwayTackles(clamp((int) Math.round(profile.tacklesBase()
                + (homePossession - 50) * 0.15 + rng.nextGaussian() * profile.tackleNoise()), 8, 35));
        stats.setHomeInterceptions(clamp((int) Math.round(profile.interceptionsBase()
                + (50 - homePossession) * 0.1 + rng.nextGaussian() * profile.interceptionsNoise()), 3, 22));
        stats.setAwayInterceptions(clamp((int) Math.round(profile.interceptionsBase()
                + (homePossession - 50) * 0.1 + rng.nextGaussian() * profile.interceptionsNoise()), 3, 22));
        stats.setHomeClearances(clamp((int) Math.round(profile.clearancesBase()
                + awayShots * profile.clearancesPerShot() + rng.nextGaussian()), 5, 40));
        stats.setAwayClearances(clamp((int) Math.round(profile.clearancesBase()
                + homeShots * profile.clearancesPerShot() + rng.nextGaussian()), 5, 40));
        stats.setHomeSaves(Math.max(0, awayShotsOnTarget - awayGoals));
        stats.setAwaySaves(Math.max(0, homeShotsOnTarget - homeGoals));

        int homeBigChances = clamp((int) Math.round(homeShots * profile.bigChanceRate()
                + rng.nextGaussian()), 0, homeShots);
        int awayBigChances = clamp((int) Math.round(awayShots * profile.bigChanceRate()
                + rng.nextGaussian()), 0, awayShots);
        stats.setHomeBigChances(homeBigChances);
        stats.setAwayBigChances(awayBigChances);
        stats.setHomeBigChancesMissed(Math.max(0, homeBigChances - homeGoals));
        stats.setAwayBigChancesMissed(Math.max(0, awayBigChances - awayGoals));
        stats.setHomeXg(canonicalXg(homeGoals, homeShots, profile));
        stats.setAwayXg(canonicalXg(awayGoals, awayShots, profile));

        int homeCrosses = clamp((int) Math.round(profile.crossesBase() + rng.nextGaussian() * profile.crossesNoise()), 5, 40);
        int awayCrosses = clamp((int) Math.round(profile.crossesBase() + rng.nextGaussian() * profile.crossesNoise()), 5, 40);
        stats.setHomeCrosses(homeCrosses);
        stats.setAwayCrosses(awayCrosses);
        stats.setHomeCrossesAccurate(clamp((int) Math.round(homeCrosses * 0.29), 0, homeCrosses));
        stats.setAwayCrossesAccurate(clamp((int) Math.round(awayCrosses * 0.29), 0, awayCrosses));

        int homeDuels = clamp((int) Math.round(profile.duelsBase() + rng.nextGaussian() * profile.duelsNoise()), 35, 85);
        int awayDuels = clamp((int) Math.round(profile.duelsBase() + rng.nextGaussian() * profile.duelsNoise()), 35, 85);
        stats.setHomeDuelsWon(clamp((int) Math.round(homeDuels * (0.5 + edge * 0.12)), 15, 60));
        stats.setAwayDuelsWon(clamp((int) Math.round(awayDuels * (0.5 - edge * 0.12)), 15, 60));
        stats.setHomeAerialDuelsWon(clamp((int) Math.round(profile.aerialDuelsBase()
                + edge * 2 + rng.nextGaussian() * profile.aerialDuelsNoise()), 5, 25));
        stats.setAwayAerialDuelsWon(clamp((int) Math.round(profile.aerialDuelsBase()
                - edge * 2 + rng.nextGaussian() * profile.aerialDuelsNoise()), 5, 25));
        includeShooterAttempts(stats,
                input.decision().homeShooterShots() + input.decision().homePassingOpportunities(),
                input.decision().awayShooterShots() + input.decision().awayPassingOpportunities(),
                input.decision().homeShooterGoals() + input.decision().homePassingGoals(),
                input.decision().awayShooterGoals() + input.decision().awayPassingGoals(), rng);
        return stats;
    }

    /**
     * PASSING control is the share of the match controlled through the special
     * midfield mechanic, so a side with 84% control must also have possession in
     * that neighbourhood. When neither side activates it, retain the ordinary
     * power-based possession model. If both activate it, compare their control
     * strengths instead of allowing two impossible 80% possession shares.
     */
    private int canonicalHomePossession(double homeControl, double awayControl,
                                        double baselineHomePossession, double possessionNoise) {
        if (homeControl <= 0.0 && awayControl <= 0.0) {
            return clamp((int) Math.round(baselineHomePossession), 25, 75);
        }
        double controlledHomeShare;
        if (homeControl > 0.0 && awayControl > 0.0) {
            controlledHomeShare = homeControl / (homeControl + awayControl);
        } else if (homeControl > 0.0) {
            controlledHomeShare = homeControl;
        } else {
            controlledHomeShare = 1.0 - awayControl;
        }
        return clamp((int) Math.round(controlledHomeShare * 100.0 + possessionNoise), 5, 95);
    }

    /**
     * The ordinary shot model already contains every scored goal because of its
     * football floor. Add only the SHOOTER attempts that missed, then classify
     * those misses deterministically as saved, blocked or off target.
     */
    private void includeShooterAttempts(MatchStats stats,
                                        int homeAttempts, int awayAttempts,
                                        int homeGoals, int awayGoals,
                                        Random rng) {
        int homeMisses = Math.max(0, homeAttempts - homeGoals);
        int awayMisses = Math.max(0, awayAttempts - awayGoals);
        int homeSaved = randomBounded(rng, homeMisses, 0.35);
        int awaySaved = randomBounded(rng, awayMisses, 0.35);
        int homeBlocked = randomBounded(rng, homeMisses - homeSaved, 0.45);
        int awayBlocked = randomBounded(rng, awayMisses - awaySaved, 0.45);

        stats.setHomeShots(stats.getHomeShots() + homeMisses);
        stats.setAwayShots(stats.getAwayShots() + awayMisses);
        stats.setHomeShotsOnTarget(stats.getHomeShotsOnTarget() + homeSaved);
        stats.setAwayShotsOnTarget(stats.getAwayShotsOnTarget() + awaySaved);
        stats.setHomeShotsBlocked(stats.getHomeShotsBlocked() + homeBlocked);
        stats.setAwayShotsBlocked(stats.getAwayShotsBlocked() + awayBlocked);
        stats.setHomeSaves(Math.max(0, stats.getAwayShotsOnTarget() - stats.getAwayGoals()));
        stats.setAwaySaves(Math.max(0, stats.getHomeShotsOnTarget() - stats.getHomeGoals()));
    }

    private int canonicalXg(int goals, int shots, CanonicalMatchStatsProfileV1 profile) {
        double xg = goals * profile.xgPerGoal() + Math.max(0, shots - goals) * profile.xgPerOtherShot();
        return clamp((int) Math.round(xg * 100.0), 0, Integer.MAX_VALUE);
    }

    private int randomBounded(Random rng, int available, double rate) {
        if (available <= 0) return 0;
        int result = 0;
        for (int i = 0; i < available; i++) {
            if (rng.nextDouble() < rate) result++;
        }
        return result;
    }

    private int canonicalPoisson(Random rng, double mean, int max) {
        double limit = Math.exp(-mean);
        double product = 1.0;
        int count = 0;
        do {
            count++;
            product *= rng.nextDouble();
        } while (product > limit && count <= max);
        return Math.min(count - 1, max);
    }

    private int toHundredths(double xg) {
        double scaled = Math.round(xg * 100.0);
        if (scaled <= 0) return 0;
        if (scaled >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) scaled;
    }

    private void validateGeneratedCanonicalStats(MatchStats stats) {
        if (stats.getHomePossession() + stats.getAwayPossession() != 100
                || stats.getHomeShots() < stats.getHomeShotsOnTarget()
                || stats.getAwayShots() < stats.getAwayShotsOnTarget()
                || stats.getHomeShotsOnTarget() < stats.getHomeGoals()
                || stats.getAwayShotsOnTarget() < stats.getAwayGoals()
                || stats.getHomeShotsBlocked() > stats.getHomeShots() - stats.getHomeShotsOnTarget()
                || stats.getAwayShotsBlocked() > stats.getAwayShots() - stats.getAwayShotsOnTarget()
                || stats.getHomeBigChancesMissed() > stats.getHomeBigChances()
                || stats.getAwayBigChancesMissed() > stats.getAwayBigChances()
                || stats.getHomeSaves() != Math.max(0, stats.getAwayShotsOnTarget() - stats.getAwayGoals())
                || stats.getAwaySaves() != Math.max(0, stats.getHomeShotsOnTarget() - stats.getHomeGoals())) {
            throw new IllegalStateException("canonical match stats violate football invariants");
        }
    }

    /**
     * Persist stats from a LiveMatchData result (live match already generated the stats).
     */
    public MatchStats persistLiveMatchStats(
            long competitionId, int season, int round,
            long team1Id, long team2Id,
            LiveMatchData liveData,
            double homePower, double awayPower) {
        return persistLiveMatchStats(competitionId, season, round, team1Id, team2Id,
                liveData, homePower, awayPower, 0, 0, 0, 0);
    }

    public MatchStats persistLiveMatchStats(
            long competitionId, int season, int round,
            long team1Id, long team2Id,
            LiveMatchData liveData,
            double homePower, double awayPower,
            int homeShooterShots, int awayShooterShots,
            int homeShooterGoals, int awayShooterGoals) {

        Random rng = this.random;
        double totalPower = homePower + awayPower;
        double homeRatio = totalPower > 0 ? homePower / totalPower : 0.5;

        MatchStats stats = new MatchStats();
        stats.setCompetitionId(competitionId);
        stats.setSeasonNumber(season);
        stats.setRoundNumber(round);
        stats.setTeam1Id(team1Id);
        stats.setTeam2Id(team2Id);
        stats.setHomeGoals(liveData.getHomeScore());
        stats.setAwayGoals(liveData.getAwayScore());

        // Transfer existing stats from LiveMatchData
        stats.setHomePossession(liveData.getHomePossession());
        stats.setAwayPossession(liveData.getAwayPossession());
        stats.setHomeShots(liveData.getHomeShots());
        stats.setAwayShots(liveData.getAwayShots());
        stats.setHomeShotsOnTarget(liveData.getHomeShotsOnTarget());
        stats.setAwayShotsOnTarget(liveData.getAwayShotsOnTarget());
        stats.setHomeCorners(liveData.getHomeCorners());
        stats.setAwayCorners(liveData.getAwayCorners());
        stats.setHomeFouls(liveData.getHomeFouls());
        stats.setAwayFouls(liveData.getAwayFouls());
        stats.setHomeYellowCards(liveData.getHomeYellowCards());
        stats.setAwayYellowCards(liveData.getAwayYellowCards());
        stats.setHomeRedCards(liveData.getHomeRedCards());
        stats.setAwayRedCards(liveData.getAwayRedCards());

        // Generate the stats that LiveMatchData doesn't have
        int homePoss = liveData.getHomePossession();
        double possRatioHome = homePoss / 50.0;
        double possRatioAway = (100 - homePoss) / 50.0;

        // Passes
        int homePasses = clamp((int) (450 * possRatioHome + rng.nextGaussian() * 40), 200, 750);
        int awayPasses = clamp((int) (450 * possRatioAway + rng.nextGaussian() * 40), 200, 750);
        stats.setHomePasses(homePasses);
        stats.setAwayPasses(awayPasses);
        stats.setHomePassAccuracy(clamp((int) (78 + (homeRatio - 0.5) * 20 + rng.nextGaussian() * 3), 60, 95));
        stats.setAwayPassAccuracy(clamp((int) (78 + ((1 - homeRatio) - 0.5) * 20 + rng.nextGaussian() * 3), 60, 95));

        // Shots blocked
        int homeBlocked = clamp((int) (liveData.getHomeShots() * (0.20 + rng.nextDouble() * 0.15)), 0,
                liveData.getHomeShots() - liveData.getHomeShotsOnTarget());
        int awayBlocked = clamp((int) (liveData.getAwayShots() * (0.20 + rng.nextDouble() * 0.15)), 0,
                liveData.getAwayShots() - liveData.getAwayShotsOnTarget());
        stats.setHomeShotsBlocked(homeBlocked);
        stats.setAwayShotsBlocked(awayBlocked);

        // Free kicks
        stats.setHomeFreeKicks(liveData.getAwayFouls());
        stats.setAwayFreeKicks(liveData.getHomeFouls());

        // Offsides
        stats.setHomeOffsides(clamp((int) (1.5 + (homeRatio - 0.3) * 4 + rng.nextGaussian()), 0, 8));
        stats.setAwayOffsides(clamp((int) (1.5 + ((1 - homeRatio) - 0.3) * 4 + rng.nextGaussian()), 0, 8));

        // Tackles
        stats.setHomeTackles(clamp((int) (18 + (50 - homePoss) * 0.15 + rng.nextGaussian() * 3), 8, 35));
        stats.setAwayTackles(clamp((int) (18 + (homePoss - 50) * 0.15 + rng.nextGaussian() * 3), 8, 35));

        // Interceptions
        stats.setHomeInterceptions(clamp((int) (11 + (50 - homePoss) * 0.1 + rng.nextGaussian() * 2), 3, 22));
        stats.setAwayInterceptions(clamp((int) (11 + (homePoss - 50) * 0.1 + rng.nextGaussian() * 2), 3, 22));

        // Clearances
        stats.setHomeClearances(clamp((int) (18 + (50 - homePoss) * 0.2 + liveData.getAwayShots() * 0.5 + rng.nextGaussian() * 3), 5, 40));
        stats.setAwayClearances(clamp((int) (18 + (homePoss - 50) * 0.2 + liveData.getHomeShots() * 0.5 + rng.nextGaussian() * 3), 5, 40));

        // Saves
        stats.setHomeSaves(Math.max(0, liveData.getAwayShotsOnTarget() - liveData.getAwayScore()));
        stats.setAwaySaves(Math.max(0, liveData.getHomeShotsOnTarget() - liveData.getHomeScore()));

        // Big chances and xG use the same per-shot model as instant matches,
        // while preserving the shot/on-target counts narrated by the live engine.
        ChanceLine homeChanceLine = generatePreMatchChanceLine(liveData.getHomeShots(), liveData.getHomeScore(),
                homeRatio, liveData.getHomeShotsOnTarget(), rng);
        ChanceLine awayChanceLine = generatePreMatchChanceLine(liveData.getAwayShots(), liveData.getAwayScore(),
                1 - homeRatio, liveData.getAwayShotsOnTarget(), rng);
        stats.setHomeBigChances(homeChanceLine.bigChances());
        stats.setAwayBigChances(awayChanceLine.bigChances());
        stats.setHomeBigChancesMissed(homeChanceLine.bigChancesMissed());
        stats.setAwayBigChancesMissed(awayChanceLine.bigChancesMissed());
        stats.setHomeXg(liveData.getHomeXg() != null
                ? liveData.getHomeXg() : homeChanceLine.xgHundredths());
        stats.setAwayXg(liveData.getAwayXg() != null
                ? liveData.getAwayXg() : awayChanceLine.xgHundredths());

        // Crosses
        int hc = clamp((int) (18 + rng.nextGaussian() * 4 + liveData.getHomeCorners() * 0.5), 5, 40);
        int ac = clamp((int) (18 + rng.nextGaussian() * 4 + liveData.getAwayCorners() * 0.5), 5, 40);
        stats.setHomeCrosses(hc);
        stats.setAwayCrosses(ac);
        stats.setHomeCrossesAccurate(clamp((int) (hc * (0.25 + rng.nextDouble() * 0.15)), 0, hc));
        stats.setAwayCrossesAccurate(clamp((int) (ac * (0.25 + rng.nextDouble() * 0.15)), 0, ac));

        // Duels
        int hd = clamp((int) (55 + rng.nextGaussian() * 8), 35, 85);
        double duelWin = 0.45 + (homeRatio - 0.5) * 0.2 + rng.nextGaussian() * 0.05;
        stats.setHomeDuelsWon(clamp((int) (hd * duelWin), 15, 60));
        stats.setAwayDuelsWon(clamp((int) (hd * (1 - duelWin)), 15, 60));
        stats.setHomeAerialDuelsWon(clamp((int) (14 + rng.nextGaussian() * 3), 5, 25));
        stats.setAwayAerialDuelsWon(clamp((int) (14 + rng.nextGaussian() * 3), 5, 25));

        includeShooterAttempts(stats, homeShooterShots, awayShooterShots,
                homeShooterGoals, awayShooterGoals, rng);

        return saveWithShotEvents(stats);
    }

    private MatchStats saveWithShotEvents(MatchStats stats) {
        MatchStats saved = matchStatsRepository.save(stats);
        if (shotEventService != null) shotEventService.replaceForMatch(saved);
        return saved;
    }

    /**
     * Retrieve match stats for a specific match.
     */
    public Optional<MatchStats> getMatchStats(long competitionId, int season, int round, long team1Id, long team2Id) {
        return matchStatsRepository.findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(
                competitionId, season, round, team1Id, team2Id);
    }

    /**
     * Get aggregated season stats for a team (averages across all matches).
     */
    public Map<String, Object> getTeamSeasonStats(long teamId, int season) {
        List<MatchStats> homeMatches = matchStatsRepository.findAllByTeam1IdAndSeasonNumber(teamId, season);
        List<MatchStats> awayMatches = matchStatsRepository.findAllByTeam2IdAndSeasonNumber(teamId, season);

        int totalMatches = homeMatches.size() + awayMatches.size();
        if (totalMatches == 0) return Map.of("totalMatches", 0);

        // Aggregate: sum home-side stats from home matches + away-side stats from away matches
        int totalShots = 0, totalShotsOnTarget = 0, totalCorners = 0, totalFouls = 0;
        int totalPasses = 0, totalTackles = 0, totalGoals = 0, totalGoalsConceded = 0;
        int totalPossession = 0, totalOffsides = 0, totalBigChances = 0;
        int totalXg = 0, totalXgAgainst = 0;
        int totalYellow = 0, totalRed = 0, totalSaves = 0;
        int totalPassAccSum = 0;

        for (MatchStats m : homeMatches) {
            totalShots += m.getHomeShots();
            totalShotsOnTarget += m.getHomeShotsOnTarget();
            totalCorners += m.getHomeCorners();
            totalFouls += m.getHomeFouls();
            totalPasses += m.getHomePasses();
            totalTackles += m.getHomeTackles();
            totalGoals += m.getHomeGoals();
            totalGoalsConceded += m.getAwayGoals();
            totalPossession += m.getHomePossession();
            totalOffsides += m.getHomeOffsides();
            totalBigChances += m.getHomeBigChances();
            totalXg += m.getHomeXg();
            totalXgAgainst += m.getAwayXg();
            totalYellow += m.getHomeYellowCards();
            totalRed += m.getHomeRedCards();
            totalSaves += m.getHomeSaves();
            totalPassAccSum += m.getHomePassAccuracy();
        }
        for (MatchStats m : awayMatches) {
            totalShots += m.getAwayShots();
            totalShotsOnTarget += m.getAwayShotsOnTarget();
            totalCorners += m.getAwayCorners();
            totalFouls += m.getAwayFouls();
            totalPasses += m.getAwayPasses();
            totalTackles += m.getAwayTackles();
            totalGoals += m.getAwayGoals();
            totalGoalsConceded += m.getHomeGoals();
            totalPossession += m.getAwayPossession();
            totalOffsides += m.getAwayOffsides();
            totalBigChances += m.getAwayBigChances();
            totalXg += m.getAwayXg();
            totalXgAgainst += m.getHomeXg();
            totalYellow += m.getAwayYellowCards();
            totalRed += m.getAwayRedCards();
            totalSaves += m.getAwaySaves();
            totalPassAccSum += m.getAwayPassAccuracy();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalMatches", totalMatches);
        result.put("totalGoals", totalGoals);
        result.put("totalGoalsConceded", totalGoalsConceded);
        result.put("avgPossession", Math.round(totalPossession * 10.0 / totalMatches) / 10.0);
        result.put("totalShots", totalShots);
        result.put("avgShots", Math.round(totalShots * 10.0 / totalMatches) / 10.0);
        result.put("totalShotsOnTarget", totalShotsOnTarget);
        result.put("avgShotsOnTarget", Math.round(totalShotsOnTarget * 10.0 / totalMatches) / 10.0);
        result.put("shotAccuracy", totalShots > 0 ? Math.round(totalShotsOnTarget * 1000.0 / totalShots) / 10.0 : 0);
        result.put("totalCorners", totalCorners);
        result.put("totalFouls", totalFouls);
        result.put("totalPasses", totalPasses);
        result.put("avgPassAccuracy", Math.round(totalPassAccSum * 10.0 / totalMatches) / 10.0);
        result.put("totalTackles", totalTackles);
        result.put("totalOffsides", totalOffsides);
        result.put("totalBigChances", totalBigChances);
        result.put("totalXg", Math.round(totalXg) / 100.0);
        result.put("totalXgAgainst", Math.round(totalXgAgainst) / 100.0);
        result.put("totalYellowCards", totalYellow);
        result.put("totalRedCards", totalRed);
        result.put("totalSaves", totalSaves);

        return result;
    }

    // ==================== TACTICAL HELPERS FOR STATS ====================

    private double getTacticalPossessionBonus(PersonalizedTactic tactic) {
        if (tactic == null) return 0;
        MatchEngineConfig.Stats sc = engineConfig.getStats();
        double bonus = 0;
        if ("Keep Ball".equals(tactic.getInPossession())) bonus += sc.getTacticalPossessionKeepBall();
        else if ("Free Ball Early".equals(tactic.getInPossession())) bonus += sc.getTacticalPossessionFreeBallEarly();
        if ("Short Passing".equals(tactic.getPassingType())) bonus += sc.getTacticalPossessionShortPassing();
        else if ("Long Ball".equals(tactic.getPassingType())) bonus += sc.getTacticalPossessionLongBall();
        if ("Low".equals(tactic.getTempo())) bonus += sc.getTacticalPossessionTempoLow();
        else if ("High".equals(tactic.getTempo())) bonus += sc.getTacticalPossessionTempoHigh();
        return bonus;
    }

    private double getAttackingMentalityShotBonus(PersonalizedTactic tactic) {
        if (tactic == null) return 0;
        MatchEngineConfig.Stats sc = engineConfig.getStats();
        String mentality = tactic.getMentality() != null ? tactic.getMentality() : "Balanced";
        return switch (mentality) {
            case "Very Attacking" -> sc.getShotBonusVeryAttacking();
            case "Attacking" -> sc.getShotBonusAttacking();
            case "Defensive" -> sc.getShotBonusDefensive();
            case "Very Defensive" -> sc.getShotBonusVeryDefensive();
            default -> 0;
        };
    }

    /**
     * Chance quality is drawn from the pre-match matchup and shot budget, not
     * conditioned on the score that happened to be sampled.  A 1-1 upset can
     * therefore retain (for example) a 2.4-0.4 xG profile.
     */
    private ChanceLine generatePreMatchChanceLine(int requestedShots, int goals, double powerShare,
                                                   Integer fixedShotsOnTarget, Random rng) {
        MatchEngineConfig.Stats sc = engineConfig.getStats();
        int shots = Math.max(requestedShots, goals);
        if (shots == 0) return new ChanceLine(0, 0, 0, 0);

        double bigChanceRate = clamp(sc.getXgBigChanceRate()
                + (powerShare - 0.5) * sc.getXgBigChancePowerSpan(), 0.02, 0.18);
        double totalXg = 0;
        int bigChances = 0;
        for (int index = 0; index < shots; index++) {
            ChanceQuality chance = sampleBaseChanceQuality(bigChanceRate, rng);
            totalXg += chance.xg();
            if (chance.big()) bigChances++;
        }
        // Multiple goals from an entire set of near-zero-quality attempts are not a
        // credible event ledger. Keep the one-shot wonder goal possible, while
        // applying a conservative floor once a side scores more than once.
        if (goals > 1) totalXg = Math.max(totalXg, goals * .30);

        int shotsOnTarget;
        if (fixedShotsOnTarget != null) {
            shotsOnTarget = clamp(fixedShotsOnTarget, goals, shots);
        } else {
            double onTargetRate = clamp(sc.getShotsOnTargetBase()
                    + (powerShare - 0.5) * sc.getShotsOnTargetEdgeSpan()
                    + rng.nextGaussian() * sc.getShotsOnTargetNoise(), 0.12, 0.70);
            shotsOnTarget = goals + randomBounded(rng, shots - goals, onTargetRate);
        }
        return new ChanceLine(shotsOnTarget, bigChances,
                Math.max(0, bigChances - Math.min(bigChances, goals)),
                Math.max(0, (int) Math.round(totalXg * 100)));
    }

    private ChanceLine generateChanceLine(int requestedShots, int goals, double powerShare,
                                          Integer fixedShotsOnTarget, Random rng) {
        MatchEngineConfig.Stats sc = engineConfig.getStats();
        int shots = Math.max(requestedShots, goals);
        if (shots == 0) return new ChanceLine(0, 0, 0, 0);

        double bigChanceRate = clamp(sc.getXgBigChanceRate()
                + (powerShare - 0.5) * sc.getXgBigChancePowerSpan(), 0.02, 0.18);
        ChanceSample selectedSample = null;
        double bestScorelineProbability = -1;
        int attempts = Math.max(1, sc.getXgScorelineResampleAttempts());
        for (int attempt = 0; attempt < attempts; attempt++) {
            ChanceSample candidate = sampleChanceSet(shots, Math.min(goals, shots), bigChanceRate, rng);
            double probability = exactGoalCountProbability(candidate.chances(), Math.min(goals, shots));
            if (probability > bestScorelineProbability) {
                selectedSample = candidate;
                bestScorelineProbability = probability;
            }
            if (probability >= sc.getXgScorelineProbabilityFloor()) {
                selectedSample = candidate;
                break;
            }
        }

        boolean[] goalShot = selectedSample.goalShots();
        double[] chances = selectedSample.chances();
        boolean[] bigChance = selectedSample.bigChances();
        double totalXg = selectedSample.totalXg();

        int bigChances = 0;
        int bigChanceGoals = 0;
        for (int i = 0; i < shots; i++) {
            if (!bigChance[i]) continue;
            bigChances++;
            if (goalShot[i]) bigChanceGoals++;
        }

        int shotsOnTarget;
        if (fixedShotsOnTarget != null) {
            shotsOnTarget = clamp(fixedShotsOnTarget, goals, shots);
        } else {
            double baseOnTargetRate = clamp(sc.getShotsOnTargetBase()
                    + (powerShare - 0.5) * sc.getShotsOnTargetEdgeSpan()
                    + rng.nextGaussian() * sc.getShotsOnTargetNoise(), 0.12, 0.70);
            shotsOnTarget = goals;
            for (int i = 0; i < shots; i++) {
                if (goalShot[i]) continue;
                double shotOnTargetChance = clamp(baseOnTargetRate + (chances[i] - 0.10) * 0.45,
                        0.08, 0.85);
                if (rng.nextDouble() < shotOnTargetChance) shotsOnTarget++;
            }
        }

        return new ChanceLine(shotsOnTarget, bigChances,
                Math.max(0, bigChances - bigChanceGoals), Math.max(0, (int) Math.round(totalXg * 100)));
    }

    private ChanceSample sampleChanceSet(int shots, int goals, double bigChanceRate, Random rng) {
        boolean[] goalShots = selectOutcomeSlots(shots, goals, rng);
        double[] chances = new double[shots];
        boolean[] bigChances = new boolean[shots];
        double totalXg = 0;
        for (int i = 0; i < shots; i++) {
            ChanceQuality chance = sampleChanceQuality(bigChanceRate, goalShots[i], rng);
            chances[i] = chance.xg();
            bigChances[i] = chance.big();
            totalXg += chance.xg();
        }
        return new ChanceSample(goalShots, chances, bigChances, totalXg);
    }

    /**
     * Draw chance quality conditional on the result of the shot. If the base
     * chance distribution is f(p), Bayes gives f(p | goal) proportional to
     * p*f(p), and f(p | miss) proportional to (1-p)*f(p). Rejection sampling
     * applies exactly those likelihood weights. This keeps a low-xG wonder goal
     * possible, while making six such goals in one match appropriately remote.
     */
    private ChanceQuality sampleChanceQuality(double bigChanceRate, boolean goal, Random rng) {
        while (true) {
            ChanceQuality candidate = sampleBaseChanceQuality(bigChanceRate, rng);
            double outcomeLikelihood = goal ? candidate.xg() : 1.0 - candidate.xg();
            if (rng.nextDouble() < outcomeLikelihood) return candidate;
        }
    }

    private ChanceQuality sampleBaseChanceQuality(double bigChanceRate, Random rng) {
        MatchEngineConfig.Stats sc = engineConfig.getStats();
        boolean big = rng.nextDouble() < bigChanceRate;
        double xg = big
                ? sc.getXgBigMin() + Math.pow(rng.nextDouble(), 1.5)
                        * (sc.getXgBigMax() - sc.getXgBigMin())
                : sc.getXgRegularMin() + Math.pow(rng.nextDouble(), 2.0)
                        * (sc.getXgRegularMax() - sc.getXgRegularMin());
        return new ChanceQuality(xg, big);
    }

    private boolean[] selectOutcomeSlots(int shots, int goals, Random rng) {
        boolean[] goalShot = new boolean[shots];
        int goalsLeft = goals;
        for (int i = 0; i < shots && goalsLeft > 0; i++) {
            int slotsLeft = shots - i;
            if (rng.nextInt(slotsLeft) < goalsLeft) {
                goalShot[i] = true;
                goalsLeft--;
            }
        }
        return goalShot;
    }

    /** Exact Poisson-binomial probability for scoring precisely {@code goals} from these shots. */
    private double exactGoalCountProbability(double[] chances, int goals) {
        if (goals < 0 || goals > chances.length) return 0;
        double[] probabilities = new double[goals + 1];
        probabilities[0] = 1.0;
        int processed = 0;
        for (double chance : chances) {
            int upper = Math.min(++processed, goals);
            for (int scored = upper; scored >= 1; scored--) {
                probabilities[scored] = probabilities[scored] * (1.0 - chance)
                        + probabilities[scored - 1] * chance;
            }
            probabilities[0] *= 1.0 - chance;
        }
        return probabilities[goals];
    }

    private double logNormalMultiplier(Random rng, double sigma) {
        return Math.exp(rng.nextGaussian() * sigma - 0.5 * sigma * sigma);
    }

    private int poisson(Random rng, double mean, int max) {
        double limit = Math.exp(-mean);
        double product = 1.0;
        int count = 0;
        do {
            count++;
            product *= rng.nextDouble();
        } while (product > limit && count <= max);
        return Math.min(count - 1, max);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ChanceQuality(double xg, boolean big) {}

    private record ChanceSample(boolean[] goalShots, double[] chances,
                                boolean[] bigChances, double totalXg) {}

    private record ChanceLine(int shotsOnTarget, int bigChances, int bigChancesMissed, int xgHundredths) {}
}
