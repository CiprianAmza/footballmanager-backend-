package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.TacticalScoreService.Matchup;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Engine-unification proof: the interactive {@link LiveMatchSession} must NARRATE
 * the exact scoreline the instant two-axis engine ({@link TacticalScoreService#score})
 * produces for the same profiles + tactic vectors + seed. We compute the instant
 * score, pin a live session to it, advance to full time, and assert the live final
 * {@code homeScore}/{@code awayScore} equal the instant score EXACTLY — while stats
 * stay coherent (every goal is a shot on target; goals never exceed shots).
 */
class LiveMatchPinnedScorelineTest {

    private LiveMatchSimulationService service;
    private TacticalScoreService tacticalScoreService;
    private MatchEngineConfig engineConfig;

    private static final long HOME_TEAM = 1L;
    private static final long AWAY_TEAM = 2L;
    private static final long COMP = 10L;

    @BeforeEach
    void setUp() throws Exception {
        engineConfig = new MatchEngineConfig();
        engineConfig.getTacticalModel().setEnabled(true);

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

        tacticalScoreService = new TacticalScoreService();
        injectField(TacticalScoreService.class, tacticalScoreService, "engineConfig", engineConfig);

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
    void liveSessionEndsOnExactInstantScoreline() {
        // Asymmetric profiles so the instant engine produces a non-trivial,
        // non-draw scoreline; loop seeds to also cover a high-scoring case.
        TeamProfile p1 = new TeamProfile(70.0, 55.0);
        TeamProfile p2 = new TeamProfile(50.0, 45.0);
        TacticVector t1 = new TacticVector(0.4, 0.3, 0.2);
        TacticVector t2 = new TacticVector(-0.2, -0.1, 0.5);

        for (long seed = 1; seed <= 25; seed++) {
            // 1) Instant engine scoreline for this matchup + seed (home = team1).
            List<Integer> instant = tacticalScoreService.score(p1, t1, p2, t2, new Random(seed));
            int targetHome = instant.get(0);
            int targetAway = instant.get(1);

            // 2) Pin a live session to that scoreline and advance to full time.
            Matchup matchup = tacticalScoreService.matchup(p1, t1, p2, t2);
            LiveMatchSession session = service.createInteractiveSession(
                    HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, (int) seed,
                    false, matchup, targetHome, targetAway);
            session.advanceUntilAndSnapshot(session.getTotalMinutes());

            // 3) Live final score == instant score, EXACTLY.
            assertTrue(session.isFinished(), "session should reach full time (seed " + seed + ")");
            assertEquals(targetHome, session.getHomeScore(),
                    "home live score must equal instant score (seed " + seed + ")");
            assertEquals(targetAway, session.getAwayScore(),
                    "away live score must equal instant score (seed " + seed + ")");

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
