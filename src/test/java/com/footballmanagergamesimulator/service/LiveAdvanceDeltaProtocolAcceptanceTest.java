package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.LiveMatchData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GATE 3 — delta protocol (ACCEPTANCE), plus the opt-in half of GATE 6.
 *
 * <p><b>Expected to be RED until Faza 2 is implemented.</b> Run with
 * {@code mvn test -Pfaza2-gates}. Nothing in this gate exists today — the delta DTO has
 * not been written — so this class is pure acceptance.
 *
 * <p>Specifies the contract decided in AI_HANDOFF rev. 6 answer 1:
 * {@code LiveMatchAdvanceDelta { baseMinute, currentMinute, eventsAdded, ambientSegments,
 * canonicalAnimationsAdded, statePatch }}; a delta is applied only when {@code baseMinute}
 * matches the client's current engine minute; stale, duplicate and out-of-order deltas are
 * harmless; same-minute canonical moments stay ordered by {@code slotIndex}; and the legacy
 * full {@code /advance} serialization is unchanged while the delta is opt-in.
 */
@Tag("faza2-acceptance")
class LiveAdvanceDeltaProtocolAcceptanceTest {

    private static final String GATE = "gate 3 (delta protocol)";

    @Test
    void deltaDto_hasTheAgreedShape() {
        Class<?> delta = Faza2ContractProbe.requireType(Faza2ContractProbe.ADVANCE_DELTA, GATE,
                "the response-only delta representation of POST /advance.");
        Faza2ContractProbe.requireProperties(delta, List.of(
                "baseMinute", "currentMinute", "eventsAdded",
                "ambientSegments", "canonicalAnimationsAdded", "statePatch"), GATE);
    }

    @Test
    void everyDeltaDeclaresTheBaseMinuteItMustBeAppliedOnto() throws Exception {
        Faza2ContractProbe.DeltaEntryPoint advance = Faza2ContractProbe.requireDeltaEntryPoint(GATE);
        Faza2GateHarness h = new Faza2GateHarness();
        Faza2ContractProbe.setAmbientEnabled(h.engineConfig, true, GATE);
        LiveMatchSession session = h.start();

        Object first = advance.advance(h, session, 10);
        assertEquals(0, Faza2ContractProbe.intProperty(first, "baseMinute"),
                "the first delta is based on the kickoff minute");
        assertEquals(10, Faza2ContractProbe.intProperty(first, "currentMinute"));

        Object second = advance.advance(h, session, 20);
        assertEquals(10, Faza2ContractProbe.intProperty(second, "baseMinute"),
                "each delta declares the minute the client must already be at, so a client "
                        + "with a gap can discard it and resync through GET /state");
        assertEquals(20, Faza2ContractProbe.intProperty(second, "currentMinute"));
        assertEquals(10, Faza2ContractProbe.listProperty(second, "ambientSegments").size(),
                "one ambient segment per engine minute covered by the delta");
    }

    @Test
    void duplicateAndOutOfOrderRequests_yieldHarmlessNoOpDeltas() throws Exception {
        Faza2ContractProbe.DeltaEntryPoint advance = Faza2ContractProbe.requireDeltaEntryPoint(GATE);
        Faza2GateHarness h = new Faza2GateHarness();
        Faza2ContractProbe.setAmbientEnabled(h.engineConfig, true, GATE);
        LiveMatchSession session = h.start();
        advance.advance(h, session, 30);
        int resolveCalls = h.resolveCalls.size();

        Object duplicate = advance.advance(h, session, 30);
        assertEquals(30, Faza2ContractProbe.intProperty(duplicate, "currentMinute"));
        assertTrue(Faza2ContractProbe.listProperty(duplicate, "eventsAdded").isEmpty(),
                "a duplicate advance adds no events");
        assertTrue(Faza2ContractProbe.listProperty(duplicate, "canonicalAnimationsAdded").isEmpty(),
                "a duplicate advance adds no canonical moments");

        Object stale = advance.advance(h, session, 12); // out-of-order / stale client
        assertEquals(30, Faza2ContractProbe.intProperty(stale, "currentMinute"),
                "an out-of-order advance never rewinds the engine");
        assertEquals(Faza2ContractProbe.intProperty(stale, "baseMinute"),
                Faza2ContractProbe.intProperty(stale, "currentMinute"),
                "a no-op delta is self-identifying: baseMinute == currentMinute");
        assertTrue(Faza2ContractProbe.listProperty(stale, "eventsAdded").isEmpty());
        assertEquals(resolveCalls, h.resolveCalls.size(),
                "neither the duplicate nor the stale request re-resolved a canonical slot");
    }

    @Test
    void sameMinuteCanonicalMoments_areOrderedBySlotIndexInsideTheDelta() throws Exception {
        Faza2ContractProbe.DeltaEntryPoint advance = Faza2ContractProbe.requireDeltaEntryPoint(GATE);
        Faza2GateHarness h = new Faza2GateHarness(List.of(
                new int[]{0, 1, 55}, new int[]{1, 1, 55}, new int[]{2, 0, 70}));
        h.enableAnimationV3();
        Faza2ContractProbe.setAmbientEnabled(h.engineConfig, true, GATE);
        LiveMatchSession session = h.start(true);

        advance.advance(h, session, 54);
        Object delta = advance.advance(h, session, 55);

        List<Object> moments = Faza2ContractProbe.listProperty(delta, "canonicalAnimationsAdded");
        assertFalse(moments.isEmpty(), "the two same-minute canonical goals appear in the delta");
        int previous = Integer.MIN_VALUE;
        for (Object moment : moments) {
            int slotIndex = Faza2ContractProbe.intProperty(moment, "slotIndex");
            assertTrue(slotIndex > previous,
                    "same-minute canonical moments stay ordered by slotIndex");
            previous = slotIndex;
        }
    }

    /**
     * GATE 6, opt-in half: introducing the delta must not change the legacy representation.
     * The exhaustive field-level pin of that payload is green today in
     * {@code LiveAdvanceLegacySerializationCharacterizationTest}; here it is re-asserted
     * with ambient generation ENABLED, which is the condition the gate actually cares about.
     */
    @Test
    void legacyAdvance_stillReturnsTheFullUnchangedPayloadWhileTheDeltaIsOptIn() throws Exception {
        Faza2ContractProbe.requireType(Faza2ContractProbe.ADVANCE_DELTA, GATE,
                "the delta must be an additive, opt-in representation.");
        Faza2GateHarness h = new Faza2GateHarness();
        Faza2ContractProbe.setAmbientEnabled(h.engineConfig, true, GATE);
        LiveMatchSession session = h.start();

        LiveMatchData legacy = session.advanceUntilAndSnapshot(30);
        String json = h.mapper.writeValueAsString(legacy);

        assertFalse(json.contains("ambientSegments"),
                "ambient frames must never leak into the legacy full /advance payload");
        assertFalse(json.contains("baseMinute"),
                "the delta envelope must never leak into the legacy full /advance payload");
        assertEquals(30, legacy.getCurrentMinute());
        assertFalse(legacy.getTimeline().isEmpty(), "the legacy payload is still cumulative");
    }
}
