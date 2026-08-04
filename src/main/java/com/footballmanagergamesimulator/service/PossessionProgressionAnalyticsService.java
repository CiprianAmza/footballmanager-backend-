package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.PlayerSeasonStat;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.PossessionProgression;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.PlayerSeasonStatRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Team-season view of useful possession, territory and individual build-up contribution. */
@Service
public class PossessionProgressionAnalyticsService {
    private final MatchStatsRepository matchStatsRepository;
    private final PossessionProgressionLedgerService ledgerService;
    private final PlayerSeasonStatRepository playerSeasonStatRepository;
    private final HumanRepository humanRepository;
    private final PlayerSkillsRepository playerSkillsRepository;

    public PossessionProgressionAnalyticsService(MatchStatsRepository matchStatsRepository,
                                                 PossessionProgressionLedgerService ledgerService,
                                                 PlayerSeasonStatRepository playerSeasonStatRepository,
                                                 HumanRepository humanRepository,
                                                 PlayerSkillsRepository playerSkillsRepository) {
        this.matchStatsRepository = matchStatsRepository;
        this.ledgerService = ledgerService;
        this.playerSeasonStatRepository = playerSeasonStatRepository;
        this.humanRepository = humanRepository;
        this.playerSkillsRepository = playerSkillsRepository;
    }

    public PossessionProgressionAnalytics analytics(long teamId, int seasonNumber) {
        List<MatchStats> matches = new ArrayList<>();
        matches.addAll(matchStatsRepository.findAllByTeam1IdAndSeasonNumber(teamId, seasonNumber));
        matches.addAll(matchStatsRepository.findAllByTeam2IdAndSeasonNumber(teamId, seasonNumber));
        matches.sort(Comparator.comparingInt(MatchStats::getRoundNumber).thenComparingLong(MatchStats::getId));

        List<PossessionProgression> rows = new ArrayList<>();
        int shots = 0;
        for (MatchStats match : matches) {
            shots += teamId == match.getTeam1Id() ? match.getHomeShots() : match.getAwayShots();
            ledgerService.progressionsForMatch(match).stream()
                    .filter(row -> row.getTeamId() == teamId).findFirst().ifPresent(rows::add);
        }

        Totals totals = totals(rows, shots);
        Averages averages = averages(rows, totals);
        ProgressionFunnel funnel = new ProgressionFunnel(totals.completedPasses(), totals.progressivePasses(),
                totals.finalThirdEntries(), totals.penaltyAreaEntries(), totals.shots());
        List<PlayerContribution> players = contributions(teamId, seasonNumber, totals);
        return new PossessionProgressionAnalytics(teamId, seasonNumber, matches.size(), totals, averages, funnel,
                players, new DataQuality(PossessionProgressionLedgerService.QUALITY, rows.size(),
                        players.isEmpty() ? "NO_PLAYER_SEASON_ROWS" : "MODELLED_ALLOCATION"),
                "Progression and territory are deterministic estimates from the saved match stat line. "
                        + "Player values allocate team totals using actual seasonal passes, dribbles and chances "
                        + "plus technical attributes; they reconcile to the displayed team totals and are not event tracking.");
    }

    private Totals totals(List<PossessionProgression> rows, int shots) {
        return new Totals(
                sum(rows, Metric.POSSESSIONS), sum(rows, Metric.COMPLETED_PASSES),
                sum(rows, Metric.PROGRESSIVE_PASSES), sum(rows, Metric.PROGRESSIVE_CARRIES),
                sum(rows, Metric.FINAL_THIRD), sum(rows, Metric.PENALTY_AREA),
                sum(rows, Metric.LINE_BREAKING), sum(rows, Metric.PASSES_INTO_BOX),
                sum(rows, Metric.RECEPTIONS), sum(rows, Metric.SWITCHES),
                sum(rows, Metric.TEN_PASS), sum(rows, Metric.BUILD_UP), sum(rows, Metric.DIRECT),
                Math.max(0, shots));
    }

    private Averages averages(List<PossessionProgression> rows, Totals totals) {
        int matches = rows.size();
        int possessions = Math.max(1, totals.possessions());
        double weightedDuration = rows.stream().mapToDouble(
                row -> row.getAveragePossessionDurationSeconds() * row.getPossessions()).sum() / possessions;
        double weightedSpeed = rows.stream().mapToDouble(
                row -> row.getDirectSpeedMetersPerSecond() * row.getPossessions()).sum() / possessions;
        return new Averages(
                round(matches == 0 ? 0 : rows.stream().mapToDouble(PossessionProgression::getFieldTiltPercentage).average().orElse(0)),
                round(totals.completedPasses() / (double) possessions), round(weightedDuration), round(weightedSpeed),
                perMatch(totals.progressivePasses(), matches), perMatch(totals.progressiveCarries(), matches),
                percentage(totals.finalThirdEntries(), totals.progressivePasses() + totals.progressiveCarries()),
                percentage(totals.penaltyAreaEntries(), totals.finalThirdEntries()),
                percentage(totals.shots(), totals.penaltyAreaEntries()));
    }

