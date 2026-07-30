package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.frontend.LiveMatchData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GATE 2 — retry / recovery (CHARACTERIZATION half).
 *
 * <p>Duplicate {@code /advance(untilMinute=N)}, a lost response and cold recovery at N
 * are all invariants that exist TODAY, so they are pinned here as characterization. The
 * ambient half of the gate — "the same ambient segment is regenerated for N" — has no
 * implementation yet and lives in {@code AmbientDeterminismRecoveryAcceptanceTest}
 * (tagged {@code faza2-acceptance}).
 *
 * <p>What is pinned: no duplicate event, no duplicate canonical slot resolution, no
 * duplicate animation recipe row, and — after a cold recovery at N — the same future
 * canonical goals and the same eligible-scorer candidate sets as an uninterrupted run.
 */
class LiveAdvanceRetryRecoveryCharacterizationTest {

    @Test
    void duplicateAdvanceToTheSameMinute_isANoOp() throws Exception {
        Faza2GateHarness h = new Faza2GateHarness();
        LiveMatchSession session = h.start();

        LiveMatchData first = session.advanceUntilAndSnapshot(30);
        String fingerprint = Faza2GateHarness.canonicalFingerprint(first);
        int resolveCallsAfterFirst = h.resolveCalls.size();
        Long rngAfterFirst = Faza2GateHarness.liveRandomState(session);

        // The client never saw the response and repeats the exact same request.
        LiveMatchData retry = session.advanceUntilAndSnapshot(30);

        assertEquals(fingerprint, Faza2GateHarness.canonicalFingerprint(retry),
                "a duplicate /advance to an already-reached minute changes nothing");
        assertEquals(resolveCallsAfterFirst, h.resolveCalls.size(),
                "no canonical slot is resolved a second time");
        assertEquals(rngAfterFirst, Faza2GateHarness.liveRandomState(session),
                "a duplicate advance consumes no live RNG");
        assertEquals(30, session.currentMinute);
        assertNoDuplicateGoals(retry);
    }

    @Test
    void outOfOrderAdvanceToAnEarlierMinute_isANoOp() throws Exception {
        Faza2GateHarness h = new Faza2GateHarness();
        LiveMatchSession session = h.start();
        session.advanceUntilAndSnapshot(50);
        String fingerprint = Faza2GateHarness.canonicalFingerprint(session.snapshot());
        int resolveCalls = h.resolveCalls.size();

        LiveMatchData stale = session.advanceUntilAndSnapshot(20); // stale/out-of-order request

        assertEquals(fingerprint, Faza2GateHarness.canonicalFingerprint(stale),
                "an out-of-order advance never rewinds the engine");
        assertEquals(50, session.currentMinute);
        assertEquals(resolveCalls, h.resolveCalls.size(), "no slot is re-resolved");
    }

    @Test
    void coldRecoveryAtN_thenContinue_keepsTheSameFutureGoalsAndEligibleScorers() throws Exception {
        // Control: one uninterrupted run.
        Faza2GateHarness control = new Faza2GateHarness();
        LiveMatchSession controlSession = control.start();
        LiveMatchData controlResult = controlSession.advanceUntilAndSnapshot(controlSession.getTotalMinutes());

        // Same fixture, crashed at 50' and cold-recovered from the persisted checkpoint.
        Faza2GateHarness crashed = new Faza2GateHarness();
        LiveMatchSession before = crashed.start();
        before.advanceUntilAndSnapshot(50);
        int scoreAtCrash = before.getHomeScore() + before.getAwayScore();
        assertNotNull(crashed.checkpointJson(), "the crash point was checkpointed");

        crashed.dropInMemorySessions(); // backend restart
        LiveMatchSession recovered = crashed.coldRecover();
        assertNotNull(recovered, "a non-committed canonical fixture is recoverable");
        assertEquals(50, recovered.currentMinute, "recovery resumes at the checkpoint minute");
        assertEquals(scoreAtCrash, recovered.getHomeScore() + recovered.getAwayScore(),
                "pre-checkpoint goals are restored, not replayed");

        LiveMatchData recoveredResult = recovered.advanceUntilAndSnapshot(recovered.getTotalMinutes());

        assertEquals(controlResult.getHomeScore(), recoveredResult.getHomeScore());
        assertEquals(controlResult.getAwayScore(), recoveredResult.getAwayScore());
        assertEquals(Faza2GateHarness.goalFacts(controlResult), Faza2GateHarness.goalFacts(recoveredResult),
                "continued play after recovery keeps the same future canonical goals and scorers");
        assertEquals(control.candidateIdsBySlot, crashed.candidateIdsBySlot,
                "the eligible-scorer candidate set offered for each slot is unchanged by recovery");
        assertNoDuplicateGoals(recoveredResult);
        assertEquals(new HashSet<>(crashed.resolveCalls).size(), crashed.resolvedScorers.size(),
                "every canonical slot resolves to exactly one persisted scorer across the interruption");
    }

    @Test
    void coldRecovery_reusesPersistedAnimationRecipes_withoutDuplicatingThem() throws Exception {
        Faza2GateHarness h = new Faza2GateHarness();
        h.enableAnimationV3();
        LiveMatchSession before = h.start(true);
        before.advanceUntilAndSnapshot(50);
        List<String> rowsAtCrash = new ArrayList<>(h.recipeRows.keySet());
        assertTrue(rowsAtCrash.size() > 0, "moments before the crash were persisted as recipes");
        List<String> jsonAtCrash = rowsAtCrash.stream()
                .map(k -> h.recipeRows.get(k).getRecipeJson()).toList();

        h.dropInMemorySessions();
        LiveMatchSession recovered = h.coldRecover();
        assertNotNull(recovered);
        LiveMatchData result = recovered.advanceUntilAndSnapshot(recovered.getTotalMinutes());

        // Pre-crash recipes are byte-identical: recovery replays them, it never regenerates.
        for (int i = 0; i < rowsAtCrash.size(); i++) {
            assertEquals(jsonAtCrash.get(i), h.recipeRows.get(rowsAtCrash.get(i)).getRecipeJson(),
                    "recipe " + rowsAtCrash.get(i) + " was not rewritten by recovery");
        }
        Set<String> identities = new HashSet<>();
        for (GoalAnimationData a : result.getCanonicalAnimations()) {
            assertTrue(identities.add(a.getMinute() + "/" + a.getSlotIndex()),
                    "duplicate animation identity after recovery: " + a.getMinute() + "/" + a.getSlotIndex());
        }
        assertEquals(identities.size(), h.recipeRows.size(),
                "exactly one persisted recipe row per exposed moment");
    }

    private static void assertNoDuplicateGoals(LiveMatchData data) {
        Set<String> seen = new HashSet<>();
        for (LiveMatchData.LiveMatchMinute m : data.getTimeline()) {
            if (!"goal".equals(m.getEventType())) continue;
            assertTrue(seen.add(m.getMinute() + "/" + m.getTeamId() + "/" + m.getPlayerId()
                            + "/" + m.getHomeScore() + ":" + m.getAwayScore()),
                    "duplicate goal event at minute " + m.getMinute());
        }
    }
}
