package com.footballmanagergamesimulator.nameGenerator;

import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Base for style-backed strategies: each subclass just names its {@link NameStyle};
 * assembly lives in {@link FragmentNameGenerator}. Subclasses expose a
 * {@link RandomGenerator} constructor so tests can seed them.
 */
public abstract class AbstractNameGeneratorStrategy implements NameGeneratorStrategy {

    private final FragmentNameGenerator generator;

    protected AbstractNameGeneratorStrategy(NameStyle style) {
        this(style, new Random());
    }

    protected AbstractNameGeneratorStrategy(NameStyle style, RandomGenerator random) {
        this.generator = new FragmentNameGenerator(style, random);
    }

    @Override
    public final String generateName(long nationId) {
        return generator.generateName();
    }
}