    private List<PlayerContribution> contributions(long teamId, int seasonNumber, Totals totals) {
        Map<Long, PlayerAggregate> aggregates = new LinkedHashMap<>();
        for (PlayerSeasonStat stat : playerSeasonStatRepository.findAllByTeamIdAndSeasonNumber(teamId, seasonNumber)) {
            aggregates.computeIfAbsent(stat.getPlayerId(), ignored -> new PlayerAggregate()).add(stat);
        }
        if (aggregates.isEmpty()) return List.of();

        Map<Long, Human> humans = indexHumans(humanRepository.findAllById(aggregates.keySet()));
        Map<Long, PlayerSkills> skills = new HashMap<>();
        for (PlayerSkills row : playerSkillsRepository.findAllByPlayerIdIn(aggregates.keySet())) {
            skills.put(row.getPlayerId(), row);
        }

        List<PlayerDraft> drafts = new ArrayList<>();
        for (Map.Entry<Long, PlayerAggregate> entry : aggregates.entrySet()) {
            long playerId = entry.getKey();
            PlayerAggregate stat = entry.getValue();
            PlayerSkills skill = skills.get(playerId);
            double passingQuality = quality(skill, "PASS");
            double carryingQuality = quality(skill, "CARRY");
            double receptionQuality = quality(skill, "RECEIVE");
            double passWeight = Math.max(.1, stat.passesCompleted * (.55 + passingQuality));
            double carryWeight = Math.max(.1, stat.dribblesCompleted * 3.0 * (.55 + carryingQuality)
                    + stat.minutes / 180.0 * carryingQuality);
            double creationWeight = Math.max(.1, stat.chancesCreated * 4.0 + passWeight * .12);
            Human human = humans.get(playerId);
            drafts.add(new PlayerDraft(playerId, human == null ? "Player " + playerId : human.getName(),
                    human == null ? "" : human.getPosition(), stat.appearances, stat.minutes,
                    round(stat.passesCompleted), passWeight, carryWeight, creationWeight,
                    Math.max(.1, stat.minutes * (.45 + receptionQuality))));
        }

        double passWeightTotal = drafts.stream().mapToDouble(PlayerDraft::passWeight).sum();
        double carryWeightTotal = drafts.stream().mapToDouble(PlayerDraft::carryWeight).sum();
        double creationWeightTotal = drafts.stream().mapToDouble(PlayerDraft::creationWeight).sum();
        double receptionWeightTotal = drafts.stream().mapToDouble(PlayerDraft::receptionWeight).sum();
        List<PlayerContribution> result = new ArrayList<>();
        for (PlayerDraft draft : drafts) {
            double progressivePasses = allocate(totals.progressivePasses(), draft.passWeight, passWeightTotal);
            double progressiveCarries = allocate(totals.progressiveCarries(), draft.carryWeight, carryWeightTotal);
            double lineBreaks = allocate(totals.lineBreakingPasses(), draft.creationWeight, creationWeightTotal);
            double passesIntoBox = allocate(totals.passesIntoBox(), draft.creationWeight, creationWeightTotal);
            double receptions = allocate(totals.receptionsBetweenLines(), draft.receptionWeight, receptionWeightTotal);
            double switches = allocate(totals.switchesOfPlay(), draft.passWeight, passWeightTotal);
            double buildUps = allocate(totals.buildUpAttacks(), draft.passWeight + draft.receptionWeight * .02,
                    passWeightTotal + receptionWeightTotal * .02);
            double progressionActions = progressivePasses + progressiveCarries;
            result.add(new PlayerContribution(draft.playerId, draft.name, draft.position, draft.appearances,
                    draft.minutes, draft.passesCompleted, progressivePasses, progressiveCarries, lineBreaks,
                    passesIntoBox, receptions, switches, buildUps,
                    percentage(progressionActions, totals.progressivePasses() + totals.progressiveCarries())));
        }
        result.sort(Comparator.comparingDouble(PlayerContribution::progressionSharePercentage).reversed()
                .thenComparing(PlayerContribution::name));
        return result;
    }

    private Map<Long, Human> indexHumans(Iterable<Human> values) {
        Map<Long, Human> result = new HashMap<>();
        for (Human human : values) result.put(human.getId(), human);
        return result;
    }

