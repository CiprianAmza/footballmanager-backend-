package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
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

        // Rebalanced 2026: every stat is now ~70% deterministic from team-power
        // ratio and ~30% noise. Old version had σ values that overwhelmed the
        // power signal, so two top teams looked statistically the same as two
        // relegation candidates. Now squad value visibly drives the line.
        Random rng = this.random;
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

        // --- SHOTS ---
        double homeBaseShots = sc.getShotsBase() + homeRatio * sc.getShotsEdgeScale()
                + homeGoals * sc.getShotsGoalsBonus() + rng.nextGaussian() * 1.0;
        double awayBaseShots = sc.getShotsBase() + (1 - homeRatio) * sc.getShotsEdgeScale()
                + awayGoals * sc.getShotsGoalsBonus() + rng.nextGaussian() * 1.0;
        homeBaseShots += getAttackingMentalityShotBonus(homeTactic);
        awayBaseShots += getAttackingMentalityShotBonus(awayTactic);
        int homeShots = clamp((int) homeBaseShots, 2, 30);
        int awayShots = clamp((int) awayBaseShots, 2, 30);
        stats.setHomeShots(homeShots);
        stats.setAwayShots(awayShots);

        // Shots on target
        double homeSoTRate = sc.getShotsOnTargetBase() + edge * sc.getShotsOnTargetEdgeSpan()
                + rng.nextDouble() * sc.getShotsOnTargetNoise();
        double awaySoTRate = sc.getShotsOnTargetBase() - edge * sc.getShotsOnTargetEdgeSpan()
                + rng.nextDouble() * sc.getShotsOnTargetNoise();
        int homeSoT = Math.max(homeGoals, clamp((int) (homeShots * homeSoTRate), 0, homeShots));
        int awaySoT = Math.max(awayGoals, clamp((int) (awayShots * awaySoTRate), 0, awayShots));
        stats.setHomeShotsOnTarget(homeSoT);
        stats.setAwayShotsOnTarget(awaySoT);

        // Blocked shots
        int homeBlocked = clamp((int) (homeShots * (sc.getBlockedShotsBase() + rng.nextDouble() * sc.getBlockedShotsNoiseSpan())), 0, homeShots - homeSoT);
        int awayBlocked = clamp((int) (awayShots * (sc.getBlockedShotsBase() + rng.nextDouble() * sc.getBlockedShotsNoiseSpan())), 0, awayShots - awaySoT);
        stats.setHomeShotsBlocked(homeBlocked);
        stats.setAwayShotsBlocked(awayBlocked);

        // --- CORNERS ---
        int homeCorners = clamp((int) (sc.getCornersBase() + homeShots * sc.getCornersPerShot() + rng.nextGaussian() * sc.getCornersNoise()), 0, 15);
        int awayCorners = clamp((int) (sc.getCornersBase() + awayShots * sc.getCornersPerShot() + rng.nextGaussian() * sc.getCornersNoise()), 0, 15);
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

        // --- BIG CHANCES ---
        int homeBigChances = Math.max(homeGoals, clamp((int) (homeSoT * sc.getBigChancesSoTRatio() + rng.nextGaussian() * 0.8), 0, 8));
        int awayBigChances = Math.max(awayGoals, clamp((int) (awaySoT * sc.getBigChancesSoTRatio() + rng.nextGaussian() * 0.8), 0, 8));
        stats.setHomeBigChances(homeBigChances);
        stats.setAwayBigChances(awayBigChances);
        stats.setHomeBigChancesMissed(Math.max(0, homeBigChances - homeGoals));
        stats.setAwayBigChancesMissed(Math.max(0, awayBigChances - awayGoals));

        // --- xG ---
        double homeXg = homeBigChances * sc.getXgPerBigChance()
                + (homeSoT - homeGoals) * sc.getXgPerSotMiss()
                + (homeShots - homeSoT) * sc.getXgPerWideShot()
                + rng.nextGaussian() * 0.08;
        double awayXg = awayBigChances * sc.getXgPerBigChance()
                + (awaySoT - awayGoals) * sc.getXgPerSotMiss()
                + (awayShots - awaySoT) * sc.getXgPerWideShot()
                + rng.nextGaussian() * 0.08;
        stats.setHomeXg(Math.max(0, (int) (homeXg * 100)));
        stats.setAwayXg(Math.max(0, (int) (awayXg * 100)));

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
        return matchStatsRepository.save(stats);
    }

    /**
     * Persist stats from a LiveMatchData result (live match already generated the stats).
     */
    public MatchStats persistLiveMatchStats(
            long competitionId, int season, int round,
            long team1Id, long team2Id,
            LiveMatchData liveData,
            double homePower, double awayPower) {

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

        // Big chances
        int hbc = liveData.getHomeScore() + clamp((int) (rng.nextDouble() * 3), 0, 4);
        int abc = liveData.getAwayScore() + clamp((int) (rng.nextDouble() * 3), 0, 4);
        stats.setHomeBigChances(hbc);
        stats.setAwayBigChances(abc);
        stats.setHomeBigChancesMissed(Math.max(0, hbc - liveData.getHomeScore()));
        stats.setAwayBigChancesMissed(Math.max(0, abc - liveData.getAwayScore()));

        // xG
        double hxg = hbc * 0.35 + (liveData.getHomeShotsOnTarget() - liveData.getHomeScore()) * 0.12
                + (liveData.getHomeShots() - liveData.getHomeShotsOnTarget()) * 0.05 + rng.nextGaussian() * 0.15;
        double axg = abc * 0.35 + (liveData.getAwayShotsOnTarget() - liveData.getAwayScore()) * 0.12
                + (liveData.getAwayShots() - liveData.getAwayShotsOnTarget()) * 0.05 + rng.nextGaussian() * 0.15;
        stats.setHomeXg(Math.max(0, (int) (hxg * 100)));
        stats.setAwayXg(Math.max(0, (int) (axg * 100)));

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

        return matchStatsRepository.save(stats);
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
