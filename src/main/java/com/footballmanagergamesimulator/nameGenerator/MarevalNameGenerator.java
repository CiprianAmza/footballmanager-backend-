package com.footballmanagergamesimulator.nameGenerator;

import java.util.random.RandomGenerator;

/** Salty harbour surnames for the Mareval nation ({@link NameStyles#MAREVAL}): Carveiro, Montalho. */
public class MarevalNameGenerator extends AbstractNameGeneratorStrategy {

    public MarevalNameGenerator() {
        super(NameStyles.MAREVAL);
    }

    public MarevalNameGenerator(RandomGenerator random) {
        super(NameStyles.MAREVAL, random);
    }
}