    private double quality(PlayerSkills skill, String type) {
        if (skill == null) return .5;
        double score = switch (type) {
            case "CARRY" -> skill.getDribbling() * .40 + skill.getTechnique() * .25
                    + skill.getPace() * .20 + skill.getAcceleration() * .15;
            case "RECEIVE" -> skill.getOffTheBall() * .40 + skill.getFirstTouch() * .35
                    + skill.getDecisions() * .25;
            default -> skill.getPassing() * .38 + skill.getVision() * .30
                    + skill.getDecisions() * .18 + skill.getTechnique() * .14;
        };
        return Math.max(.05, score / 20.0);
    }

    private double allocate(int total, double weight, double weightTotal) {
        return round(weightTotal <= 0 ? 0 : total * weight / weightTotal);
    }
    private double percentage(double numerator, double denominator) {
        return round(denominator <= 0 ? 0 : numerator * 100.0 / denominator);
    }
    private double perMatch(int total, int matches) { return round(matches == 0 ? 0 : total / (double) matches); }
    private double round(double value) {
        if (Math.abs(value) < .005) return 0;
        return Math.round(value * 100.0) / 100.0;
    }

    private int sum(List<PossessionProgression> rows, Metric metric) {
        return rows.stream().mapToInt(row -> switch (metric) {
            case POSSESSIONS -> row.getPossessions(); case COMPLETED_PASSES -> row.getCompletedPasses();
            case PROGRESSIVE_PASSES -> row.getProgressivePasses(); case PROGRESSIVE_CARRIES -> row.getProgressiveCarries();
            case FINAL_THIRD -> row.getFinalThirdEntries(); case PENALTY_AREA -> row.getPenaltyAreaEntries();
            case LINE_BREAKING -> row.getLineBreakingPasses(); case PASSES_INTO_BOX -> row.getPassesIntoBox();
            case RECEPTIONS -> row.getReceptionsBetweenLines(); case SWITCHES -> row.getSwitchesOfPlay();
            case TEN_PASS -> row.getTenPassSequences(); case BUILD_UP -> row.getBuildUpAttacks();
            case DIRECT -> row.getDirectAttacks();
        }).sum();
    }

    private enum Metric { POSSESSIONS, COMPLETED_PASSES, PROGRESSIVE_PASSES, PROGRESSIVE_CARRIES,
        FINAL_THIRD, PENALTY_AREA, LINE_BREAKING, PASSES_INTO_BOX, RECEPTIONS, SWITCHES,
        TEN_PASS, BUILD_UP, DIRECT }

    private static final class PlayerAggregate {
        int appearances;
        int minutes;
        double passesCompleted;
        double chancesCreated;
        double dribblesCompleted;
        void add(PlayerSeasonStat stat) {
            appearances += stat.getAppearances(); minutes += stat.getMinutes();
            passesCompleted += stat.getPassesCompleted(); chancesCreated += stat.getChancesCreated();
            dribblesCompleted += stat.getDribblesCompleted();
        }
    }
    private record PlayerDraft(long playerId, String name, String position, int appearances, int minutes,
                               double passesCompleted, double passWeight, double carryWeight,
                               double creationWeight, double receptionWeight) {}

    public record Totals(int possessions, int completedPasses, int progressivePasses, int progressiveCarries,
                         int finalThirdEntries, int penaltyAreaEntries, int lineBreakingPasses,
                         int passesIntoBox, int receptionsBetweenLines, int switchesOfPlay,
                         int tenPassSequences, int buildUpAttacks, int directAttacks, int shots) {}
    public record Averages(double fieldTiltPercentage, double passesPerPossession,
                           double averagePossessionDurationSeconds, double directSpeedMetersPerSecond,
                           double progressivePassesPerMatch, double progressiveCarriesPerMatch,
                           double progressionToFinalThirdPercentage, double finalThirdToBoxPercentage,
                           double boxToShotPercentage) {}
    public record ProgressionFunnel(int completedPasses, int progressivePasses, int finalThirdEntries,
                                    int penaltyAreaEntries, int shots) {}
    public record PlayerContribution(long playerId, String name, String position, int appearances, int minutes,
                                     double passesCompleted, double progressivePasses, double progressiveCarries,
                                     double lineBreakingPasses, double passesIntoBox,
                                     double receptionsBetweenLines, double switchesOfPlay,
                                     double buildUpInvolvements, double progressionSharePercentage) {}
    public record DataQuality(String teamMetrics, int modeledMatches, String playerContributions) {}
    public record PossessionProgressionAnalytics(long teamId, int seasonNumber, int matches, Totals totals,
                                                 Averages averages, ProgressionFunnel funnel,
                                                 List<PlayerContribution> playerContributions,
                                                 DataQuality dataQuality, String methodology) {}
}
