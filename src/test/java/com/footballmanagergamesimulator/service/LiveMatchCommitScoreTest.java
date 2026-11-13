package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The canonical score is fixed before kickoff. A manual substitution changes who is on
 * the pitch, never the score that gets committed.
 */
class LiveMatchCommitScoreTest {

    private static final long HOME_TEAM = 1L;
    private static final long AWAY_TEAM = 2L;
    private static final long COMP = 10L;

    private LiveMatchSimulationService liveService;

    @BeforeEach
    void setUp() throws Exception {
        liveService = new LiveMatchSimulationService();
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        PlayerSkillsRepository playerSkillsRepository = mock(PlayerSkillsRepository.class);

        inject("humanRepository", humanRepository);
        inject("teamRepository", teamRepository);
        inject("competitionRepository", competitionRepository);
        inject("playerSkillsRepository", playerSkillsRepository);
        inject("matchEventRepository", mock(MatchEventRepository.class));
        inject("goalAnimationService", mock(GoalAnimationService.class));
        inject("engineConfig", new MatchEngineConfig());

        when(teamRepository.findNameById(HOME_TEAM)).thenReturn("Home FC");
        when(teamRepository.findNameById(AWAY_TEAM)).thenReturn("Away FC");
        when(competitionRepository.findNameById(COMP)).thenReturn("Test League");
        when(humanRepository.findAllByTeamIdAndTypeId(HOME_TEAM, 1L)).thenReturn(squad(100L));
        when(humanRepository.findAllByTeamIdAndTypeId(AWAY_TEAM, 1L)).thenReturn(squad(200L));
        when(playerSkillsRepository.findAllByPlayerIdIn(any())).thenReturn(Collections.emptyList());
    }

    @Test
    void manualSubstitutionKeepsTheCanonicalScore() {
        LiveMatchSession session = liveService.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 1, false, 2, 1);
        session.setDeferredContext(100.0, 80.0, "442", "442", null, null, false, 0, 0L, 0);

        session.applyUserSub(101L, 112L);
        session.advanceUntilAndSnapshot(session.getTotalMinutes());
        var outcome = liveService.resolveCommitOutcome(session);

        assertEquals(2, outcome.homeGoals());
        assertEquals(1, outcome.awayGoals());
        assertEquals(100.0, outcome.homePower());
        assertEquals(80.0, outcome.awayPower());
    }

    /** 1 GK + 11 outfield + 1 bench player = 13 players. */
    private static List<Human> squad(long baseId) {
        List<Human> squad = new ArrayList<>();
        squad.add(human(baseId, "GK", 70));
        String[] outfield = {"DC", "DC", "DL", "DR", "MC", "MC", "ML", "MR", "AMC", "ST", "ST"};
        for (int i = 0; i < outfield.length; i++) {
            squad.add(human(baseId + 1 + i, outfield[i], 70 - i % 5));
        }
        squad.add(human(baseId + 12, "ST", 55));
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
        h.setMorale(80.0);
        return h;
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field field = LiveMatchSimulationService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(liveService, value);
    }
}
