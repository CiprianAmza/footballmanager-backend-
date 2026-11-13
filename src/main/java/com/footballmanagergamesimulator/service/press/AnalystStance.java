package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.Analyst;

/**
 * How a pundit's memory of a manager shifts his tone (design DESIGN_START_CARIERA §6.4, decided 2026-11-12): his
 * relation with the manager starts at 50; every {@value #STEP} points away from it moves his temperament one step
 * (critic ↔ balanced ↔ supportive), within the three.
 */
public final class AnalystStance {

    public static final int STEP = 20;

    private AnalystStance() {
    }

    public static Analyst.Temperament effective(Analyst.Temperament own, int relation) {
        int shift = (relation - 50) / STEP;
        int index = Math.max(0, Math.min(Analyst.Temperament.values().length - 1, own.ordinal() + shift));
        return Analyst.Temperament.values()[index];
    }
}
