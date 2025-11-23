package com.footballmanagergamesimulator.nameGenerator;

import com.footballmanagergamesimulator.transfermarket.*;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class CompositeNameGenerator implements NameGeneratorStrategy {

    private final Map<Long, NameGeneratorStrategy> _transferStrategies = new HashMap<>();

    @PostConstruct
    public void init() {

        _transferStrategies.put(NameGeneratorUtil.ELEVEN_NAME_GENERATOR_STRATEGY, new ElevenNameGenerator());
        _transferStrategies.put(NameGeneratorUtil.KESS_NAME_GENERATOR_STRATEGY, new KessNameGenerator());

    }

    @Override
    public String generateName(long nationId) {
        
        return _transferStrategies.getOrDefault(nationId, new ElevenNameGenerator()).generateName(nationId);
    }
}
