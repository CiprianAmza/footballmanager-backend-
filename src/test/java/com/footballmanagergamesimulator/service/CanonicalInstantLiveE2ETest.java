package com.footballmanagergamesimulator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.matchplan.Contributor;
import com.footballmanagergamesimulator.matchplan.InstantMatchExecutor;
import com.footballmanagergamesimulator.matchplan.Lineup;
import com.footballmanagergamesimulator.matchplan.LineupAdapter;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.matchplan.MatchPlanningService;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.LiveCommitContextRepository;
import com.footballmanagergamesimulator.repository.MatchAnimationRecipeRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.TacticalScoreService.Matchup;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import com.footballmanagergamesimulator.user.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Production-boundary proof for the canonical engine. The reference side uses the real
 * instant executor; the watched side uses the real live session plus the real proxied,
 * transactional MatchPlanService and repositories. Both consume the same fixture identity,
 * seed, score and kickoff lineups. With no substitutions available, the complete goal
 * timeline (minute, side and scorer) must be identical, and the live display must equal the
 * events durably persisted for that fixture.
 */
@DataJpaTest
@Import({MatchPlanService.class, MatchPlanningService.class, InstantMatchExecutor.class,
        com.footballmanagergamesimulator.matchplan.ContributionResolver.class, MatchEngineConfig.class})
class CanonicalInstantLiveE2ETest {

    private static final long HOME = 10L;
    private static final long AWAY = 20L;
    private static final long COMPETITION = 100L;
    private static final int SEASON = 3;
    private static final int ROUND = 7;

    @Autowired private MatchPlanService matchPlanService;
    @Autowired private MatchPlanningService planningService;
    @Autowired private InstantMatchExecutor instantExecutor;
    @Autowired private MatchEngineConfig engineConfig;
    @Autowired private MatchEventRepository eventRepository;
    @Autowired private MatchPlanRepository planRepository;
    @Autowired private CompetitionTeamInfoMatchRepository fixtureRepository;
    @Autowired private MatchAnimationRecipeRepository animationRecipeRepository;
    @Autowired private LiveCommitContextRepository liveCommitContextRepository;

    @MockBean private LineupAdapter lineupAdapter;
    @MockBean private UserContext userContext;

    private Lineup homeLineup;
    private Lineup awayLineup;
    private HumanRepository humanRepository;
    private TeamRepository teamRepository;
    private CompetitionRepository competitionRepository;
    private PlayerSkillsRepository playerSkillsRepository;

    @BeforeEach
    void setUp() {
        engineConfig.getMatchPlan().setEnabled(true);
        engineConfig.getTacticalModel().setEnabled(true);

        homeLineup = lineup(100L);
        awayLineup = lineup(200L);
        when(userContext.isHumanTeam(anyLong())).thenReturn(false);
        when(lineupAdapter.build(eq(HOME), any(), anyLong(), eq(LineupAdapter.Mode.AI_INSTANT)))
                .thenReturn(new LineupAdapter.Result(homeLineup, LineupAdapter.Source.AI_INSTANT));
        when(lineupAdapter.build(eq(AWAY), any(), anyLong(), eq(LineupAdapter.Mode.AI_INSTANT)))
                .thenReturn(new LineupAdapter.Result(awayLineup, LineupAdapter.Source.AI_INSTANT));

        humanRepository = mock(HumanRepository.class);
        teamRepository = mock(TeamRepository.class);
        competitionRepository = mock(CompetitionRepository.class);
        playerSkillsRepository = mock(PlayerSkillsRepository.class);
        when(humanRepository.findAllByTeamIdAndTypeId(HOME, 1L)).thenReturn(squad(100L));
        when(humanRepository.findAllByTeamIdAndTypeId(AWAY, 1L)).thenReturn(squad(200L));
        when(teamRepository.findNameById(HOME)).thenReturn("Home FC");
        when(teamRepository.findNameById(AWAY)).thenReturn("Away FC");
        when(competitionRepository.findNameById(COMPETITION)).thenReturn("Test League");
        when(playerSkillsRepository.findAllByPlayerIdIn(any())).thenReturn(Collections.emptyList());
    }

