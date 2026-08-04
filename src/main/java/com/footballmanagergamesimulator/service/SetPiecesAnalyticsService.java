package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Team Set Pieces Hub with attacking/defending splits and a primary-competition benchmark. */
@Service
public class SetPiecesAnalyticsService {
    private final MatchStatsRepository matches; private final SetPieceEventService events;
    private final CompetitionRepository competitions; private final HumanRepository humans;
    private final PlayerSkillsRepository skills; private final PersonalizedTacticRepository tactics;

    public SetPiecesAnalyticsService(MatchStatsRepository matches, SetPieceEventService events,
                                     CompetitionRepository competitions, HumanRepository humans,
                                     PlayerSkillsRepository skills, PersonalizedTacticRepository tactics) {
        this.matches = matches; this.events = events; this.competitions = competitions;
        this.humans = humans; this.skills = skills; this.tactics = tactics;
    }

    public SetPiecesAnalytics analytics(long teamId, int season) {
        List<MatchStats> teamMatches = teamMatches(teamId, season);
        List<SetPieceEvent> attacking = new ArrayList<>(), defending = new ArrayList<>(); double totalXga = 0;
        for (MatchStats match : teamMatches) {
            boolean home = match.getTeam1Id() == teamId; totalXga += (home ? match.getAwayXg() : match.getHomeXg()) / 100.0;
            for (SetPieceEvent event : events.eventsForMatch(match)) {
                if (event.getTeamId() == teamId) attacking.add(event);
                if (event.getOpponentTeamId() == teamId) defending.add(event);
            }
        }
        Summary summary = summary(attacking, defending, totalXga);
        long primaryCompetition = primaryCompetition(teamMatches);
        Competition competition = primaryCompetition == 0 ? null : competitions.findById(primaryCompetition).orElse(null);
        Benchmark benchmark = benchmark(primaryCompetition, season, summary, teamMatches.size());
        List<Breakdown> delivery = breakdown(attacking, SetPieceEvent::getDeliveryStyle, "CORNER");
        List<Breakdown> zonesFor = breakdown(attacking, SetPieceEvent::getDeliveryZone, "CORNER");
        List<Breakdown> zonesAgainst = breakdown(defending, SetPieceEvent::getDeliveryZone, "CORNER");
        List<Breakdown> firstContact = breakdown(attacking, SetPieceEvent::getFirstContact, "CORNER");
        List<Breakdown> secondBalls = breakdown(attacking, SetPieceEvent::getSecondBallRecovery, "CORNER");
        List<SetPieceType> typesFor = types(attacking); List<SetPieceType> typesAgainst = types(defending);
        List<DangerousPlayer> dangerous = dangerousPlayers(teamId, summary.cornerXgFor());
        List<Vulnerability> vulnerabilities = vulnerabilities(defending, totalXga);
        List<Insight> insights = insights(summary, benchmark, zonesAgainst, vulnerabilities);
        return new SetPiecesAnalytics(teamId, season, teamMatches.size(), primaryCompetition,
                competition == null ? "No competition" : competition.getName(), summary, delivery, zonesFor, zonesAgainst,
                firstContact, secondBalls, typesFor, typesAgainst, dangerous, vulnerabilities, benchmark, insights,
                new DataQuality("OBSERVED_COUNTS", SetPieceEventService.QUALITY,
                        "Delivery, contact, second-ball and marking diagnostics are modeled; xG reconciles to ShotEvent."));
    }

