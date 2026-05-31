package com.footballmanagergamesimulator.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerCardConfigBindingTest {

    private PlayerCardConfig bind(Map<String, Object> props) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(props);
        return new Binder(source).bind("player.card", PlayerCardConfig.class)
                .orElseGet(PlayerCardConfig::new);
    }

    @Test
    void bindsBucketOverridesAndScaleOverrides() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("player.card.buckets.SHO.Finishing", 2.5);
        props.put("player.card.buckets.SHO[Long Shots]", 4.0);
        props.put("player.card.attribute-scale.target-max", 95.0);

        PlayerCardConfig cfg = bind(props);

        assertThat(cfg.getAttributeScale().getTargetMax()).isEqualTo(95.0);
        assertThat(cfg.weight("SHO", "Finishing")).isEqualTo(2.5);
        assertThat(cfg.weight("SHO", "Long Shots")).isEqualTo(4.0);
        assertThat(cfg.weight("PAC", "Pace")).isEqualTo(1.0);
        assertThat(cfg.weight("DEF", "Finishing")).isEqualTo(0.0);
    }
}
