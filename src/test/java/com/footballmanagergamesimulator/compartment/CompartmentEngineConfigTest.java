package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CompartmentEngineConfigTest {

    private final CompartmentEngineConfig config = CompartmentConfigFixture.load();

    @Test
    void productionYamlBindsCompleteTypedContract() {
        assertThat(config.getCompartments()).containsOnlyKeys(Compartment.values());
        assertThat(config.getPositions()).hasSize(14).containsKeys("GK", "DM", "ST");
        assertThat(config.getRoles()).hasSize(PlayerRole.values().length)
                .containsKeys(PlayerRole.POACHER, PlayerRole.PRESSING_FORWARD, PlayerRole.SHADOW_STRIKER);
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
        assertThat(config.getRating().getFitnessFloor()).isEqualTo(1.0);
        assertThat(config.getRating().getMoraleSlope()).isZero();
        assertThat(config.getRating().getAttributeMax()).isEqualTo(19);
        assertThat(config.getRating().getExceptionalAttributeValue()).isEqualTo(20);
        assertThat(config.getShooter().getStandardShotDistribution()).containsExactly(
                0.25, 0.40, 0.20, 0.075, 0.05, 0.025);
        assertThat(config.getShooter().getExceptionalPositioningShotDistribution()).containsExactly(
                0.05, 0.50, 0.225, 0.10, 0.075, 0.05);
        assertThat(config.getShooter().getPressing()).containsOnlyKeys(
                "VeryEasy", "Easy", "Normal", "Aggressive", "VeryAggressive");
        assertThat(config.getPassingStyle().getMidfieldThreshold()).isEqualTo(19.0);
        assertThat(config.getPassingStyle().getBaseSuppression()).isEqualTo(0.99);
        assertThat(config.getPassingStyle().getAggressiveSuppression()).isEqualTo(0.97);
        assertThat(config.getPassingStyle().getVeryAggressiveSuppression()).isEqualTo(0.90);
        assertThat(config.getPassingStyle().getLongPassingSuppression()).isEqualTo(0.85);
        assertThat(config.getPassingStyle().getFinishing19Factor()).isEqualTo(0.60);
        assertThat(config.getPassingStyle().getStrikerOpportunityDistribution()).containsExactly(
                0.15, 0.25, 0.175, 0.15, 0.125, 0.10, 0.05);
    }

    @Test
    void initialMentalityTraitExposureAndProbabilityValuesArePinned() {
        var veryAttacking = config.getMentalities().get(Mentality.VERY_ATTACKING);
        assertThat(veryAttacking.getMidfieldToAttack()).isEqualTo(0.90);
        assertThat(veryAttacking.getMidfieldToDefense()).isEqualTo(0.10);
        assertThat(veryAttacking.getTransferFrom()).isEqualTo(Compartment.DEFENSE);
        assertThat(veryAttacking.getTransferTo()).isEqualTo(Compartment.ATTACK);
        assertThat(veryAttacking.getTransferShare()).isEqualTo(0.20);
        assertThat(veryAttacking.getOpenness()).isEqualTo(3.10);

        var refuses = config.getWorkRate().getTraits().get(PlayerTrait.REFUSES_DEFENSIVE_WORK);
        assertThat(refuses.getEngagement()).isEqualTo(0.08);
        assertThat(refuses.getAttackMultiplier()).isEqualTo(10.0);
        assertThat(refuses.isIgnoresDefensiveInstructions()).isTrue();
        assertThat(refuses.getForcedDefensiveMoraleDelta()).isEqualTo(-3.0);

        assertThat(config.getExposure().getCoverageReduction()).isEqualTo(0.65);
        assertThat(config.getExposure().getSecondDmWeight()).isEqualTo(0.55);
        assertThat(config.getExposure().getCbRecoveryPaceCap()).isEqualTo(0.50);
        assertThat(config.getExposure().getPenaltyStrength()).isEqualTo(0.55);
        assertThat(config.getExposure().getPenaltyExponent()).isEqualTo(1.70);

        assertThat(config.getProbability().getMatchupExponent()).isEqualTo(3.5);
        assertThat(config.getProbability().getHomeAdvantage()).isEqualTo(1.08);
        assertThat(config.getProbability().getGammaShape()).isEqualTo(8.0);
        assertThat(config.getProbability().getGoalCap()).isEqualTo(7);
        assertThat(config.getProbability().getExtraTimeScale()).isCloseTo(1.0 / 3.0, within(1e-15));
        assertThat(config.getProbability().getIntervalLowerQuantile()).isEqualTo(0.05);
        assertThat(config.getProbability().getIntervalUpperQuantile()).isEqualTo(0.95);
        assertThat(config.getAggregation().getWideRedistributionShare()).isEqualTo(0.20);
    }

    @Test
    void weightsProfileContainsNoRolloutFlags() throws Exception {
        String weights = new String(new org.springframework.core.io.ClassPathResource(
                "compartment-scoring-weights-v1.yml").getInputStream().readAllBytes());
        assertThat(weights).doesNotContain("\n  enabled:").doesNotContain("shadow-enabled");
    }
}