    private List<MatchStats> teamMatches(long teamId, int season) {
        List<MatchStats> rows = new ArrayList<>(); rows.addAll(matches.findAllByTeam1IdAndSeasonNumber(teamId, season));
        rows.addAll(matches.findAllByTeam2IdAndSeasonNumber(teamId, season));
        rows.sort(Comparator.comparingInt(MatchStats::getRoundNumber).thenComparingLong(MatchStats::getId)); return rows;
    }
    private Summary summary(List<SetPieceEvent> forEvents, List<SetPieceEvent> against, double totalXga) {
        List<SetPieceEvent> cornersFor = type(forEvents, "CORNER"), cornersAgainst = type(against, "CORNER");
        double cornerXgFor = xg(cornersFor), cornerXgAgainst = xg(cornersAgainst), setPieceXga = xg(against);
        return new Summary(cornersFor.size(), cornersAgainst.size(), round(cornerXgFor), round(cornerXgAgainst),
                rate(cornerXgFor, cornersFor.size()), rate(cornerXgAgainst, cornersAgainst.size()),
                round(xg(forEvents)), round(setPieceXga), percentage(setPieceXga, totalXga),
                countOutcome(cornersFor, "SHOT"), countOutcome(cornersFor, "GOAL"),
                countOutcome(cornersAgainst, "SHOT"), countOutcome(cornersAgainst, "GOAL"),
                percentage(countValue(cornersFor, SetPieceEvent::getSecondBallRecovery, "ATTACKING"), cornersFor.size()));
    }
    private long primaryCompetition(List<MatchStats> rows) { return rows.stream().collect(Collectors.groupingBy(
            MatchStats::getCompetitionId, Collectors.counting())).entrySet().stream()
            .max(Map.Entry.<Long, Long>comparingByValue().thenComparing(Map.Entry::getKey)).map(Map.Entry::getKey).orElse(0L); }
    private Benchmark benchmark(long competitionId, int season, Summary team, int teamMatchCount) {
        if (competitionId == 0) return new Benchmark(0, 0, 0, 0, 0, 0);
        Map<Long, TeamBench> byTeam = new HashMap<>();
        for (MatchStats match : matches.findAllByCompetitionIdAndSeasonNumber(competitionId, season)) {
            for (SetPieceEvent event : events.eventsForMatch(match)) {
                TeamBench bench = byTeam.computeIfAbsent(event.getTeamId(), ignored -> new TeamBench());
                bench.matches.add(event.getMatchStatsId());
                if ("CORNER".equals(event.getType())) { bench.corners++; bench.cornerXg += event.getXg() / 10_000.0; }
            }
        }
        double cornersPerMatch = byTeam.values().stream().mapToDouble(b -> b.corners / (double) Math.max(1, b.matches.size())).average().orElse(0);
        double xgPerCorner = byTeam.values().stream().mapToDouble(b -> rateRaw(b.cornerXg, b.corners)).average().orElse(0);
        return new Benchmark(competitionId, byTeam.size(), round(cornersPerMatch), round(xgPerCorner),
                round(team.cornerXgPerCornerFor() - xgPerCorner),
                round(team.cornersFor() / (double) Math.max(1, teamMatchCount) - cornersPerMatch));
    }
    private List<Breakdown> breakdown(List<SetPieceEvent> rows, Function<SetPieceEvent, String> classifier, String type) {
        return rows.stream().filter(e -> type.equals(e.getType())).collect(Collectors.groupingBy(classifier))
                .entrySet().stream().map(e -> new Breakdown(e.getKey(), e.getValue().size(), round(xg(e.getValue())),
                        countOutcome(e.getValue(), "SHOT"), countOutcome(e.getValue(), "GOAL"), percentage(e.getValue().size(), type(rows, type).size())))
                .sorted(Comparator.comparingInt(Breakdown::events).reversed()).toList();
    }
    private List<SetPieceType> types(List<SetPieceEvent> rows) {
        return rows.stream().collect(Collectors.groupingBy(SetPieceEvent::getType)).entrySet().stream()
                .map(e -> new SetPieceType(e.getKey(), e.getValue().size(), round(xg(e.getValue())),
                        countOutcome(e.getValue(), "SHOT"), countOutcome(e.getValue(), "GOAL")))
                .sorted(Comparator.comparingInt(SetPieceType::events).reversed()).toList();
    }
    private List<DangerousPlayer> dangerousPlayers(long teamId, double cornerXg) {
        List<Human> squad = humans.findAllByTeamIdAndTypeId(teamId, 1L);
        Map<Long, PlayerSkills> byId = skills.findAllByPlayerIdIn(squad.stream().map(Human::getId).toList()).stream()
                .collect(Collectors.toMap(PlayerSkills::getPlayerId, Function.identity(), (a,b) -> a));
        PersonalizedTactic tactic = tactics.findPersonalizedTacticByTeamId(teamId).orElse(null);
        return squad.stream().map(player -> {
            PlayerSkills s = byId.get(player.getId());
            double aerial = s == null ? 10 : s.getHeading() * .30 + s.getJumpingReach() * .25 + s.getAnticipation() * .15 + s.getOffTheBall() * .15 + s.getFinishing() * .15;
            boolean taker = tactic != null && (Objects.equals(tactic.getCornerTakerLeftId(), player.getId()) || Objects.equals(tactic.getCornerTakerRightId(), player.getId()));
            double delivery = s == null ? 10 : s.getCorners() * .5 + s.getCrossing() * .3 + s.getTechnique() * .2;
            double score = Math.max(aerial, taker ? delivery + 2 : delivery * .65);
            return new DangerousPlayer(player.getId(), player.getName(), player.getPosition(), taker ? "CORNER_TAKER" : "AERIAL_TARGET", round(score), 0);
        }).sorted(Comparator.comparingDouble(DangerousPlayer::dangerScore).reversed()).limit(8).map(p ->
                new DangerousPlayer(p.playerId(), p.name(), p.position(), p.role(), p.dangerScore(), round(cornerXg * p.dangerScore() / 120.0))).toList();
    }
    private List<Vulnerability> vulnerabilities(List<SetPieceEvent> against, double totalXga) {
        List<SetPieceEvent> corners = type(against, "CORNER");
        return corners.stream().collect(Collectors.groupingBy(e -> e.getDeliveryZone() + ":" + ("ATTACKER".equals(e.getFirstContact()) ? "INDIVIDUAL" : "ZONAL")))
                .entrySet().stream().map(e -> { String[] key = e.getKey().split(":"); double value = xg(e.getValue());
                    return new Vulnerability(key[1], key[0], e.getValue().size(), round(value), percentage(value, totalXga)); })
                .sorted(Comparator.comparingDouble(Vulnerability::xga).reversed()).limit(5).toList();
    }
    private List<Insight> insights(Summary s, Benchmark b, List<Breakdown> zones, List<Vulnerability> vulnerabilities) {
        List<Insight> out = new ArrayList<>();
        vulnerabilities.stream().filter(v -> "FAR_POST".equals(v.zone()) && v.shareOfTotalXgaPercentage() > 0)
                .findFirst().ifPresent(v -> out.add(new Insight("RISK", "Far-post corner vulnerability",
                        round(v.shareOfTotalXgaPercentage()) + "% of total xGA comes from corners delivered to the far post.", "Review far-post responsibility and weak-side tracking.")));
        if (s.cornerXgPerCornerFor() > b.leagueXgPerCorner() + .01) out.add(new Insight("STRENGTH", "Corners beat the benchmark", "The team creates " + s.cornerXgPerCornerFor() + " xG/corner versus " + b.leagueXgPerCorner() + " in the competition.", "Protect the current delivery and target pairing."));
        if (s.secondBallRecoveryPercentage() < 40) out.add(new Insight("OPPORTUNITY", "Second balls are being lost", "Only " + s.secondBallRecoveryPercentage() + "% of attacking corner second balls are recovered.", "Hold one extra player at the edge of the box."));
        if (out.isEmpty()) out.add(new Insight("INFO", "Set-piece profile is balanced", "No major deviation is visible yet.", "Reassess after a larger sample.")); return out;
    }
    private List<SetPieceEvent> type(List<SetPieceEvent> rows, String type) { return rows.stream().filter(e -> type.equals(e.getType())).toList(); }
    private double xg(List<SetPieceEvent> rows) { return rows.stream().mapToInt(SetPieceEvent::getXg).sum() / 10_000.0; }
    private int countOutcome(List<SetPieceEvent> rows, String o) { return (int) rows.stream().filter(e -> o.equals(e.getOutcome()) || ("SHOT".equals(o) && "GOAL".equals(e.getOutcome()))).count(); }
    private int countValue(List<SetPieceEvent> rows, Function<SetPieceEvent,String> f, String v) { return (int) rows.stream().filter(e -> v.equals(f.apply(e))).count(); }
    private double rate(double n, int d) { return round(rateRaw(n,d)); } private double rateRaw(double n, int d) { return d == 0 ? 0 : n/d; }
    private double percentage(double n, double d) { return round(d == 0 ? 0 : n*100/d); }
    private double round(double v) { return Math.abs(v) < .005 ? 0 : Math.round(v*100.0)/100.0; }
    private static final class TeamBench { Set<Long> matches = new HashSet<>(); int corners; double cornerXg; }

