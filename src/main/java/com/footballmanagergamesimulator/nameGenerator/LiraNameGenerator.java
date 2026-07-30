package com.footballmanagergamesimulator.nameGenerator;

import java.util.random.RandomGenerator;

/** Melodic/lyrical names for the Literature nation ({@link NameStyles#LIRA}): Aurelio, Belanora. */
public class LiraNameGenerator extends AbstractNameGeneratorStrategy {

    public LiraNameGenerator() {
        super(NameStyles.LIRA);
    }

    public LiraNameGenerator(RandomGenerator random) {
        super(NameStyles.LIRA, random);
    }
}
