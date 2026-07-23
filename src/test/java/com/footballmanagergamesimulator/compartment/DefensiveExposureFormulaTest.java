package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DefensiveExposureFormulaTest {

    private final CompartmentEngineConfig config = CompartmentConfigFixture.load();
    private final DefensiveExposureFormula formula = new DefensiveExposureFormula(config);

    @Test
    void refusesDefensiveWorkOverridesTrackBackAndAppliesForcedMoraleCost() {
        var behavior = formula.resolveWorkBehavior(Set.of(PlayerTrait.REFUSES_DEFENSIVE_WORK),
                ForwardInstruction.TRACK_BACK, true);
        assertThat(behavior.engagement()).isEqualTo(0.08);
        assertThat(behavior.attackMultiplier()).isEqualTo(1.15);
        assertThat(behavior.ignoresDefensiveInstructions()).isTrue();
        assertThat(behavior.moraleDelta()).isEqualTo(-3.0);

        var stayForward = formula.resolveWorkBehavior(Set.of(), ForwardInstruction.STAY_FORWARD, true);
        assertThat(stayForward.engagement()).isEqualTo(0.30);
        assertThat(stayForward.attackMultiplier()).isEqualTo(1.08);
        assertThat(stayForward.moraleDelta()).isEqualTo(0.0);

        var trackBack = formula.resolveWorkBehavior(Set.of(), ForwardInstruction.TRACK_BACK, false);
        assertThat(trackBack.engagement()).isEqualTo(1.15);
        assertThat(trackBack.attackMultiplier()).isEqualTo(0.95);
    }

    @Test
    void exposureCoverageResidualAndNonlinearProtectionFollowContract() {
        double exposure = formula.exposure(List.of(
                new DefensiveExposureFormula.ZoneEngagement("CENTRAL", 0.08),
                new DefensiveExposureFormula.ZoneEngagement("HALF_SPACE", 0.30)));
        double coverage = formula.coverage(0.80, 0.60, 0.90);
        var result = formula.apply(100.0, exposure, coverage);

        assertThat(exposure).isCloseTo(1.48, within(1e-12));
        assertThat(coverage).isCloseTo(1.63, within(1e-12));
        double expectedResidual = Math.max(0, 1.48 - 0.65 * 1.63);
        double expectedMultiplier = Math.exp(-0.55 * Math.pow(expectedResidual, 1.70));
        assertThat(result.residualRisk()).isCloseTo(expectedResidual, within(1e-12));
        assertThat(result.protectionMultiplier()).isCloseTo(expectedMultiplier, within(1e-12));
        assertThat(result.finalAttackProtection()).isCloseTo(100 * expectedMultiplier, within(1e-12));
    }

    @Test
    void multipleRefusersCreateNonlinearlyLargerPenaltyWhenCoverageIsFixed() {
        double one = formula.exposure(List.of(
                new DefensiveExposureFormula.ZoneEngagement("CENTRAL", 0.08)));
        double three = formula.exposure(List.of(
                new DefensiveExposureFormula.ZoneEngagement("CENTRAL", 0.08),
                new DefensiveExposureFormula.ZoneEngagement("HALF_SPACE", 0.08),
                new DefensiveExposureFormula.ZoneEngagement("WIDE", 0.08)));
        double coverage = formula.coverage(0.90, 0.70, 0.80);

        var oneResult = formula.apply(100, one, coverage);
        var threeResult = formula.apply(100, three, coverage);
        assertThat(oneResult.finalAttackProtection()).isGreaterThan(threeResult.finalAttackProtection());
        assertThat(threeResult.residualRisk()).isGreaterThan(oneResult.residualRisk());
    }
}
