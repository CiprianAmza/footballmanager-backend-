package com.footballmanagergamesimulator.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RatingWeightsConfigTest {

    @Test
    @DisplayName("""
            GIVEN the rating weight table written in application.yml
            WHEN it is bound the way Spring binds it at startup
            THEN every position reads exactly the same weights as the built-in table
              (names with spaces such as "One On Ones" included)
            """)
    void applicationYmlMatchesTheBuiltInTable() throws Exception {
        MutablePropertySources properties = new MutablePropertySources();
        // The production file, not the test-classpath application.yml that shadows it.
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new FileSystemResource("src/main/resources/application.yml"))) {
            properties.addLast(source);
        }
        RatingWeightsConfig fromYml = new Binder(ConfigurationPropertySources.from(properties))
                .bind("game.rating-weights", Bindable.of(RatingWeightsConfig.class))
                .orElseThrow(IllegalStateException::new);
        RatingWeightsConfig builtIn = new RatingWeightsConfig();

        for (String position : List.of("GK", "DC", "DL", "DR", "MC", "ML", "MR", "ST")) {
            assertEquals(builtIn.weightsFor(position), fromYml.weightsFor(position), position);
        }
    }
}
