package com.footballmanagergamesimulator.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Rejects a hidden authoritative path that cannot persist its decision. */
@Component
public final class CompartmentRolloutValidator {
    private final CompartmentEngineConfig compartmentConfig;
    private final MatchEngineConfig matchConfig;

    public CompartmentRolloutValidator(CompartmentEngineConfig compartmentConfig,
                                       MatchEngineConfig matchConfig) {
        this.compartmentConfig = Objects.requireNonNull(compartmentConfig, "compartmentConfig");
        this.matchConfig = Objects.requireNonNull(matchConfig, "matchConfig");
    }

    @PostConstruct
    public void validateAtStartup() {
        if (compartmentConfig.isEnabled() && compartmentConfig.isShadowEnabled()) {
            throw new IllegalStateException(
                    "match.engine.compartment.enabled and shadow-enabled are mutually exclusive");
        }
        if (compartmentConfig.isEnabled() && !matchConfig.getMatchPlan().isEnabled()) {
            throw new IllegalStateException(
                    "match.engine.compartment.enabled requires match.engine.match-plan.enabled");
        }
    }
}
