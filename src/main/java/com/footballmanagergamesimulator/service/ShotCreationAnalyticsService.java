package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.ShotEvent;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ShotCreationAnalyticsService {

    private final MatchStatsRepository matchStatsRepository;
    private final ShotEventService shotEventService;

    public ShotCreationAnalyticsService(MatchStatsRepository matchStatsRepository, ShotEventService shotEventService) {
        this.matchStatsRepository = matchStatsRepository;
        this.shotEventService = shotEventService;
    }

    public ShotCreationAnalytics analytics(long teamId, int seasonNumber) {
        List<MatchStats> matches = new ArrayList<>();
        matches.addAll(matchStatsRepository.findAllByTeam1IdAndSeasonNumber(teamId, seasonNumber));
        matches.addAll(matchStatsRepository.findAllByTeam2IdAndSeasonNumber(teamId, seasonNumber));
        matches.sort(Comparator.comparingInt(MatchStats::getRoundNumber).thenComparingLong(MatchStats::getId));

        List<ShotEvent> shots = new ArrayList<>();
        List<ShotEvent> faced = new ArrayList<>();
        for (MatchStats match : matches) {
            for (ShotEvent event : shotEventService.eventsForMatch(match)) {
                if (event.getTeamId() == teamId) shots.add(event);
                else if (event.getOpponentTeamId() == teamId) faced.add(event);
            }
        }

        double totalXg = sumXg(shots);
        double totalXgot = sumXgot(shots);
        double onTargetXg = shots.stream().filter(ShotEvent::isOnTarget).mapToDouble(this::xg).sum();
        int goals = countOutcome(shots, "GOAL");
        int inside = (int) shots.stream().filter(ShotEvent::isInsideBox).count();
        int bigChances = (int) shots.stream().filter(ShotEvent::isBigChance).count();
        int pressureShots = (int) shots.stream().filter(ShotEvent::isUnderPressure).count();
        int modeled = (int) shots.stream().filter(shot -> "MODELED".equals(shot.getDataQuality())).count();
        int observed = shots.size() - modeled;

        int accurateCrosses = matches.stream().mapToInt(match -> teamId == match.getTeam1Id()
                ? match.getHomeCrossesAccurate() : match.getAwayCrossesAccurate()).sum();
        int passes = matches.stream().mapToInt(match -> teamId == match.getTeam1Id()
                ? match.getHomePasses() : match.getAwayPasses()).sum();
        int corners = matches.stream().mapToInt(match -> teamId == match.getTeam1Id()
                ? match.getHomeCorners() : match.getAwayCorners()).sum();
        Funnel funnel = funnel(passes, accurateCrosses, corners, inside, shots.size(), goals);
        int touchesInBox = Math.max(inside, (int) Math.round(funnel.boxEntries() * 2.45 + inside * .55));

        double xgotFaced = sumXgot(faced);
        int goalsConceded = countOutcome(faced, "GOAL");
        Execution execution = new Execution(
                round(totalXgot), round(onTargetXg), round(totalXgot - onTargetXg),
                round(xgotFaced), goalsConceded, round(xgotFaced - goalsConceded));

        List<ShotPoint> map = shots.stream().map(this::point).toList();
        return new ShotCreationAnalytics(
                teamId, seasonNumber, matches.size(), shots.size(), goals,
                round(totalXg), round(shots.isEmpty() ? 0 : totalXg / shots.size()),
                inside, shots.size() - inside, bigChances,
                round(shots.stream().mapToDouble(ShotEvent::getDistanceMeters).average().orElse(0)),
                round(shots.stream().mapToDouble(ShotEvent::getAngleDegrees).average().orElse(0)),
                pressureShots, touchesInBox,
                breakdown(shots, ShotEvent::getSituation),
                breakdown(shots, ShotEvent::getCreationType),
                breakdown(shots, ShotEvent::getChannel),
                sequences(shots), funnel, execution, map,
                new DataQuality(observed, modeled,
                        modeled > 0 ? "MODELED_FROM_MATCH_STATS" : "OBSERVED_SHOT_EVENTS"),
                "xG is pre-shot chance quality. xGOT applies only to on-target shots and uses goalmouth placement; "
                        + "xGOT-xG measures execution. Funnel steps before shots are transparent match-stat estimates.");
    }

    private Funnel funnel(int passes, int accurateCrosses, int corners, int insideShots, int shots, int goals) {
        int possessions = Math.max(shots, (int) Math.round(passes / 4.35));
        int finalThird = Math.min(possessions, Math.max(shots,
                (int) Math.round(shots * 2.5 + accurateCrosses * .7 + corners * .45)));
        int boxEntries = Math.min(finalThird, Math.max(shots,
                (int) Math.round(insideShots * 1.9 + accurateCrosses * .6 + corners * .3)));
        return new Funnel(possessions, finalThird, boxEntries, shots, goals, "MODELED_BEFORE_SHOT");
    }

    private List<Breakdown> breakdown(List<ShotEvent> shots, Function<ShotEvent, String> classifier) {
        Map<String, List<ShotEvent>> groups = shots.stream().collect(Collectors.groupingBy(
                shot -> classifier.apply(shot) == null ? "UNKNOWN" : classifier.apply(shot),
                LinkedHashMap::new, Collectors.toList()));
        return groups.entrySet().stream().map(entry -> new Breakdown(
                        entry.getKey(), entry.getValue().size(), countOutcome(entry.getValue(), "GOAL"),
                        round(sumXg(entry.getValue())), round(sumXgot(entry.getValue()))))
                .sorted(Comparator.comparingInt(Breakdown::shots).reversed()).toList();
    }

    private List<Sequence> sequences(List<ShotEvent> shots) {
        Map<String, List<ShotEvent>> groups = shots.stream().collect(Collectors.groupingBy(
                shot -> shot.getSequenceLabel() == null ? "OTHER" : shot.getSequenceLabel(),
                LinkedHashMap::new, Collectors.toList()));
        return groups.entrySet().stream().map(entry -> new Sequence(entry.getKey(), entry.getValue().size(),
                        countOutcome(entry.getValue(), "GOAL"), round(sumXg(entry.getValue())),
                        entry.getValue().stream().map(ShotEvent::getCreationType).filter(value -> value != null)
                                .findFirst().orElse("OTHER")))
                .sorted(Comparator.comparingInt(Sequence::shots).reversed().thenComparing(Sequence::label))
                .limit(6).toList();
    }

    private ShotPoint point(ShotEvent shot) {
        return new ShotPoint(shot.getMatchStatsId(), shot.getShotIndex(), shot.getRoundNumber(), shot.getMinute(),
                shot.getOriginX(), shot.getOriginY(), round(xg(shot)), round(xgot(shot)), shot.getOutcome(),
                shot.getSituation(), shot.getCreationType(), shot.getChannel(), shot.isBigChance(),
                shot.isUnderPressure(), shot.isInsideBox(), shot.isOnTarget(), shot.getDataQuality());
    }

    private int countOutcome(List<ShotEvent> shots, String outcome) {
        return (int) shots.stream().filter(shot -> outcome.equals(shot.getOutcome())).count();
    }
    private double sumXg(List<ShotEvent> shots) { return shots.stream().mapToDouble(this::xg).sum(); }
    private double sumXgot(List<ShotEvent> shots) { return shots.stream().mapToDouble(this::xgot).sum(); }
    private double xg(ShotEvent shot) { return shot.getXg() / 10_000.0; }
    private double xgot(ShotEvent shot) { return shot.getXgot() / 10_000.0; }
    private double round(double value) {
        if (Math.abs(value) < .005) return 0;
        return Math.round(value * 100.0) / 100.0;
    }

    public record Breakdown(String key, int shots, int goals, double xg, double xgot) {}
    public record Sequence(String label, int shots, int goals, double xg, String creationType) {}
    public record Funnel(int possessions, int finalThirdEntries, int boxEntries, int shots, int goals, String dataQuality) {}
    public record Execution(double xgot, double onTargetXg, double shootingGoalsAdded,
                            double xgotFaced, int goalsConceded, double goalsPrevented) {}
    public record DataQuality(int observedShots, int modeledShots, String status) {}
    public record ShotPoint(long matchStatsId, int shotIndex, int round, int minute,
                            double x, double y, double xg, double xgot, String outcome,
                            String situation, String creationType, String channel,
                            boolean bigChance, boolean underPressure, boolean insideBox,
                            boolean onTarget, String dataQuality) {}
    public record ShotCreationAnalytics(long teamId, int seasonNumber, int matches, int shots, int goals,
                                       double xg, double xgPerShot, int shotsInsideBox, int shotsOutsideBox,
                                       int bigChances, double averageShotDistanceMeters,
                                       double averageShotAngleDegrees, int shotsUnderPressure,
                                       int touchesInOppositionBox, List<Breakdown> xgBySituation,
                                       List<Breakdown> shotsByCreationType, List<Breakdown> chancesByChannel,
                                       List<Sequence> topShotSequences, Funnel funnel, Execution execution,
                                       List<ShotPoint> shotMap, DataQuality dataQuality, String methodology) {}
}
