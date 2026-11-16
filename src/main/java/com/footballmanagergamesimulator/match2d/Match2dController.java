package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Preview of a match played by the 2D ratings engine instead of scripted phases.
 * Read-only: it changes no result, table or saved match; the engine's score is its own.
 */
@RestController
@RequestMapping("/match/2d")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class Match2dController {

    private static final int MIN_MINUTES = 1;
    private static final int MAX_MINUTES = 120;

    private final Match2dLineupService lineups;
    private final Match2dEngineClient engine;
    private final Match2dResultService results;
    private final Match2dReplayService replays;

    public Match2dController(Match2dLineupService lineups, Match2dEngineClient engine,
                             Match2dResultService results, Match2dReplayService replays) {
        this.lineups = lineups;
        this.engine = engine;
        this.results = results;
        this.replays = replays;
    }

    /**
     * Sends both clubs' current line-ups and tactics to the engine and returns its answer:
     * {@code viewUrl} is the page to show (in an iframe), {@code score} the result it played.
     * The same teams and seed always give the same match.
     */
    @GetMapping("/preview")
    public JsonNode preview(@RequestParam long teamId1,
                            @RequestParam long teamId2,
                            @RequestParam(defaultValue = "1") long seed,
                            @RequestParam(defaultValue = "90") int minutes) {
        if (teamId1 == teamId2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick two different teams.");
        }
        int length = Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
        return engine.play(lineups.build(teamId1), lineups.build(teamId2), seed, length);
    }

    /**
     * Only the result of one match (score, goals, ratings, statistics), without the replay
     * page. {@code seed} is optional (default {@code homeTeamId * 100000 + awayTeamId}),
     * {@code minutes} defaults to 90, {@code ref} comes back unchanged.
     */
    @PostMapping("/result")
    public Match2dResult result(@RequestBody Match2dResultService.Match match) {
        return results.result(match);
    }

    /**
     * Results of a group of matches, {@code {"matches": [...]}} in, {@code {"results": [...]}}
     * out, in the same order. A match that cannot be played comes back as {@code ref} and
     * {@code error}; the others are still played.
     */
    @PostMapping("/results")
    public Results results(@RequestBody Group group) {
        return new Results(results.results(group == null ? null : group.matches()));
    }

    public record Group(List<Match2dResultService.Match> matches) {
    }

    public record Results(List<Match2dResult> results) {
    }

    /**
     * Watch again a real fixture the 2D engine played: same values as {@code /match/report}.
     * Answers {@code viewUrl}, {@code score}, {@code engineVersion}, {@code versionMismatch};
     * 404 when the match was not played by the 2D engine.
     */
    @GetMapping("/replay/{competitionId}/{season}/{round}/{teamId1}/{teamId2}")
    public Map<String, Object> replay(@PathVariable long competitionId,
                                      @PathVariable int season,
                                      @PathVariable int round,
                                      @PathVariable long teamId1,
                                      @PathVariable long teamId2) {
        return replays.replay(competitionId, season, round, teamId1, teamId2);
    }

    /** Exactly what the engine would receive for this club: starting eleven, attributes, tactic. */
    @GetMapping("/lineup/{teamId}")
    public Match2dTeam lineup(@PathVariable long teamId) {
        return lineups.build(teamId);
    }
}
