package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GATE 4 — checkpoint size / boundary, ambient half (ACCEPTANCE), plus the ambient half
 * of GATE 5 (commit isolation).
 *
 * <p><b>Expected to be RED until Faza 2 is implemented.</b> Run with
 * {@code mvn test -Pfaza2-gates}.
 *
 * <p>Specifies: {@code checkpointJson} and {@code match_animation_recipe} contain no
 * ambient frame arrays; only the minimal version/spec metadata needed for deterministic
 * regeneration is checkpointed, and it round-trips; and generating ambient never advances
 * or commits the match. The non-ambient halves are green today in
 * {@code LiveCheckpointBoundaryCharacterizationTest} and
 * {@code LiveCommitIsolationCharacterizationTest}.
 */
@Tag("faza2-acceptance")
class AmbientCheckpointBoundaryAcceptanceTest {

    private static final String GATE = "gate 4 (checkpoint size/boundary)";

    @Test
    void checkpointJson_containsNoAmbientFrameArrays() throws Exception {
        Faza2ContractProbe.requireType(Faza2ContractProbe.AMBIENT_COMPILER, GATE,
                "ambient frames stay unpersisted, explicitly outside the exact canonical "
                        + "checkpoint invariant (rev. 6 answer 2).");
        Faza2GateHarness h = new Faza2GateHarness();
        Faza2ContractProbe.setAmbientEnabled(h.engineConfig, true, GATE);
        LiveMatchSession session = h.start();
        session.advanceUntilAndSnapshot(60);

        String json = h.checkpointJson();
        assertNotNull(json, "the canonical session still checkpoints with ambient on");
        for (String forbidden : new String[]{"ambientSegments", "\"frames\"", "ballX", "ballY",
                "positions", "ballCarrierId"}) {
            assertFalse(json.contains(forbidden),
                    "checkpointJson must not carry ambient frame payload (" + forbidden + ")");
        }
        assertTrue(json.length() < 200_000,
                "the checkpoint stays a bounded engine-state document, not a frame log "
                        + "(was " + json.length() + " chars)");
    }

    @Test
    void ambientVersionMetadata_isPinnedForTheSessionAndRoundTrips() throws Exception {
        Faza2ContractProbe.requireType(Faza2ContractProbe.AMBIENT_SEGMENT_SPEC, GATE,
                "the small versioned spec that makes each minute's branch inputs reconstructible "
                        + "from the exact checkpoint (rev. 6 answer 2).");
        Faza2ContractProbe.DeltaEntryPoint advance = Faza2ContractProbe.requireDeltaEntryPoint(GATE);

        Faza2GateHarness h = new Faza2GateHarness();
        Faza2ContractProbe.setAmbientEnabled(h.engineConfig, true, GATE);
        LiveMatchSession session = h.start();
        advance.advance(h, session, 39);
        List<Integer> versionsBefore = versions(advance.advance(h, session, 40));
        assertFalse(versionsBefore.isEmpty(), "segments declare their ambientVersion");

        // The pinned version must survive a restart, so a redeployed generator does not
        // silently re-render an in-progress session with new code.
        String checkpointBefore = h.checkpointJson();
        h.dropInMemorySessions();
        LiveMatchSession recovered = h.coldRecover();
        assertNotNull(recovered);
        assertEquals(40, recovered.currentMinute);
        assertEquals(versionsBefore, versions(advance.advance(h, recovered, 40)),
                "the ambient generator version pinned for this session round-trips through "
                        + "the checkpoint");
        assertEquals(checkpointBefore.length(), h.checkpointJson().length(),
                "restoring did not grow the checkpoint document");
    }

    @Test
    void ambient_addsNoRowsToMatchAnimationRecipe_andNeverCommits() throws Exception {
        Faza2ContractProbe.requireType(Faza2ContractProbe.AMBIENT_COMPILER, GATE,
                "ambient recipes remain unpersisted (rev. 6 answer 2).");

        Faza2GateHarness off = new Faza2GateHarness();
        off.enableAnimationV3();
        Faza2ContractProbe.setAmbientEnabled(off.engineConfig, false, GATE);
        LiveMatchSession offSession = off.start(true);
        offSession.advanceUntilAndSnapshot(offSession.getTotalMinutes());

        Faza2GateHarness on = new Faza2GateHarness();
        on.enableAnimationV3();
        Faza2ContractProbe.setAmbientEnabled(on.engineConfig, true, GATE);
        LiveMatchSession onSession = on.start(true);
        onSession.advanceUntilAndSnapshot(onSession.getTotalMinutes());

        assertEquals(off.recipeRows.keySet(), on.recipeRows.keySet(),
                "match_animation_recipe holds moment recipes only — never ambient segments");
        assertFalse(onSession.isCommitted(), "generating ambient never commits the match");
        assertTrue(onSession.isAwaitingCommit());
    }

    private static List<Integer> versions(Object delta) {
        List<Integer> out = new ArrayList<>();
        for (Object segment : Faza2ContractProbe.listProperty(delta, "ambientSegments")) {
            out.add(Faza2ContractProbe.intProperty(segment, "ambientVersion"));
        }
        return out;
    }
}
