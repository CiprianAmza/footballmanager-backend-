package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.Analyst;

import java.util.Random;

/**
 * The style a journalist is born with (design DESIGN_START_CARIERA §6.1.1, decided 2026-11-13): his voice sets the
 * range each parameter is drawn from, so a tabloid man is loud and loose with facts while an agency reporter is
 * dry and dependable. The values are only a starting point; they can be edited in the database afterwards.
 */
public final class AnalystStyle {

    private AnalystStyle() {
    }

    /** Inclusive ranges, in the order: sensationalism, stats focus, verbosity, reliability, stubbornness. */
    record Ranges(int[] sensationalism, int[] statsFocus, int[] verbosity, int[] reliability, int[] stubbornness) {
    }

    static Ranges ranges(Analyst.Voice voice) {
        return switch (voice) {
            case TABLOID -> new Ranges(r(65, 95), r(5, 35), r(1, 2), r(20, 55), r(25, 65));
            case ANALYTICAL -> new Ranges(r(10, 40), r(70, 100), r(2, 3), r(60, 85), r(25, 65));
            case OLD_SCHOOL -> new Ranges(r(25, 60), r(5, 30), r(2, 3), r(50, 75), r(60, 90));
            case POLEMICIST -> new Ranges(r(50, 85), r(15, 45), r(2, 3), r(35, 65), r(55, 85));
            case INSIDER -> new Ranges(r(35, 70), r(15, 45), r(2, 3), r(45, 90), r(25, 65));
            case AGENCY -> new Ranges(r(0, 20), r(40, 70), r(1, 2), r(75, 95), r(25, 65));
        };
    }

    /** Harshness range of each tier: critic 70–95, balanced 40–60, supportive 5–30. */
    static int[] harshnessRange(Analyst.Temperament temperament) {
        return switch (temperament) {
            case CRITIC -> r(70, 95);
            case BALANCED -> r(40, 60);
            case SUPPORTIVE -> r(5, 30);
        };
    }

    /**
     * Gives {@code analyst} the voice and draws his parameters from its ranges. His harshness stays inside the
     * tier he already has ({@link Analyst#getTemperament()}), so a styled pundit keeps his temperament.
     */
    public static void apply(Analyst analyst, Analyst.Voice voice, Random rng) {
        Ranges ranges = ranges(voice);
        analyst.setVoice(voice);
        if (analyst.getTemperament() == null) analyst.setTemperament(Analyst.Temperament.BALANCED);
        analyst.setHarshness(draw(harshnessRange(analyst.getTemperament()), rng));
        analyst.setSensationalism(draw(ranges.sensationalism(), rng));
        analyst.setStatsFocus(draw(ranges.statsFocus(), rng));
        analyst.setVerbosity(draw(ranges.verbosity(), rng));
        analyst.setReliability(draw(ranges.reliability(), rng));
        analyst.setStubbornness(draw(ranges.stubbornness(), rng));
    }

    private static int draw(int[] range, Random rng) {
        return range[0] + rng.nextInt(range[1] - range[0] + 1);
    }

    private static int[] r(int from, int to) {
        return new int[]{from, to};
    }
}
