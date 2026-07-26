package com.footballmanagergamesimulator.animation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AnimationV3Settings {
    // Java initializers mirror the @Value defaults so the component is usable without Spring wiring.
    @Value("${match.animation.v3.enabled:false}")
    private boolean enabled = false;
    @Value("${match.animation.v3.max-player-step:0.45}")
    private double maxPlayerStep = 0.45;
    @Value("${match.animation.v3.max-player-acceleration:0.15}")
    private double maxPlayerAcceleration = 0.15;
    @Value("${match.animation.v3.max-ball-step:1.5}")
    private double maxBallStep = 1.5;

    public boolean enabled() {
        return enabled;
    }

    public AnimationPhysicsProfile physicsProfile() {
        return new AnimationPhysicsProfile(maxPlayerStep, maxPlayerAcceleration, maxBallStep);
    }
}