    public record Summary(int cornersFor, int cornersAgainst, double cornerXgFor, double cornerXgAgainst,
                          double cornerXgPerCornerFor, double cornerXgPerCornerAgainst, double setPieceXgFor,
                          double setPieceXgAgainst, double setPieceShareOfTotalXgaPercentage, int cornerShotsFor,
                          int cornerGoalsFor, int cornerShotsAgainst, int cornerGoalsAgainst, double secondBallRecoveryPercentage) {}
    public record Breakdown(String key, int events, double xg, int shots, int goals, double sharePercentage) {}
    public record SetPieceType(String type, int events, double xg, int shots, int goals) {}
    public record DangerousPlayer(long playerId, String name, String position, String role, double dangerScore, double estimatedXgContribution) {}
    public record Vulnerability(String markingScheme, String zone, int incidents, double xga, double shareOfTotalXgaPercentage) {}
    public record Benchmark(long competitionId, int teams, double leagueCornersPerMatch, double leagueXgPerCorner,
                            double xgPerCornerDifference, double cornerVolumeDifference) {}
    public record Insight(String type, String title, String evidence, String recommendation) {}
    public record DataQuality(String counts, String deliveryDetails, String note) {}
    public record SetPiecesAnalytics(long teamId, int seasonNumber, int matches, long benchmarkCompetitionId,
                                     String benchmarkCompetitionName, Summary summary, List<Breakdown> cornerDeliveryStyles,
                                     List<Breakdown> cornerZonesFor, List<Breakdown> cornerZonesAgainst,
                                     List<Breakdown> firstContact, List<Breakdown> secondBallRecovery,
                                     List<SetPieceType> typesFor, List<SetPieceType> typesAgainst,
                                     List<DangerousPlayer> dangerousPlayers, List<Vulnerability> vulnerabilities,
                                     Benchmark benchmark, List<Insight> insights, DataQuality dataQuality) {}
}
