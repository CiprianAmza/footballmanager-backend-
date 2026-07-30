package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.frontend.LiveMatchData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * GATE 5 — commit isolation (CHARACTERIZATION half, backend side).
 *
 * <p>The frontend half of the gate ("replay, zoom and view switching never call
 * {@code /advance} or {@code /commit}") is a frontend concern and was delivered in
 * Round 1. The backend half is that <em>generating and reading presentation data
 * cannot advance or commit a match</em>, and that the commit path stays transactionally
 * idempotent and rollback-safe.
 *
 * <p>The transactional half is already covered and stays in the mandatory regression
 * list — {@code MatchPlanIdempotencyTest}, {@code MatchPlanRollbackTest},
 * {@code MatchPlanConcurrencyTest} and
 * {@code MatchdayCoordinatorCanonicalCommitConcurrencyTest}. This class adds only the
 * session-level gap those do not cover: repeated presentation reads are inert, and the
 * commit flags behave the way {@code MatchdayCoordinator}'s rollback hook assumes.
 */
class LiveCommitIsolationCharacterizationTest {

    @Test
    void generatingAndReadingPresentationData_neitherAdvancesNorCommits() throws Exception {
        Faza2GateHarness h = new Faza2GateHarness();
        h.enableAnimationV3();
        LiveMatchSession session = h.start(true);
        session.advanceUntilAndSnapshot(session.getTotalMinutes());

        assertTrue(session.isFinished());
        assertTrue(session.isAwaitingCommit(), "full time reached, commit not yet run");
        int minuteAfterFullTime = session.currentMinute;
        int resolveCalls = h.resolveCalls.size();
        int recipeRows = h.recipeRows.size();
        Long rng = Faza2GateHarness.liveRandomState(session);
        String checkpoint = h.checkpointJson();

        // Everything a replay / zoom / view switch does on the backend: re-read the state and
        // re-read the persisted moment recipes.
        for (int i = 0; i < 3; i++) {
            LiveMatchData snapshot = session.snapshot();
            h.mapper.writeValueAsString(snapshot);
            List<GoalAnimationData> replayed = h.service.loadCanonicalAnimations(Faza2GateHarness.FIXTURE);
            assertFalse(replayed.isEmpty(), "replay reads the persisted recipes");
        }

        assertEquals(minuteAfterFullTime, session.currentMinute, "no minute was advanced");
        assertEquals(resolveCalls, h.resolveCalls.size(), "no canonical slot was resolved");
        assertEquals(recipeRows, h.recipeRows.size(), "no recipe row was rewritten");
        assertEquals(rng, Faza2GateHarness.liveRandomState(session), "no live RNG consumed");
        assertEquals(checkpoint, h.checkpointJson(), "no checkpoint rewritten");
        assertFalse(session.isCommitted(), "reading never commits");
        verify(h.matchPlanService, never()).markCommitted(anyString());
        verify(h.matchPlanService, never()).finishLivePlan(anyString());
    }

    @Test
    void commitFlags_areIdempotentAndRollbackReversible() throws Exception {
        Faza2GateHarness h = new Faza2GateHarness();
        LiveMatchSession session = h.start();
        session.advanceUntilAndSnapshot(session.getTotalMinutes());
        assertTrue(session.isAwaitingCommit());

        session.markCommitted();
        session.markCommitted(); // duplicate /commit
        assertTrue(session.isCommitted());
        assertFalse(session.isAwaitingCommit(), "a committed session is no longer awaiting commit");

        // MatchdayCoordinator registers this on afterCompletion(ROLLED_BACK) BEFORE the first
        // side effect, so a rolled-back commit leaves the session committable again.
        session.resetForRetry();
        assertFalse(session.isCommitted());
        assertTrue(session.isAwaitingCommit(), "after a rollback the match can be committed again");
        assertEquals(Faza2GateHarness.TARGET_HOME, session.getHomeScore(),
                "rollback of the commit does not alter the canonical scoreline");
        assertEquals(3, h.resolveCalls.size(),
                "the commit flag dance re-resolves nothing: one resolution per planned slot");
    }
}
