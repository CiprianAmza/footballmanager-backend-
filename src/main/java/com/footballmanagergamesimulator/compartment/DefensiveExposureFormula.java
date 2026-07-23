package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig.WorkRule;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure contract for defensive engagement, exposure, nonlinear residual risk and AP reduction. */
public final class DefensiveExposureFormula {

    private final CompartmentEngineConfig config;

    public DefensiveExposureFormula(CompartmentEngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public WorkBehavior resolveWorkBehavior(Set<PlayerTrait> traits, ForwardInstruction instruction,
                                            boolean forcedDefensive) {
        Set<PlayerTrait> safeTraits = traits == null ? Set.of() : Set.copyOf(traits);
        ForwardInstruction safeInstruction = instruction == null ? ForwardInstruction.DEFAULT : instruction;
        WorkRule rule;
        boolean traitOverride = safeTraits.contains(PlayerTrait.REFUSES_DEFENSIVE_WORK);
        if (traitOverride) {
            rule = config.getWorkRate().getTraits().get(PlayerTrait.REFUSES_DEFENSIVE_WORK);
        } else {
            rule = config.getWorkRate().getInstructions().get(safeInstruction);
        }
        if (rule == null) throw new IllegalStateException("Missing work-rate rule");
        double moraleDelta = forcedDefensive ? rule.getForcedDefensiveMoraleDelta() : 0.0;
        return new WorkBehavior(rule.getEngagement(), rule.getAttackMultiplier(),
                traitOverride && rule.isIgnoresDefensiveInstructions(), moraleDelta);
    }

    public double exposure(List<ZoneEngagement> sources) {
        double exposure = 0;
        for (ZoneEngagement source : List.copyOf(sources)) {
            Double zoneWeight = config.getExposure().getZoneWeights().get(source.zone());
            if (zoneWeight == null) throw new IllegalArgumentException("Unknown exposure zone: " + source.zone());
            if (!Double.isFinite(source.engagement()) || source.engagement() < 0) {
                throw new IllegalArgumentException("engagement must be finite and non-negative");
            }
            exposure += zoneWeight * (1.0 - source.engagement());
        }
        return exposure;
    }

    public double coverage(double bestDm, double secondDm, double cbRecoveryPace) {
        requireUnit(bestDm, "bestDm");
        requireUnit(secondDm, "secondDm");
        requireUnit(cbRecoveryPace, "cbRecoveryPace");
        return bestDm + config.getExposure().getSecondDmWeight() * secondDm
                + Math.min(cbRecoveryPace, config.getExposure().getCbRecoveryPaceCap());
    }

    public ExposureResult apply(double attackProtection, double exposure, double coverage) {
        if (!Double.isFinite(attackProtection) || attackProtection < 0) {
            throw new IllegalArgumentException("attackProtection must be finite and non-negative");
        }
        if (!Double.isFinite(exposure) || !Double.isFinite(coverage) || coverage < 0) {
            throw new IllegalArgumentException("exposure/coverage must be finite and coverage non-negative");
        }
        double residualRisk = Math.max(0.0,
                exposure - config.getExposure().getCoverageReduction() * coverage);
        double penaltyMultiplier = Math.exp(-config.getExposure().getPenaltyStrength()
                * Math.pow(residualRisk, config.getExposure().getPenaltyExponent()));
        return new ExposureResult(exposure, coverage, residualRisk, penaltyMultiplier,
                attackProtection * penaltyMultiplier);
    }

    private static void requireUnit(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be in [0,1]");
        }
    }

    public record WorkBehavior(double engagement, double attackMultiplier,
                               boolean ignoresDefensiveInstructions, double moraleDelta) {}
    public record ZoneEngagement(String zone, double engagement) {
        public ZoneEngagement {
            if (zone == null || zone.isBlank()) throw new IllegalArgumentException("zone is required");
        }
    }
    public record ExposureResult(double exposure, double coverage, double residualRisk,
                                 double protectionMultiplier, double finalAttackProtection) {}
}
