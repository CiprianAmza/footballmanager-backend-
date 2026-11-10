package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.service.CompetitionValueRankingService;
import com.footballmanagergamesimulator.service.CompetitionExpectationService;
import com.footballmanagergamesimulator.model.CompetitionSeasonExpectation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/value-rankings")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class CompetitionValueRankingController {
    private final CompetitionValueRankingService service;
    private final CompetitionExpectationService expectations;

    public CompetitionValueRankingController(CompetitionValueRankingService service,
            CompetitionExpectationService expectations) {
        this.service = service;
        this.expectations = expectations;
    }

    @GetMapping("/expectation/{season}/{competitionId}/{teamId}")
    public CompetitionSeasonExpectation expectation(@PathVariable int season,
            @PathVariable long competitionId, @PathVariable long teamId) {
        return expectations.openingPrediction(season, competitionId, teamId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No verified opening prediction for this team and season"));
    }

    @GetMapping
    public CompetitionValueRankingService.RankingResponse rankings(
            @RequestParam(defaultValue = "players") String subject,
            @RequestParam(defaultValue = "season") String scope,
            @RequestParam(required = false) Integer season,
            @RequestParam(required = false) Long competitionId) {
        return service.rank(subject, scope, season, competitionId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String invalidArgument(IllegalArgumentException error) {
        return error.getMessage();
    }
}
