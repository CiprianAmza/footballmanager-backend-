package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.ManagerTeamTacticView;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.frontend.TacticView;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionRelation;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionRelationRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tactic")
@CrossOrigin(origins = "*")
public class TacticController {

    @Autowired
    TeamRepository teamRepository;
    @Autowired
    HumanRepository humanRepository;
    @Autowired
    CompetitionRepository competitionRepository;
    @Autowired
    TeamCompetitionRelationRepository teamCompetitionRelationRepository;
    @Autowired
    TacticService tacticService;

    @PostMapping("/firstEleven")
    public void saveFirstEleven(String tactic) { // tactic format: GK=1231&DC=1331&DL=123...
        // TODO
    }

    @GetMapping("/getPlayers/{teamId}")
    private List<PlayerView> getPlayers(@PathVariable(name = "teamId") String teamId) {

        long _teamId = Long.parseLong(teamId);

        Team team = teamRepository.findById(_teamId).orElse(null);
        if (team == null)
            throw new RuntimeException("Team not found.");

        List<Human> getAllPlayers = humanRepository.findAllByTeamIdAndTypeId(Long.parseLong(teamId), TypeNames.HUMAN_TYPE);

        List<PlayerView> allPlayers = getAllPlayers
                .stream()
                .map(player -> adaptPlayer(player, team))
                .toList();

        return allPlayers;
    }

    @GetMapping("/getAllPossibleTactics/{teamId}")
    private List<TacticView> getAllPossibleTactics(@PathVariable(name = "teamId") String teamId) {

        long _teamId = Long.parseLong(teamId);

        Team team = teamRepository.findById(_teamId).orElse(null);
        if (team == null)
            throw new RuntimeException("Team not found.");

        List<TacticView> tacticViews = new ArrayList<>();
        List<String> allTactics = tacticService.getAllExistingTactics();
        for (String tactic: allTactics) {
            List<PlayerView> bestEleven = getBestElevenPlayers(team, tacticService.getRoomInTeamByTactic(tactic));

            TacticView tacticView = new TacticView();
            tacticView.setTacticName(tactic);
            tacticView.setTotalRating(bestEleven.stream().mapToDouble(PlayerView::getRating).sum());

            tacticViews.add(tacticView);
        }

        return tacticViews
                .stream()
                .sorted((x, y) -> Double.compare(y.getTotalRating(), x.getTotalRating()))
                .toList();
    }

    @GetMapping("/getBestEleven/{teamId}/{tactic}")
    private List<PlayerView> getBestEleven(@PathVariable(name = "teamId") String teamId, @PathVariable(name = "tactic", required = false) String tactic) {

        long _teamId = Long.parseLong(teamId);

        Team team = teamRepository.findById(_teamId).orElse(null);
        if (team == null)
            throw new RuntimeException("Team not found.");

        Map<String, Integer> tacticFormat = tacticService.getRoomInTeamByTactic(tactic);

        return getBestElevenPlayers(team, tacticFormat);
    }

    private List<PlayerView> getBestElevenPlayers(Team team, Map<String, Integer> tacticFormat) {

        List<Human> allPlayers = humanRepository.findAllByTeamIdAndTypeId(team.getId(), TypeNames.HUMAN_TYPE);
        List<PlayerView> players = allPlayers.stream().map(player -> adaptPlayer(player, team)).toList();
        Map<String, List<PlayerView>> positionToPlayers = new HashMap<>();

        for (PlayerView playerView: players) {
            if (!positionToPlayers.containsKey(playerView.getPosition()))
                positionToPlayers.put(playerView.getPosition(), new ArrayList<>());
            positionToPlayers.get(playerView.getPosition()).add(playerView);
        }

        Map<String, List<PlayerView>> bestEleven = new HashMap<>();
        int available = 11;
        List<PlayerView> restPlayers = new ArrayList<>();

        for (Map.Entry<String, Integer> entry: tacticFormat.entrySet()) {
            String position = entry.getKey();
            Integer needed = entry.getValue();
            bestEleven.put(position, new ArrayList<>());

            List<PlayerView> playersForThisPosition = positionToPlayers.getOrDefault(position, new ArrayList<>()).stream()
                    .sorted((x, y) -> Double.compare(y.getRating(), x.getRating()))
                    .toList();

            for (int i = 0; i < Math.min(needed, playersForThisPosition.size()); i++) {
                bestEleven.get(position).add(playersForThisPosition.get(i));
                available -= 1;
            }

            for (int i = needed; i < playersForThisPosition.size(); i++) {
                restPlayers.add(playersForThisPosition.get(i));
            }
        }

        List<PlayerView> firstEleven = new ArrayList<>();
        for (List<PlayerView> playerViews: bestEleven.values())
            firstEleven.addAll(playerViews);

        firstEleven.sort((p1, p2) -> Double.compare(p2.getRating(), p1.getRating()));
        for (int i = 0; i < Math.min(available, restPlayers.size()); i++) { // todo handle case where team does not have at least 11 players...
            firstEleven.add(restPlayers.get(i));
        }

        for (int i = firstEleven.size(); i < 11; i++) {
            PlayerView playerView = new PlayerView();
            playerView.setAge(16);
            playerView.setName("Generated");
            playerView.setPosition("MC");
            playerView.setRating(10);

            firstEleven.add(playerView);
        }

        return firstEleven
                .stream()
                .sorted((p1, p2) ->
                        Integer.compare(
                                tacticService.getValueForTacticDisplay(p1.getPosition()),
                                tacticService.getValueForTacticDisplay(p2.getPosition())))
                .toList();
    }

