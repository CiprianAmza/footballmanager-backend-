package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.DefensivePressure;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.PlayerSeasonStat;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.PlayerSeasonStatRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/** Season-level pressing and defending report with per-metric provenance. */
@Service
public class PressingDefenceAnalyticsService {
    private final MatchStatsRepository matches;
    private final DefensivePressureLedgerService ledger;
    private final PlayerSeasonStatRepository playerStats;
    private final HumanRepository humans;

    public PressingDefenceAnalyticsService(MatchStatsRepository matches, DefensivePressureLedgerService ledger,
                                           PlayerSeasonStatRepository playerStats, HumanRepository humans) {
        this.matches = matches; this.ledger = ledger; this.playerStats = playerStats; this.humans = humans;
    }

    public PressingDefenceAnalytics analytics(long teamId, int season) {
        List<MatchStats> matchRows = new ArrayList<>();
        matchRows.addAll(matches.findAllByTeam1IdAndSeasonNumber(teamId, season));
        matchRows.addAll(matches.findAllByTeam2IdAndSeasonNumber(teamId, season));
        matchRows.sort(Comparator.comparingInt(MatchStats::getRoundNumber).thenComparingLong(MatchStats::getId));
        List<DefensivePressure> rows = new ArrayList<>();
        double xga = 0; int shotsAllowed = 0;
        for (MatchStats match : matchRows) {
            boolean home = match.getTeam1Id() == teamId;
            xga += (home ? match.getAwayXg() : match.getHomeXg()) / 100.0;
            shotsAllowed += home ? match.getAwayShots() : match.getHomeShots();
            ledger.rowsForMatch(match).stream().filter(row -> row.getTeamId() == teamId).findFirst().ifPresent(rows::add);
        }
        Totals totals = totals(rows);
        Averages averages = new Averages(
                average(rows, DefensivePressure::getPpdaProxy), null, "UNAVAILABLE_REQUIRES_ZONED_EVENTS",
                percentage(totals.successfulPressures, totals.pressures),
                weighted(rows, DefensivePressure::getBallRecoveryTimeSeconds),
                average(rows, DefensivePressure::getDefensiveLineHeightMeters),
                round(shotsAllowed == 0 ? 0 : xga / shotsAllowed),
                percentage(totals.duelsWon, totals.duels),
                percentage(totals.highTurnoversToShot, totals.highTurnovers));
        RecoveryZones zones = new RecoveryZones(totals.recoveriesLeft, totals.recoveriesCentre, totals.recoveriesRight);
        return new PressingDefenceAnalytics(teamId, season, rows.size(), round(xga), shotsAllowed, totals, averages,
                zones, playerContributions(teamId, season), quality(),
                "Real PPDA requires opponent passes and defensive actions restricted to defined pitch zones. "
                        + "It remains unavailable. Pressure, regain, turnover, recovery-zone and line-height values are "
                        + "deterministic models; xGA, shots, clearances, blocks, interceptions and duel wins come from MatchStats.");
    }

    private Totals totals(List<DefensivePressure> rows) {
        return new Totals(sum(rows, D.PRESSURES), sum(rows, D.SUCCESS), sum(rows, D.COUNTER), sum(rows, D.REGAIN5),
                sum(rows, D.REGAIN8), sum(rows, D.HIGH), sum(rows, D.HIGH_SHOT), sum(rows, D.HIGH_GOAL),
                sum(rows, D.FORCED), sum(rows, D.LEFT), sum(rows, D.CENTRE), sum(rows, D.RIGHT),
                sum(rows, D.HIGH_ACTIONS), sum(rows, D.BOX_ALLOWED), sum(rows, D.TRANSITION_SHOTS),
                sum(rows, D.ERROR_SHOT), sum(rows, D.ERROR_GOAL), sum(rows, D.DUELS), sum(rows, D.DUELS_WON),
                sum(rows, D.CLEARANCES), sum(rows, D.BLOCKS), sum(rows, D.INTERCEPTIONS));
    }

    private List<PlayerContribution> playerContributions(long teamId, int season) {
        List<PlayerSeasonStat> stats = playerStats.findAllByTeamIdAndSeasonNumber(teamId, season);
        Map<Long, Human> names = new HashMap<>();
        for (Human human : humans.findAllById(stats.stream().map(PlayerSeasonStat::getPlayerId).toList())) names.put(human.getId(), human);
        Map<Long, PlayerAggregate> grouped = new LinkedHashMap<>();
        stats.forEach(stat -> grouped.computeIfAbsent(stat.getPlayerId(), ignored -> new PlayerAggregate()).add(stat));
        return grouped.entrySet().stream().map(entry -> {
            Human human = names.get(entry.getKey()); PlayerAggregate value = entry.getValue();
            return new PlayerContribution(entry.getKey(), human == null ? "Player " + entry.getKey() : human.getName(),
                    human == null ? "" : human.getPosition(), value.appearances, value.minutes,
                    round(value.pressures), round(value.counterpressures), round(value.defensiveActions), round(value.tackles),
                    percentage(value.tackles, value.pressures));
        }).sorted(Comparator.comparingDouble(PlayerContribution::pressures).reversed()).toList();
    }

