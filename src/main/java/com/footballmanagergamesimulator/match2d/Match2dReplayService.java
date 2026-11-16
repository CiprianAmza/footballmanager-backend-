package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Records the fixtures played by the 2D engine and plays them again for watching. */
@Service
public class Match2dReplayService {

    private final Match2dReplayRepository replays;
    private final Match2dEngineClient engine;

    public Match2dReplayService(Match2dReplayRepository replays, Match2dEngineClient engine) {
        this.replays = replays;
        this.engine = engine;
    }

    /** Saves (or refreshes) the replay of a fixture, in the caller's transaction. */
    @Transactional
    public void record(String fixtureKey, long competitionId, int season, int round,
                       long team1Id, long team2Id, Match2dRoundPlanner.Played played) {
        String version = played.result().engineVersion();
        Match2dReplay replay = replays.findByFixtureKey(fixtureKey).orElse(null);
        if (replay == null) {
            replay = new Match2dReplay(fixtureKey, competitionId, season, round, team1Id, team2Id,
                    played.seed(), Match2dRoundPlanner.MATCH_MINUTES, version, played.requestJson());
        } else {
            replay.update(competitionId, season, round, team1Id, team2Id,
                    played.seed(), Match2dRoundPlanner.MATCH_MINUTES, version, played.requestJson());
        }
        replays.save(replay);
    }

    /** Whether this match (either team order) was played by the 2D engine. */
    @Transactional(readOnly = true)
    public boolean exists(long competitionId, int season, int round, long teamId1, long teamId2) {
        return replays.existsByCompetitionIdAndSeasonAndRoundAndTeam1IdAndTeam2Id(
                competitionId, season, round, teamId1, teamId2)
                || replays.existsByCompetitionIdAndSeasonAndRoundAndTeam1IdAndTeam2Id(
                competitionId, season, round, teamId2, teamId1);
    }

    /**
     * Plays the saved match again and returns {@code viewUrl}, {@code score},
     * {@code engineVersion} and {@code versionMismatch} (the engine now runs another version
     * than the one that decided the result, so the page may differ from it). 404 when the
     * match was not played by the 2D engine; 502/503 when the engine does not answer.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> replay(long competitionId, int season, int round, long teamId1, long teamId2) {
        Match2dReplay replay = find(competitionId, season, round, teamId1, teamId2)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This match was not played by the 2D match engine."));
        JsonNode answer = engine.replay(replay.getRequestJson());
        String version = answer.hasNonNull("engineVersion") ? answer.get("engineVersion").asText() : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewUrl", answer.hasNonNull("viewUrl") ? answer.get("viewUrl").asText() : null);
        out.put("score", answer.get("score"));
        out.put("engineVersion", version);
        out.put("versionMismatch", !Objects.equals(version, replay.getEngineVersion()));
        return out;
    }

    private Optional<Match2dReplay> find(long competitionId, int season, int round, long teamId1, long teamId2) {
        Optional<Match2dReplay> exact = replays.findFirstByCompetitionIdAndSeasonAndRoundAndTeam1IdAndTeam2Id(
                competitionId, season, round, teamId1, teamId2);
        return exact.isPresent() ? exact
                : replays.findFirstByCompetitionIdAndSeasonAndRoundAndTeam1IdAndTeam2Id(
                        competitionId, season, round, teamId2, teamId1);
    }
}
