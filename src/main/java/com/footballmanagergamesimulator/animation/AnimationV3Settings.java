package com.footballmanagergamesimulator.animation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AnimationV3Settings {
    @Value("${match.animation.v3.enabled:false}")
    private boolean enabled;
    @Value("${match.animation.v3.max-player-step:0.9}")
    private double maxPlayerStep;
    @Value("${match.animation.v3.max-player-acceleration:0.45}")
    private double maxPlayerAcceleration;
    @Value("${match.animation.v3.max-ball-step:4.0}")
    private double maxBallStep;

    public boolean enabled() {
        return enabled;
    }

    public AnimationPhysicsProfile physicsProfile() {
        return new AnimationPhysicsProfile(maxPlayerStep, maxPlayerAcceleration, maxBallStep);
    }
}
