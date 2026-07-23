package com.footballmanagergamesimulator.animation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AnimationV3Settings {
    // Java initializers mirror the @Value defaults so the component is usable without Spring wiring.
    @Value("${match.animation.v3.enabled:false}")
    private boolean enabled = false;
    @Value("${match.animation.v3.max-player-step:0.9}")
    private double maxPlayerStep = 0.9;
    @Value("${match.animation.v3.max-player-acceleration:0.45}")
    private double maxPlayerAcceleration = 0.45;
    @Value("${match.animation.v3.max-ball-step:4.0}")
    private double maxBallStep = 4.0;

    public boolean enabled() {
        return enabled;
    }

    public AnimationPhysicsProfile physicsProfile() {
        return new AnimationPhysicsProfile(maxPlayerStep, maxPlayerAcceleration, maxBallStep);
    }
}
