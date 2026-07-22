package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Physically impossible physics profiles are rejected at construction, before any generation. */
class AnimationProfileTest {
    @Test void nonPositiveLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AnimationPhysicsProfile(0, 0.45, 4));
        assertThrows(IllegalArgumentException.class, () -> new AnimationPhysicsProfile(0.9, -1, 4));
        assertThrows(IllegalArgumentException.class, () -> new AnimationPhysicsProfile(0.9, 0.45, Double.NaN));
    }

    @Test void impossiblySmallBallStepIsRejected() {
        // The exact configuration that previously threw from the compiler is now rejected up front.
        assertThrows(IllegalArgumentException.class, () -> new AnimationPhysicsProfile(0.9, 0.45, 0.01));
        assertThrows(IllegalArgumentException.class, () -> new AnimationPhysicsProfile(0.9, 0.45, 0.5));
    }

    @Test void impossiblySmallPlayerLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AnimationPhysicsProfile(0.1, 0.45, 4));
        assertThrows(IllegalArgumentException.class, () -> new AnimationPhysicsProfile(0.9, 0.02, 4));
    }

    @Test void ballSlowerThanPlayerIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AnimationPhysicsProfile(1.5, 0.6, 1.2));
    }

    @Test void feasibleProfilesAreAccepted() {
        assertDoesNotThrow(AnimationPhysicsProfile::defaults);
        assertDoesNotThrow(() -> new AnimationPhysicsProfile(0.6, 0.25, 4.0));
        assertDoesNotThrow(() -> new AnimationPhysicsProfile(0.5, 0.2, 1.5));
    }

    @Test void settingsExposeTheConfiguredProfile() {
        AnimationV3Settings settings = new AnimationV3Settings();
        // Field defaults mirror application.yml defaults and must build a feasible profile.
        assertDoesNotThrow(settings::physicsProfile);
        assertFalse(settings.enabled());
    }
}
