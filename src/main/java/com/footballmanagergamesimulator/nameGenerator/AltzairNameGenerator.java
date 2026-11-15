package com.footballmanagergamesimulator.nameGenerator;

import java.util.random.RandomGenerator;

/** Forge-country surnames for the Altzair nation ({@link NameStyles#ALTZAIR}): Burdineta, Ikazaga. */
public class AltzairNameGenerator extends AbstractNameGeneratorStrategy {

    public AltzairNameGenerator() {
        super(NameStyles.ALTZAIR);
    }

    public AltzairNameGenerator(RandomGenerator random) {
        super(NameStyles.ALTZAIR, random);
    }
}
