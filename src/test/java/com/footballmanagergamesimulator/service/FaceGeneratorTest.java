package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import org.junit.jupiter.api.Test;

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
