package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.PossessionProgression;
import com.footballmanagergamesimulator.repository.PossessionProgressionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Derives and persists a deterministic progression ledger from the authoritative match stat line. */
@Service
public class PossessionProgressionLedgerService {
    public static final String QUALITY = "MODELED_FROM_MATCH_STATS";

    private final PossessionProgressionRepository repository;

    public PossessionProgressionLedgerService(PossessionProgressionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void replaceForMatch(MatchStats stats) {
        if (stats == null || stats.getId() <= 0) return;
        repository.deleteAllByMatchStatsId(stats.getId());
        repository.saveAll(generate(stats));
    }

    /** Historical saves are upgraded in memory; analytics GETs never mutate the career. */
    public List<PossessionProgression> progressionsForMatch(MatchStats stats) {
        List<PossessionProgression> stored = repository.findAllByMatchStatsIdOrderByTeamIdAsc(stats.getId());
        return stored == null || stored.size() != 2 ? generate(stats) : stored;
    }

    public List<PossessionProgression> generate(MatchStats stats) {
        Draft home = draft(stats, true);
        Draft away = draft(stats, false);
        int totalTerritory = Math.max(1, home.finalThirdEntries + away.finalThirdEntries);
        return List.of(toEntity(stats, true, home, 100.0 * home.finalThirdEntries / totalTerritory),
                toEntity(stats, false, away, 100.0 * away.finalThirdEntries / totalTerritory));
    }

    private Draft draft(MatchStats stats, boolean home) {
        int passes = nonNegative(home ? stats.getHomePasses() : stats.getAwayPasses());
        int accuracy = clamp(home ? stats.getHomePassAccuracy() : stats.getAwayPassAccuracy(), 0, 100);
        int possession = clamp(home ? stats.getHomePossession() : stats.getAwayPossession(), 0, 100);
        int shots = nonNegative(home ? stats.getHomeShots() : stats.getAwayShots());
        int corners = nonNegative(home ? stats.getHomeCorners() : stats.getAwayCorners());
        int crosses = nonNegative(home ? stats.getHomeCrosses() : stats.getAwayCrosses());
        int accurateCrosses = nonNegative(home ? stats.getHomeCrossesAccurate() : stats.getAwayCrossesAccurate());
        int completed = Math.min(passes, (int) Math.round(passes * accuracy / 100.0));

        double passesPerPossession = clamp(3.05 + accuracy / 24.0 + possession / 45.0, 3.2, 8.5);
        int possessions = Math.max(shots, (int) Math.round(completed / passesPerPossession));
        int progressivePasses = Math.min(completed, Math.max(shots,
                (int) Math.round(completed * clamp(.085 + shots / 230.0 + accurateCrosses / 700.0, .09, .22))));
        int progressiveCarries = Math.max(0,
                (int) Math.round(shots * .75 + corners * .30 + accurateCrosses * .38 + crosses * .08));
        int finalThirdEntries = Math.max(shots,
                (int) Math.round(progressivePasses * .66 + progressiveCarries * .70 + corners * .30));
        int penaltyAreaEntries = Math.min(finalThirdEntries, Math.max(shots,
                (int) Math.round(finalThirdEntries * (.42 + Math.min(.12, accurateCrosses / 100.0)))));
        int passesIntoBox = Math.min(penaltyAreaEntries, Math.max(0,
                (int) Math.round(progressivePasses * .20 + accurateCrosses * .45)));
        int lineBreakingPasses = Math.min(progressivePasses,
                (int) Math.round(progressivePasses * .39 + passesIntoBox * .24));
        int receptionsBetweenLines = Math.min(finalThirdEntries,
                (int) Math.round(lineBreakingPasses * .86 + progressiveCarries * .25));
        int switches = Math.min(progressivePasses,
                (int) Math.round(completed * .021 + crosses * .13));
        int tenPassSequences = Math.max(0,
                (int) Math.round(possessions * (possession / 100.0) * .10 * (passesPerPossession / 5.5)));
        int buildUps = Math.max(0,
                (int) Math.round(possessions * .075 * (possession / 50.0) * (accuracy / 80.0)));
        int direct = Math.max(0,
                (int) Math.round(possessions * .045 * (1.45 - possession / 100.0) + shots * .15));
        double avgDuration = possessions == 0 ? 0 : (5_400.0 * possession / 100.0) / possessions;
        double directShare = direct + buildUps == 0 ? 0 : direct / (double) (direct + buildUps);
        double directSpeed = clamp(1.02 + directShare * 1.08 + (6.0 - passesPerPossession) * .07, .72, 2.75);
        return new Draft(possessions, completed, progressivePasses, progressiveCarries, finalThirdEntries,
                penaltyAreaEntries, lineBreakingPasses, passesIntoBox, receptionsBetweenLines, switches,
                round(passesPerPossession), round(avgDuration), round(directSpeed), tenPassSequences, buildUps, direct);
    }

    private PossessionProgression toEntity(MatchStats stats, boolean home, Draft draft, double fieldTilt) {
        PossessionProgression row = new PossessionProgression();
        row.setMatchStatsId(stats.getId());
        row.setCompetitionId(stats.getCompetitionId());
        row.setSeasonNumber(stats.getSeasonNumber());
        row.setRoundNumber(stats.getRoundNumber());
        row.setTeamId(home ? stats.getTeam1Id() : stats.getTeam2Id());
        row.setOpponentTeamId(home ? stats.getTeam2Id() : stats.getTeam1Id());
        row.setPossessions(draft.possessions);
        row.setCompletedPasses(draft.completedPasses);
        row.setProgressivePasses(draft.progressivePasses);
        row.setProgressiveCarries(draft.progressiveCarries);
        row.setFinalThirdEntries(draft.finalThirdEntries);
        row.setPenaltyAreaEntries(draft.penaltyAreaEntries);
        row.setLineBreakingPasses(draft.lineBreakingPasses);
        row.setPassesIntoBox(draft.passesIntoBox);
        row.setReceptionsBetweenLines(draft.receptionsBetweenLines);
        row.setSwitchesOfPlay(draft.switchesOfPlay);
        row.setFieldTiltPercentage(round(fieldTilt));
        row.setPassesPerPossession(draft.passesPerPossession);
        row.setAveragePossessionDurationSeconds(draft.averagePossessionDurationSeconds);
        row.setDirectSpeedMetersPerSecond(draft.directSpeedMetersPerSecond);
        row.setTenPassSequences(draft.tenPassSequences);
        row.setBuildUpAttacks(draft.buildUpAttacks);
        row.setDirectAttacks(draft.directAttacks);
        row.setDataQuality(QUALITY);
        return row;
    }

    private int nonNegative(int value) { return Math.max(0, value); }
    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private double round(double value) { return Math.round(value * 100.0) / 100.0; }

    private record Draft(int possessions, int completedPasses, int progressivePasses, int progressiveCarries,
                         int finalThirdEntries, int penaltyAreaEntries, int lineBreakingPasses,
                         int passesIntoBox, int receptionsBetweenLines, int switchesOfPlay,
                         double passesPerPossession, double averagePossessionDurationSeconds,
                         double directSpeedMetersPerSecond, int tenPassSequences,
                         int buildUpAttacks, int directAttacks) {}
}