    @Test
    void sameFixture_instantAndLiveProduceIdenticalCanonicalGoalsAndDurableDisplay() {
        CompetitionTeamInfoMatch fixture = new CompetitionTeamInfoMatch();
        fixture.setCompetitionId(COMPETITION);
        fixture.setSeasonNumber(String.valueOf(SEASON));
        fixture.setRound(ROUND);
        fixture.setTeam1Id(HOME);
        fixture.setTeam2Id(AWAY);
        fixture = fixtureRepository.saveAndFlush(fixture);

        String fixtureKey = MatchPlanService.competitionFixtureKey(fixture.getId());
        long seed = MatchPlanService.seedFor(fixtureKey, COMPETITION, SEASON, ROUND, HOME, AWAY);
        MatchPlan instantPlan = planningService.plan(fixtureKey, seed, HOME, AWAY, 3, 2);
        List<MatchEvent> instantEvents = instantExecutor.execute(instantPlan, homeLineup, awayLineup,
                new InstantMatchExecutor.MatchContext(fixtureKey, COMPETITION, SEASON, ROUND));

        LiveMatchSimulationService liveService = liveService();
        TacticalScoreService tacticalScoreService = new TacticalScoreService();
        ReflectionTestUtils.setField(tacticalScoreService, "engineConfig", engineConfig);
        Matchup matchup = tacticalScoreService.matchup(
                new TeamProfile(70, 60), new TacticVector(0.2, 0.1, 0.0),
                new TeamProfile(65, 55), new TacticVector(-0.1, 0.2, 0.1));

        LiveMatchSession live = liveService.createInteractiveSession(
                HOME, AWAY, 100.0, 90.0, COMPETITION, SEASON, ROUND,
                false, matchup, 3, 2, fixture.getId(), "442", "442");
        assertTrue(live.isCanonicalPlanBound());
        live.advanceUntilAndSnapshot(live.getTotalMinutes());

        List<GoalKey> expected = goalKeys(instantEvents);
        List<GoalKey> persisted = goalKeys(eventRepository.findByFixtureKey(fixtureKey));
        List<GoalKey> displayed = live.snapshot().getTimeline().stream()
                .filter(e -> "goal".equals(e.getEventType()))
                .map(e -> new GoalKey(e.getMinute(), e.getTeamId(), e.getPlayerId()))
                .toList();

        // Full canonical contribution set (goals AND assists), ordered by slot then event order.
        // The live goal timeline does not surface a separate assist row, so the durable MatchEvent
        // store is the source of truth for assists; instant and live must persist identical ones.
        List<ContributionKey> expectedContrib = contributionKeys(instantEvents);
        List<ContributionKey> persistedContrib = contributionKeys(eventRepository.findByFixtureKey(fixtureKey));

        assertEquals(3, live.getHomeScore());
        assertEquals(2, live.getAwayScore());
        assertEquals(expected, persisted, "instant and live resolve the same canonical slots");
        assertEquals(persisted, displayed, "the UI displays exactly the durable canonical goals");
        assertEquals(expectedContrib, persistedContrib,
                "instant and live persist identical canonical goals AND assists");
        assertTrue(persistedContrib.size() >= persisted.size(),
                "contribution set includes the goal events");
        assertTrue(persistedContrib.stream().anyMatch(c -> "assist".equals(c.eventType())),
                "at least one canonical assist was resolved and compared");

        matchPlanService.finishLivePlan(fixtureKey);
        assertEquals(MatchPlan.Status.COMPLETED,
                planRepository.findByFixtureKey(fixtureKey).orElseThrow().getStatus());
    }

    private LiveMatchSimulationService liveService() {
        LiveMatchSimulationService service = new LiveMatchSimulationService();
        ReflectionTestUtils.setField(service, "humanRepository", humanRepository);
        ReflectionTestUtils.setField(service, "teamRepository", teamRepository);
        ReflectionTestUtils.setField(service, "competitionRepository", competitionRepository);
        ReflectionTestUtils.setField(service, "playerSkillsRepository", playerSkillsRepository);
        ReflectionTestUtils.setField(service, "matchEventRepository", eventRepository);
        ReflectionTestUtils.setField(service, "goalAnimationService", mock(GoalAnimationService.class));
        ReflectionTestUtils.setField(service, "engineConfig", engineConfig);
        ReflectionTestUtils.setField(service, "matchPlanService", matchPlanService);
        ReflectionTestUtils.setField(service, "fixtureRepositoryForRecovery", fixtureRepository);
        ReflectionTestUtils.setField(service, "liveCommitContextRepository", liveCommitContextRepository);
        ReflectionTestUtils.setField(service, "matchAnimationRecipeRepository", animationRecipeRepository);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "playerValueService", mock(PlayerValueService.class));
        ReflectionTestUtils.setField(service, "matchSimulationService", mock(MatchSimulationService.class));
        return service;
    }

    private List<GoalKey> goalKeys(List<MatchEvent> events) {
        return events.stream()
                .filter(e -> "goal".equals(e.getEventType()))
                .sorted(java.util.Comparator.comparingInt(MatchEvent::getSlotIndex))
                .map(e -> new GoalKey(e.getMinute(), e.getTeamId(), e.getPlayerId()))
                .toList();
    }

    /** Goal AND assist events, canonically ordered (slot, then event order within a slot). */
    private List<ContributionKey> contributionKeys(List<MatchEvent> events) {
        return events.stream()
                .filter(e -> "goal".equals(e.getEventType()) || "assist".equals(e.getEventType()))
                .sorted(java.util.Comparator.comparingInt(MatchEvent::getSlotIndex)
                        .thenComparingInt(MatchEvent::getEventOrder))
                .map(e -> new ContributionKey(e.getMinute(), e.getTeamId(), e.getPlayerId(), e.getEventType()))
                .toList();
    }

    private Lineup lineup(long base) {
        List<Contributor> xi = new ArrayList<>();
        String[] positions = {"GK", "DC", "DC", "DL", "DR", "MC", "MC", "AML", "AMR", "ST", "ST"};
        for (int i = 0; i < positions.length; i++) {
            xi.add(new Contributor(base + i, "P" + (base + i), positions[i],
                    15.0 + i / 10.0, 12 + i % 4, 13 + i % 3, 12 + i % 5,
                    100.0, false, false));
        }
        return new Lineup(xi, List.of(), List.of());
    }

    private List<Human> squad(long base) {
        List<Human> result = new ArrayList<>();
        for (Contributor contributor : lineup(base).getStartingXI()) {
            Human human = new Human();
            human.setId(contributor.playerId());
            human.setName(contributor.name());
            human.setPosition(contributor.position());
            human.setRating(contributor.rating());
            human.setFitness(100.0);
            human.setRetired(false);
            result.add(human);
        }
        return result;
    }

    private record GoalKey(int minute, long teamId, long playerId) {}

    private record ContributionKey(int minute, long teamId, long playerId, String eventType) {}
}
