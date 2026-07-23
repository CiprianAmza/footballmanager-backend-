package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.model.PlayerSkills;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerAttributeMappingTest {

    @Test
    void everyPureAttributeIsMappedExactlyOnce() {
        assertThat(PlayerAttributeMapping.mappedAttributes())
                .containsExactlyInAnyOrder(PlayerAttribute.values())
                .hasSize(PlayerAttribute.values().length);
    }

    @Test
    void eachAttributeReadsItsOwnCanonicalField() {
        Map.Entry<PlayerSkills, Map<PlayerAttribute, Integer>> fixture =
                AdapterTestFixture.skillsWithDistinctAttributes();
        PlayerSkills skills = fixture.getKey();
        Map<PlayerAttribute, Integer> expected = fixture.getValue();

        for (PlayerAttribute attribute : PlayerAttribute.values()) {
            assertThat(PlayerAttributeMapping.rawValue(skills, attribute))
                    .as("attribute %s", attribute)
                    .isEqualTo(expected.get(attribute));
        }
    }

    @Test
    void rawAttributeMapIsCompleteAndFaithful() {
        Map.Entry<PlayerSkills, Map<PlayerAttribute, Integer>> fixture =
                AdapterTestFixture.skillsWithDistinctAttributes();

        Map<PlayerAttribute, Integer> map = PlayerAttributeMapping.rawAttributeMap(fixture.getKey());

        assertThat(map).containsExactlyInAnyOrderEntriesOf(fixture.getValue());
    }

    @Test
    void distinctSentinelsHaveNoAccidentalFieldSwap() {
        // The fixture uses 29 distinct sentinels; a swap between two fields would collide detection.
        Map<PlayerAttribute, Integer> expected = AdapterTestFixture.skillsWithDistinctAttributes().getValue();
        assertThat(expected.values()).doesNotHaveDuplicates();
    }
}
