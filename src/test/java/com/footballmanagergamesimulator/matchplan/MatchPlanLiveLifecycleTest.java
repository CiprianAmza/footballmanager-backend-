package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LIVE lifecycle over ONE persisted plan row, with the real Spring
 * {@code @Transactional} proxy and fixture lock exercised (proxied service, not a
 * {@code new} + reflection wiring): prepare → resolve one due slot idempotently →
 * record a real substitution → finish, never regenerating the fixed schedule; plus
 * detached recovery and rebuilding the current on-pitch set from the snapshot.
 */
@DataJpaTest
@Import({MatchPlanService.class, MatchPlanningService.class, InstantMatchExecutor.class,
        ContributionResolver.class, MatchEngineConfig.class})
class MatchPlanLiveLifecycleTest {

    @Autowired private MatchPlanService service;
    @Autowired private MatchPlanRepository planRepository;
    @Autowired private MatchEventRepository eventRepository;
    @Autowired private CompetitionTeamInfoMatchRepository fixtureRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchAppearanceRepository appearanceRepository;
    @Autowired private com.footballmanagergamesimulator.repository.MatchSubstitutionRepository substitutionRepository;

    @MockBean private LineupAdapter lineupAdapter;
    @MockBean private com.footballmanagergamesimulator.user.UserContext userContext;

    private Contributor gk(long id) {
        return new Contributor(id, "GK" + id, "GK", 15.0, 0, 0, 0, 100.0, false, false);
    }

    private Contributor out(long id, String pos) {
        return new Contributor(id, "P" + id, pos, 15.0, 15, 15, 15, 100.0, false, false);
    }

    private Lineup normalXi(long base) {
        return new Lineup(List.of(
                out(base + 1, "GK"), out(base + 2, "DC"), out(base + 3, "MC"), out(base + 4, "MC"),
                out(base + 5, "AMR"), out(base + 6, "AML"), out(base + 7, "ST"), out(base + 8, "ST"),
                out(base + 9, "DL"), out(base + 10, "DR"), out(base + 11, "DC")), List.of());
    }

    /** Home XI = one outfielder (120) + ten keepers (weight 0), bench = star (199). */
    private Lineup gkFillerXiWithStarBench() {
        List<Contributor> starters = new ArrayList<>();
        starters.add(out(120, "ST"));
        for (long i = 130; i < 140; i++) starters.add(gk(i));
        return new Lineup(starters, List.of(out(199, "ST")), List.of());
    }

    private String newFixture() {
        CompetitionTeamInfoMatch f = new CompetitionTeamInfoMatch();
        f.setCompetitionId(100L);
        f.setSeasonNumber("1");
        f.setRound(5L);
        f.setTeam1Id(10L);
        f.setTeam2Id(20L);
        f = fixtureRepository.saveAndFlush(f);
        return MatchPlanService.competitionFixtureKey(f.getId());
    }

    @Test
    void prepareResolveFinish_onOnePlanRow_neverRegenerates() {
        String fx = newFixture();
        MatchPlan prepared = service.prepareLivePlan(fx, 100L, 1, 5, 10L, 20L,
                normalXi(100), normalXi(200), 2, 1);
        long planId = prepared.getId();
        assertEquals(MatchPlan.Status.PLANNED, prepared.getStatus());

        // Resolve every slot at its minute, against the kickoff starters.
        LivePlanSnapshot snap = service.loadLivePlanSnapshot(fx).orElseThrow();
        for (LivePlanSnapshot.SlotView s : snap.slots()) {
            Lineup lineup = new Lineup(snap.starters(s.teamId()), snap.bench(s.teamId()), List.of());
            service.resolveDueSlot(fx, 100L, 1, 5, s.slotIndex(), lineup.onPitchAt(s.minute()));
        }

        assertEquals(planId, planRepository.findByFixtureKey(fx).orElseThrow().getId(), "same plan row");
        assertEquals(MatchPlan.Status.IN_PROGRESS,
                planRepository.findByFixtureKey(fx).orElseThrow().getStatus());
        long goals = eventRepository.findByFixtureKey(fx).stream()
                .filter(e -> "goal".equals(e.getEventType())).count();
        assertEquals(3, goals, "2+1 canonical goals resolved");

        service.finishLivePlan(fx);
        MatchPlan finished = planRepository.findByFixtureKey(fx).orElseThrow();
        assertEquals(planId, finished.getId(), "finish never regenerates");
        assertEquals(MatchPlan.Status.COMPLETED, finished.getStatus());
        assertEquals(3, finished.getGoalSlots().stream().filter(GoalSlot::isResolved).count());
        assertEquals(22, appearanceRepository.findByMatchPlan(finished).size(), "11 per team, no subs");
    }

