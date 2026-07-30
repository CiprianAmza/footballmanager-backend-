package com.footballmanagergamesimulator.nameGenerator;

import java.util.random.RandomGenerator;

/** Latin/galactic names ({@link NameStyles#ELEVEN}): Exansius, Ireolus. */
public class ElevenNameGenerator extends AbstractNameGeneratorStrategy {

    public ElevenNameGenerator() {
        super(NameStyles.ELEVEN);
    }

    public ElevenNameGenerator(RandomGenerator random) {
        super(NameStyles.ELEVEN, random);
    }
}
