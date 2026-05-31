package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.BestTacticService;
import com.footballmanagergamesimulator.service.BestTacticService.TacticRow;
import com.footballmanagergamesimulator.service.GameStateService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.service.TacticSimulationService;
import com.footballmanagergamesimulator.service.TacticSimulationService.CompetitionResult;
import com.footballmanagergamesimulator.service.TacticSimulationService.TacticPointsResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Non-admin, user-facing tactic / competition simulation endpoints. Powers the frontend pages that
 * rank a team's 900 tactic settings by REAL simulated season points and run custom round-robin
 * competitions. Read-only.
 */
@RestController
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class TacticSimController {

    @Autowired private TacticSimulationService tacticSimulationService;
    @Autowired private BestTacticService bestTacticService;
    @Autowired private TacticService tacticService;
    @Autowired private GameStateService gameState;
    @Autowired private RoundRepository roundRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository ctiRepo;
    @Autowired private TeamRepository teamRepo;

    public record AnalyticalRow(String formation, String mentality, String tempo, String passingType,
                                String inPossession, String timeWasting,
                                double expectedPoints, double expectedGoalDifference,
                                String defensiveLine, String pressing, String width) {}

    public record AnalyticalResult(long teamId, String teamName, List<AnalyticalRow> rows) {}

    public record TeamRef(long teamId, String teamName) {}

    public record LeagueTeams(long competitionId, String competitionName, List<TeamRef> teams) {}

    public record CompetitionRequest(List<Long> teamIds, int seasons) {}

    @GetMapping("/tactics/formations")
    public List<String> formations() {
        return tacticService.getAllExistingTactics();
    }

    @GetMapping("/tactics/analytical/{teamId}")
    public AnalyticalResult analytical(@PathVariable long teamId) {
        List<TacticRow> ranked = bestTacticService.rankAllTactics(teamId);
        List<AnalyticalRow> rows = new ArrayList<>(ranked.size());
        for (TacticRow r : ranked) {
            rows.add(new AnalyticalRow(r.formation(), r.mentality(), r.tempo(), r.passingType(),
                    r.inPossession(), r.timeWasting(), r.expectedPoints(), r.expectedGoalDifference(),
                    r.defensiveLine(), r.pressing(), r.width()));
        }
        String name = teamRepo.findNameById(teamId);
        return new AnalyticalResult(teamId, name == null ? "Team#" + teamId : name, rows);
    }

    @GetMapping("/tactics/simulate")
    public TacticPointsResult simulate(@RequestParam long teamId,
                                       @RequestParam(required = false, defaultValue = "442") String formation,
                                       @RequestParam(required = false, defaultValue = "10") int seasons,
                                       @RequestParam(required = false) String opponentIds) {
        return tacticSimulationService.simulateTacticPoints(teamId, formation, seasons, parseCsv(opponentIds));
    }

    @PostMapping("/competition/simulate")
    public CompetitionResult competitionSimulate(@RequestBody CompetitionRequest request) {
        int seasons = request.seasons() <= 0 ? 10 : request.seasons();
        return tacticSimulationService.simulateCompetition(request.teamIds(), seasons);
    }

    @GetMapping("/competition/leaguesAndTeams")
    public List<LeagueTeams> leaguesAndTeams() {
        Round round = roundRepository.findById(1L).orElseGet(gameState::getRound);
        int season = (int) round.getSeason();

        TreeSet<Long> leagueComps = new TreeSet<>();
        leagueComps.addAll(competitionRepository.findIdsByTypeId(1));
        leagueComps.addAll(competitionRepository.findIdsByTypeId(3));

        List<LeagueTeams> out = new ArrayList<>();
        for (long compId : leagueComps) {
            Map<Long, TeamRef> teams = new LinkedHashMap<>();
            for (CompetitionTeamInfo cti : ctiRepo.findAllByCompetitionIdAndSeasonNumber(compId, season)) {
                long teamId = cti.getTeamId();
                if (teamId <= 0 || teams.containsKey(teamId)) continue;
                String name = teamRepo.findNameById(teamId);
                teams.put(teamId, new TeamRef(teamId, name == null ? "Team#" + teamId : name));
            }
            if (teams.isEmpty()) continue;
            String compName = competitionRepository.findNameById(compId);
            out.add(new LeagueTeams(compId, compName == null ? "Competition#" + compId : compName,
                    new ArrayList<>(teams.values())));
        }
        return out;
    }

    private static List<Long> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return null;
        List<Long> ids = new ArrayList<>();
        for (String part : csv.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) ids.add(Long.parseLong(s));
        }
        return ids;
    }
}
