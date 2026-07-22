package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.MatchAppearanceRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchParticipantRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import com.footballmanagergamesimulator.repository.MatchSubstitutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cold persistence round trip: after {@code buildAndPersist}, flush + clear the
 * persistence context, reload the plan, participants, substitutions and events
 * from their repositories, rebuild both lineups ONLY from the reloaded rows, and
 * re-execute using the PERSISTED plan/slots (not a freshly planned one). The
 * re-executed timeline must be byte-for-byte identical, in order, to the stored
 * canonical events — goals and assists — proving the persisted timeline fully
 * reproduces the match.
 */
@DataJpaTest
class MatchPlanReloadTest {

    @Autowired private MatchPlanRepository planRepository;
    @Autowired private MatchEventRepository eventRepository;
    @Autowired private MatchParticipantRepository participantRepository;
    @Autowired private MatchSubstitutionRepository substitutionRepository;
    @Autowired private MatchAppearanceRepository appearanceRepository;
    @Autowired private CompetitionTeamInfoMatchRepository fixtureRepository;
    @Autowired private TestEntityManager entityManager;

    private final MatchEngineConfig cfg = new MatchEngineConfig();
    private final MatchPlanningService planning = new MatchPlanningService(cfg);
    private final InstantMatchExecutor executor = new InstantMatchExecutor(new ContributionResolver(cfg));

    private MatchPlanService service;

    private Contributor c(long id, String pos) {
        return new Contributor(id, "P" + id, pos, 12.0 + (id % 7), 10 + (int) (id % 9),
                10 + (int) (id % 6), 10 + (int) (id % 5), 100.0, false, false);
    }

    private List<Contributor> xi(long base) {
        return new ArrayList<>(List.of(
                c(base, "GK"), c(base + 1, "DC"), c(base + 2, "DC"), c(base + 3, "DL"),
                c(base + 4, "DR"), c(base + 5, "MC"), c(base + 6, "MC"), c(base + 7, "AML"),
                c(base + 8, "AMR"), c(base + 9, "ST"), c(base + 10, "ST")));
    }

    @BeforeEach
    void setup() {
        Lineup home = new Lineup(xi(100), List.of(c(120, "ST"), c(121, "MC")),
                List.of(new Lineup.SubMove(0, 60, 109L, c(120, "ST"))));
        Lineup away = new Lineup(xi(200), List.of(c(220, "ST")), List.of());

        LineupAdapter adapter = mock(LineupAdapter.class);
        when(adapter.build(eq(10L), any(), anyLong())).thenReturn(home);
        when(adapter.build(eq(20L), any(), anyLong())).thenReturn(away);

        service = new MatchPlanService();
        ReflectionTestUtils.setField(service, "planningService", planning);
        ReflectionTestUtils.setField(service, "lineupAdapter", adapter);
        ReflectionTestUtils.setField(service, "instantExecutor", executor);
        ReflectionTestUtils.setField(service, "matchEventRepository", eventRepository);
        ReflectionTestUtils.setField(service, "matchPlanRepository", planRepository);
        ReflectionTestUtils.setField(service, "matchAppearanceRepository", appearanceRepository);
        ReflectionTestUtils.setField(service, "matchParticipantRepository", participantRepository);
        ReflectionTestUtils.setField(service, "matchSubstitutionRepository", substitutionRepository);
        ReflectionTestUtils.setField(service, "fixtureRepository", fixtureRepository);
        ReflectionTestUtils.setField(service, "engineConfig", cfg);
    }

    @Test
    void coldReload_reproducesCanonicalEventsExactly() {
        CompetitionTeamInfoMatch fixture = new CompetitionTeamInfoMatch();
        fixture.setCompetitionId(100L);
        fixture.setSeasonNumber("1");
        fixture.setRound(5L);
        fixture.setTeam1Id(10L);
        fixture.setTeam2Id(20L);
        fixture = fixtureRepository.saveAndFlush(fixture);
        String fixtureKey = MatchPlanService.competitionFixtureKey(fixture.getId());

        service.buildAndPersist(fixtureKey, 100L, 1, 5, 10L, 20L, "4-4-2", "4-4-2", 3, 2);

        // Cold boundary: nothing below may reuse an in-memory entity.
        entityManager.flush();
        entityManager.clear();

        MatchPlan plan = planRepository.findByFixtureKey(fixtureKey).orElseThrow();
        Lineup home = rebuild(plan, 10L);
        Lineup away = rebuild(plan, 20L);

        // Re-execute on the persisted plan (slots already resolved) — no fresh planning.
        List<MatchEvent> reExecuted = executor.execute(plan, home, away,
                new InstantMatchExecutor.MatchContext(fixtureKey, 100L, 1, 5));

        List<MatchEvent> stored =
                eventRepository.findByFixtureKeyOrderBySlotIndexAscEventOrderAsc(fixtureKey);

        // Full ordered identity — including assists; order is part of the contract.
        assertFalse(stored.isEmpty());
        assertEquals(identity(stored), identity(reExecuted));
    }

    private List<String> identity(List<MatchEvent> events) {
        List<String> ids = new ArrayList<>();
        for (MatchEvent e : events) {
            ids.add(e.getSlotIndex() + "|" + e.getEventOrder() + "|" + e.getEventType()
                    + "|" + e.getMinute() + "|" + e.getTeamId() + "|" + e.getPlayerId()
                    + "|" + e.getPlayerName());
        }
        return ids;
    }

    private Lineup rebuild(MatchPlan plan, long teamId) {
        List<MatchParticipant> parts = participantRepository
                .findByMatchPlanOrderByTeamIdAscParticipantIndexAsc(plan).stream()
                .filter(p -> p.getTeamId() == teamId).toList();
        Map<Long, Contributor> byId = new LinkedHashMap<>();
        List<Contributor> starters = new ArrayList<>();
        List<Contributor> bench = new ArrayList<>();
        for (MatchParticipant p : parts) {
            Contributor c = p.toContributor();
            byId.put(c.playerId(), c);
            (p.isStarter() ? starters : bench).add(c);
        }
        List<Lineup.SubMove> subs = new ArrayList<>();
        substitutionRepository.findByMatchPlanOrderByTeamIdAscSubIndexAsc(plan).stream()
                .filter(s -> s.getTeamId() == teamId)
                .forEach(s -> subs.add(new Lineup.SubMove(s.getSubIndex(), s.getMinute(),
                        s.getOffPlayerId(), byId.get(s.getOnPlayerId()))));
        return new Lineup(starters, bench, subs);
    }
}
