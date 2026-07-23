package com.footballmanagergamesimulator.analytics;

import com.footballmanagergamesimulator.matchplan.MatchPlan;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 0 parity / reconciliation gate.
 *
 * <p>Reads the committed canonical goal totals from a {@link MatchPlan} and
 * compares them to the two other places the same score is already persisted: the
 * fixture result row ({@link CompetitionTeamInfoMatch#getTeam1Score()}) and the
 * {@link MatchStats} aggregate. It reports agreement, an explicit diff, or that
 * there is nothing canonical to reconcile against — it never recomputes a score,
 * resamples events, or fabricates any missing fact.
 */
@Service
public class MatchReconciliationService {

    private final MatchPlanRepository matchPlanRepository;
    private final MatchStatsRepository matchStatsRepository;
    private final CompetitionTeamInfoMatchRepository fixtureRepository;

    public MatchReconciliationService(MatchPlanRepository matchPlanRepository,
                                      MatchStatsRepository matchStatsRepository,
                                      CompetitionTeamInfoMatchRepository fixtureRepository) {
        this.matchPlanRepository = matchPlanRepository;
        this.matchStatsRepository = matchStatsRepository;
        this.fixtureRepository = fixtureRepository;
    }

    /** Immutable outcome of a reconciliation. Null score fields mean "source absent". */
    public record Result(ReconciliationStatus status, String detail,
                         Integer canonicalHome, Integer canonicalAway,
                         Integer resultHome, Integer resultAway,
                         Integer statsHome, Integer statsAway) {

        public boolean isMismatch() {
            return status == ReconciliationStatus.MISMATCH;
        }

        static Result notApplicable(String detail) {
            return new Result(ReconciliationStatus.NOT_APPLICABLE, detail,
                    null, null, null, null, null, null);
        }
    }

    @Transactional(readOnly = true)
    public Result reconcile(String fixtureKey) {
        Optional<MatchPlan> planOpt = matchPlanRepository.findByFixtureKey(fixtureKey);
        if (planOpt.isEmpty()) {
            return Result.notApplicable("No canonical MatchPlan for fixture");
        }
        MatchPlan plan = planOpt.get();
        if (plan.getStatus() != MatchPlan.Status.COMPLETED
                && plan.getStatus() != MatchPlan.Status.COMMITTED) {
            return new Result(ReconciliationStatus.PENDING,
                    "MatchPlan not terminal (" + plan.getStatus() + ")",
                    plan.getHomeGoals(), plan.getAwayGoals(), null, null, null, null);
        }

        int canonicalHome = plan.getHomeGoals();
        int canonicalAway = plan.getAwayGoals();

        Integer resultHome = null;
        Integer resultAway = null;
        Integer statsHome = null;
        Integer statsAway = null;

        long matchRowId = FixtureIdentity.matchRowId(fixtureKey);
        if (matchRowId >= 0) {
            Optional<CompetitionTeamInfoMatch> fixtureOpt = fixtureRepository.findById(matchRowId);
            if (fixtureOpt.isPresent()) {
                CompetitionTeamInfoMatch fixture = fixtureOpt.get();
                if (fixture.getTeam1Score() >= 0 && fixture.getTeam2Score() >= 0) {
                    resultHome = fixture.getTeam1Score();
                    resultAway = fixture.getTeam2Score();
                }
                Integer season = parseSeason(fixture.getSeasonNumber());
                if (season != null) {
                    Optional<MatchStats> statsOpt = matchStatsRepository
                            .findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(
                                    fixture.getCompetitionId(), season, (int) fixture.getRound(),
                                    fixture.getTeam1Id(), fixture.getTeam2Id());
                    if (statsOpt.isPresent()) {
                        statsHome = statsOpt.get().getHomeGoals();
                        statsAway = statsOpt.get().getAwayGoals();
                    }
                }
            }
        }

        List<String> diffs = new ArrayList<>();
        if (resultHome != null && (resultHome != canonicalHome || resultAway != canonicalAway)) {
            diffs.add("result=" + resultHome + "-" + resultAway);
        }
        if (statsHome != null && (statsHome != canonicalHome || statsAway != canonicalAway)) {
            diffs.add("stats=" + statsHome + "-" + statsAway);
        }
        if (!diffs.isEmpty()) {
            String detail = "canonical=" + canonicalHome + "-" + canonicalAway + "; "
                    + String.join("; ", diffs);
            return new Result(ReconciliationStatus.MISMATCH, detail,
                    canonicalHome, canonicalAway, resultHome, resultAway, statsHome, statsAway);
        }
        return new Result(ReconciliationStatus.MATCH, null,
                canonicalHome, canonicalAway, resultHome, resultAway, statsHome, statsAway);
    }

    private static Integer parseSeason(String season) {
        if (season == null) {
            return null;
        }
        try {
            return Integer.parseInt(season.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
