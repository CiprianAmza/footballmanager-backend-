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
    void score_isDeterministicForASeed() {
        // Production scoring passes the simulator's seeded RNG into score(...), so a fixed seed must
        // reproduce the same scoreline (the basis of round-level determinism).
        TeamProfile a = service.profile(List.of(new StarterValue("ST", 1500), new StarterValue("DC", 1500)));
        TeamProfile b = service.profile(List.of(new StarterValue("MC", 1400), new StarterValue("DC", 1400)));
        TacticVector ta = service.vector(tactic("Attacking", "Higher"));
        TacticVector tb = service.vector(tactic("Defensive", "Lower"));

        assertThat(service.score(a, ta, b, tb, new Random(20260528L)))
                .isEqualTo(service.score(a, ta, b, tb, new Random(20260528L)));
    }

    @Test
    void coaching_amplifiesEachSideByAbility() {
        TeamProfile raw = new TeamProfile(1000, 1000);
        double k = cfg.getTacticalModel().getCoachStrength(); // default 0.12

        TeamProfile neutral = service.coachedProfile(raw, 50, 50);
        assertThat(neutral.attack()).isCloseTo(1000, within(1e-9));
        assertThat(neutral.defense()).isCloseTo(1000, within(1e-9));

        // Attack-strong, defence-weak coach: attack up, defense down.
        TeamProfile lopsided = service.coachedProfile(raw, 100, 0);
        assertThat(lopsided.attack()).isCloseTo(1000 * (1 + k), within(1e-9));
        assertThat(lopsided.defense()).isCloseTo(1000 * (1 - k), within(1e-9));
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
    void mentalityIsATradeOff_leanIntoYourStrength() {
        // An attack-heavy squad gains from going attacking (amplifies its strong side) more than
        // from going defensive (which buffs its weak side at the cost of its strength). The trade-off
        // is bounded — a tactic alone is not a blowout.
        TeamProfile attackHeavy = service.profile(List.of(new StarterValue("ST", 2000))); // ~att 1900 / def 100
        TeamProfile opp = service.profile(List.of(new StarterValue("MC", 1500)));
        TacticVector neutral = service.vector(new PersonalizedTactic());

        double attacking = service.expectedGoalDifference(attackHeavy, service.vector(tactic("Very Attacking", "Standard")), opp, neutral);
        double defensive = service.expectedGoalDifference(attackHeavy, service.vector(tactic("Very Defensive", "Standard")), opp, neutral);

        assertThat(attacking).as("attack-heavy squad should prefer attacking").isGreaterThan(defensive);
        assertThat(Math.abs(attacking - defensive)).as("tactic swing is bounded").isLessThan(1.0);
    }

    @Test
    void noUniversallyBestTactic_bestMentalityDependsOnYourSquad() {
        // The best mentality is not universal: an attack-heavy squad prefers attacking, a
        // defence-heavy squad prefers defending. Tactics must suit your players.
        TeamProfile attackHeavy = service.profile(List.of(new StarterValue("ST", 2000)));
        TeamProfile defenceHeavy = service.profile(List.of(new StarterValue("DC", 2000)));
        TeamProfile opp = service.profile(List.of(new StarterValue("MC", 1500)));
        TacticVector neutral = service.vector(new PersonalizedTactic());
        TacticVector attacking = service.vector(tactic("Very Attacking", "Standard"));
        TacticVector defending = service.vector(tactic("Very Defensive", "Standard"));

        assertThat(service.expectedGoalDifference(attackHeavy, attacking, opp, neutral))
                .as("attack-heavy prefers attacking")
                .isGreaterThan(service.expectedGoalDifference(attackHeavy, defending, opp, neutral));
        assertThat(service.expectedGoalDifference(defenceHeavy, defending, opp, neutral))
                .as("defence-heavy prefers defending")
                .isGreaterThan(service.expectedGoalDifference(defenceHeavy, attacking, opp, neutral));
    }

    @Test
    void scoreExtraTime_yieldsFarFewerGoalsThanAFullMatch() {
        // The ET mini-match scales openness down (default ~0.33), so over many samples it must
        // produce substantially fewer total goals than a full-90 score for the same matchup.
        TeamProfile a = service.profile(List.of(new StarterValue("ST", 1500), new StarterValue("DC", 1500)));
        TeamProfile b = service.profile(List.of(new StarterValue("MC", 1500), new StarterValue("DC", 1500)));
        TacticVector ta = service.vector(new PersonalizedTactic());
        TacticVector tb = service.vector(new PersonalizedTactic());

        int fullTotal = 0, etTotal = 0;
        Random rng = new Random(20260530L);
        for (int i = 0; i < 4000; i++) {
            var full = service.score(a, ta, b, tb, rng);
            fullTotal += full.get(0) + full.get(1);
            var et = service.scoreExtraTime(a, ta, b, tb, rng);
            etTotal += et.get(0) + et.get(1);
        }
        assertThat(etTotal).as("extra time produces fewer goals than a full match").isLessThan(fullTotal / 2);
    }

    @Test
    void scoreExtraTime_isDeterministicForASeed() {
        TeamProfile a = service.profile(List.of(new StarterValue("ST", 1600)));
        TeamProfile b = service.profile(List.of(new StarterValue("DC", 1400)));
        TacticVector t = service.vector(new PersonalizedTactic());
        assertThat(service.scoreExtraTime(a, t, b, t, new Random(7L)))
                .isEqualTo(service.scoreExtraTime(a, t, b, t, new Random(7L)));
    }

    @Test
    void matchup_redistributesValueByTactic() {
        // The exposed matchup decomposition must agree with the model: an attacking bias raises
        // effective attack and lowers effective defense (the trade-off).
        TeamProfile a = service.profile(List.of(new StarterValue("ST", 1000), new StarterValue("MC", 1000), new StarterValue("DC", 1000)));
        TeamProfile b = service.profile(List.of(new StarterValue("ST", 1000), new StarterValue("MC", 1000), new StarterValue("DC", 1000)));
        TacticVector attacking = service.vector(tactic("Very Attacking", "Standard"));
        TacticVector neutral = service.vector(new PersonalizedTactic());

        var balanced = service.matchup(a, neutral, b, neutral);
        var biased = service.matchup(a, attacking, b, neutral);
        assertThat(biased.effAtt1()).as("attacking lifts effective attack").isGreaterThan(balanced.effAtt1());
        assertThat(biased.effDef1()).as("attacking drops effective defense").isLessThan(balanced.effDef1());
        assertThat(balanced.effAtt1()).isPositive();
        assertThat(balanced.openness()).isPositive();
    }

    // ==================== Strat-2: control cost + new counters ====================

    /** Build a tactic that exercises the new axes; everything else neutral. */
    private PersonalizedTactic axes(String line, String press, String width, String passing) {
        PersonalizedTactic t = new PersonalizedTactic();
        t.setMentality("Balanced"); t.setTempo("Standard");
        t.setTimeWasting("Sometimes"); t.setInPossession("Standard");
        t.setPassingType(passing);
        t.setDefensiveLine(line); t.setPressing(press); t.setWidth(width);
        return t;
    }

    @Test
    void newAxes_neutralByDefault_soDefaultTacticIsNoOp() {
        TacticVector neutral = service.vector(new PersonalizedTactic());
        assertThat(neutral.line()).isEqualTo(0.0);
        assertThat(neutral.press()).isEqualTo(0.0);
        assertThat(neutral.width()).isEqualTo(0.0);
        // A balanced/normal tactic must reproduce the exact same matchup with the new code path.
        TeamProfile a = service.profile(List.of(new StarterValue("ST", 1000), new StarterValue("DC", 1000)));
        TeamProfile b = service.profile(List.of(new StarterValue("MC", 1000), new StarterValue("DC", 1000)));
        var base = service.matchup(a, neutral, b, neutral);
        var same = service.matchup(a, service.vector(axes("Standard", "Low", "Balanced", "Normal")),
                b, service.vector(axes("Standard", "Low", "Balanced", "Normal")));
        assertThat(same.effAtt1()).isCloseTo(base.effAtt1(), within(1e-9));
        assertThat(same.effDef1()).isCloseTo(base.effDef1(), within(1e-9));
    }

    @Test
    void control_raisesDefenseButCostsOwnAttack() {
        // Strat 1: control is no longer a free win — it lifts defense AND lowers own attack.
        TeamProfile a = service.profile(List.of(new StarterValue("ST", 1000), new StarterValue("MC", 1000), new StarterValue("DC", 1000)));
        TeamProfile b = service.profile(List.of(new StarterValue("MC", 1000)));
        TacticVector neutral = service.vector(new PersonalizedTactic());
        PersonalizedTactic keepBall = new PersonalizedTactic();
        keepBall.setMentality("Balanced"); keepBall.setTempo("Standard");
        keepBall.setInPossession("Keep Ball"); keepBall.setTimeWasting("Always"); keepBall.setPassingType("Normal");

        var base = service.matchup(a, neutral, b, neutral);
        var controlled = service.matchup(a, service.vector(keepBall), b, neutral);
        assertThat(controlled.effDef1()).as("control raises defense").isGreaterThan(base.effDef1());
        assertThat(controlled.effAtt1()).as("control costs own attack").isLessThan(base.effAtt1());
    }

    @Test
    void highLine_isPunishedByADirectOpponentButNotAShortPassingOne() {
        TeamProfile a = service.profile(List.of(new StarterValue("DC", 1500)));
        TeamProfile b = service.profile(List.of(new StarterValue("ST", 1500)));
        TacticVector highLine = service.vector(axes("High", "Low", "Balanced", "Normal"));
        TacticVector oppDirect = service.vector(axes("Standard", "Low", "Balanced", "Long"));
        TacticVector oppShort = service.vector(axes("Standard", "Low", "Balanced", "Short"));

        double defVsDirect = service.matchup(a, highLine, b, oppDirect).effDef1();
        double defVsShort = service.matchup(a, highLine, b, oppShort).effDef1();
        assertThat(defVsDirect).as("a high line concedes space to a direct opponent").isLessThan(defVsShort);
    }

    @Test
    void pressing_disruptsTheOpponentsAttack() {
        TeamProfile a = service.profile(List.of(new StarterValue("MC", 1500)));
        TeamProfile b = service.profile(List.of(new StarterValue("ST", 1500)));
        TacticVector neutral = service.vector(new PersonalizedTactic());
        TacticVector highPress = service.vector(axes("Standard", "High", "Balanced", "Normal"));

        double oppAttVsPress = service.matchup(a, highPress, b, neutral).effAtt2();
        double oppAttVsNeutral = service.matchup(a, neutral, b, neutral).effAtt2();
        assertThat(oppAttVsPress).as("high pressing lowers the opponent's effective attack").isLessThan(oppAttVsNeutral);
    }

    @Test
    void width_isRockPaperScissors() {
        TeamProfile a = service.profile(List.of(new StarterValue("ST", 1500)));
        TeamProfile b = service.profile(List.of(new StarterValue("DC", 1500)));
        TacticVector wide = service.vector(axes("Standard", "Low", "Wide", "Normal"));
        TacticVector oppNarrow = service.vector(axes("Standard", "Low", "Narrow", "Normal"));
        TacticVector oppWide = service.vector(axes("Standard", "Low", "Wide", "Normal"));

        double attVsNarrow = service.matchup(a, wide, b, oppNarrow).effAtt1();
        double attVsWide = service.matchup(a, wide, b, oppWide).effAtt1();
        assertThat(attVsNarrow).as("wide attack beats a narrow defense; cancels vs a wide one").isGreaterThan(attVsWide);
    }
}
