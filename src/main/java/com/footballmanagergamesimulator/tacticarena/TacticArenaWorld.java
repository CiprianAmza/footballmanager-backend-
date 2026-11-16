package com.footballmanagergamesimulator.tacticarena;

import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.NationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads the club list and a club's tactics-page data from the database this process runs
 * on. Used by the base-world builder (child process), which writes the results to disk;
 * the {@code /tactic-arena/clubs} routes serve those files.
 */
@Component
public class TacticArenaWorld {

    @Autowired private TeamRepository teamRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private NationService nationService;
    @Autowired private TacticController tacticController;
    @Autowired private com.footballmanagergamesimulator.service.squad.LeaguePredictionService leaguePrediction;

    public Map<String, Object> clubs() {
        Map<Long, List<Team>> teamsByCompetition = teamRepository.findAll().stream()
                .collect(Collectors.groupingBy(Team::getCompetitionId));
        List<Competition> leagues = competitionRepository.findAll().stream()
                .filter(Competition::isLeague)
                .filter(c -> teamsByCompetition.containsKey(c.getId()))
                .sorted(Comparator.comparingInt(Competition::getTier)
                        .thenComparing((Competition c) -> c.getReputation() == null ? 0 : c.getReputation(), Comparator.reverseOrder())
                        .thenComparing(Competition::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Competition league : leagues) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("competitionId", league.getId());
            row.put("name", league.getName());
            row.put("nationId", league.getNationId());
            row.put("nationName", nationService.infoFor(league.getNationId()).name());
            row.put("tier", league.getTier());
            List<Map<String, Object>> teams = new ArrayList<>();
            teamsByCompetition.get(league.getId()).stream()
                    .sorted(Comparator.comparingInt(Team::getReputation).reversed().thenComparing(Team::getName))
                    .forEach(team -> {
                        Map<String, Object> teamJson = teamRow(team);
                        // The press's predicted finish (the game's media prediction), e.g. 6 of 12.
                        Integer position = null, size = null;
                        try {
                            int[] finish = leaguePrediction.predictedFinish(league.getId(), team.getId());
                            if (finish[0] > 0) {
                                position = finish[0];
                                size = finish[1];
                            }
                        } catch (RuntimeException ignored) {
                            // no prediction for this league
                        }
                        teamJson.put("predictedPosition", position);
                        teamJson.put("leagueSize", size);
                        teams.add(teamJson);
                    });
            row.put("teams", teams);
            rows.add(row);
        }
        return Map.of("leagues", rows);
    }

    public Map<String, Object> setup(long teamId) {
        Team team = teamRepository.findById(teamId).orElseThrow(() -> new IllegalArgumentException("No such club " + teamId));
        Map<String, Object> info = teamRow(team);
        info.put("competitionId", team.getCompetitionId());
        info.put("competitionName", competitionRepository.findById(team.getCompetitionId()).map(Competition::getName).orElse(null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("team", info);
        body.put("players", TacticArenaSupport.players(tacticController, teamId));
        body.put("formationCatalog", tacticController.formationCatalog(String.valueOf(teamId)));
        body.put("savedTactic", tacticController.getFormation(String.valueOf(teamId)));
        body.put("teamView", tacticController.getTeamTacticView(teamId).getBody());
        return body;
    }

    private static Map<String, Object> teamRow(Team team) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("teamId", team.getId());
        row.put("name", team.getName());
        row.put("reputation", team.getReputation());
        row.put("color1", team.getColor1());
        row.put("color2", team.getColor2());
        return row;
    }
}
