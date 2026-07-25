package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

final class CalibrationConfigFixture {
    private CalibrationConfigFixture() {}

    static CalibrationConfigProfile load() {
        try {
            MutablePropertySources properties = new MutablePropertySources();
            for (PropertySource<?> source : new YamlPropertySourceLoader().load("phase13-weights",
                    new ClassPathResource("compartment-scoring-weights-v1.yml"))) properties.addLast(source);
            for (PropertySource<?> source : new YamlPropertySourceLoader().load("application",
                    new ClassPathResource("application.yml"))) properties.addLast(source);
            Binder binder = new Binder(ConfigurationPropertySources.from(properties));
            CompartmentEngineConfig compartment = binder.bind("match.engine.compartment",
                    Bindable.of(CompartmentEngineConfig.class))
                    .orElseThrow(() -> new IllegalStateException("missing compartment calibration profile"));
            MatchEngineConfig match = binder.bind("match.engine", Bindable.of(MatchEngineConfig.class))
                    .orElseThrow(() -> new IllegalStateException("missing match calibration profile"));
            return new CalibrationConfigProfile(compartment, match);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load calibration config", exception);
        }
    }
}
