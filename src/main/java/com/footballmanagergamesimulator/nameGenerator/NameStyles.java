package com.footballmanagergamesimulator.nameGenerator;

import java.util.List;
import java.util.Map;

/**
 * The phonetic identities shipped with the game, one per fictional culture.
 * Old styles assemble syllables; the newer cultures use an authored given/family pair.
 */
public final class NameStyles {

    public static final String PREFIX = "PREFIX";
    public static final String VOWEL = "VOWEL";
    public static final String MIDDLE = "MIDDLE";
    public static final String SUFFIX = "SUFFIX";
    public static final String GIVEN = "GIVEN";
    public static final String FAMILY = "FAMILY";
    /** A whole word used as a name stem (Tervala: Koivu, Honka). */
    public static final String WORD = "WORD";
    /** A lowercase place ending appended to a WORD (Tervala: niemi, koski). */
    public static final String PLACE = "PLACE";

    /** Hand-authored portrait players; the allocator leaves these exact pairs for the world seed. */
    public static final Map<Long, List<String>> REFERENCE_NAMES = Map.of(
            11L, List.of("Aven Saloren", "Neris Vaelo"),
            12L, List.of("Dorn Vradek", "Odran Tesk"),
            13L, List.of("Saevi Qelith", "Izel Vaes"));

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

    /**
     * Salty harbour surnames and nicknames, for Mareval: Carveiro, Montalho, Fazinho, Portouro, Salgares.
     * The short PREFIX+SUFFIX pattern keeps nickname-like names in the mix next to the fuller ones.
     */
    public static final NameStyle MAREVAL = NameStyle.of("mareval",
            Map.of(
                    PREFIX, List.of("Carv", "Mont", "Salg", "Port", "Barr", "Cant", "Faz", "Mour", "Pesc", "Riv", "Sard",
                            "Telh", "Vent", "Xav", "Zamb", "Cord", "Lam", "Nav", "Galv", "Farol", "Tav", "Zel",
                            "Alm", "Bras", "Cald", "Dour", "Esp", "Font", "Gand", "Lour", "Mar", "Nog", "Oliv",
                            "Pin", "Quint", "Rib", "Seix", "Tor", "Val", "Vid"),
                    VOWEL, List.of("a", "e", "i", "o"),
                    MIDDLE, List.of("l", "r", "n", "d", "t"),
                    SUFFIX, List.of("eiro", "eira", "alho", "inho", "ares", "ouro", "eza", "edo", "osa")),
            List.of(
                    new NameStyle.Pattern(1, List.of(PREFIX, SUFFIX)),
                    new NameStyle.Pattern(1, List.of(PREFIX, VOWEL, MIDDLE, SUFFIX))));

    /**
     * Forest surnames, for Tervala: Kuusala, Honkisto, Tervonen, Koivuniemi, Susivaara.
     * Besides the fragment patterns, a WORD+PLACE pattern builds compound place-names (Koivu+niemi).
     */
    public static final NameStyle TERVALA = NameStyle.of("tervala",
            Map.of(
                    PREFIX, List.of("Koiv", "Kuus", "Honk", "Terv", "Naav", "Kaarn", "Sammal", "Hav", "Kanerv", "Paj",
                            "Tamm", "Vaar", "Korp", "Louh", "Hirv", "Karh", "Sus", "Tukk", "Sah", "Leht", "Niem",
                            "Saar", "Kiv", "Rant", "Virt", "Lamm", "Pelt", "Ruok", "Vuor", "Kosk"),
                    VOWEL, List.of("a", "e", "i", "u"),
                    MIDDLE, List.of("r", "k", "t", "n", "m", "s"),
                    SUFFIX, List.of("ela", "ala", "ula", "isto", "onen", "anen", "inen", "unen", "ola", "io"),
                    WORD, List.of("Koivu", "Honka", "Terva", "Naava", "Havu", "Paju", "Susi", "Hirvi", "Karhu", "Tukki",
                            "Kivi", "Ranta", "Virta", "Kuusi"),
                    PLACE, List.of("niemi", "koski", "salo", "vaara", "saari", "lehto", "korpi", "joki")),
            List.of(
                    new NameStyle.Pattern(2, List.of(PREFIX, SUFFIX)),
                    new NameStyle.Pattern(1, List.of(WORD, PLACE)),
                    new NameStyle.Pattern(3, List.of(PREFIX, VOWEL, MIDDLE, SUFFIX))));

