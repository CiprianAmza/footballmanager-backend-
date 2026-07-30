package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only best-tactic facade backed exclusively by the canonical compartment evaluator.
 * Every formation/tactic is priced against the club's real league opponents; no synthetic
 * two-axis panel and no historical score formula participates in the ranking.
 */
@Service
public class BestTacticService {

    @Autowired private TacticSimulationService tacticSimulationService;
    @Autowired private TacticService tacticService;
    @Autowired private TeamRepository teamRepository;

    public record TacticRow(String formation, String mentality, String tempo, String passingType,
                            String inPossession, String timeWasting, double expectedGoalDifference,
                            double expectedPoints, String defensiveLine, String pressing, String width,
                            String dribbling, String foulFrequency, String foulHardness,
                            String tempoFragmentation, String widePlay, String transition, String recovery) {}

    public record BestTacticResult(long teamId, String teamName, String recommendedFormation,
                                   String recommendedMentality, String recommendedTempo,
                                   String recommendedPassingType, String recommendedInPossession,
                                   String recommendedTimeWasting, String recommendedDefensiveLine,
                                   String recommendedPressing, String recommendedWidth,
                                   String recommendedDribbling, String recommendedFoulFrequency,
                                   String recommendedFoulHardness, String recommendedTempoFragmentation,
                                   String recommendedWidePlay, String recommendedTransition, String recommendedRecovery,
                                   double expectedGoalDifference,
                                   double expectedPoints, double winProbability, double drawProbability,
                                   double lossProbability, double baseSquadValue, List<TacticRow> top) {}

    private record CanonicalRank(TacticRow row, double winProbability,
                                 double drawProbability, double lossProbability) {}

    public BestTacticResult findBestTactic(long teamId) {
        List<CanonicalRank> ranked = rankCanonical(teamId);
        if (ranked.isEmpty()) throw new IllegalArgumentException("No canonical tactic candidates for team " + teamId);
        CanonicalRank bestRank = ranked.get(0);
        TacticRow best = bestRank.row();
        List<TacticRow> top = ranked.stream().limit(15).map(CanonicalRank::row).toList();
        double baseSquadValue = tacticSimulationService
                .canonicalFormation(teamId, best.formation(), reconstruct(best)).topXiRating();
        String name = teamRepository.findNameById(teamId);
        return new BestTacticResult(teamId, name == null ? "Team#" + teamId : name,
                best.formation(), best.mentality(), best.tempo(), best.passingType(),
                best.inPossession(), best.timeWasting(), best.defensiveLine(), best.pressing(),
                best.width(), best.dribbling(), best.foulFrequency(), best.foulHardness(),
                best.tempoFragmentation(), best.widePlay(), best.transition(),
                best.recovery(),
                best.expectedGoalDifference(), best.expectedPoints(),
                bestRank.winProbability(), bestRank.drawProbability(), bestRank.lossProbability(),
                baseSquadValue, top);
    }

    public List<TacticRow> rankAllTactics(long teamId) {
        return rankCanonical(teamId).stream().map(CanonicalRank::row).toList();
    }

    private List<CanonicalRank> rankCanonical(long teamId) {
        List<CanonicalRank> ranked = new ArrayList<>();
        for (String formation : tacticService.getAllExistingTactics()) {
            TacticSimulationService.AnalyticalResult result =
                    tacticSimulationService.analyticalTacticPoints(teamId, formation, 0, null);
            for (TacticSimulationService.AnalyticalRow row : result.rows()) {
                TacticRow tactic = new TacticRow(formation, row.mentality(), row.tempo(), row.passingType(),
                        row.inPossession(), row.timeWasting(), row.expectedGoalDifference(),
                        row.expectedPoints(), row.defensiveLine(), row.pressing(), row.width(),
                        row.dribbling(), row.foulFrequency(), row.foulHardness(),
                        row.tempoFragmentation(), row.widePlay(), row.transition(), row.recovery());
                ranked.add(new CanonicalRank(tactic, row.winProbability(), row.drawProbability(),
                        row.lossProbability()));
            }
        }
        ranked.sort(Comparator.comparingDouble((CanonicalRank value) -> value.row().expectedPoints())
                .thenComparingDouble(value -> value.row().expectedGoalDifference()).reversed());
        return List.copyOf(ranked);
    }

    private static PersonalizedTactic reconstruct(TacticRow row) {
        PersonalizedTactic tactic = new PersonalizedTactic();
        tactic.setTactic(row.formation());
        tactic.setMentality(row.mentality());
        tactic.setTempo(row.tempo());
        tactic.setPassingType(row.passingType());
        tactic.setInPossession(row.inPossession());
        tactic.setTimeWasting(row.timeWasting());
        tactic.setDefensiveLine(row.defensiveLine());
        tactic.setPressing(row.pressing());
        tactic.setWidth(row.width());
        tactic.setDribbling(row.dribbling());
        tactic.setFoulFrequency(row.foulFrequency());
        tactic.setFoulHardness(row.foulHardness());
        tactic.setTempoFragmentation(row.tempoFragmentation());
        tactic.setWidePlay(row.widePlay());
        tactic.setTransition(row.transition());
        tactic.setRecovery(row.recovery());
        return tactic;
    }
}
