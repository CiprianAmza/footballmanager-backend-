package com.footballmanagergamesimulator.nameGenerator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FragmentNameGeneratorTest {

    private static final List<NameStyle> ALL_STYLES =
            List.of(NameStyles.ELEVEN, NameStyles.KESS, NameStyles.VARD, NameStyles.LIRA);

    @Test
    void everyStyleProducesNonNullNonEmptyNames() {
        for (NameStyle style : ALL_STYLES) {
            FragmentNameGenerator generator = FragmentNameGenerator.seeded(style, 7L);
            for (int i = 0; i < 200; i++) {
                String name = generator.generateName();
                assertNotNull(name, style.id());
                assertTrue(!name.isBlank(), style.id() + " produced a blank name");
            }
        }
    }

    @Test
    void everyStyleProducesVariety() {
        for (NameStyle style : ALL_STYLES) {
            FragmentNameGenerator generator = FragmentNameGenerator.seeded(style, 11L);
            Set<String> distinct = new HashSet<>();
            for (int i = 0; i < 300; i++)
                distinct.add(generator.generateName());
            assertTrue(distinct.size() >= 50,
                    style.id() + " produced only " + distinct.size() + " distinct names out of 300");
        }
    }

    @Test
    void fixedSeedIsReproducible() {
        for (NameStyle style : ALL_STYLES) {
            FragmentNameGenerator first = FragmentNameGenerator.seeded(style, 42L);
            FragmentNameGenerator second = FragmentNameGenerator.seeded(style, 42L);
            List<String> firstRun = new ArrayList<>();
            List<String> secondRun = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                firstRun.add(first.generateName());
                secondRun.add(second.generateName());
            }
            assertEquals(firstRun, secondRun, style.id() + " is not reproducible under a fixed seed");
        }
    }

    @Test
    void namesRespectLengthAndStructureRules() {
        for (NameStyle style : ALL_STYLES) {
            FragmentNameGenerator generator = FragmentNameGenerator.seeded(style, 99L);
            for (int i = 0; i < 300; i++) {
                String name = generator.generateName();
                assertTrue(name.length() >= 3 && name.length() <= 16,
                        style.id() + " length out of bounds: " + name);
                assertTrue(name.matches("[A-Z][a-zA-Z]+"),
                        style.id() + " produced non-name-shaped output: " + name);
            }
        }
    }

    @Test
    void emptyPoolIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> NameStyle.of("bad",
                Map.of("PREFIX", List.of()),
                List.of(new NameStyle.Pattern(1, List.of("PREFIX")))));
    }

    @Test
    void blankFragmentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> NameStyle.of("bad",
                Map.of("PREFIX", List.of("Ab", " ")),
                List.of(new NameStyle.Pattern(1, List.of("PREFIX")))));
    }

    @Test
    void missingPatternsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> NameStyle.of("bad",
                Map.of("PREFIX", List.of("Ab")),
                List.of()));
    }

    @Test
    void patternReferencingUnknownPoolIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> NameStyle.of("bad",
                Map.of("PREFIX", List.of("Ab")),
                List.of(new NameStyle.Pattern(1, List.of("PREFIX", "SUFFIX")))));
    }

    @Test
    void nonPositivePatternWeightIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> NameStyle.of("bad",
                Map.of("PREFIX", List.of("Ab")),
                List.of(new NameStyle.Pattern(0, List.of("PREFIX")))));
    }

    @Test
    void weightedPatternsAreAllUsed() {
        NameStyle style = NameStyle.of("two-shapes",
                Map.of("A", List.of("Ba"), "B", List.of("zu"), "C", List.of("ko")),
                List.of(new NameStyle.Pattern(3, List.of("A", "B")),
                        new NameStyle.Pattern(1, List.of("A", "B", "C"))));
        FragmentNameGenerator generator = FragmentNameGenerator.seeded(style, 5L);
        Set<String> produced = new HashSet<>();
        for (int i = 0; i < 200; i++)
            produced.add(generator.generateName());
        assertEquals(Set.of("Bazu", "Bazuko"), produced);
    }
}
