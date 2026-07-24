package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.Compartment;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.TacticalContextInput;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompartmentDomainAdapterPhase3Test {
    private final CompartmentEngineConfig config = AdapterTestFixture.loadConfig();
    private final CompartmentDomainAdapter adapter = new CompartmentDomainAdapter(config);

    @Test
    void contextOverloadPreservesFinePositionRoleDutyAndDefaultOverloadStaysKZero() {
        var snapshot = DomainSnapshotFactory.fromDomain(AdapterTestFixture.human(7L, "WBL", 100, 70),
                AdapterTestFixture.skillsAll(20), "WBL", "Wing Back", "Attack", 1.0, 80.0);
        var context = new TacticalContextInput("Attacking", "Higher", "Short", "High", "High", "Wide",
                List.of("Stay Wider"));
        var input = adapter.toRatingInput(snapshot, context);
        assertThat(input.position()).isEqualTo("WBL");
        assertThat(input.role()).isEqualTo("Wing Back");
        assertThat(input.duty().name()).isEqualTo("ATTACK");
        assertThat(input.contextCoefficients()).isNotEmpty();
        assertThat(adapter.toRatingInput(snapshot).contextCoefficients()).isEmpty();
    }

    @Test
    void configuredCalculatorKeepsTotalContextRatioWithinSeventyToOneHundredThirtyPercent() {
        var snapshot = DomainSnapshotFactory.fromDomain(AdapterTestFixture.human(8L, "MC", 100, 70),
                AdapterTestFixture.skillsAll(20), "MC", "Advanced Playmaker", "Support", 1.0, 100.0);
        var extreme = new TacticalContextInput("Very Attacking", "Much Higher", "Long", "High", "High", "Wide",
                List.of("Get Further Forward", "Play Through Balls", "Try More Direct Passes"));
        var rating = adapter.rate(snapshot, extreme);
        assertThat(rating.compartments().values()).allSatisfy(breakdown ->
                assertThat(breakdown.contextRatio()).isBetween(.70, 1.30));
        assertThat(rating.compartments().values().stream().flatMap(b -> b.attributes().stream()))
                .filteredOn(attribute -> attribute.attribute() == PlayerAttribute.PASSING)
                .isNotEmpty()
                .allSatisfy(attribute -> assertThat(attribute.requestedContextCoefficient()).isGreaterThan(0));
        assertThat(rating.compartments()).containsOnlyKeys(Compartment.values());
    }
}
