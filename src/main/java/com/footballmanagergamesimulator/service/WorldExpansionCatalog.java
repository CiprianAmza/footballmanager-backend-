package com.footballmanagergamesimulator.service;

import java.util.List;
import java.util.Set;

/** Approved domestic world content. European membership is deliberately a separate decision. */
public final class WorldExpansionCatalog {
    private WorldExpansionCatalog() {}

    public record Club(String name, String stadium, String color1, String color2,
                       int reputation, int strategy, int academy, int youthTraining, int seniorTraining) {}
    public record Nation(long id, String name, String league, String cup, List<Club> clubs) {}
    public record ReferencePlayer(long nationId, String club, String name, String position,
                                  int skin, int hairStyle, int hairColor, int eyeColor, int head, int features,
                                  int brows, int eyes, int nose, int mouth) {}

    public static final Set<Long> NATION_IDS = Set.of(11L, 12L, 13L);
    public static final List<Nation> NATIONS = List.of(
            new Nation(11, "Veyra", "Liga Veyra", "Cupa Veyra", List.of(
                    new Club("Avelis", "Velar", "#0B6B72", "#F2E5C4", 7800, 3, 16, 17, 16),
                    new Club("La Nerai", "Nerath", "#193A72", "#F5F1E8", 7400, 2, 14, 15, 16),
                    new Club("Vessara", "Lumina Vessarei", "#F4C95D", "#26344A", 7100, 4, 13, 15, 14),
                    new Club("Talma Oravel", "Cele Noua Vele", "#F2F0E5", "#C84E3B", 6800, 3, 15, 14, 13),
                    new Club("Veyrin", "Veyrul Vechi", "#6E4C91", "#E8DDBA", 6500, 2, 11, 13, 14),
                    new Club("Calven", "Tesatura", "#17745B", "#E6D9B5", 6200, 1, 12, 12, 13),
                    new Club("Sora Talessa", "Sarea Alba", "#D9E4E9", "#263845", 5900, 4, 10, 12, 12),
                    new Club("Iri Sela", "Selaria", "#3179B8", "#E8F0F2", 5700, 3, 13, 11, 10),
                    new Club("Miravela", "Oglinda Apei", "#65C8C4", "#18364B", 5500, 6, 11, 12, 10),
                    new Club("Orda Sere", "Coroana Serei", "#D5AE49", "#164B51", 5200, 1, 9, 10, 11),
                    new Club("Nelora", "Nelor", "#A3445D", "#F4DAB5", 4900, 2, 10, 9, 10),
                    new Club("Sa Riva", "Malul Rosu", "#D9673E", "#203F58", 4600, 4, 8, 9, 9))),
            new Nation(12, "Branik", "Liga Branik", "Cupa Branik", List.of(
                    new Club("Dobrak", "Vatra Dobrak", "#B5432F", "#252B30", 8200, 5, 13, 14, 17),
                    new Club("Stal Brum", "Cele Trei Arcade", "#E6B849", "#293542", 7700, 2, 12, 13, 16),
                    new Club("Vezdan", "Vezdar", "#F0E3C4", "#66452F", 7300, 4, 11, 13, 15),
                    new Club("Orda Dovren", "Dovran", "#D87832", "#263C4B", 7000, 1, 14, 13, 14),
                    new Club("Trask", "Podul Negru", "#25262C", "#C3CBD2", 6700, 2, 10, 11, 14),
                    new Club("Borna", "Borna Mare", "#396A4B", "#E7DFC8", 6400, 3, 12, 12, 13),
                    new Club("Uldran", "Uldra", "#55436A", "#B9BEC4", 6100, 6, 10, 11, 12),
                    new Club("Drevna", "Furnalul Vechi", "#9D2D3C", "#D7C5A5", 5900, 4, 9, 10, 12),
                    new Club("Radova", "Radovar", "#2F527C", "#E9E9DF", 5700, 1, 11, 11, 10),
                    new Club("Zorad", "Zorul", "#E8C95B", "#273A37", 5400, 3, 8, 10, 11),
                    new Club("Tovarn", "Tovar", "#739485", "#F0D7A7", 5200, 2, 9, 9, 10),
                    new Club("Dorr Kal", "Sase Ciocane", "#437D88", "#D9C49D", 4900, 4, 8, 9, 9))),
            new Nation(13, "Sileth", "Liga Sileth", "Cupa Sileth", List.of(
                    new Club("Thessa", "Cele Patru Priviri", "#6F58B8", "#DDEBF2", 7600, 3, 17, 17, 15),
                    new Club("Or Silei", "Sileion", "#32A99B", "#183B48", 7200, 2, 15, 16, 15),
                    new Club("Xael", "Xaelor", "#B85288", "#EADBE7", 6900, 4, 14, 15, 14),
                    new Club("Saevara", "Saevar", "#E9C866", "#38354D", 6600, 3, 16, 14, 13),
                    new Club("Zelith", "Zelar", "#8ABCE0", "#28375B", 6300, 1, 13, 14, 12),
                    new Club("Ishara", "Ishar", "#CC6958", "#F0DFC2", 6000, 2, 12, 13, 12),
                    new Club("Leth Oss", "Ossara", "#CBD9DF", "#495063", 5700, 6, 14, 12, 11),
                    new Club("Quess", "Quesar", "#6F9860", "#EDF0D4", 5500, 4, 11, 12, 11),
                    new Club("Izhorel", "Izhor", "#397CD4", "#1D2946", 5300, 2, 12, 11, 10),
                    new Club("Veiss", "Veisar", "#A56DC9", "#F0D4B5", 5000, 3, 10, 11, 10),
                    new Club("Nara Zel", "Narazel", "#D7933D", "#393247", 4800, 1, 11, 10, 9),
                    new Club("Ulthia", "Ulthar", "#E9E9DB", "#388D97", 4500, 4, 9, 10, 9)))
    );

    /** These are ordinary squad members, not bonus players added on top of the 22. */
    public static final List<ReferencePlayer> REFERENCE_PLAYERS = List.of(
            new ReferencePlayer(11, "Avelis", "Aven Saloren", "MC", 0, 8, 0, 0, 1, 1, 2, 0, 4, 0),
            new ReferencePlayer(11, "La Nerai", "Neris Vaelo", "AMR", 3, 7, 1, 1, 12, 4, 4, 2, 0, 2),
            new ReferencePlayer(12, "Dobrak", "Dorn Vradek", "DC", 2, 0, 0, 0, 3, 0, 7, 4, 2, 3),
            new ReferencePlayer(12, "Stal Brum", "Odran Tesk", "ST", 3, 1, 1, 1, 2, 1, 0, 0, 7, 2),
            new ReferencePlayer(13, "Thessa", "Saevi Qelith", "AMC", 0, 8, 0, 0, 4, 1, 4, 0, 4, 0),
            new ReferencePlayer(13, "Or Silei", "Izel Vaes", "GK", 3, 1, 1, 1, 2, 4, 8, 2, 5, 2)
    );
}
