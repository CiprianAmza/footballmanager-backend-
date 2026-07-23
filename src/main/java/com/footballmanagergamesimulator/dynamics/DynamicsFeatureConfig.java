package com.footballmanagergamesimulator.dynamics;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Master switch for the Team Dynamics section (hierarchy, social groups,
 * happiness, monthly team meeting, player conversations). Defaults OFF until
 * rollout; when disabled the API exposes explicit empty/disabled states and
 * no dynamics data is computed or persisted.
 */
@Component
@ConfigurationProperties(prefix = "dynamics")
@Data
public class DynamicsFeatureConfig {

    private boolean enabled = false;
}
