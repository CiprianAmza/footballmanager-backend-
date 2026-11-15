package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class WorldExpansionCatalogTest {
    @Test
    void approvedCountriesHaveCompleteDistinctDomesticClubsAndUsablePortraitReferences() {
        assertEquals(3, WorldExpansionCatalog.NATIONS.size());
        var names = new HashSet<String>();
        var grounds = new HashSet<String>();
        for (var nation : WorldExpansionCatalog.NATIONS) {
            assertEquals(12, nation.clubs().size());
            assertTrue(WorldExpansionCatalog.NATION_IDS.contains(nation.id()));
            assertEquals("Liga " + nation.name(), nation.league());
            for (var club : nation.clubs()) {
                assertTrue(names.add(club.name()));
                assertTrue(grounds.add(club.stadium()));
                assertTrue(club.color1().matches("#[0-9A-F]{6}"));
                assertTrue(club.color2().matches("#[0-9A-F]{6}"));
                assertNotEquals(club.color1(), club.color2());
                assertTrue(club.academy() >= 1 && club.academy() <= 20);
                assertTrue(club.youthTraining() >= 1 && club.youthTraining() <= 20);
                assertTrue(club.seniorTraining() >= 1 && club.seniorTraining() <= 20);
                assertTrue(club.reputation() > 0 && club.reputation() <= 10000);
            }
        }
        assertEquals(36, names.size());
        assertEquals(6, WorldExpansionCatalog.REFERENCE_PLAYERS.size());
        for (var player : WorldExpansionCatalog.REFERENCE_PLAYERS) {
            var nation = WorldExpansionCatalog.NATIONS.stream().filter(n -> n.id() == player.nationId())
                    .findFirst().orElseThrow();
            assertTrue(nation.clubs().stream().anyMatch(c -> c.name().equals(player.club())));
            assertTrue(player.skin() >= 0 && player.skin() < 6);
            assertTrue(player.hairStyle() >= 0 && player.hairStyle() < 20);
            assertTrue(player.hairColor() >= 0 && player.hairColor() < 6);
            assertTrue(player.eyeColor() >= 0 && player.eyeColor() < 4);
            assertTrue(player.head() >= 0 && player.head() < 18);
            assertTrue(player.features() >= 0 && player.features() < 10);
            assertTrue(player.brows() >= 0 && player.brows() < 9);
            assertTrue(player.eyes() >= 0 && player.eyes() < 10);
            assertTrue(player.nose() >= 0 && player.nose() < 10);
            assertTrue(player.mouth() >= 0 && player.mouth() < 5);
        }
    }
}
