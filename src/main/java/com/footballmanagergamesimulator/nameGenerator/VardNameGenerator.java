package com.footballmanagergamesimulator.nameGenerator;

import java.util.random.RandomGenerator;

/** Nordic names ({@link NameStyles#VARD}): Valandar, Skorvald. */
public class VardNameGenerator extends AbstractNameGeneratorStrategy {

    public VardNameGenerator() {
        super(NameStyles.VARD);
    }

    public VardNameGenerator(RandomGenerator random) {
        super(NameStyles.VARD, random);
    }
}
