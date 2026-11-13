package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.Analyst;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalistStyleTest {

    @Test
    @DisplayName("""
            GIVEN harshness values 67, 66, 34 and 33
            WHEN the tier is read
            THEN they are critic, balanced, balanced, supportive; a journalist without harshness (created before the
             style parameters) keeps the temperament he was created with, one with harshness follows it even
             when the stored temperament says otherwise, and a row with neither reads balanced
            """)
    void harshnessSetsTheTier() {
        assertEquals(Analyst.Temperament.CRITIC, Analyst.Temperament.ofHarshness(67));
        assertEquals(Analyst.Temperament.BALANCED, Analyst.Temperament.ofHarshness(66));
        assertEquals(Analyst.Temperament.BALANCED, Analyst.Temperament.ofHarshness(34));
        assertEquals(Analyst.Temperament.SUPPORTIVE, Analyst.Temperament.ofHarshness(33));

        Analyst legacy = new Analyst();
        legacy.setTemperament(Analyst.Temperament.CRITIC);
        assertEquals(Analyst.Temperament.CRITIC, legacy.tone());
        assertFalse(legacy.isReporter());

        Analyst edited = new Analyst();
        edited.setTemperament(Analyst.Temperament.CRITIC);
        edited.setHarshness(10);
        assertEquals(Analyst.Temperament.SUPPORTIVE, edited.tone());
        assertEquals(Analyst.Temperament.BALANCED, new Analyst().tone());
    }

    @Test
    @DisplayName("""
            GIVEN stubbornness 0, 50 and 100
            WHEN the relation points per tier are read
            THEN they are 10, 20 (the old fixed step) and 30; a balanced journalist whose relation with a manager
             fell to 30 turns critic when easily swayed or average, and stays balanced when stubborn
            """)
    void stubbornnessSetsHowFastHeChangesHisMind() {
        assertEquals(10, AnalystStance.step(0));
        assertEquals(AnalystStance.STEP, AnalystStance.step(50));
        assertEquals(30, AnalystStance.step(100));

        assertEquals(Analyst.Temperament.CRITIC, AnalystStance.towards(balanced(0), 30));
        assertEquals(Analyst.Temperament.CRITIC, AnalystStance.towards(balanced(50), 30));
        assertEquals(Analyst.Temperament.BALANCED, AnalystStance.towards(balanced(100), 30));
        assertEquals(Analyst.Temperament.SUPPORTIVE, AnalystStance.towards(balanced(50), 70));
        assertEquals(Analyst.Temperament.CRITIC, AnalystStance.shifted(Analyst.Temperament.CRITIC, -2));
    }

    @Test
    @DisplayName("""
            GIVEN 200 journalists of every voice and temperament
            WHEN each is given his style
            THEN every parameter falls inside the range of his voice (a tabloid man: sensationalism 65-95,
             reliability 20-55, verbosity 1-2; an agency reporter: sensationalism 0-20, reliability 75-95) and his
             harshness stays inside the tier of his temperament
            """)
    void styleIsDrawnFromTheVoice() {
        Random rng = new Random(7);
        for (Analyst.Voice voice : Analyst.Voice.values()) {
            AnalystStyle.Ranges ranges = AnalystStyle.ranges(voice);
            for (Analyst.Temperament temperament : Analyst.Temperament.values()) {
                for (int i = 0; i < 200; i++) {
                    Analyst analyst = new Analyst();
                    analyst.setTemperament(temperament);
                    AnalystStyle.apply(analyst, voice, rng);

                    assertEquals(voice, analyst.getVoice());
                    assertEquals(temperament, analyst.tone());
                    assertTrue(within(analyst.getSensationalism(), ranges.sensationalism()));
                    assertTrue(within(analyst.getStatsFocus(), ranges.statsFocus()));
                    assertTrue(within(analyst.getVerbosity(), ranges.verbosity()));
                    assertTrue(within(analyst.getReliability(), ranges.reliability()));
                    assertTrue(within(analyst.getStubbornness(), ranges.stubbornness()));
                }
            }
        }
        assertEquals(65, AnalystStyle.ranges(Analyst.Voice.TABLOID).sensationalism()[0]);
        assertEquals(55, AnalystStyle.ranges(Analyst.Voice.TABLOID).reliability()[1]);
        assertEquals(20, AnalystStyle.ranges(Analyst.Voice.AGENCY).sensationalism()[1]);
        assertEquals(75, AnalystStyle.ranges(Analyst.Voice.AGENCY).reliability()[0]);
    }

    private static Analyst balanced(int stubbornness) {
        Analyst analyst = new Analyst();
        analyst.setTemperament(Analyst.Temperament.BALANCED);
        analyst.setStubbornness(stubbornness);
        return analyst;
    }

    private static boolean within(int value, int[] range) {
        return value >= range[0] && value <= range[1];
    }
}
