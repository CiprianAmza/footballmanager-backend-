package com.footballmanagergamesimulator.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * De-risks the {@code match.engine.player-value} config binding before designers populate all
 * eight position blocks. Confirms the supported YAML key forms for the nested attribute-weight
 * and familiarity maps — including the multi-word attribute names (e.g. "First Touch") that
 * require bracket/quoted keys.
 */
class PlayerValueConfigBindingTest {

    private MatchEngineConfig bind(Map<String, Object> props) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(props);
        return new Binder(source).bind("match.engine", MatchEngineConfig.class)
                .orElseGet(MatchEngineConfig::new);
    }

    @Test
    void bindsSingleWordAndMultiWordAttributeWeights() {
        Map<String, Object> props = new LinkedHashMap<>();
        // single-word attribute: plain dotted key
        props.put("match.engine.player-value.weights.ST.Finishing", 3.0);
        // multi-word attribute: bracket form (the YAML quoted-key equivalent)
        props.put("match.engine.player-value.weights.ST[First Touch]", 4.0);
        props.put("match.engine.player-value.scale-multiplier", 12.0);

        MatchEngineConfig cfg = bind(props);

        assertThat(cfg.getPlayerValue().getScaleMultiplier()).isEqualTo(12.0);
        // overrides win per-attribute over the shipped default profile
        assertThat(cfg.getPlayerValue().weight("ST", "Finishing")).isEqualTo(3.0);
        assertThat(cfg.getPlayerValue().weight("ST", "First Touch")).isEqualTo(4.0);
        // a non-overridden attribute keeps its shipped ST default (Heading = 4)
        assertThat(cfg.getPlayerValue().weight("ST", "Heading")).isEqualTo(4.0);
        // an attribute irrelevant to the position's default profile resolves to 0.0 (DC has no Finishing)
        assertThat(cfg.getPlayerValue().weight("DC", "Finishing")).isEqualTo(0.0);
        // a non-overridden position keeps its shipped default (DC Tackling = 5)
        assertThat(cfg.getPlayerValue().weight("DC", "Tackling")).isEqualTo(5.0);
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
