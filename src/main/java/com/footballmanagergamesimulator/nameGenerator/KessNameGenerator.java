package com.footballmanagergamesimulator.nameGenerator;

import java.util.random.RandomGenerator;

/** Harsh/guttural names ({@link NameStyles#KESS}): Kagor, Nekrur. */
public class KessNameGenerator extends AbstractNameGeneratorStrategy {

    public KessNameGenerator() {
        super(NameStyles.KESS);
    }

    public KessNameGenerator(RandomGenerator random) {
        super(NameStyles.KESS, random);
    }
}
