package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/teams")
@CrossOrigin(origins = "*")
public class TeamController {
    
    TeamRepository teamRepository;
    HumanRepository humanRepository;
    
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

}
