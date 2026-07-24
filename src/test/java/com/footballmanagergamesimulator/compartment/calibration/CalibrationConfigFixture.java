package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
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
    static CompartmentEngineConfig load() {
        try {
            MutablePropertySources properties = new MutablePropertySources();
            for (PropertySource<?> source : new YamlPropertySourceLoader().load("phase13-weights",
                    new ClassPathResource("compartment-scoring-weights-v1.yml"))) properties.addLast(source);
            for (PropertySource<?> source : new YamlPropertySourceLoader().load("application",
                    new ClassPathResource("application.yml"))) properties.addLast(source);
            return new Binder(ConfigurationPropertySources.from(properties))
                    .bind("match.engine.compartment", Bindable.of(CompartmentEngineConfig.class))
                    .orElseThrow(() -> new IllegalStateException("missing compartment calibration profile"));
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load calibration config", exception);
        }
    }
}
