package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig.InstructionWeights.ConflictPair;
import com.footballmanagergamesimulator.config.MatchEngineConfig.InstructionWeights.InstructionBonus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure-logic tests for {@link PlayerInstructionService#computeInstructionMultiplier} — the
 * config-driven per-instruction bonus table. Shares a plain {@link MatchEngineConfig} with
 * production so defaults and overrides behave identically in both.
 */
class PlayerInstructionServiceTest {

    private PlayerInstructionService service;
    private MatchEngineConfig cfg;

    @BeforeEach
    void setUp() {
        service = new PlayerInstructionService();
        cfg = new MatchEngineConfig();
        service.engineConfig = cfg;
    }

    @Test
    void noInstructions_isNeutral() {
        assertThat(service.computeInstructionMultiplier(List.of(), "MC", "general")).isEqualTo(1.0);
    }

    @Test
    void defaultBonus_usesPerPositionExceptionOverBase() {
        // "Mark Tighter": base -0.005, but DC gets +0.01
        assertThat(service.computeInstructionMultiplier(List.of("Mark Tighter"), "DC", "general"))
                .isCloseTo(1.01, within(1e-9));
        assertThat(service.computeInstructionMultiplier(List.of("Mark Tighter"), "MC", "general"))
                .isCloseTo(0.995, within(1e-9));
    }

    @Test
    void overrideReplacesAnInstructionEntry() {
        InstructionBonus ib = new InstructionBonus();
        ib.setBase(0.05);
        cfg.getInstructionWeights().setBonuses(Map.of("Stay Wider", ib));

        // Override applies regardless of position (no per-position exceptions in the override).
        assertThat(service.computeInstructionMultiplier(List.of("Stay Wider"), "DC", "general"))
                .isCloseTo(1.05, within(1e-9));
    }

    @Test
    void bonusScale_scalesTheAccumulatedBonus() {
        cfg.getInstructionWeights().setBonusScale(2.0);
        // "Stay Wider" ML = 0.008 → ×2 = 0.016
        assertThat(service.computeInstructionMultiplier(List.of("Stay Wider"), "ML", "general"))
                .isCloseTo(1.016, within(1e-9));
    }

    @Test
    void conflictingPair_appliesTheConflictPenalty() {
        // Tackle Harder (0.005) + Ease Off Tackles (-0.003) = 0.002, minus conflict 0.02 = -0.018
        assertThat(service.computeInstructionMultiplier(List.of("Tackle Harder", "Ease Off Tackles"), "DC", "general"))
                .isCloseTo(0.982, within(1e-9));
    }

    @Test
    void clampsToConfiguredBounds() {
        cfg.getInstructionWeights().setBonusScale(100.0); // blow past the ceiling
        assertThat(service.computeInstructionMultiplier(List.of("Shoot More Often"), "ST", "general"))
                .isCloseTo(cfg.getInstructionWeights().getClampMax(), within(1e-9));
    }

    @Test
    void conflictPairList_isConfigurable() {
        // Replace the shipped pairs with a single custom one; the old pairs no longer conflict.
        cfg.getInstructionWeights().setConflicts(List.of(
                new ConflictPair("Shoot More Often", "Pass It Shorter")));

        // The previously-shipped Tackle Harder / Ease Off Tackles pair now carries no penalty:
        // 0.005 + (-0.003) = 0.002 → 1.002
        assertThat(service.computeInstructionMultiplier(List.of("Tackle Harder", "Ease Off Tackles"), "DC", "general"))
                .isCloseTo(1.002, within(1e-9));

        // The newly-configured pair does apply the penalty: ST Shoot More Often (0.01) +
        // Pass It Shorter (0.003) = 0.013, minus conflict 0.02 = -0.007 → 0.993
        assertThat(service.computeInstructionMultiplier(List.of("Shoot More Often", "Pass It Shorter"), "ST", "general"))
                .isCloseTo(0.993, within(1e-9));
    }
}
