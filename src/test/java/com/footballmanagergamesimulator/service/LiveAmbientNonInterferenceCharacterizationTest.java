package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.frontend.LiveMatchData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GATE 1 — presentation non-interference (CHARACTERIZATION half).
 *
 * <p>The gate as specified compares a fixture with ambient generation ON against the
 * same fixture with it OFF. Ambient does not exist yet, so the on/off comparison lives
 * in {@code AmbientPresentationNonInterferenceAcceptanceTest} (tagged
 * {@code faza2-acceptance}, honestly red). What CAN be pinned today, and is pinned here,
 * is everything that comparison will rest on:
 *
 * <ol>
 *   <li>the canonical outcome of one fixture is <em>reproducible</em> — two independent
 *       runs of the same canonical fixture produce byte-identical score, ordered slots,
 *       contributors, substitutions, cards, statistics, pitch state and checkpoint. Without
 *       this the ambient on/off comparison could not distinguish interference from noise;</li>
 *   <li>reading and serializing the presentation payload does not touch the live RNG
 *       checkpoint state, nor the engine minute/score/timeline. The ambient compiler must
 *       inherit exactly this property (Codex rev. 6 gate 1, rev. 6 question 2 purity
 *       boundary);</li>
 *   <li>same-minute canonical moments are ordered by {@code slotIndex}, on both the
 *       timeline and the animation boundary.</li>
 * </ol>
 */
class LiveAmbientNonInterferenceCharacterizationTest {

    @Test
    void sameCanonicalFixtureRunTwice_producesIdenticalOutcomeAndCheckpoint() throws Exception {
        Faza2GateHarness a = new Faza2GateHarness();
        Faza2GateHarness b = new Faza2GateHarness();

        LiveMatchSession sa = a.start();
        LiveMatchSession sb = b.start();
        LiveMatchData da = sa.advanceUntilAndSnapshot(sa.getTotalMinutes());
        LiveMatchData db = sb.advanceUntilAndSnapshot(sb.getTotalMinutes());

        assertEquals(Faza2GateHarness.TARGET_HOME, da.getHomeScore(), "the canonical scoreline is played");
        assertEquals(Faza2GateHarness.TARGET_AWAY, da.getAwayScore());
        assertEquals(Faza2GateHarness.canonicalFingerprint(da), Faza2GateHarness.canonicalFingerprint(db),
                "the canonical outcome of a fixture is fully reproducible — this is the baseline "
                        + "the ambient on/off comparison of gate 1 is measured against");
        assertNotNull(a.checkpointJson(), "a canonical session writes its checkpoint");
        assertEquals(a.checkpointJson(), b.checkpointJson(),
                "the canonical checkpoint fields are identical across runs");
        assertEquals(Faza2GateHarness.liveRandomState(sa), Faza2GateHarness.liveRandomState(sb),
                "the live RNG ends in the same state");
    }

    @Test
    void repeatedSnapshotAndSerialization_leaveTheLiveRngAndEngineStateUntouched() throws Exception {
        Faza2GateHarness h = new Faza2GateHarness();
        LiveMatchSession session = h.start();
        session.advanceUntilAndSnapshot(45);

        Long rngBefore = Faza2GateHarness.liveRandomState(session);
        assertNotNull(rngBefore, "a canonical session runs on the checkpointable RNG");
        int minuteBefore = session.currentMinute;
        int homeBefore = session.getHomeScore();
        int timelineBefore = session.snapshot().getTimeline().size();
        String checkpointBefore = h.checkpointJson();

        // Everything a presentation layer is allowed to do: read the state repeatedly and
        // serialize it. The ambient compiler must be exactly this harmless.
        for (int i = 0; i < 5; i++) {
            LiveMatchData snapshot = session.snapshot();
            h.mapper.writeValueAsString(snapshot);
            h.mapper.writeValueAsString(session.asLiveMatchData());
        }

        assertEquals(rngBefore, Faza2GateHarness.liveRandomState(session),
                "reading/serializing the presentation payload must not consume live RNG");
        assertEquals(minuteBefore, session.currentMinute, "no engine minute was advanced");
        assertEquals(homeBefore, session.getHomeScore(), "no goal was invented");
        assertEquals(timelineBefore, session.snapshot().getTimeline().size(), "the timeline is unchanged");
        assertEquals(checkpointBefore, h.checkpointJson(), "no checkpoint was rewritten");
        assertFalse(session.isCommitted(), "reading state never commits");
    }

