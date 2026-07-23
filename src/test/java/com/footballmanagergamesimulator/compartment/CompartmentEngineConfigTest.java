package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CompartmentEngineConfigTest {

    private final CompartmentEngineConfig config = CompartmentConfigFixture.load();

    @Test
    void productionYamlBindsCompleteTypedContractWithFlagOff() {
        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getCompartments()).containsOnlyKeys(Compartment.values());
        assertThat(config.getPositions()).hasSize(14).containsKeys("GK", "DM", "ST");
        assertThat(config.getRoles()).hasSize(24).containsKeys(PlayerRole.POACHER, PlayerRole.PRESSING_FORWARD);
        assertThat(config.getDuties()).containsOnlyKeys(Duty.values());
        assertThat(config.getMentalities()).containsOnlyKeys(Mentality.values());
        assertThat(config.getPositionCompartmentOverrides().get("GK"))
                .containsKey(Compartment.DEFENSE);

        config.getCompartments().forEach((compartment, weights) ->
                assertThat(weights.getAttributes().values().stream().mapToDouble(Double::doubleValue).sum())
                        .as(compartment + " attribute weights")
                        .isCloseTo(1.0, within(1e-12)));
        assertThat(config.getPositionCompartmentOverrides().get("GK").get(Compartment.DEFENSE)
                .getAttributes().values().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(1.0, within(1e-12));
    }

    @Test
    void initialMentalityTraitExposureAndProbabilityValuesArePinned() {
        var veryAttacking = config.getMentalities().get(Mentality.VERY_ATTACKING);
        assertThat(veryAttacking.getMidfieldToAttack()).isEqualTo(0.90);
        assertThat(veryAttacking.getMidfieldToDefense()).isEqualTo(0.10);
        assertThat(veryAttacking.getTransferFrom()).isEqualTo(Compartment.DEFENSE);
        assertThat(veryAttacking.getTransferTo()).isEqualTo(Compartment.ATTACK);
        assertThat(veryAttacking.getTransferShare()).isEqualTo(0.20);
        assertThat(veryAttacking.getOpenness()).isEqualTo(1.15);

        var refuses = config.getWorkRate().getTraits().get(PlayerTrait.REFUSES_DEFENSIVE_WORK);
        assertThat(refuses.getEngagement()).isEqualTo(0.08);
        assertThat(refuses.getAttackMultiplier()).isEqualTo(1.15);
        assertThat(refuses.isIgnoresDefensiveInstructions()).isTrue();
        assertThat(refuses.getForcedDefensiveMoraleDelta()).isEqualTo(-3.0);

        assertThat(config.getExposure().getCoverageReduction()).isEqualTo(0.65);
        assertThat(config.getExposure().getSecondDmWeight()).isEqualTo(0.55);
        assertThat(config.getExposure().getCbRecoveryPaceCap()).isEqualTo(0.50);
        assertThat(config.getExposure().getPenaltyStrength()).isEqualTo(0.55);
        assertThat(config.getExposure().getPenaltyExponent()).isEqualTo(1.70);

        assertThat(config.getProbability().getMatchupExponent()).isEqualTo(1.5);
        assertThat(config.getProbability().getHomeAdvantage()).isEqualTo(1.08);
        assertThat(config.getProbability().getGammaShape()).isEqualTo(12.0);
        assertThat(config.getProbability().getGoalCap()).isEqualTo(7);
        assertThat(config.getProbability().getExtraTimeScale()).isCloseTo(1.0 / 3.0, within(1e-15));
        assertThat(config.getProbability().getIntervalLowerQuantile()).isEqualTo(0.05);
        assertThat(config.getProbability().getIntervalUpperQuantile()).isEqualTo(0.95);
    }
}
