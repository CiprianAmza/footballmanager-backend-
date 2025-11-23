package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.ScheduleView;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.MatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/match")
@CrossOrigin(origins = "*")
public class MatchController {

    @Autowired
    CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired
    MatchService matchService;
    @Autowired
    CompetitionController competitionController;

    @GetMapping("/getScheduleForSeasonNumber/{seasonNumber}/{teamId}")
    public List<ScheduleView> getScheduleForSeasonNumberAndTeamId(@PathVariable(name = "seasonNumber") int seasonNumber, @PathVariable(name = "teamId") long teamId) {

        List<CompetitionTeamInfoMatch> competitionTeamInfoMatches = competitionTeamInfoMatchRepository.findAllBySeasonNumberAndTeamId(String.valueOf(seasonNumber), teamId);

        return matchService.getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(competitionTeamInfoMatches, teamId, seasonNumber);
    }

    @GetMapping("/getScheduleForCurrentSeasonAndTeamId/{teamId}")
    public List<ScheduleView> getScheduleForCurrentSeasonAndTeamId(@PathVariable(name = "teamId") long teamId) {

        long currentSeason = Long.parseLong(competitionController.getCurrentSeason());
        List<CompetitionTeamInfoMatch> competitionTeamInfoMatches = competitionTeamInfoMatchRepository.findAllBySeasonNumberAndTeamId(String.valueOf(currentSeason), teamId);

        return matchService.getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(competitionTeamInfoMatches, teamId, currentSeason);
    }
}