    /**
     * Forge-country surnames, for Altzair: Burdineta, Ikazaga, Labarri, Sutondo, Mailegi.
     * Pool use differs from the other styles: WORD holds r-final roots (Harr, Iturr) and PLACE their
     * endings, while MIDDLE holds whole compound tails (izabal, aberri) that close a name on their own,
     * appended to either a PREFIX or a WORD. No VOWEL pool is used.
     */
    public static final NameStyle ALTZAIR = NameStyle.of("altzair",
            Map.of(
                    PREFIX, List.of("Burdin", "Ikaz", "Lab", "Sut", "Mail", "Ingud", "Olab", "Garm", "Kez", "Mend",
                            "Zub", "Etx", "Goik", "Bel", "Zabal", "Ald", "Ormaz", "Azk", "Uriz", "Olaz",
                            "Txap", "Altz", "Aran", "Bidas", "Erret", "Gald", "Ilun", "Jaur", "Lekun", "Mitx",
                            "Nab", "Oiz", "Pag", "Sagast", "Zum", "Izag", "Unz", "Otx", "Atx", "Arizt"),
                    SUFFIX, List.of("eta", "aga", "arri", "ondo", "egi", "uri", "oa", "ain", "ola"),
                    WORD, List.of("Harr", "Urr", "Arr", "Ibarr", "Iturr", "Larr", "Agirr", "Elorr", "Gorr", "Sarr",
                            "Lizar", "Ugar"),
                    PLACE, List.of("eta", "aga", "ondo", "egi", "uri", "oa", "ain", "ola"),
                    MIDDLE, List.of("izabal", "aberri", "ezar", "agoiti", "ebeiti", "agarai", "aburu", "amendi",
                            "ibar", "ebide", "ategi", "iturri")),
            List.of(
                    new NameStyle.Pattern(3, List.of(PREFIX, SUFFIX)),
                    new NameStyle.Pattern(1, List.of(WORD, PLACE)),
                    new NameStyle.Pattern(3, List.of(PREFIX, MIDDLE)),
                    new NameStyle.Pattern(1, List.of(WORD, MIDDLE))));

    /** Veyra: flowing vowels and v/l/r; short and longer given names share one family pool. */
    public static final NameStyle VEYRA = fullNameStyle("veyra",
            List.of("Aven", "Neris", "Maelis", "Lio", "Teren", "Sova", "Elian", "Orel",
                    "Varo", "Iven", "Saren", "Luma", "Rivo", "Nael", "Evin", "Talio",
                    "Meran", "Silo", "Yaren", "Olis", "Vela", "Daren", "Aro", "Selin"),
            List.of("Saloren", "Vaelo", "Orava", "Mavess", "Ilvar", "Narel", "Veyalen", "Caleth",
                    "Sirel", "Alorai", "Revorel", "Selaven", "Mireth", "Uvelin", "Dorel", "Laeven",
                    "Ossai", "Veloran", "Neral", "Saivel", "Coren", "Meralis", "Tessel", "Auvren",
                    "Leveth", "Orien", "Naress", "Virel", "Esvan", "Pellor", "Saeven", "Olt"));

    /** Branik: compact consonant clusters and clipped endings, distinct from Khess's suffixes. */
    public static final NameStyle BRANIK = fullNameStyle("branik",
            List.of("Dorn", "Odran", "Dren", "Irdan", "Tovan", "Udro", "Bram", "Eddan",
                    "Ordam", "Venn", "Branik", "Dast", "Merrit", "Ulben", "Rudd", "Stenn",
                    "Beld", "Tarnel", "Noll", "Borrin", "Ett", "Joss", "Delmar", "Osket"),
            List.of("Vradek", "Tesk", "Volbak", "Zemrat", "Drazel", "Belm", "Breddal", "Talmet",
                    "Vossad", "Darnel", "Mordet", "Bramvek", "Tordal", "Denvoss", "Beltrum", "Orrad",
                    "Stammel", "Vennor", "Rudbelt", "Dravet", "Ornbast", "Brasset", "Dolmar", "Fennard",
                    "Hulden", "Marod", "Nerbet", "Ostrel", "Skeld", "Uldram", "Dorr", "Marn"));

    /** Sileth: sibilants, ae/ei vowels, and a few short ancestral family names. */
    public static final NameStyle SILETH = fullNameStyle("sileth",
            List.of("Saevi", "Izel", "Eshai", "Thesin", "Orya", "Nemei", "Qael", "Zhen",
                    "Vaeli", "Cyran", "Aeth", "Ossir", "Ysela", "Tevrai", "Lyss", "Azhel",
                    "Sei", "Rhyel", "Ilza", "Qorin", "Ethei", "Vezra", "Naesh", "Syren"),
            List.of("Qelith", "Vaes", "Xorun", "Ulai", "Shezel", "Azhar", "Azharel", "Eshor",
                    "Thaelis", "Soryn", "Ixavel", "Nemeth", "Ossaith", "Kyreth", "Vezzar", "Aelq",
                    "Shaevin", "Thorys", "Isketh", "Raezel", "Olyth", "Zhaelis", "Quorin", "Ezhai",
                    "Vessael", "Syrith", "Thelq", "Ishavel", "Noryth", "Sezari", "Orz", "Ul"));

    private static NameStyle fullNameStyle(String id, List<String> given, List<String> family) {
        return NameStyle.of(id,
                Map.of(GIVEN, given, FAMILY, family.stream().map(name -> " " + name).toList()),
                List.of(new NameStyle.Pattern(1, List.of(GIVEN, FAMILY))));
    }

    private NameStyles() {
    }
}