    @Test
    void resolveDueSlot_isIdempotent_noReassignOnReplay() {
        String fx = newFixture();
        service.prepareLivePlan(fx, 100L, 1, 5, 10L, 20L, normalXi(100), normalXi(200), 3, 0);
        LivePlanSnapshot snap = service.loadLivePlanSnapshot(fx).orElseThrow();
        LivePlanSnapshot.SlotView homeSlot = snap.regularTimeSlots(10L).get(0);

        List<Contributor> firstOnPitch = new Lineup(snap.starters(10L), List.of(), List.of())
                .onPitchAt(homeSlot.minute());
        List<MatchEvent> first = service.resolveDueSlot(fx, 100L, 1, 5, homeSlot.slotIndex(), firstOnPitch);
        long firstScorer = first.stream().filter(e -> "goal".equals(e.getEventType()))
                .findFirst().orElseThrow().getPlayerId();

        // Replay with a completely different on-pitch set — must NOT reassign.
        List<MatchEvent> replay = service.resolveDueSlot(fx, 100L, 1, 5, homeSlot.slotIndex(),
                List.of(out(777, "ST"), out(778, "ST")));
        long replayScorer = replay.stream().filter(e -> "goal".equals(e.getEventType()))
                .findFirst().orElseThrow().getPlayerId();

        assertEquals(firstScorer, replayScorer, "resolved slot is never reassigned");
        long goalEvents = eventRepository.findByFixtureKey(fx).stream()
                .filter(e -> "goal".equals(e.getEventType()) && e.getSlotIndex() == homeSlot.slotIndex()).count();
        assertEquals(1, goalEvents, "no duplicate goal event on replay");
    }

    @Test
    void substitute_scoresCanonicalGoal_afterEntering_viaLifecycleAndRecovery() {
        String fx = newFixture();
        service.prepareLivePlan(fx, 100L, 1, 5, 10L, 20L, gkFillerXiWithStarBench(), normalXi(200), 2, 0);

        // The user subs the lone outfielder (120) off for the star (199) at minute 1.
        service.recordLiveSubstitution(fx, 10L, 1, 120L, 199L);

        // Recovery: rebuild the current on-pitch set from the snapshot + the sub, and
        // resolve each home slot against it. The star (only non-GK on the pitch after
        // minute 1) must be the canonical scorer of every home goal.
        LivePlanSnapshot snap = service.loadLivePlanSnapshot(fx).orElseThrow();
        Lineup homeLive = snap.rebuildLineup(10L);
        for (LivePlanSnapshot.SlotView s : snap.regularTimeSlots(10L)) {
            List<MatchEvent> ev = service.resolveDueSlot(fx, 100L, 1, 5, s.slotIndex(),
                    homeLive.onPitchAt(s.minute()));
            long scorer = ev.stream().filter(e -> "goal".equals(e.getEventType()))
                    .findFirst().orElseThrow().getPlayerId();
            assertEquals(199L, scorer, "the substitute is credited with the goal at minute " + s.minute());
        }

        // The subbed-off outfielder enters nothing and the substitute is fielded in his slot.
        Lineup.SubMove move = homeLive.getSubs().get(0);
        assertEquals(199L, move.on().playerId());
        assertEquals("ST", move.on().position(), "substitute fielded in the vacated ST slot");
    }