    private List<MetricQuality> quality() {
        return List.of(
                new MetricQuality("realPpda", "UNAVAILABLE", "Needs zoned opponent passes and defensive actions"),
                new MetricQuality("ppdaProxy", "DERIVED_PROXY", "Whole-pitch opponent passes / defensive actions"),
                new MetricQuality("pressingAndRecovery", "MODELED", "Deterministic match-stat estimates"),
                new MetricQuality("defensiveLineHeight", "MODELED", "No player tracking is persisted"),
                new MetricQuality("xgaShotsClearancesBlocksInterceptionsDuels", "OBSERVED", "Saved MatchStats"));
    }

    private int sum(List<DefensivePressure> rows, D metric) { return rows.stream().mapToInt(row -> switch (metric) {
        case PRESSURES -> row.getPressures(); case SUCCESS -> row.getSuccessfulPressures(); case COUNTER -> row.getCounterpressures();
        case REGAIN5 -> row.getRegainsWithinFiveSeconds(); case REGAIN8 -> row.getRegainsWithinEightSeconds();
        case HIGH -> row.getHighTurnovers(); case HIGH_SHOT -> row.getHighTurnoversToShot(); case HIGH_GOAL -> row.getHighTurnoversToGoal();
        case FORCED -> row.getForcedTurnovers(); case LEFT -> row.getRecoveriesLeft(); case CENTRE -> row.getRecoveriesCentre();
        case RIGHT -> row.getRecoveriesRight(); case HIGH_ACTIONS -> row.getDefensiveActionsOppositionHalf();
        case BOX_ALLOWED -> row.getAllowedBoxEntries(); case TRANSITION_SHOTS -> row.getTransitionShotsAllowed();
        case ERROR_SHOT -> row.getErrorsLeadingToShot(); case ERROR_GOAL -> row.getErrorsLeadingToGoal();
        case DUELS -> row.getDuels(); case DUELS_WON -> row.getDuelsWon(); case CLEARANCES -> row.getClearances();
        case BLOCKS -> row.getBlocks(); case INTERCEPTIONS -> row.getInterceptions();
    }).sum(); }
    private double average(List<DefensivePressure> rows, java.util.function.ToDoubleFunction<DefensivePressure> f) {
        return round(rows.stream().mapToDouble(f).average().orElse(0));
    }
    private double weighted(List<DefensivePressure> rows, java.util.function.ToDoubleFunction<DefensivePressure> f) {
        int weight = rows.stream().mapToInt(DefensivePressure::getPressures).sum();
        return round(weight == 0 ? 0 : rows.stream().mapToDouble(row -> f.applyAsDouble(row) * row.getPressures()).sum() / weight);
    }
    private double percentage(double n, double d) { return round(d <= 0 ? 0 : n * 100 / d); }
    private double round(double value) { return Math.abs(value) < .005 ? 0 : Math.round(value * 100.0) / 100.0; }
    private enum D { PRESSURES, SUCCESS, COUNTER, REGAIN5, REGAIN8, HIGH, HIGH_SHOT, HIGH_GOAL, FORCED,
        LEFT, CENTRE, RIGHT, HIGH_ACTIONS, BOX_ALLOWED, TRANSITION_SHOTS, ERROR_SHOT, ERROR_GOAL, DUELS,
        DUELS_WON, CLEARANCES, BLOCKS, INTERCEPTIONS }
    private static final class PlayerAggregate { int appearances, minutes; double pressures, counterpressures, defensiveActions, tackles;
        void add(PlayerSeasonStat s) { appearances += s.getAppearances(); minutes += s.getMinutes(); pressures += s.getPressures();
            counterpressures += s.getCounterpressures(); defensiveActions += s.getDefensiveActions(); tackles += s.getTackles(); } }

    public record Totals(int pressures, int successfulPressures, int counterpressures, int regainsWithinFiveSeconds,
                         int regainsWithinEightSeconds, int highTurnovers, int highTurnoversToShot, int highTurnoversToGoal,
                         int forcedTurnovers, int recoveriesLeft, int recoveriesCentre, int recoveriesRight,
                         int defensiveActionsOppositionHalf, int allowedBoxEntries, int transitionShotsAllowed,
                         int errorsLeadingToShot, int errorsLeadingToGoal, int duels, int duelsWon,
                         int clearances, int blocks, int interceptions) {}
    public record Averages(double ppdaProxy, Double realPpda, String realPpdaStatus, double pressureSuccessPercentage,
                           double ballRecoveryTimeSeconds, double defensiveLineHeightMeters, double xgaPerShot,
                           double duelWinPercentage, double highTurnoverToShotPercentage) {}
    public record RecoveryZones(int left, int centre, int right) {}
    public record PlayerContribution(long playerId, String name, String position, int appearances, int minutes,
                                     double pressures, double counterpressures, double defensiveActions,
                                     double pressureRegains, double pressureRegainPercentage) {}
    public record MetricQuality(String metric, String status, String reason) {}
    public record PressingDefenceAnalytics(long teamId, int seasonNumber, int matches, double xga, int shotsAllowed,
                                           Totals totals, Averages averages, RecoveryZones recoveryZones,
                                           List<PlayerContribution> playerContributions, List<MetricQuality> dataQuality,
                                           String methodology) {}
}
