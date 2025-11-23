package com.footballmanagergamesimulator.nameGenerator;

import java.util.List;
import java.util.Random;

public class KessNameGenerator extends AbstractNameGeneratorStrategy {

    private static List<String> SUFFIXES = List.of("or", "nor", "gor", "vor", "yvor", "vur", "nur", "zur", "pur", "ypur", "yvur", "ur");
    private static List<String> MIDDLE = List.of("g", "r", "k", "kr");
    private static List<String> VOWELS = List.of("a", "e", "i", "o", "u");
    private static List<String> PREFIXES = List.of("No", "Nu", "Na", "Me", "Ma", "Mu", "Mu", "Ko", "K", "Ke", "Ka", "Kr", "Kv");


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

