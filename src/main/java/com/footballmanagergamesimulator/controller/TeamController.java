package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/teams")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class TeamController {
    
    TeamRepository teamRepository;
    HumanRepository humanRepository;
    @Autowired
    ClubCoefficientRepository clubCoefficientRepository;
    @Autowired
    TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired
    CompetitionRepository competitionRepository;
    @Autowired
    RoundRepository roundRepository;

    @Autowired
    public TeamController(TeamRepository teamRepository, HumanRepository humanRepository) {

        this.teamRepository = teamRepository;
        this.humanRepository = humanRepository;
    }

    @GetMapping("/getTeamNameById/{teamId}")
    public String getTeamNameByTeamId(@PathVariable(name = "teamId") long teamId) {

        return teamRepository.findNameById(teamId);
    }
    
    @GetMapping("/allPlayers/{teamId}")
    public List<Human> getAllPlayersForSquadByTeamId(@PathVariable(name = "teamId") long teamId) {

        List<Human> allPlayers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);

        return allPlayers;
    }

    @GetMapping("/finances/{teamId}")
    public Map<String, Object> getTeamFinances(@PathVariable(name = "teamId") long teamId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return Map.of();

        Round round = roundRepository.findById(1L).orElse(new Round());
        int currentSeason = (int) round.getSeason();

        Map<String, Object> finances = new LinkedHashMap<>();
        finances.put("transferBudget", team.getTransferBudget());
        finances.put("salaryBudget", team.getSalaryBudget());
        finances.put("totalFinances", team.getTotalFinances());

        // Calculate competition income for current season
        List<Competition> allComps = competitionRepository.findAll();
        List<TeamCompetitionDetail> allDetails = teamCompetitionDetailRepository.findAll();

        long leagueIncome = 0;
        long europeanIncome = 0;
        String leagueName = "";
        int leaguePosition = 0;

        for (Competition comp : allComps) {
            if (comp.getTypeId() != 1 && comp.getTypeId() != 3) continue;

            long leagueBase = (comp.getTypeId() == 1) ? 1_500_000L : 400_000L;

            List<TeamCompetitionDetail> standings = allDetails.stream()
                    .filter(d -> d.getCompetitionId() == comp.getId())
                    .sorted((a, b) -> {
                        if (a.getPoints() != b.getPoints()) return b.getPoints() - a.getPoints();
                        if (a.getGoalDifference() != b.getGoalDifference()) return b.getGoalDifference() - a.getGoalDifference();
                        return b.getGoalsFor() - a.getGoalsFor();
                    })
                    .toList();

            int numTeams = standings.size();
            if (numTeams == 0) continue;

            int position = 1;
            for (TeamCompetitionDetail detail : standings) {
                if (detail.getTeamId() == teamId) {
                    leagueIncome = (long) (leagueBase * (numTeams + 1 - position) / (numTeams / 2.0));
                    leagueName = comp.getName();
                    leaguePosition = position;
                    break;
                }
                position++;
            }
        }

        // European income
        Optional<ClubCoefficient> cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(teamId, currentSeason);
        if (cc.isEmpty()) {
            cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(teamId, currentSeason - 1);
        }
        europeanIncome = cc.map(c -> (long) (c.getPoints() * 200_000)).orElse(0L);

        finances.put("leagueIncome", leagueIncome);
        finances.put("leagueName", leagueName);
        finances.put("leaguePosition", leaguePosition);
        finances.put("europeanIncome", europeanIncome);
        finances.put("estimatedSeasonIncome", leagueIncome + europeanIncome);

        return finances;
    }

}
