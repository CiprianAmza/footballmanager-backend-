package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.DefensivePressure;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.PossessionProgression;
import com.footballmanagergamesimulator.model.ShotEvent;
import com.footballmanagergamesimulator.repository.DefensivePressureRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Deterministic fallback until zoned, timestamped defensive events are persisted by the match engine. */
@Service
public class DefensivePressureLedgerService {
    public static final String QUALITY = "MODELED_FROM_MATCH_STATS";
    private final DefensivePressureRepository repository;
    private final PossessionProgressionLedgerService progressionService;
    private final ShotEventService shotEventService;

    public DefensivePressureLedgerService(DefensivePressureRepository repository,
                                          PossessionProgressionLedgerService progressionService,
                                          ShotEventService shotEventService) {
        this.repository = repository;
        this.progressionService = progressionService;
        this.shotEventService = shotEventService;
    }

    @Transactional
    public void replaceForMatch(MatchStats stats) {
        if (stats == null || stats.getId() <= 0) return;
        repository.deleteAllByMatchStatsId(stats.getId());
        repository.saveAll(generate(stats));
    }

    public List<DefensivePressure> rowsForMatch(MatchStats stats) {
        List<DefensivePressure> stored = repository.findAllByMatchStatsIdOrderByTeamIdAsc(stats.getId());
        return stored == null || stored.size() != 2 ? generate(stats) : stored;
    }

    public List<DefensivePressure> generate(MatchStats stats) {
        List<PossessionProgression> progression = progressionService.progressionsForMatch(stats);
        List<ShotEvent> shots = shotEventService.eventsForMatch(stats);
        return List.of(team(stats, true, progression, shots), team(stats, false, progression, shots));
    }

    private DefensivePressure team(MatchStats stats, boolean home, List<PossessionProgression> progression,
                                   List<ShotEvent> shots) {
        long teamId = home ? stats.getTeam1Id() : stats.getTeam2Id();
        long opponentId = home ? stats.getTeam2Id() : stats.getTeam1Id();
        int tackles = nn(home ? stats.getHomeTackles() : stats.getAwayTackles());
        int interceptions = nn(home ? stats.getHomeInterceptions() : stats.getAwayInterceptions());
        int fouls = nn(home ? stats.getHomeFouls() : stats.getAwayFouls());
        int clearances = nn(home ? stats.getHomeClearances() : stats.getAwayClearances());
        int duelsWon = nn(home ? stats.getHomeDuelsWon() : stats.getAwayDuelsWon());
        int opponentDuelsWon = nn(home ? stats.getAwayDuelsWon() : stats.getHomeDuelsWon());
        int opponentPasses = nn(home ? stats.getAwayPasses() : stats.getHomePasses());
        int opponentAccuracy = clamp(home ? stats.getAwayPassAccuracy() : stats.getHomePassAccuracy(), 0, 100);
        int possession = clamp(home ? stats.getHomePossession() : stats.getAwayPossession(), 0, 100);
        int blocks = nn(home ? stats.getAwayShotsBlocked() : stats.getHomeShotsBlocked());
        int pressures = Math.max(tackles + interceptions, (int) Math.round(tackles * 1.75 + interceptions * 1.45 + fouls * .35));
        int successful = Math.min(pressures, tackles + interceptions + (int) Math.round(fouls * .12));
        int counterpressures = Math.min(pressures, (int) Math.round(pressures * (.27 + possession / 300.0)));
        int regain5 = Math.min(counterpressures, (int) Math.round(counterpressures * (.30 + successful / (double) Math.max(1, pressures) * .22)));
        int regain8 = Math.min(counterpressures, Math.max(regain5, (int) Math.round(counterpressures * .68)));
        int highTurnovers = Math.max(regain8, (int) Math.round(interceptions * .42 + tackles * .28));
        int highToShot = (int) shots.stream().filter(s -> s.getTeamId() == teamId && "RECOVERY".equals(s.getCreationType())).count();
        int highToGoal = (int) shots.stream().filter(s -> s.getTeamId() == teamId && "RECOVERY".equals(s.getCreationType()) && "GOAL".equals(s.getOutcome())).count();
        int transitionsAllowed = (int) shots.stream().filter(s -> s.getTeamId() == opponentId && "RECOVERY".equals(s.getCreationType())).count();
        int forced = Math.max(highTurnovers, successful + (int) Math.round(clearances * .18));
        double recoveryTime = clamp(14.8 - regain5 * .12 - regain8 * .05 + (opponentAccuracy - 80) * .08, 4.0, 22.0);
        int allowedBox = progression.stream().filter(row -> row.getTeamId() == opponentId)
                .mapToInt(PossessionProgression::getPenaltyAreaEntries).findFirst().orElse(0);
        int defensiveActionsHigh = Math.min(tackles + interceptions + fouls,
                (int) Math.round((tackles + interceptions + fouls) * (.24 + pressures / 250.0)));
        int recoveries = tackles + interceptions;
        int left = (int) Math.round(recoveries * (.30 + ((stats.getId() + teamId) % 5) / 100.0));
        int centre = (int) Math.round(recoveries * .40);
        int right = Math.max(0, recoveries - left - centre);
        int errorsShot = Math.min(transitionsAllowed, Math.max(0, (int) Math.round(transitionsAllowed * .24)));
        int errorsGoal = Math.min(errorsShot, (int) shots.stream().filter(s -> s.getTeamId() == opponentId
                && "RECOVERY".equals(s.getCreationType()) && "GOAL".equals(s.getOutcome())).count());
        int actions = Math.max(1, tackles + interceptions + fouls);

        DefensivePressure row = new DefensivePressure();
        row.setMatchStatsId(stats.getId()); row.setCompetitionId(stats.getCompetitionId());
        row.setSeasonNumber(stats.getSeasonNumber()); row.setRoundNumber(stats.getRoundNumber());
        row.setTeamId(teamId); row.setOpponentTeamId(opponentId);
        row.setPpdaProxy(round(opponentPasses / (double) actions));
        row.setPressures(pressures); row.setSuccessfulPressures(successful); row.setCounterpressures(counterpressures);
        row.setRegainsWithinFiveSeconds(regain5); row.setRegainsWithinEightSeconds(regain8);
        row.setBallRecoveryTimeSeconds(round(recoveryTime)); row.setHighTurnovers(highTurnovers);
        row.setHighTurnoversToShot(highToShot); row.setHighTurnoversToGoal(highToGoal); row.setForcedTurnovers(forced);
        row.setRecoveriesLeft(left); row.setRecoveriesCentre(centre); row.setRecoveriesRight(right);
        row.setDefensiveActionsOppositionHalf(defensiveActionsHigh); row.setAllowedBoxEntries(allowedBox);
        row.setTransitionShotsAllowed(transitionsAllowed); row.setErrorsLeadingToShot(errorsShot); row.setErrorsLeadingToGoal(errorsGoal);
        row.setDuels(duelsWon + opponentDuelsWon); row.setDuelsWon(duelsWon); row.setClearances(clearances);
        row.setBlocks(blocks); row.setInterceptions(interceptions);
        row.setDefensiveLineHeightMeters(round(clamp(38 + possession * .18 + pressures * .08, 35, 58)));
        row.setDataQuality(QUALITY);
        return row;
    }

    private int nn(int value) { return Math.max(0, value); }
    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private double round(double value) { return Math.round(value * 100.0) / 100.0; }
}
