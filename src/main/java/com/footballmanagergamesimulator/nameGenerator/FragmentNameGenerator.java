package com.footballmanagergamesimulator.nameGenerator;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Assembles names from a {@link NameStyle}: picks one of the style's weighted
 * patterns, then a random fragment from each pool the pattern references.
 *
 * <p>The randomness source is injectable so tests can pass a seeded
 * {@link Random} and get a reproducible sequence — see {@link #seeded}.
 */
public final class FragmentNameGenerator implements NameGeneratorStrategy {

    private final NameStyle style;
    private final RandomGenerator random;

    public FragmentNameGenerator(NameStyle style) {
        this(style, new Random());
    }

    public FragmentNameGenerator(NameStyle style, RandomGenerator random) {
        this.style = Objects.requireNonNull(style, "style");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** A generator whose output sequence is fully determined by the seed. */
    public static FragmentNameGenerator seeded(NameStyle style, long seed) {
        return new FragmentNameGenerator(style, new Random(seed));
    }

    /** The nationId is selection input for {@link CompositeNameGenerator}, not for assembly. */
    @Override
    public String generateName(long nationId) {
        return generateName();
    }

    public String generateName() {
        NameStyle.Pattern pattern = pickPattern();
        StringBuilder name = new StringBuilder();
        for (String poolKey : pattern.poolKeys()) {
            List<String> pool = style.pool(poolKey);
            name.append(pool.get(random.nextInt(pool.size())));
        }
        return name.toString();
    }

    private NameStyle.Pattern pickPattern() {
        List<NameStyle.Pattern> patterns = style.patterns();
        if (patterns.size() == 1)
            return patterns.get(0);
        int roll = random.nextInt(style.totalWeight());
        for (NameStyle.Pattern pattern : patterns) {
            roll -= pattern.weight();
            if (roll < 0)
                return pattern;
        }
        return patterns.get(patterns.size() - 1);
    }

    public NameStyle style() {
        return style;
    }
}
