package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.SeasonObjective;
import com.footballmanagergamesimulator.repository.SeasonObjectiveRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.model.Round;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/objectives")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class SeasonObjectiveController {

    @Autowired
    private SeasonObjectiveRepository seasonObjectiveRepository;

    @Autowired
    private RoundRepository roundRepository;

    @GetMapping("/{teamId}/{season}")
    public List<SeasonObjective> getObjectivesForTeamAndSeason(
            @PathVariable long teamId,
            @PathVariable int season) {
        return seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(teamId, season);
    }

    @GetMapping("/current/{teamId}")
    public List<SeasonObjective> getCurrentSeasonObjectives(@PathVariable long teamId) {
        Round round = roundRepository.findById(1L).orElse(null);
        int currentSeason = round != null ? (int) round.getSeason() : 1;
        return seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(teamId, currentSeason);
    }
}