    /**
     * KNOWN INTERFERENCE, pinned deliberately. Turning the legacy animation budget on or off
     * changes the narrated match: {@code LiveMatchSession.tickAttackBranch} spends a live RNG
     * draw per non-goal shot when {@code goalAnimations != null} ("preserve the historical
     * presentation RNG draw"). The canonical scoreline is unaffected — that is pinned by the
     * plan — but fouls, cards, possession and commentary all shift.
     *
     * <p>This is the precedent gate 1 exists to prevent repeating: the ambient compiler must
     * use its own local RNG (Codex rev. 6 question 2) so that ambient ON and ambient OFF are
     * genuinely indistinguishable. If this test ever starts failing because the two runs
     * became identical, the historical draw was removed — a deliberate change, not a bug.
     */
    @Test
    void togglingTheLegacyAnimationBudget_currentlyPerturbsTheEngineRng() throws Exception {
        Faza2GateHarness withBudget = new Faza2GateHarness();
        LiveMatchData withData = withBudget.start(true).advanceUntilAndSnapshot(90);

        Faza2GateHarness withoutBudget = new Faza2GateHarness();
        LiveMatchData withoutData = withoutBudget.start(false).advanceUntilAndSnapshot(90);

        assertEquals(withoutData.getHomeScore(), withData.getHomeScore(),
                "the canonical scoreline is immune — it comes from the plan");
        assertEquals(withoutData.getAwayScore(), withData.getAwayScore());
        assertEquals(Faza2GateHarness.goalFacts(withoutData), Faza2GateHarness.goalFacts(withData),
                "canonical goal minutes and scorers are immune");
        assertNotEquals(Faza2GateHarness.canonicalFingerprint(withoutData),
                Faza2GateHarness.canonicalFingerprint(withData),
                "the rest of the narration is NOT immune today: the animation budget consumes "
                        + "live RNG. Ambient generation must not copy this pattern.");
    }

    @Test
    void sameMinuteCanonicalSlots_areEmittedAndAnimatedInSlotIndexOrder() throws Exception {
        // Two home goals at the same minute — the collision case the ordering rule exists for.
        Faza2GateHarness h = new Faza2GateHarness(List.of(
                new int[]{0, 1, 55}, new int[]{1, 1, 55}, new int[]{2, 0, 70}));
        h.enableAnimationV3();
        LiveMatchSession session = h.start(true);
        LiveMatchData data = session.advanceUntilAndSnapshot(session.getTotalMinutes());

        List<Integer> resolutionOrder = h.resolveCalls.stream().distinct().toList();
        assertEquals(List.of(0, 1, 2), resolutionOrder,
                "canonical slots resolve in slotIndex order, including two slots at one minute");

        List<LiveMatchData.LiveMatchMinute> goalsAt55 = data.getTimeline().stream()
                .filter(m -> "goal".equals(m.getEventType()) && m.getMinute() == 55)
                .toList();
        assertEquals(2, goalsAt55.size(), "both same-minute goals are narrated, neither is nudged");
        assertEquals(1, goalsAt55.get(0).getHomeScore(), "running score follows slotIndex order");
        assertEquals(2, goalsAt55.get(1).getHomeScore());

        List<GoalAnimationData> canonical = data.getCanonicalAnimations();
        assertNotNull(canonical, "a bound canonical plan exposes the ordered animation boundary");
        for (int i = 1; i < canonical.size(); i++) {
            GoalAnimationData prev = canonical.get(i - 1);
            GoalAnimationData cur = canonical.get(i);
            assertTrue(prev.getMinute() < cur.getMinute()
                            || (prev.getMinute() == cur.getMinute() && prev.getSlotIndex() < cur.getSlotIndex()),
                    "animations are ordered by (minute, slotIndex): " + prev.getMinute() + "/"
                            + prev.getSlotIndex() + " before " + cur.getMinute() + "/" + cur.getSlotIndex());
        }
    }
}
