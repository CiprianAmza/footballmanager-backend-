package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.Analyst;

/**
 * How a journalist's memory of a manager shifts his tone (design DESIGN_START_CARIERA §6.4, decided 2026-11-12):
 * his relation with the manager starts at 50; every step away from it moves his tone one tier
 * (critic ↔ balanced ↔ supportive), within the three. The step is {@value #STEP} for a journalist of average
 * stubbornness; a stubborn one needs more, an easily swayed one less (§6.1.1, decided 2026-11-13).
 */
public final class AnalystStance {

    public static final int STEP = 20;
    static final int NEUTRAL_RELATION = 50;

    private AnalystStance() {
    }

    public static Analyst.Temperament effective(Analyst.Temperament own, int relation) {
        return effective(own, relation, STEP);
    }

    public static Analyst.Temperament effective(Analyst.Temperament own, int relation, int step) {
        return shifted(own, (relation - NEUTRAL_RELATION) / Math.max(1, step));
    }

    /** {@code steps} tiers towards supportive (negative: towards critic), kept within the three. */
    public static Analyst.Temperament shifted(Analyst.Temperament own, int steps) {
        int index = Math.max(0, Math.min(Analyst.Temperament.values().length - 1, own.ordinal() + steps));
        return Analyst.Temperament.values()[index];
    }

    /** Relation points per tier for a 0–100 stubbornness: 10 (easily swayed) to 30, {@value #STEP} at 50. */
    public static int step(int stubbornness) {
        return 10 + Math.max(0, Math.min(100, stubbornness)) / 5;
    }

    /** His tone towards one manager: his own tone, moved by what he remembers of him. */
    public static Analyst.Temperament towards(Analyst analyst, int relation) {
        return effective(analyst.tone(), relation, step(analyst.getStubbornness()));
    }
}
