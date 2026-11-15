package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link FaceGenerator}: descriptors are reproducible from the player id and the
 *  skin/hair distributions follow each nation's weight tables. No Spring context needed. */
class FaceGeneratorTest {

    private final FaceGenerator faceGenerator = new FaceGenerator();

    private Human humanWithId(long id) {
        Human h = new Human();
        h.setId(id);
        return h;
    }

    @Test
    void sameIdAndNationProduceIdenticalDescriptor() {
        Human a = humanWithId(12345L);
        Human b = humanWithId(12345L);

        faceGenerator.assignFace(a, 3L);
        faceGenerator.assignFace(b, 3L);

        assertEquals(a.getBaseFaceId(), b.getBaseFaceId());
        assertEquals(a.getSkinTone(), b.getSkinTone());
        assertEquals(a.getHairStyle(), b.getHairStyle());
        assertEquals(a.getHairColor(), b.getHairColor());
        assertEquals(a.getEyeColor(), b.getEyeColor());
    }

    @Test
    void reassigningIsIdempotent() {
        Human a = humanWithId(777L);
        faceGenerator.assignFace(a, 2L);
        int skin = a.getSkinTone();
        int hair = a.getHairColor();

        faceGenerator.assignFace(a, 2L);
        assertEquals(skin, a.getSkinTone());
        assertEquals(hair, a.getHairColor());
    }

    @Test
    void skinToneDistributionFollowsNationWeights() {
        // Dong (id 2) skews dark; Gallactick (id 1) skews pale. Mean skin index must reflect that.
        double dongMean = meanSkinTone(2L);
        double gallactickMean = meanSkinTone(1L);

        assertTrue(dongMean > gallactickMean + 1.0,
                "Dong should be visibly darker-skinned than Gallactick: dong=" + dongMean
                        + " gallactick=" + gallactickMean);
    }

    @Test
    void hairColourDistributionFollowsNationWeights() {
        // Dong (id 2) is mostly black hair (index 0); Gallactick (id 1) has lots of blonde (index 3).
        double dongBlack = fractionHairColor(2L, 0);
        double gallactickBlack = fractionHairColor(1L, 0);

        assertTrue(dongBlack > gallactickBlack + 0.2,
                "Dong should have far more black hair than Gallactick: dong=" + dongBlack
                        + " gallactick=" + gallactickBlack);
    }

    @Test
    void newNationsHaveDeterministicDiverseDescriptorsWithinTheActualEditorContract() {
        Map<Long, String> species = Map.of(11L, "veyran", 12L, "branik", 13L, "sileth");
        Map<Long, Set<Integer>> heads = Map.of(
                11L, Set.of(1, 4, 12, 2), 12L, Set.of(0, 3, 14, 15), 13L, Set.of(1, 2, 4, 12, 15));
        for (var entry : species.entrySet()) {
            Set<String> portraits = new HashSet<>();
            Set<Integer> skinVariants = new HashSet<>();
            Set<Integer> hairVariants = new HashSet<>();
            for (long id = 10_000; id < 10_264; id++) {
                Human player = humanWithId(id);
                Human replay = humanWithId(id);
                faceGenerator.assignFace(player, entry.getKey());
                faceGenerator.assignFace(replay, entry.getKey());
                assertEquals(entry.getValue(), player.getSpecies());
                assertEquals(descriptor(player), descriptor(replay));
                assertTrue(heads.get(entry.getKey()).contains(player.getFaceShape()));
                assertTrue(player.getBaseFaceId() >= 0 && player.getBaseFaceId() < 10);
                assertTrue(player.getSkinTone() >= 0 && player.getSkinTone() < 6);
                assertTrue(player.getHairStyle() >= 0 && player.getHairStyle() < 20);
                assertTrue(player.getHairColor() >= 0 && player.getHairColor() < 6);
                assertTrue(player.getEyeColor() >= 0 && player.getEyeColor() < 4);
                assertTrue(player.getEyeShape() >= 0 && player.getEyeShape() < 10);
                assertTrue(player.getNoseShape() >= 0 && player.getNoseShape() < 10);
                assertTrue(player.getMouthShape() >= 0 && player.getMouthShape() < 5);
                assertTrue(player.getBrowShape() >= 0 && player.getBrowShape() < 9);
                portraits.add(descriptor(player));
                skinVariants.add(player.getSkinTone());
                hairVariants.add(player.getHairColor());
            }
            assertTrue(portraits.size() > 250, "a full league must not share a handful of portraits");
            assertEquals(6, skinVariants.size());
            assertEquals(6, hairVariants.size());
            assertEquals(entry.getValue(), FaceGenerator.speciesFor(entry.getKey()));
        }
    }

    private String descriptor(Human player) {
        return player.getSpecies() + ":" + player.getBaseFaceId() + ":" + player.getSkinTone()
                + ":" + player.getHairStyle() + ":" + player.getHairColor() + ":" + player.getEyeColor()
                + ":" + player.getFaceShape() + ":" + player.getEyeShape() + ":" + player.getNoseShape()
                + ":" + player.getMouthShape() + ":" + player.getBrowShape();
    }

    @Test
    void concurrentlyAddedNationsKeepTheirOriginalNationIdsAndSpecies() {
        assertEquals("salmarid", FaceGenerator.speciesFor(8L));
        assertEquals("kaarnid", FaceGenerator.speciesFor(9L));
        assertEquals("ikazkin", FaceGenerator.speciesFor(10L));
        for (long nationId : new long[]{8, 9, 10}) {
            for (long id = 1000; id < 1100; id++) {
                Human player = humanWithId(id);
                faceGenerator.assignFace(player, nationId);
                assertEquals(Map.of(8L, "salmarid", 9L, "kaarnid", 10L, "ikazkin").get(nationId), player.getSpecies());
                assertTrue(Set.of(0, 1, 2, 4).contains(player.getFaceShape()));
                assertTrue(Set.of(0, 1, 3, 5, 8).contains(player.getHairStyle()));
                assertTrue(Set.of(0, 2, 4).contains(player.getBrowShape()));
            }
        }
        assertEquals(java.util.List.of("Mareval", "Tervala", "Altzair", "Veyra", "Branik", "Sileth"),
                NationService.countries().stream().filter(n -> n.id() >= 8).map(NationService.NationInfo::name).toList());
    }

    private double meanSkinTone(long nationId) {
        int n = 4000;
        long sum = 0;
        for (long id = 1; id <= n; id++) {
            Human h = humanWithId(id);
            faceGenerator.assignFace(h, nationId);
            sum += h.getSkinTone();
        }
        return sum / (double) n;
    }

    private double fractionHairColor(long nationId, int colorIndex) {
        int n = 4000;
        int hits = 0;
        for (long id = 1; id <= n; id++) {
            Human h = humanWithId(id);
            faceGenerator.assignFace(h, nationId);
            if (h.getHairColor() == colorIndex)
                hits++;
        }
        return hits / (double) n;
    }
}
