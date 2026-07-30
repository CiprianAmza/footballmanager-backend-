package com.footballmanagergamesimulator.nameGenerator;

import java.util.List;
import java.util.Map;

/**
 * The phonetic identities shipped with the game, one per fictional culture.
 * Pool keys are shared vocabulary: PREFIX, VOWEL, MIDDLE, SUFFIX.
 */
public final class NameStyles {

    public static final String PREFIX = "PREFIX";
    public static final String VOWEL = "VOWEL";
    public static final String MIDDLE = "MIDDLE";
    public static final String SUFFIX = "SUFFIX";

    /** Latin/galactic: Exansius, Ireolus. Historic default, also used by the legacy static generator. */
    public static final NameStyle ELEVEN = NameStyle.of("eleven",
            Map.of(
                    PREFIX, List.of("Ex", "Aec", "Oc", "Eol", "Ir", "Uv", "Uk", "Ux", "Ax", "Ox", "Ix", "Iec", "Iol"),
                    VOWEL, List.of("a", "e", "i", "o", "u"),
                    MIDDLE, List.of("p", "n", "an", "rp"),
                    SUFFIX, List.of("ius", "us", "sus", "sius")),
            List.of(new NameStyle.Pattern(1, List.of(PREFIX, VOWEL, MIDDLE, SUFFIX))));

    /** Harsh/guttural: Kagor, Nekrur. */
    public static final NameStyle KESS = NameStyle.of("kess",
            Map.of(
                    PREFIX, List.of("No", "Nu", "Na", "Me", "Ma", "Mu", "Mu", "Ko", "K", "Ke", "Ka", "Kr", "Kv"),
                    VOWEL, List.of("a", "e", "i", "o", "u"),
                    MIDDLE, List.of("g", "r", "k", "kr"),
                    SUFFIX, List.of("or", "nor", "gor", "vor", "yvor", "vur", "nur", "zur", "pur", "ypur", "yvur", "ur")),
            List.of(new NameStyle.Pattern(1, List.of(PREFIX, VOWEL, MIDDLE, SUFFIX))));

    /** Nordic: Valandar, Skorvald. Two patterns, as the original VardNameGenerator flipped a coin. */
    public static final NameStyle VARD = NameStyle.of("vard",
            Map.of(
                    PREFIX, List.of("Al", "Ar", "Bal", "Br", "Dal", "Dr", "El", "Fal", "Gr", "Hal", "Jar", "Sk", "Th", "Val"),
                    VOWEL, List.of("a", "e", "i", "o", "u"),
                    MIDDLE, List.of("l", "r", "n", "nd", "rg", "rn", "sk", "v"),
                    SUFFIX, List.of("ar", "ard", "en", "ir", "orn", "rik", "und", "var", "vald", "heim")),
            List.of(
                    new NameStyle.Pattern(1, List.of(PREFIX, VOWEL, MIDDLE, SUFFIX)),
                    new NameStyle.Pattern(1, List.of(PREFIX, MIDDLE, VOWEL, SUFFIX))));

    /**
     * Melodic/lyrical, for the Literature nation: Aurelio, Belanora, Calindiel.
     * Prefixes end in soft consonants and suffixes open with vowels, so every
     * junction alternates consonant/vowel and stays pronounceable. The short
     * PREFIX+VOWEL+SUFFIX pattern gets a smaller weight to keep most names fuller.
     */
    public static final NameStyle LIRA = NameStyle.of("lira",
            Map.of(
                    PREFIX, List.of("Ael", "Al", "Aur", "Bel", "Cal", "El", "Fen", "Il", "Lor", "Mar", "Or", "Sel", "Tal", "Vel"),
                    VOWEL, List.of("a", "e", "i", "o"),
                    MIDDLE, List.of("l", "ll", "n", "nd", "r", "s", "v", "m"),
                    SUFFIX, List.of("ia", "io", "ien", "iel", "ora", "aris", "ino", "era", "elle", "ande")),
            List.of(
                    new NameStyle.Pattern(3, List.of(PREFIX, VOWEL, MIDDLE, SUFFIX)),
                    new NameStyle.Pattern(1, List.of(PREFIX, VOWEL, SUFFIX))));

    private NameStyles() {
    }
}
