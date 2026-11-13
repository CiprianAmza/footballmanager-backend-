package com.footballmanagergamesimulator.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * De-risks the {@code match.engine.player-value} familiarity matrix binding.
 */
class PlayerValueConfigBindingTest {

    private MatchEngineConfig bind(Map<String, Object> props) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(props);
        return new Binder(source).bind("match.engine", MatchEngineConfig.class)
                .orElseGet(MatchEngineConfig::new);
    }

    @Test
    void bindsFamiliarityPenaltyMatrix() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("match.engine.player-value.default-familiarity-penalty", 0.4);
        props.put("match.engine.player-value.familiarity-penalty.MR.ML", 0.8);
        props.put("match.engine.player-value.familiarity-penalty.ST.DC", 0.2);

        MatchEngineConfig cfg = bind(props);

        assertThat(cfg.getPlayerValue().familiarity("MR", "MR")).isEqualTo(1.0); // natural
        assertThat(cfg.getPlayerValue().familiarity("MR", "ML")).isEqualTo(0.8); // override
        assertThat(cfg.getPlayerValue().familiarity("ST", "DC")).isEqualTo(0.2); // override
        assertThat(cfg.getPlayerValue().familiarity("ST", "GK")).isEqualTo(0.1); // shipped default matrix
        assertThat(cfg.getPlayerValue().familiarity("XX", "YY")).isEqualTo(0.4); // unknown ⇒ configured default
    }
}
