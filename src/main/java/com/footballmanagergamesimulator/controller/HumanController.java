package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Player;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.PlayerSkillsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

@RestController
@RequestMapping("/humans")
@CrossOrigin(origins = "*")
public class HumanController {

    HumanRepository humanRepository;
    TeamRepository teamRepository;
    PlayerSkillsRepository playerSkillsRepository;

    @Autowired
    public HumanController(HumanRepository humanRepository, TeamRepository teamRepository, PlayerSkillsRepository playerSkillsRepository) {

        this.humanRepository = humanRepository;
        this.teamRepository = teamRepository;
        this.playerSkillsRepository = playerSkillsRepository;
    }

    @GetMapping("/allPlayers")
    public List<PlayerView> getAllPlayers() {

        return humanRepository
                .findAll()
                .stream()
                .filter(human -> human.getTypeId() == 1 && !human.isRetired())
                .map(this::buildPlayerView)
                .toList();
    }

    @GetMapping("/{playerId}")
    public PlayerView getById(@PathVariable(name = "playerId") Long playerId) {

        if (playerId == null)
            return null;

        Optional<Human> human = humanRepository.findById(playerId);
        if (human.isEmpty())
            return null;

        PlayerView playerView = buildPlayerView(human.get());

        return playerView;
    }
    
    public PlayerView buildPlayerView(Human player) { // todo move into service

        PlayerView playerView = new PlayerView();
        Team team;

        if (player.getTeamId() == null) { // player is retired
            playerView.setTeamName("N/A");
        } else {
            Optional<Team> possibleTeam = teamRepository.findById(player.getTeamId());
            team = possibleTeam.get();
            playerView.setTeamName(team.getName()); // todo team can never be null, which means Free Agent is not yet possible - maybe set teamID to 0 in that case
        }

        playerView.setId(player.getId());
        playerView.setName(player.getName());
        playerView.setPosition(player.getPosition());
        playerView.setRating(player.getRating());
        playerView.setAge(player.getAge());

        playerView.setMorale(player.getMorale());
        playerView.setFitness(player.getFitness());
        playerView.setCurrentStatus(player.getCurrentStatus());

        playerView.setSalary(player.getSalary());
        playerView.setAgreedPlayingTime(player.getAgreedPlayingTime());
        playerView.setContractStartDate(player.getContractStartDate());
        playerView.setContractEndDate(player.getContractEndDate());

        playerView.setWealth(player.getWealth());
        playerView.setSeasonCreated(player.getSeasonCreated());

        playerView.setBestEverRating(player.getBestEverRating());
        playerView.setSeasonOfBestEverRating(player.getSeasonOfBestEverRating());

        List<String> skillNames = new ArrayList<>();
        List<Long> skillValues =  new ArrayList<>();

        List<String> names = List.of("Acceleration", "Pace", "Strength", "Dribbling", "Passing", "Work Rate",
                "Shot", "Finishing", "Crossing", "Defending");

        Optional<PlayerSkills> playerSkills = playerSkillsRepository.findPlayerSkillsByPlayerId(player.getId());

        if (playerSkills.isPresent()) {
            for (int i = 0; i < names.size(); i++) {
                skillNames.add(names.get(i));
                skillValues.add(PlayerSkillsService.GETTER_MAP.get("skill" + (i + 1)).apply(playerSkills.get()));
            }
        }

        playerView.setSkillNames(skillNames);
        playerView.setSkillValues(skillValues);

        return playerView;
    }
}
