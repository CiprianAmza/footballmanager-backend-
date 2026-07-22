package com.footballmanagergamesimulator.frontend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Flag-off byte-compatibility of the {@link LiveMatchData} JSON contract: the new
 * canonical-animations boundary must be entirely ABSENT from the serialized payload
 * when the canonical plan is not bound, so the legacy Angular client sees exactly the
 * old shape.
 */
class LiveMatchDataJsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void flagOff_omitsCanonicalAnimations_soJsonMatchesLegacyContract() throws Exception {
        LiveMatchData data = new LiveMatchData();
        data.setGoalAnimations(new LinkedHashMap<>()); // legacy minute-keyed map present
        // canonicalAnimations left null (flag off / canonical plan not bound)

        String json = mapper.writeValueAsString(data);

        assertFalse(json.contains("canonicalAnimations"),
                "the canonical-animations property must be omitted when flag off");
        assertTrue(json.contains("goalAnimations"), "the legacy map is still serialized");
    }

    @Test
    void flagOn_includesCanonicalAnimations() throws Exception {
        LiveMatchData data = new LiveMatchData();
        data.setCanonicalAnimations(List.of(new GoalAnimationData()));

        String json = mapper.writeValueAsString(data);

        assertTrue(json.contains("canonicalAnimations"),
                "with a bound canonical plan the ordered list is serialized");
    }
}
