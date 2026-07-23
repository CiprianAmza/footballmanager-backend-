package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class CompartmentConfigFixture {

    private CompartmentConfigFixture() {}

    static CompartmentEngineConfig load() {
        try {
            MutablePropertySources properties = new MutablePropertySources();
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"))) {
                properties.addLast(source);
            }
            return new Binder(ConfigurationPropertySources.from(properties))
                    .bind("match.engine.compartment", Bindable.of(CompartmentEngineConfig.class))
                    .orElseThrow(() -> new IllegalStateException("compartment config is not bound"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load application.yml", e);
        }
    }

    static Map<PlayerAttribute, Integer> attributes(CompartmentEngineConfig config, int value) {
        Map<PlayerAttribute, Integer> attributes = new LinkedHashMap<>();
        config.getCompartments().values().forEach(weights ->
                weights.getAttributes().keySet().forEach(name -> attributes.put(name, value)));
        config.getPositionCompartmentOverrides().values().forEach(byCompartment ->
                byCompartment.values().forEach(weights ->
                        weights.getAttributes().keySet().forEach(name -> attributes.put(name, value))));
        return attributes;
    }
}
