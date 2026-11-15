package com.footballmanagergamesimulator.match2d;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks the 2D match engine for the result of a match, or of a group of matches, between
 * clubs of this game. Read-only: nothing is saved and the game's own match engine is not
 * involved; the result is the 2D engine's alone.
 */
@Service
public class Match2dResultService {

    static final int MIN_MINUTES = 1;
    static final int MAX_MINUTES = 120;
    static final int DEFAULT_MINUTES = 90;
    /** Multiplier of the home id in the default seed: {@code home * 100000 + away}. */
    static final long SEED_HOME_FACTOR = 100_000L;

    private final Match2dLineupService lineups;
    private final Match2dEngineClient engine;

    public Match2dResultService(Match2dLineupService lineups, Match2dEngineClient engine) {
        this.lineups = lineups;
        this.engine = engine;
    }

    /**
     * One match to play. {@code seed} and {@code minutes} are optional (see
     * {@link #seedOf} and {@link #DEFAULT_MINUTES}); {@code ref} is the caller's own label,
     * returned unchanged.
     */
    public record Match(long homeTeamId, long awayTeamId, Long seed, Integer minutes, String ref) {
    }

    /** Plays one match; a missing team or line-up fails the request. */
    public Match2dResult result(Match match) {
        if (match == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No match given.");
        }
        if (match.homeTeamId() == match.awayTeamId()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick two different teams.");
        }
        return engine.result(lineups.build(match.homeTeamId()), lineups.build(match.awayTeamId()),
                seedOf(match), minutesOf(match), match.ref());
    }

    /**
     * Plays a group of matches and returns one result per match, in the same order. Each
     * club's line-up is built once per call, however many matches it plays. A match whose
     * teams are invalid or cannot field a line-up comes back with only {@code ref} and
     * {@code error}; the others are still played.
     */
    public List<Match2dResult> results(List<Match> matches) {
        if (matches == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No matches given.");
        }
        Map<Long, Object> built = new HashMap<>(); // team id -> Match2dTeam, or the failure text
        Match2dResult[] out = new Match2dResult[matches.size()];
        List<Integer> played = new ArrayList<>();
        List<Match2dEngineClient.Request> requests = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            Match match = matches.get(i);
            if (match == null) {
                out[i] = Match2dResult.failed(null, "No match given.");
                continue;
            }
            if (match.homeTeamId() == match.awayTeamId()) {
                out[i] = Match2dResult.failed(match.ref(), "Pick two different teams.");
                continue;
            }
            Object home = built.computeIfAbsent(match.homeTeamId(), this::buildOrReason);
            Object away = built.computeIfAbsent(match.awayTeamId(), this::buildOrReason);
            if (!(home instanceof Match2dTeam homeTeam)) {
                out[i] = Match2dResult.failed(match.ref(), (String) home);
            } else if (!(away instanceof Match2dTeam awayTeam)) {
                out[i] = Match2dResult.failed(match.ref(), (String) away);
            } else {
                played.add(i);
                requests.add(new Match2dEngineClient.Request(homeTeam, awayTeam,
                        seedOf(match), minutesOf(match), match.ref()));
            }
        }
        if (!requests.isEmpty()) {
            List<Match2dResult> answers = engine.results(requests);
            for (int k = 0; k < played.size(); k++) out[played.get(k)] = answers.get(k);
        }
        return List.of(out);
    }

    /** The team's line-up, or why it cannot be built. */
    private Object buildOrReason(long teamId) {
        try {
            return lineups.build(teamId);
        } catch (ResponseStatusException failure) {
            return failure.getReason() != null ? failure.getReason() : failure.getMessage();
        } catch (RuntimeException failure) {
            return "The line-up of team " + teamId + " could not be built: " + failure.getMessage();
        }
    }

    /** The seed asked for, else {@code homeTeamId * 100000 + awayTeamId}: fixed for the pairing, home first. */
    static long seedOf(Match match) {
        return match.seed() != null ? match.seed() : match.homeTeamId() * SEED_HOME_FACTOR + match.awayTeamId();
    }

    static int minutesOf(Match match) {
        int minutes = match.minutes() != null ? match.minutes() : DEFAULT_MINUTES;
        return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
    }
}
