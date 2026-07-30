package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.frontend.LiveMatchData;
import com.footballmanagergamesimulator.matchplan.MatchAnimationRecipe;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GATE 4 — checkpoint size / boundary (CHARACTERIZATION half).
 *
 * <p>Pins the boundary that ambient frames must respect: {@code checkpointJson} carries
 * engine state only and never a frame array, its size is independent of how much
 * animation the match produced, and {@code match_animation_recipe} holds exactly one
 * durable row per canonical/visual moment identity — never one row per engine minute.
 *
 * <p>The ambient half ("no ambient frame arrays; the minimal version/spec metadata
 * round-trips") is in {@code AmbientCheckpointBoundaryAcceptanceTest}, tagged
 * {@code faza2-acceptance}.
 */
class LiveCheckpointBoundaryCharacterizationTest {

    @Test
    void checkpointJson_carriesNoAnimationFrameArrays() throws Exception {
        Faza2GateHarness h = new Faza2GateHarness();
        h.enableAnimationV3();
        LiveMatchSession session = h.start(true);
        LiveMatchData data = session.advanceUntilAndSnapshot(60);

        assertFalse(data.getCanonicalAnimations().isEmpty(), "the visible payload does carry frames");
        assertTrue(data.getCanonicalAnimations().stream().anyMatch(a -> !a.getFrames().isEmpty()));

        String json = h.checkpointJson();
        assertNotNull(json, "the canonical session checkpointed");
        for (String forbidden : new String[]{"\"frames\"", "ballX", "ballY", "positions", "ballCarrierId"}) {
            assertFalse(json.contains(forbidden),
                    "checkpointJson must not contain the frame payload token " + forbidden);
        }
        LiveSessionCheckpoint checkpoint = h.checkpoint();
        assertNotNull(checkpoint);
        assertNull(checkpoint.state().getGoalAnimations(), "legacy animation map is stripped from the checkpoint");
        assertNull(checkpoint.state().getCanonicalAnimations(), "V3 animation list is stripped from the checkpoint");
    }

    /**
     * Rendering frames is inert: with the animation budget identical, whether the V3
     * compiler actually produces frames or not leaves the engine checkpoint byte-identical.
     * This is the property the ambient compiler must inherit.
     *
     * <p>Note the deliberately narrow comparison — the animation BUDGET
     * ({@code generateGoalAnimations}) is held constant in both runs, because toggling the
     * budget itself does perturb the engine today. That is pinned separately in
     * {@code LiveAmbientNonInterferenceCharacterizationTest}.
     */
    @Test
    void checkpointJson_isIdenticalWhetherOrNotFramesAreActuallyRendered() throws Exception {
        Faza2GateHarness rendered = new Faza2GateHarness();
        rendered.enableAnimationV3();
        rendered.start(true).advanceUntilAndSnapshot(60);

        Faza2GateHarness notRendered = new Faza2GateHarness(); // same budget, no V3 compiler
        notRendered.start(true).advanceUntilAndSnapshot(60);

        assertFalse(rendered.recipeRows.isEmpty(), "the first run really did compile frames");
        assertTrue(notRendered.recipeRows.isEmpty(), "the second run compiled none");
        assertEquals(notRendered.checkpointJson(), rendered.checkpointJson(),
                "compiling presentation frames leaves the engine checkpoint byte-identical — "
                        + "the invariant ambient generation must inherit");
    }

    @Test
    void checkpointJson_roundTripsTheEngineStateItClaimsToCarry() throws Exception {
        Faza2GateHarness h = new Faza2GateHarness();
        LiveMatchSession session = h.start();
        session.advanceUntilAndSnapshot(55);

        LiveSessionCheckpoint checkpoint = h.checkpoint();
        assertNotNull(checkpoint);
        assertEquals(55, checkpoint.state().getCurrentMinute());
        assertEquals(session.getHomeScore(), checkpoint.state().getHomeScore());
        assertEquals(session.getAwayScore(), checkpoint.state().getAwayScore());
        assertEquals(session.matchStates.size(), checkpoint.players().size(),
                "every player's exact engine state is checkpointed");
        assertFalse(checkpoint.fieldedPositions().isEmpty(), "fielded roles round-trip");
        assertNotNull(h.commitContext.get().getCheckpointRandomState(),
                "the live RNG word is checkpointed as a scalar, not as a frame log");

        h.dropInMemorySessions();
        LiveMatchSession recovered = h.coldRecover();
        assertNotNull(recovered);
        assertEquals(55, recovered.currentMinute);
        assertEquals(session.getHomeScore(), recovered.getHomeScore());
        assertEquals(session.snapshot().getHomeShots(), recovered.snapshot().getHomeShots());
        assertEquals(session.snapshot().getTimeline().size(), recovered.snapshot().getTimeline().size());
    }

    @Test
    void animationRecipes_areOneRowPerMomentIdentity_notOnePerEngineMinute() throws Exception {
        Faza2GateHarness h = new Faza2GateHarness();
        h.enableAnimationV3();
        LiveMatchSession session = h.start(true);
        LiveMatchData data = session.advanceUntilAndSnapshot(session.getTotalMinutes());

        Set<String> identities = new HashSet<>();
        for (GoalAnimationData a : data.getCanonicalAnimations()) {
            identities.add(Faza2GateHarness.FIXTURE + "|" + a.getSlotIndex());
        }
        assertEquals(identities, h.recipeRows.keySet(),
                "exactly one durable recipe row per (fixtureKey, slotIndex) moment identity");
        assertTrue(h.recipeRows.size() < session.getTotalMinutes(),
                "recipes are per moment, not per engine minute (" + h.recipeRows.size()
                        + " rows for " + session.getTotalMinutes() + " minutes)");
        for (MatchAnimationRecipe row : h.recipeRows.values()) {
            assertTrue(row.getMinute() >= 0 && row.getMinute() <= session.getTotalMinutes(),
                    "a recipe row is stamped with the engine minute it belongs to");
            assertNotNull(row.getRecipeJson());
        }
    }
}
