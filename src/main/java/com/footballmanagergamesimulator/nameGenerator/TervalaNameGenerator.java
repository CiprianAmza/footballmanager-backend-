package com.footballmanagergamesimulator.nameGenerator;

import java.util.random.RandomGenerator;

/** Forest surnames for the Tervala nation ({@link NameStyles#TERVALA}): Kuusala, Koivuniemi. */
public class TervalaNameGenerator extends AbstractNameGeneratorStrategy {

    public TervalaNameGenerator() {
        super(NameStyles.TERVALA);
    }

    public TervalaNameGenerator(RandomGenerator random) {
        super(NameStyles.TERVALA, random);
    }
}
