package com.footballmanagergamesimulator.nameGenerator;

import java.util.List;
import java.util.Random;

public class ElevenNameGenerator extends AbstractNameGeneratorStrategy {

    private static List<String> SUFFIXES = List.of("ius", "us", "sus", "sius");
    private static List<String> MIDDLE = List.of("p", "n", "an", "rp");
    private static List<String> VOWELS = List.of("a", "e", "i", "o", "u");
    private static List<String> PREFIXES = List.of("Ex", "Aec", "Oc", "Eol", "Ir", "Uv", "Uk", "Ux", "Ax", "Ox", "Ix", "Iec", "Iol");


    @Override
    public String generateName(long nationId) {

        StringBuilder name = new StringBuilder();
        Random random = new Random();
        name.append(PREFIXES.get(random.nextInt(PREFIXES.size())));
        name.append(VOWELS.get(random.nextInt(VOWELS.size())));
        name.append(MIDDLE.get(random.nextInt(MIDDLE.size())));
        name.append(SUFFIXES.get(random.nextInt(SUFFIXES.size())));

        return name.toString();
    }
}