    @Test
    void loadLivePlanSnapshot_isDetached_andRecoversResolvedGoalsWithoutReplay() {
        String fx = newFixture();
        service.prepareLivePlan(fx, 100L, 1, 5, 10L, 20L, normalXi(100), normalXi(200), 2, 1);
        LivePlanSnapshot before = service.loadLivePlanSnapshot(fx).orElseThrow();
        LivePlanSnapshot.SlotView homeSlot = before.regularTimeSlots(10L).get(0);
        service.resolveDueSlot(fx, 100L, 1, 5, homeSlot.slotIndex(),
                new Lineup(before.starters(10L), List.of(), List.of()).onPitchAt(homeSlot.minute()));

        LivePlanSnapshot after = service.loadLivePlanSnapshot(fx).orElseThrow();
        // Detached record graph: readable with no open transaction / lazy proxy.
        assertEquals(3, after.slots().size());
        assertEquals(22, after.participants().size());
        assertEquals(1, after.resolvedGoals(10L), "the resolved home goal is recovered");
        long unresolved = after.slots().stream().filter(s -> !s.resolved()).count();
        assertEquals(2, unresolved, "future slots stay unresolved");
    }

    @Test
    void knockoutExtraTime_rebuildsPlanWithEtSlots_preservingRegularTimeAndSubs() {
        String fx = newFixture();
        // Home kickoff has a bench (199) so a real substitution can bring him on.
        Lineup homeKickoff = new Lineup(normalXi(100).getStartingXI(), List.of(out(199, "ST")), List.of());
        service.prepareLivePlan(fx, 100L, 1, 5, 10L, 20L, homeKickoff, normalXi(200), 1, 1); // 1-1 at 90'
        // Play: resolve the regular-time goals and record a substitution.
        LivePlanSnapshot snap = service.loadLivePlanSnapshot(fx).orElseThrow();
        for (LivePlanSnapshot.SlotView s : snap.slots()) {
            Lineup l = new Lineup(snap.starters(s.teamId()), snap.bench(s.teamId()), List.of());
            service.resolveDueSlot(fx, 100L, 1, 5, s.slotIndex(), l.onPitchAt(s.minute()));
        }
        service.recordLiveSubstitution(fx, 10L, 70, 111L, 199L);

        // Regular-time scorers as the user watched them (before ET is appended).
        List<Long> regularScorersBefore = eventRepository.findByFixtureKey(fx).stream()
                .filter(e -> "goal".equals(e.getEventType()))
                .map(com.footballmanagergamesimulator.model.MatchEvent::getPlayerId).sorted().toList();

        // Extra time decided 1-0 to home, no shootout — APPENDED to the same plan.
        service.appendExtraTimeAndFinalize(fx, 100L, 1, 5, 10L, 20L, 1, 0, -1, -1);
        service.markCommitted(fx);

        MatchPlan plan = planRepository.findByFixtureKey(fx).orElseThrow();
        assertTrue(plan.hadExtraTime());
        assertEquals(MatchPlan.Status.COMMITTED, plan.getStatus());
        assertEquals(3, plan.getGoalSlots().size(), "2 regular-time + 1 extra-time slot");
        assertEquals(1, plan.getGoalSlots().stream()
                .filter(s -> s.getPhase() == GoalPhase.EXTRA_TIME).count(), "one ET slot");
        assertEquals(1, eventRepository.findByFixtureKey(fx).stream()
                .filter(e -> "goal".equals(e.getEventType()) && e.getMinute() >= 91).count(),
                "the ET goal is a canonical event in minutes 91-120");
        assertEquals(3, eventRepository.findByFixtureKey(fx).stream()
                .filter(e -> "goal".equals(e.getEventType())).count(), "2 regular + 1 ET goal");
        // The regular-time scorers are PRESERVED (append, not delete/regenerate).
        List<Long> regularScorersAfter = eventRepository.findByFixtureKey(fx).stream()
                .filter(e -> "goal".equals(e.getEventType()) && e.getMinute() < 91)
                .map(com.footballmanagergamesimulator.model.MatchEvent::getPlayerId).sorted().toList();
        assertEquals(regularScorersBefore, regularScorersAfter, "regular-time scorers not re-resolved");
        // The substitution recorded during play survives.
        assertEquals(1, substitutionRepository.findByMatchPlanOrderByTeamIdAscSubIndexAsc(plan).stream()
                .filter(s -> s.getTeamId() == 10L).count(), "recorded sub preserved");
    }

