package com.footballmanagergamesimulator.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Rejects an authoritative scoring path that cannot persist its decision. */
@Component
public final class CompartmentRolloutValidator {
    private final MatchEngineConfig matchConfig;

    public CompartmentRolloutValidator(MatchEngineConfig matchConfig) {
        this.matchConfig = Objects.requireNonNull(matchConfig, "matchConfig");
    }

    @PostConstruct
    public void validateAtStartup() {
        if (!matchConfig.getMatchPlan().isEnabled()) {
            throw new IllegalStateException(
                    "the authoritative compartment engine requires match.engine.match-plan.enabled");
        }
    }
}
