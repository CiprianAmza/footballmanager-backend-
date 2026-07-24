package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.Mentality;

/** Builds a deterministic fixture that activates the consumer named by a catalog leaf. */
public final class CalibrationScenarioFactory {
    private CalibrationScenarioFactory() {}

    public static ScoringSensitivityScenario forWeight(CanonicalScoringWeightKey leaf) {
        if (leaf == null) throw new NullPointerException("leaf");
        var base = CalibrationScenarioFixtures.selectedWeights();
        String path = leaf.path();
        var team = base.baselineTeam();
        if (path.contains("SHADOW_STRIKER")) team = team.withShadowStriker();
        else if (path.contains("mentalities.")) {
            String name = path.substring(path.indexOf("mentalities.") + 12);
            name = name.substring(0, name.indexOf('.'));
            team = team.withMentality(Mentality.valueOf(name));
        } else if (path.contains("work-rate.instructions.STAY_FORWARD")) {
            team = team.withStayForward();
        } else if (path.contains("familiarity-penalty")) {
            team = team.withoutPersistentFamiliarity();
        } else if (path.contains("context-rules.linehigh")) {
            team = team.withDefensiveLine("High");
        }
        return new ScoringSensitivityScenario("activator-" + path, team, base.opponent(), base.seed(), 1);
    }
}
