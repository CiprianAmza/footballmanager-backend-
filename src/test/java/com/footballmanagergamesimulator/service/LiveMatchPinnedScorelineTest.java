package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The interactive {@link LiveMatchSession} must NARRATE exactly the canonical scoreline
 * it was pinned to: advance to full time and the live final score equals the target,
 * while stats stay coherent (every goal is a shot on target; goals never exceed shots).
 */
class LiveMatchPinnedScorelineTest {

    private LiveMatchSimulationService service;
    private MatchEngineConfig engineConfig;

    private static final long HOME_TEAM = 1L;
    private static final long AWAY_TEAM = 2L;
    private static final long COMP = 10L;

    @BeforeEach
    void setUp() throws Exception {
        engineConfig = new MatchEngineConfig();

        service = new LiveMatchSimulationService();
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        PlayerSkillsRepository playerSkillsRepository = mock(PlayerSkillsRepository.class);
        MatchEventRepository matchEventRepository = mock(MatchEventRepository.class);
        GoalAnimationService goalAnimationService = mock(GoalAnimationService.class);

        injectService("humanRepository", humanRepository);
        injectService("teamRepository", teamRepository);
        injectService("competitionRepository", competitionRepository);
        injectService("playerSkillsRepository", playerSkillsRepository);
        injectService("matchEventRepository", matchEventRepository);
        injectService("goalAnimationService", goalAnimationService);
        injectService("engineConfig", engineConfig);

        // Two full 13-man squads (GK + 11 outfield + 1 bench) so the engine has
        // a real pitch + bench to narrate against.
        List<Human> homeSquad = buildSquad(100L);
        List<Human> awaySquad = buildSquad(200L);
        when(humanRepository.findAllByTeamIdAndTypeId(HOME_TEAM, 1L)).thenReturn(homeSquad);
        when(humanRepository.findAllByTeamIdAndTypeId(AWAY_TEAM, 1L)).thenReturn(awaySquad);
        when(teamRepository.findNameById(HOME_TEAM)).thenReturn("Home FC");
        when(teamRepository.findNameById(AWAY_TEAM)).thenReturn("Away FC");
        when(competitionRepository.findNameById(COMP)).thenReturn("Test League");
        when(playerSkillsRepository.findAllByPlayerIdIn(any())).thenReturn(Collections.emptyList());
    }

    @Test
    void liveSessionEndsOnExactPinnedScoreline() {
        // Draws, wins both ways, nil-nil and a high-scoring case.
        int[][] scorelines = {{0, 0}, {1, 0}, {0, 1}, {2, 2}, {3, 1}, {1, 4}, {6, 2}};

        int round = 1;
        for (int[] target : scorelines) {
            int targetHome = target[0];
            int targetAway = target[1];
            String seed = targetHome + "-" + targetAway;

            LiveMatchSession session = service.createInteractiveSession(
                    HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, round++,
                    false, targetHome, targetAway);
            session.advanceUntilAndSnapshot(session.getTotalMinutes());

            // Live final score == pinned score, EXACTLY.
            assertTrue(session.isFinished(), "session should reach full time (seed " + seed + ")");
            assertEquals(targetHome, session.getHomeScore(),
                    "home live score must equal pinned score (" + seed + ")");
            assertEquals(targetAway, session.getAwayScore(),
                    "away live score must equal pinned score (" + seed + ")");

            // 4) Stats coherence: every goal is a shot on target.
            var data = session.snapshot();
            assertTrue(data.getHomeShotsOnTarget() >= session.getHomeScore(),
                    "home goals must be <= shots on target (seed " + seed + ")");
            assertTrue(data.getAwayShotsOnTarget() >= session.getAwayScore(),
                    "away goals must be <= shots on target (seed " + seed + ")");
            assertTrue(data.getHomeShots() >= data.getHomeShotsOnTarget(),
                    "home shots on target must be <= total shots (seed " + seed + ")");
            assertTrue(data.getAwayShots() >= data.getAwayShotsOnTarget(),
                    "away shots on target must be <= total shots (seed " + seed + ")");
        }
    }

    // ---------------- fixtures ----------------

    /** 1 GK + 11 outfield + 1 bench player = 13 players. */
    private List<Human> buildSquad(long baseId) {
        List<Human> squad = new ArrayList<>();
        squad.add(human(baseId, "GK", 70));
        String[] outfield = {"DC", "DC", "DL", "DR", "MC", "MC", "ML", "MR", "AMC", "ST", "ST"};
        for (int i = 0; i < outfield.length; i++) {
            squad.add(human(baseId + 1 + i, outfield[i], 70 - i % 5));
        }
        squad.add(human(baseId + 12, "ST", 55)); // bench
        return squad;
    }

    private static Human human(long id, String position, double rating) {
        Human h = new Human();
        h.setId(id);
        h.setName(position + "_" + id);
        h.setPosition(position);
        h.setRating(rating);
        h.setRetired(false);
        h.setFitness(100.0);
        return h;
    }

    private void injectService(String fieldName, Object value) throws Exception {
        injectField(LiveMatchSimulationService.class, service, fieldName, value);
    }

    private static void injectField(Class<?> type, Object target, String fieldName, Object value) throws Exception {
        var field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
