package com.footballmanagergamesimulator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** General gameplay switches that can be changed without touching simulation code. */
@Component
@ConfigurationProperties(prefix = "gameplay")
@Data
public class GameplayFeatureConfig {
    /**
     * Emergency/game-mode switch: injuries and suspensions neither block team
     * selection nor get generated while this is true.
     */
    private boolean playerAvailabilityDisabled;
}
