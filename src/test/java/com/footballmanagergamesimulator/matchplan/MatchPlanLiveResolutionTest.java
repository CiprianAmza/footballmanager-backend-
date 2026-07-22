package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Persistence-level guarantees of the LIVE resolution entry point
 * ({@link MatchPlanService#buildAndPersistLive}): the canonical plan is reused
 * across refresh/retry (never duplicated or reassigned), the user's real
 * substitutions land in the canonical substitution + appearance timeline, and a
 * penalty-decided knockout records no goal for its shootout kicks.
 */
@DataJpaTest
class MatchPlanLiveResolutionTest {

    @Autowired private MatchPlanRepository planRepository;
    @Autowired private MatchEventRepository eventRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchAppearanceRepository appearanceRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchParticipantRepository participantRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchSubstitutionRepository substitutionRepository;

    private MatchPlanService service;

    private Contributor p(long id, String pos) {
        return new Contributor(id, "P" + id, pos, 15.0, 15, 15, 15, 100.0, false, false);
    }

    private List<Contributor> startingXI(long base) {
        return List.of(
                p(base + 1, "GK"), p(base + 2, "DC"), p(base + 3, "MC"), p(base + 4, "MC"),
                p(base + 5, "AMR"), p(base + 6, "AML"), p(base + 7, "ST"), p(base + 8, "ST"),
                p(base + 9, "DL"), p(base + 10, "DR"), p(base + 11, "DC"));
    }

    private Lineup live(long base) {
        return new Lineup(startingXI(base), List.of());
    }

    /** Home team with one real user substitution: {@code base+11} off for the bench
     *  player {@code base+99} at {@code minute}. */
    private Lineup liveWithUserSub(long base, int minute) {
        Contributor in = p(base + 99, "ST");
        return LiveLineupFactory.build(startingXI(base), List.of(in),
                List.of(new LiveLineupFactory.UserSub(base + 11, in, minute)));
    }

    @BeforeEach
    void setup() {
        MatchEngineConfig cfg = new MatchEngineConfig();
        MatchPlanningService planning = new MatchPlanningService(cfg);
        InstantMatchExecutor executor = new InstantMatchExecutor(new ContributionResolver(cfg));
        CompetitionTeamInfoMatchRepository fixtureRepository = mock(CompetitionTeamInfoMatchRepository.class);
        when(fixtureRepository.findByIdForUpdate(anyLong()))
                .thenReturn(Optional.of(new CompetitionTeamInfoMatch()));

        service = new MatchPlanService();
        ReflectionTestUtils.setField(service, "planningService", planning);
        // The live path never calls the adapter or the user-context; supply mocks so the
        // service is fully wired but assert they are untouched.
        ReflectionTestUtils.setField(service, "lineupAdapter", mock(LineupAdapter.class));
        ReflectionTestUtils.setField(service, "instantExecutor", executor);
        ReflectionTestUtils.setField(service, "matchEventRepository", eventRepository);
        ReflectionTestUtils.setField(service, "matchPlanRepository", planRepository);
        ReflectionTestUtils.setField(service, "matchAppearanceRepository", appearanceRepository);
        ReflectionTestUtils.setField(service, "matchParticipantRepository", participantRepository);
        ReflectionTestUtils.setField(service, "matchSubstitutionRepository", substitutionRepository);
        ReflectionTestUtils.setField(service, "fixtureRepository", fixtureRepository);
        ReflectionTestUtils.setField(service, "userContext",
                mock(com.footballmanagergamesimulator.user.UserContext.class));
        ReflectionTestUtils.setField(service, "engineConfig", cfg);
    }

    @Test
    void liveRefresh_reusesThePlan_noDuplicateSlotsOrEvents() {
        List<MatchEvent> first = service.buildAndPersistLive(
                "CTIM:42", 100L, 1, 5, 10L, 20L, live(100), live(200), 2, 1);
        List<MatchEvent> second = service.buildAndPersistLive(
                "CTIM:42", 100L, 1, 5, 10L, 20L, live(100), live(200), 2, 1);

        assertEquals(1, planRepository.count(), "exactly one plan on refresh");
        MatchPlan plan = planRepository.findByFixtureKey("CTIM:42").orElseThrow();
        assertEquals(3, plan.getGoalSlots().size(), "2+1 goal slots, not duplicated");
        assertEquals(first.size(), second.size());
        assertEquals(first.size(), eventRepository.findByFixtureKey("CTIM:42").size());

        Set<String> keys = new HashSet<>();
        for (MatchEvent e : eventRepository.findByFixtureKey("CTIM:42")) {
            assertTrue(keys.add(e.getSlotIndex() + "|" + e.getEventType()),
                    "duplicate event " + e.getSlotIndex() + "/" + e.getEventType());
        }
    }

    @Test
    void liveUserSub_recordedInCanonicalSubstitutionAndAppearanceTimeline() {
        service.buildAndPersistLive("CTIM:60", 100L, 1, 5, 10L, 20L,
                liveWithUserSub(100, 60), live(200), 2, 1);

        MatchPlan plan = planRepository.findByFixtureKey("CTIM:60").orElseThrow();

        List<MatchSubstitution> homeSubs = substitutionRepository
                .findByMatchPlanOrderByTeamIdAscSubIndexAsc(plan).stream()
                .filter(s -> s.getTeamId() == 10L).toList();
        assertEquals(1, homeSubs.size(), "exactly the user's one substitution");
        MatchSubstitution sub = homeSubs.get(0);
        assertEquals(0, sub.getSubIndex(), "consecutive per-team sequence starts at 0");
        assertEquals(60, sub.getMinute());
        assertEquals(111L, sub.getOffPlayerId());
        assertEquals(199L, sub.getOnPlayerId());

        // Appearances derived from the ACTUAL timeline: the subbed-off player stops at
        // 60', the substitute plays the remaining 30'.
        List<MatchAppearance> apps = appearanceRepository.findByMatchPlan(plan);
        MatchAppearance off = apps.stream()
                .filter(a -> a.getTeamId() == 10L && a.getPlayerId() == 111L).findFirst().orElseThrow();
        MatchAppearance on = apps.stream()
                .filter(a -> a.getTeamId() == 10L && a.getPlayerId() == 199L).findFirst().orElseThrow();
        assertEquals(60, off.getMinutesPlayed());
        assertEquals(60, on.getStartMinute());
        assertEquals(30, on.getMinutesPlayed());

        // The away team, which made no substitutions, has none recorded.
        assertTrue(substitutionRepository.findByMatchPlanOrderByTeamIdAscSubIndexAsc(plan).stream()
                .noneMatch(s -> s.getTeamId() == 20L), "no invented subs for the AI/away side");
    }

    @Test
    void penaltyDecidedKnockout_createsNoGoalForShootoutKicks() {
        // 1-1 after 90', 0-0 in extra time, decided 5-3 on penalties.
        service.buildAndPersistLive("CTIM:77", 100L, 1, 5, 10L, 20L,
                live(100), live(200), 1, 1, 0, 0, 5, 3);

        MatchPlan plan = planRepository.findByFixtureKey("CTIM:77").orElseThrow();
        assertEquals(5, plan.getHomeShootout());
        assertEquals(3, plan.getAwayShootout());
        assertEquals(2, plan.getGoalSlots().size(), "only the two regular-time goals are scoring slots");

        long goalEvents = eventRepository.findByFixtureKey("CTIM:77").stream()
                .filter(e -> "goal".equals(e.getEventType())).count();
        assertEquals(2, goalEvents, "shootout kicks add no goal events");
    }

    @Test
    void committedLivePlan_isImmutableOnRefresh() {
        service.buildAndPersistLive("CTIM:8", 100L, 1, 5, 10L, 20L, live(100), live(200), 1, 0);
        service.markCommitted("CTIM:8");
        assertEquals(MatchPlan.Status.COMMITTED,
                planRepository.findByFixtureKey("CTIM:8").orElseThrow().getStatus());

        long eventsBefore = eventRepository.findByFixtureKey("CTIM:8").size();
        service.buildAndPersistLive("CTIM:8", 100L, 1, 5, 10L, 20L, live(100), live(200), 1, 0);

        assertEquals(MatchPlan.Status.COMMITTED,
                planRepository.findByFixtureKey("CTIM:8").orElseThrow().getStatus());
        assertEquals(1, planRepository.count(), "a committed match is never rebuilt");
        assertEquals(eventsBefore, eventRepository.findByFixtureKey("CTIM:8").size());
    }
}
