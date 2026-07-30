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
 * GATE 2 — retry / recovery, ambient half (ACCEPTANCE).
 *
 * <p><b>Expected to be RED until Faza 2 is implemented.</b> Run with
 * {@code mvn test -Pfaza2-gates}.
 *
 * <p>Specifies: a duplicate {@code /advance(untilMinute=N)}, a lost response and a cold
 * recovery at N all regenerate the SAME ambient segment for N, without replaying the
 * engine minute — and continued play keeps the same future canonical goals and eligible
 * scorers. The non-ambient half of this gate is green today in
 * {@code LiveAdvanceRetryRecoveryCharacterizationTest}.
 */
@Tag("faza2-acceptance")
class AmbientDeterminismRecoveryAcceptanceTest {

    private static final String GATE = "gate 2 (retry/recovery)";

    @Test
    void twoRunsOfTheSameFixture_produceByteIdenticalAmbientSegments() throws Exception {
        Faza2ContractProbe.DeltaEntryPoint advance = Faza2ContractProbe.requireDeltaEntryPoint(GATE);
        Faza2ContractProbe.requireType(Faza2ContractProbe.AMBIENT_SEGMENT_DATA, GATE,
                "each segment carries its minute and its ambientVersion.");

        Faza2GateHarness a = new Faza2GateHarness();
        Faza2ContractProbe.setAmbientEnabled(a.engineConfig, true, GATE);
        LiveMatchSession sa = a.start();

        Faza2GateHarness b = new Faza2GateHarness();
        Faza2ContractProbe.setAmbientEnabled(b.engineConfig, true, GATE);
        LiveMatchSession sb = b.start();

        List<String> first = segmentsAsJson(a, advance.advance(a, sa, 30));
        List<String> second = segmentsAsJson(b, advance.advance(b, sb, 30));

        assertFalse(first.isEmpty(), "advancing 30 engine minutes produced ambient segments");
        assertEquals(first, second, "ambient regeneration is deterministic for a fixture");
    }

    @Test
    void duplicateAdvance_regeneratesTheSameSegmentsWithoutReplayingTheMinute() throws Exception {
        Faza2ContractProbe.DeltaEntryPoint advance = Faza2ContractProbe.requireDeltaEntryPoint(GATE);

        Faza2GateHarness h = new Faza2GateHarness();
        Faza2ContractProbe.setAmbientEnabled(h.engineConfig, true, GATE);
        LiveMatchSession session = h.start();

        advance.advance(h, session, 29);
        Object atThirty = advance.advance(h, session, 30);
        List<String> segments = segmentsAsJson(h, atThirty);
        int resolveCalls = h.resolveCalls.size();
        Long rng = Faza2GateHarness.liveRandomState(session);

        // The client lost the response and repeats the identical request.
        Object retry = advance.advance(h, session, 30);

        assertEquals(segments, segmentsAsJson(h, retry),
                "the same ambient segment is returned for minute 30 on a duplicate request");
        assertEquals(resolveCalls, h.resolveCalls.size(), "no canonical slot was resolved again");
        assertEquals(rng, Faza2GateHarness.liveRandomState(session),
                "the engine minute was NOT replayed to regenerate ambient");
        assertEquals(30, session.currentMinute);
    }

    @Test
    void coldRecoveryAtN_regeneratesTheSameSegmentForN() throws Exception {
        Faza2ContractProbe.DeltaEntryPoint advance = Faza2ContractProbe.requireDeltaEntryPoint(GATE);

        Faza2GateHarness h = new Faza2GateHarness();
        Faza2ContractProbe.setAmbientEnabled(h.engineConfig, true, GATE);
        LiveMatchSession before = h.start();
        advance.advance(h, before, 49);
        List<String> atFifty = segmentsAsJson(h, advance.advance(h, before, 50));
        assertFalse(atFifty.isEmpty());

        h.dropInMemorySessions(); // backend restart, /advance response never reached the client
        LiveMatchSession recovered = h.coldRecover();
        assertNotNull(recovered, "the fixture is cold-recoverable");
        assertEquals(50, recovered.currentMinute, "recovery resumes at the checkpoint minute");

        Object replayed = advance.advance(h, recovered, 50);
        assertEquals(atFifty, segmentsAsJson(h, replayed),
                "a lost /advance + cold recovery + the same request regenerate the identical "
                        + "ambient segment, without replaying the engine minute (rev. 6 answer 2)");
    }

    @Test
    void oneAmbientSegmentPerEngineMinute_neverOnePerTimelineEvent() throws Exception {
        Faza2ContractProbe.DeltaEntryPoint advance = Faza2ContractProbe.requireDeltaEntryPoint(GATE);

        // Two canonical goals at minute 55: a single engine minute carrying several events.
        Faza2GateHarness h = new Faza2GateHarness(List.of(
                new int[]{0, 1, 55}, new int[]{1, 1, 55}, new int[]{2, 0, 70}));
        Faza2ContractProbe.setAmbientEnabled(h.engineConfig, true, GATE);
        LiveMatchSession session = h.start();

        advance.advance(h, session, 54);
        Object delta = advance.advance(h, session, 55);

        List<Object> segments = Faza2ContractProbe.listProperty(delta, "ambientSegments");
        assertEquals(1, segments.size(),
                "exactly one ambient segment per engine minute, even when that minute carries "
                        + "two canonical goals");
        assertEquals(55, Faza2ContractProbe.intProperty(segments.get(0), "minute"));
        assertTrue(Faza2ContractProbe.intProperty(segments.get(0), "ambientVersion") > 0,
                "the segment carries its generator version so old versions can be frozen");
    }

    private static List<String> segmentsAsJson(Faza2GateHarness h, Object delta) throws Exception {
        List<String> out = new ArrayList<>();
        for (Object segment : Faza2ContractProbe.listProperty(delta, "ambientSegments")) {
            out.add(h.mapper.writeValueAsString(segment));
        }
        return out;
    }
}
