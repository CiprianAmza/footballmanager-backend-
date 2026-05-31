package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveMatchCommitRescoreTest {

    private static final long HOME_TEAM = 1L;
    private static final long AWAY_TEAM = 2L;
    private static final long COMP = 10L;

    private LiveMatchSimulationService liveService;
    private MatchSimulationService matchSimulationService;
    private TacticalScoreService tacticalScoreService;

    private HumanRepository humanRepository;
    private TeamRepository teamRepository;
    private CompetitionRepository competitionRepository;
    private PlayerSkillsRepository playerSkillsRepository;
    private MatchEventRepository matchEventRepository;
    private PersonalizedTacticRepository personalizedTacticRepository;

    @BeforeEach
    void setUp() throws Exception {
        MatchEngineConfig engineConfig = new MatchEngineConfig();
        engineConfig.getTacticalModel().setEnabled(true);

        liveService = new LiveMatchSimulationService();
        matchSimulationService = new MatchSimulationService();
        tacticalScoreService = new TacticalScoreService();
        PlayerValueService playerValueService = new PlayerValueService(engineConfig);

        humanRepository = mock(HumanRepository.class);
        teamRepository = mock(TeamRepository.class);
        competitionRepository = mock(CompetitionRepository.class);
        playerSkillsRepository = mock(PlayerSkillsRepository.class);
        matchEventRepository = mock(MatchEventRepository.class);
        personalizedTacticRepository = mock(PersonalizedTacticRepository.class);
        GoalAnimationService goalAnimationService = mock(GoalAnimationService.class);

        inject(LiveMatchSimulationService.class, liveService, "humanRepository", humanRepository);
        inject(LiveMatchSimulationService.class, liveService, "teamRepository", teamRepository);
        inject(LiveMatchSimulationService.class, liveService, "competitionRepository", competitionRepository);
        inject(LiveMatchSimulationService.class, liveService, "playerSkillsRepository", playerSkillsRepository);
        inject(LiveMatchSimulationService.class, liveService, "matchEventRepository", matchEventRepository);
        inject(LiveMatchSimulationService.class, liveService, "goalAnimationService", goalAnimationService);
        inject(LiveMatchSimulationService.class, liveService, "engineConfig", engineConfig);
        inject(LiveMatchSimulationService.class, liveService, "tacticalScoreService", tacticalScoreService);
        inject(LiveMatchSimulationService.class, liveService, "playerValueService", playerValueService);
        inject(LiveMatchSimulationService.class, liveService, "matchSimulationService", matchSimulationService);

        inject(TacticalScoreService.class, tacticalScoreService, "engineConfig", engineConfig);

        inject(MatchSimulationService.class, matchSimulationService, "engineConfig", engineConfig);
        inject(MatchSimulationService.class, matchSimulationService, "humanRepository", humanRepository);
        inject(MatchSimulationService.class, matchSimulationService, "personalizedTacticRepository", personalizedTacticRepository);

        when(teamRepository.findNameById(HOME_TEAM)).thenReturn("Home FC");
        when(teamRepository.findNameById(AWAY_TEAM)).thenReturn("Away FC");
        when(competitionRepository.findNameById(COMP)).thenReturn("Test League");
        when(personalizedTacticRepository.findPersonalizedTacticByTeamId(anyLong())).thenReturn(java.util.Optional.empty());

        List<Human> homeSquad = buildHomeSquad();
        List<Human> awaySquad = buildAwaySquad();
        when(humanRepository.findAllByTeamIdAndTypeId(HOME_TEAM, 1L)).thenReturn(homeSquad);
        when(humanRepository.findAllByTeamIdAndTypeId(AWAY_TEAM, 1L)).thenReturn(awaySquad);
        when(humanRepository.findAllByTeamIdAndTypeId(HOME_TEAM, TypeNames.MANAGER_TYPE)).thenReturn(List.of(manager(900L, HOME_TEAM, 70, 70, 80)));
        when(humanRepository.findAllByTeamIdAndTypeId(AWAY_TEAM, TypeNames.MANAGER_TYPE)).thenReturn(List.of(manager(901L, AWAY_TEAM, 45, 45, 40)));
        when(playerSkillsRepository.findAllByPlayerIdIn(any())).thenReturn(buildSkills(homeSquad, awaySquad));
    }

    @Test
    void manualSubstitutionChangesCanonicalCommitOutcomeAndGoalEvents() {
        LiveMatchSession baseline = createSession();
        baseline.advanceUntilAndSnapshot(baseline.getTotalMinutes());
        var baselineOutcome = liveService.resolveCommitOutcome(baseline);

        LiveMatchSession withSub = createSession();
        withSub.applyUserSub(101L, 112L);
        withSub.advanceUntilAndSnapshot(withSub.getTotalMinutes());
        var commitOutcome = liveService.resolveCommitOutcome(withSub);

        assertFalse(baselineOutcome.recalculated());
        assertEquals(0, baselineOutcome.homeGoals());
        assertEquals(0, baselineOutcome.awayGoals());

        assertTrue(commitOutcome.recalculated(), "manual sub should trigger commit-time rescore");
        assertTrue(commitOutcome.homeGoals() != baselineOutcome.homeGoals()
                        || commitOutcome.awayGoals() != baselineOutcome.awayGoals(),
                "manual sub should change the canonical commit score");

        List<MatchEvent> commitEvents = matchSimulationService.buildGoalAndAssistEvents(
                COMP, 1, 1, HOME_TEAM, AWAY_TEAM,
                commitOutcome.homeGoals(), commitOutcome.awayGoals(), "442", "442");
        long goalCount = commitEvents.stream().filter(e -> "goal".equals(e.getEventType())).count();
        assertEquals(commitOutcome.homeGoals() + commitOutcome.awayGoals(), goalCount,
                "commit goal events should match the resimulated score");
    }

    private LiveMatchSession createSession() {
        LiveMatchSession session = liveService.createInteractiveSession(
                HOME_TEAM, AWAY_TEAM, 100.0, 80.0, COMP, 1, 1, false,
                null, 0, 0);
        session.setDeferredContext(100.0, 80.0, "442", "442", null, null, false, 0, 0L, 0);
        session.setDeferredTwoAxis(
                new TacticalScoreService.TeamProfile(60.0, 60.0), new TacticalScoreService.TacticVector(0, 0, 0),
                new TacticalScoreService.TeamProfile(40.0, 40.0), new TacticalScoreService.TacticVector(0, 0, 0));
        return session;
    }

    private List<Human> buildHomeSquad() {
        List<Human> squad = new ArrayList<>();
        squad.add(player(100L, "GK", 90));
        squad.add(player(101L, "ST", 80)); // weak starter by skills, but starts on rating
        squad.add(player(102L, "ST", 80));
        squad.add(player(103L, "AMC", 80));
        squad.add(player(104L, "MC", 80));
        squad.add(player(105L, "MC", 80));
        squad.add(player(106L, "ML", 80));
        squad.add(player(107L, "MR", 80));
        squad.add(player(108L, "DC", 80));
        squad.add(player(109L, "DC", 80));
        squad.add(player(110L, "DL", 80));
        squad.add(player(111L, "DR", 80));
        squad.add(player(112L, "ST", 1));  // elite bench by skills, stays out on rating
        return squad;
    }

    private List<Human> buildAwaySquad() {
        List<Human> squad = new ArrayList<>();
        squad.add(player(200L, "GK", 60));
        squad.add(player(201L, "ST", 55));
        squad.add(player(202L, "ST", 55));
        squad.add(player(203L, "AMC", 55));
        squad.add(player(204L, "MC", 55));
        squad.add(player(205L, "MC", 55));
        squad.add(player(206L, "ML", 55));
        squad.add(player(207L, "MR", 55));
        squad.add(player(208L, "DC", 55));
        squad.add(player(209L, "DC", 55));
        squad.add(player(210L, "DL", 55));
        squad.add(player(211L, "DR", 55));
        squad.add(player(212L, "ST", 20));
        return squad;
    }

    private List<PlayerSkills> buildSkills(List<Human> homeSquad, List<Human> awaySquad) throws Exception {
        List<PlayerSkills> skills = new ArrayList<>();
        for (Human player : homeSquad) {
            int base = switch ((int) player.getId()) {
                case 101 -> 1;
                case 112 -> 20;
                case 100 -> 6;
                default -> 11;
            };
            skills.add(skills(player.getId(), player.getPosition(), base));
        }
        for (Human player : awaySquad) {
            int base = "GK".equals(player.getPosition()) ? 5 : 7;
            skills.add(skills(player.getId(), player.getPosition(), base));
        }
        return skills;
    }

    private PlayerSkills skills(long playerId, String position, int outfieldValue) throws Exception {
        PlayerSkills skills = new PlayerSkills();
        skills.setPlayerId(playerId);
        skills.setPosition(position);
        for (Field field : PlayerSkills.class.getDeclaredFields()) {
            if (field.getType() != int.class) continue;
            if ("id".equals(field.getName())) continue;
            field.setAccessible(true);
            int value = switch (field.getName()) {
                case "handling", "reflexes", "oneOnOnes", "commandOfArea", "kicking", "throwing" -> "GK".equals(position) ? 14 : 1;
                default -> outfieldValue;
            };
            field.setInt(skills, value);
        }
        return skills;
    }

    private Human player(long id, String position, double rating) {
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

    private Human manager(long id, long teamId, double off, double def, int reputation) {
        Human h = new Human();
        h.setId(id);
        h.setTeamId(teamId);
        h.setRetired(false);
        h.setOffensiveAbility(off);
        h.setDefensiveAbility(def);
        h.setManagerReputation(reputation);
        return h;
    }

    private static void inject(Class<?> type, Object target, String fieldName, Object value) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
