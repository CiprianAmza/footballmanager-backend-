package com.footballmanagergamesimulator.animation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Feature flag for the v2 animation engine. Default OFF — the legacy
 * {@code GoalAnimation*} engine remains the production path until the
 * canonical Revision 5 refactor lands and the live session is wired to
 * {@link AnimationDirector} (see ANIMATION_V2_HANDOFF.md).
 */
@Component
public class AnimationV2Settings {

    @Value("${match.animation.v2.enabled:false}")
    private boolean enabled;

    @Value("${match.animation.v2.max-player-step:0.9}")
    private double maxPlayerStep;

    @Value("${match.animation.v2.max-player-acceleration:0.45}")
    private double maxPlayerAcceleration;

    public boolean isEnabled() {
        return enabled;
    }

    public AnimationMotionLimits motionLimits() {
        return new AnimationMotionLimits(maxPlayerStep, maxPlayerAcceleration);
    }
}