    @Test
    void finishLivePlan_refusesUnresolvedScoringSlots() {
        String fx = newFixture();
        service.prepareLivePlan(fx, 100L, 1, 5, 10L, 20L, normalXi(100), normalXi(200), 2, 1); // 3 slots
        // Resolve only ONE of the three slots.
        LivePlanSnapshot snap = service.loadLivePlanSnapshot(fx).orElseThrow();
        LivePlanSnapshot.SlotView one = snap.slots().get(0);
        Lineup l = new Lineup(snap.starters(one.teamId()), snap.bench(one.teamId()), List.of());
        service.resolveDueSlot(fx, 100L, 1, 5, one.slotIndex(), l.onPitchAt(one.minute()));

        assertThrows(IllegalStateException.class, () -> service.finishLivePlan(fx),
                "cannot finalize with unresolved scoring slots");
        assertEquals(MatchPlan.Status.IN_PROGRESS, planRepository.findByFixtureKey(fx).orElseThrow().getStatus());
    }

    @Test
    void mutationOnTerminalPlan_isRefused() {
        String fx = newFixture();
        service.prepareLivePlan(fx, 100L, 1, 5, 10L, 20L, normalXi(100), normalXi(200), 1, 0); // 1 slot
        LivePlanSnapshot snap = service.loadLivePlanSnapshot(fx).orElseThrow();
        LivePlanSnapshot.SlotView s = snap.slots().get(0);
        Lineup l = new Lineup(snap.starters(s.teamId()), snap.bench(s.teamId()), List.of());
        service.resolveDueSlot(fx, 100L, 1, 5, s.slotIndex(), l.onPitchAt(s.minute()));
        service.finishLivePlan(fx);   // COMPLETED
        service.markCommitted(fx);    // COMMITTED (terminal)

        assertThrows(IllegalStateException.class,
                () -> service.recordLiveSubstitution(fx, 10L, 80, 111L, 199L),
                "no substitutions on a committed plan");
    }

    @Test
    void recordLiveSubstitution_assignsConsecutivePerTeamSubIndex() {
        String fx = newFixture();
        service.prepareLivePlan(fx, 100L, 1, 5, 10L, 20L, normalXi(100), normalXi(200), 1, 0);
        service.recordLiveSubstitution(fx, 10L, 60, 107L, 199L);
        service.recordLiveSubstitution(fx, 10L, 75, 108L, 198L);
        service.recordLiveSubstitution(fx, 20L, 70, 207L, 299L);

        MatchPlan plan = planRepository.findByFixtureKey(fx).orElseThrow();
        List<Integer> homeIdx = new ArrayList<>();
        substitutionRepository.findByMatchPlanOrderByTeamIdAscSubIndexAsc(plan).stream()
                .filter(s -> s.getTeamId() == 10L).forEach(s -> homeIdx.add(s.getSubIndex()));
        assertEquals(List.of(0, 1), homeIdx, "consecutive per-team sub index");
        long awaySubs = substitutionRepository.findByMatchPlanOrderByTeamIdAscSubIndexAsc(plan).stream()
                .filter(s -> s.getTeamId() == 20L).count();
        assertEquals(1, awaySubs);
    }
}