    private PlayerView adaptPlayer(Human human, Team team) {

        PlayerView playerView = new PlayerView();
        playerView.setAge(human.getAge());
        playerView.setPosition(human.getPosition());
        playerView.setRating(human.getRating());
        playerView.setName(human.getName());
        playerView.setTeamName(team.getName());

        return playerView;
    }

    @GetMapping("/getTeamTotalSkills/{competitionId}")
    private List<Pair<String, Double>> getTeamTotalSkills(@PathVariable(name = "competitionId") String competitionId) {

        long _competitionId = Long.parseLong(competitionId);

        List<Pair<String, Double>> teamTotalSkills = new ArrayList<>();
        for (Team team : teamRepository.findAllByCompetitionId(_competitionId)) {
            double totalSkill = getTotalTeamSkill(team.getId());
            Pair<String, Double> pair = Pair.of(team.getName(), totalSkill);
            teamTotalSkills.add(pair);
        }

        return teamTotalSkills
                .stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .toList();
    }

    private double getTotalTeamSkill(long teamId) {

        return humanRepository
                .findAll()
                .stream()
                .filter(human -> human.getTeamId() == teamId)
                .map(Human::getRating)
                .sorted((a, b) -> Double.compare(b, a))
                .limit(11)
                .reduce(Double::sum).orElse(0D);
    }

    @GetMapping("/getTeamRatingByManagerTacticForCompetitionId/{competitionId}")
    public List<ManagerTeamTacticView> getTeamRatingByManagerTacticForCompetitionId(long competitionId) {

        return getCurrentTeamSkillsAccordingToManagerFavoriteTactic(competitionId);
    }

    private List<ManagerTeamTacticView> getCurrentTeamSkillsAccordingToManagerFavoriteTactic(long competitionId) {

        List<Long> teamIds = teamCompetitionRelationRepository
                .findAll()
                .stream()
                .filter(teamCompetitionRelation -> teamCompetitionRelation.getCompetitionId() == competitionId)
                .map(TeamCompetitionRelation::getTeamId)
                .toList();

        List<ManagerTeamTacticView> managerTeamTacticViews = new ArrayList<>();

        for (Long teamId: teamIds) {
            Team team = teamRepository.findById(teamId).get();
            Human manager = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).get(0); // todo check later

            ManagerTeamTacticView managerTeamTacticView = new ManagerTeamTacticView();
            managerTeamTacticView.setManagerName(manager.getName());
            managerTeamTacticView.setTeamName(team.getName());
            managerTeamTacticView.setTactic(manager.getTacticStyle());

            double rating = getBestEleven(String.valueOf(teamId), manager.getTacticStyle())
                    .stream()
                    .mapToDouble(playerView -> playerView.getRating())
                    .sum();

            managerTeamTacticView.setTacticRating(rating);

            managerTeamTacticViews.add(managerTeamTacticView);
        }

        return managerTeamTacticViews
                .stream()
                .sorted((mttv1, mttv2) -> Double.compare(mttv2.getTacticRating(), mttv1.getTacticRating()))
                .toList();
    }
}
