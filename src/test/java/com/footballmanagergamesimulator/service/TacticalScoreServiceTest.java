package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.service.TacticalScoreService.StarterValue;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure-logic tests for the two-axis tactical model. Shares a plain {@link MatchEngineConfig} with
 * production so defaults and overrides behave identically.
 */
class TacticalScoreServiceTest {

    private TacticalScoreService service;
    private MatchEngineConfig cfg;

    @BeforeEach
    void setUp() {
        service = new TacticalScoreService();
        cfg = new MatchEngineConfig();
        service.engineConfig = cfg;
    }

    private PersonalizedTactic tactic(String mentality, String tempo) {
        PersonalizedTactic t = new PersonalizedTactic();
        t.setMentality(mentality); t.setTempo(tempo);
        t.setTimeWasting("Sometimes"); t.setInPossession("Standard"); t.setPassingType("Normal");
        return t;
    }

    @Test
    void profile_splitsValueByPositionAttackShare() {
        TeamProfile p = service.profile(List.of(
                new StarterValue("ST", 100), // 0.95 attack
                new StarterValue("GK", 100))); // 0.0 attack
        assertThat(p.attack()).isCloseTo(95.0, within(1e-9));
        assertThat(p.defense()).isCloseTo(105.0, within(1e-9));
    }

    @Test
    void vector_neutralIsZero_mentalityAndTempoMoveAxes() {
        TacticVector neutral = service.vector(new PersonalizedTactic());
        assertThat(neutral.attackBias()).isEqualTo(0.0);
        assertThat(neutral.risk()).isEqualTo(0.0);

        assertThat(service.vector(tactic("Very Attacking", "Standard")).attackBias()).isEqualTo(1.0);
        assertThat(service.vector(tactic("Very Defensive", "Standard")).attackBias()).isEqualTo(-1.0);
        assertThat(service.vector(tactic("Balanced", "Much Higher")).risk()).isEqualTo(1.0);
    }

    @Test
    void strongerSquadBeatsWeakerMostOfTheTime() {
        TeamProfile strong = service.profile(List.of(new StarterValue("MC", 2000)));
        TeamProfile weak = service.profile(List.of(new StarterValue("MC", 1000)));
        TacticVector neutral = service.vector(new PersonalizedTactic());
        Random rng = new Random(20260528L);

        int strongWins = 0, n = 2000;
        for (int i = 0; i < n; i++) {
            List<Integer> s = service.score(strong, neutral, weak, neutral, rng);
            if (s.get(0) > s.get(1)) strongWins++;
        }
        assertThat(strongWins).as("2x-value team should win most matches").isGreaterThan((int) (0.6 * n));
    }

    @Test
    void mentalityIsATradeOff_notAFreeBoost() {
        // Equal squads. Attacking raises your attack but lowers your defense, so it is not a
        // strictly-better choice — its goal difference vs a neutral opponent differs from defensive,
        // and stays in a sane band (no uncapped +stacking).
        TeamProfile mine = service.profile(List.of(new StarterValue("MC", 1500)));
        TeamProfile opp = service.profile(List.of(new StarterValue("MC", 1500)));
        TacticVector neutral = service.vector(new PersonalizedTactic());

        double egdAttacking = service.expectedGoalDifference(mine, service.vector(tactic("Very Attacking", "Standard")), opp, neutral);
        double egdDefensive = service.expectedGoalDifference(mine, service.vector(tactic("Very Defensive", "Standard")), opp, neutral);

        assertThat(egdAttacking).as("tactics must change the expected outcome").isNotEqualTo(egdDefensive);
        // Bounded: a tactic alone can't turn an even matchup into a blowout.
        assertThat(Math.abs(egdAttacking)).isLessThan(2.0);
        assertThat(Math.abs(egdDefensive)).isLessThan(2.0);
    }

    @Test
    void noUniversallyBestTactic_attackingBeatsAttackerButNotParker() {
        // The optimal answer depends on the opponent (matchup): going attacking yields a better
        // goal difference against an attacking opponent than against a defensive (parked) one.
        TeamProfile mine = service.profile(List.of(new StarterValue("MC", 1500)));
        TeamProfile opp = service.profile(List.of(new StarterValue("MC", 1500)));
        TacticVector attackingOpp = service.vector(tactic("Very Attacking", "Much Higher"));
        TacticVector defensiveOpp = service.vector(tactic("Very Defensive", "Much Lower"));
        TacticVector myAttacking = service.vector(tactic("Very Attacking", "Higher"));

        double vsAttacker = service.expectedGoalDifference(mine, myAttacking, opp, attackingOpp);
        double vsParker = service.expectedGoalDifference(mine, myAttacking, opp, defensiveOpp);

        assertThat(vsAttacker)
                .as("attacking should fare better vs an attacking opponent than vs a parked defense")
                .isGreaterThan(vsParker);
    }
}
