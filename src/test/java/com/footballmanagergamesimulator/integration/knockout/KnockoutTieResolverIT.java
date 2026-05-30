package com.footballmanagergamesimulator.integration.knockout;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.TacticalScoreService;
import com.footballmanagergamesimulator.service.TacticalScoreService.StarterValue;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import com.footballmanagergamesimulator.service.knockout.KnockoutTieResolver;
import com.footballmanagergamesimulator.service.knockout.LegFormat;
import com.footballmanagergamesimulator.service.knockout.TieResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural + statistical contract for {@link KnockoutTieResolver}: the shared
 * single-leg / two-leg tie resolution used by the outcome-simulation tests and
 * (later) the live engine.
 *
 * <p>Runs in the default {@code mvn verify} suite — it's fast (no DB, just the
 * scoring service) and guards the core knockout rules.
 */
@SpringBootTest
@DisplayName("KnockoutTieResolver: single/two-leg → always decisive, ET + penalties on level ties")
class KnockoutTieResolverIT {

    private static final long SEED = 20260528L;
    private static final double STRONG = 8000.0;
    private static final double WEAK = 4000.0;
    private static final double EQUAL = 6000.0;

    @Autowired private KnockoutTieResolver resolver;
    @Autowired private MatchSimulationService matchSim;
    @Autowired private MatchEngineConfig config;
    @Autowired private TacticalScoreService tacticalScoreService;

    private double savedEtGoals;
    private double savedPenWeaker;

    @org.junit.jupiter.api.BeforeEach
    void saveConfig() {
        savedEtGoals = config.getKnockout().getExtraTimeExpectedGoals();
        savedPenWeaker = config.getKnockout().getPenaltyWeakerTeamWinChance();
    }

    @AfterEach
    void restore() {
        matchSim.setRandomForTesting(new Random());
        config.getKnockout().setExtraTimeExpectedGoals(savedEtGoals);
        config.getKnockout().setPenaltyWeakerTeamWinChance(savedPenWeaker);
    }

    @Test
    @DisplayName("Single-leg is always decisive; level-after-90 ties go to extra time")
    void singleLegAlwaysDecisive() {
        matchSim.setRandomForTesting(new Random(SEED));
        Random tb = new Random(SEED + 1);

        int wentToExtraTime = 0;
        for (int i = 0; i < 5000; i++) {
            TieResult r = resolver.resolve(STRONG, WEAK, LegFormat.SINGLE_LEG, tb);

            // A winner is always defined (teamAWon XOR teamBWon).
            assertThat(r.teamAWon()).isNotEqualTo(r.teamBWon());
            // No second leg in a single-leg tie.
            assertThat(r.leg2A()).isEqualTo(-1);
            assertThat(r.leg2B()).isEqualTo(-1);

            if (r.leg1A() == r.leg1B()) {
                // Tied after 90' → must have gone to extra time (and maybe pens).
                assertThat(r.extraTime())
                        .as("a level single-leg match must go to extra time, iteration %d", i)
                        .isTrue();
                wentToExtraTime++;
            } else {
                // Decided in normal time → no ET, no pens, winner = higher score.
                assertThat(r.extraTime()).isFalse();
                assertThat(r.penalties()).isFalse();
                assertThat(r.teamAWon()).isEqualTo(r.leg1A() > r.leg1B());
            }
        }
        assertThat(wentToExtraTime)
                .as("some single-leg matches must end level and go to extra time")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("Two-leg: individual legs may draw, decided on aggregate")
    void twoLegLegsCanDraw() {
        matchSim.setRandomForTesting(new Random(SEED));
        Random tb = new Random(SEED + 1);

        int drawnLegs = 0;
        int levelAggregates = 0;
        for (int i = 0; i < 5000; i++) {
            TieResult r = resolver.resolve(STRONG, WEAK, LegFormat.TWO_LEG, tb);

            assertThat(r.teamAWon()).isNotEqualTo(r.teamBWon());
            assertThat(r.leg2A()).isGreaterThanOrEqualTo(0);
            assertThat(r.leg2B()).isGreaterThanOrEqualTo(0);
            assertThat(r.aggregateA()).isEqualTo(r.leg1A() + r.leg2A());
            assertThat(r.aggregateB()).isEqualTo(r.leg1B() + r.leg2B());

            if (r.leg1A() == r.leg1B() || r.leg2A() == r.leg2B()) drawnLegs++;

            if (r.aggregateA() == r.aggregateB()) {
                // Level aggregate → extra time required.
                assertThat(r.extraTime())
                        .as("a level aggregate must go to extra time, iteration %d", i)
                        .isTrue();
                levelAggregates++;
            } else if (!r.extraTime()) {
                // Decided on aggregate without ET → winner is higher aggregate.
                assertThat(r.teamAWon()).isEqualTo(r.aggregateA() > r.aggregateB());
            }
        }
        assertThat(drawnLegs).as("some individual legs must be draws").isGreaterThan(0);
        assertThat(levelAggregates).as("some ties must be level on aggregate").isGreaterThan(0);
    }

    @Test
    @DisplayName("Penalty shootout is a ~50/50 coin-flip with default config")
    void penaltiesAreCoinFlip() {
        // Equal powers + tiny ET goals → many ties reach penalties.
        config.getKnockout().setExtraTimeExpectedGoals(0.05);
        config.getKnockout().setPenaltyWeakerTeamWinChance(0.5);
        matchSim.setRandomForTesting(new Random(SEED));
        Random tb = new Random(SEED + 1);

        int pens = 0, aWins = 0;
        for (int i = 0; i < 20000; i++) {
            TieResult r = resolver.resolve(EQUAL, EQUAL, LegFormat.TWO_LEG, tb);
            if (r.penalties()) {
                pens++;
                if (r.teamAWon()) aWins++;
            }
        }
        assertThat(pens).as("equal teams with near-zero ET goals must reach penalties often").isGreaterThan(500);
        double aRate = aWins / (double) pens;
        assertThat(aRate)
                .as("penalties between equal teams should be ~50/50 (got %.3f over %d shootouts)", aRate, pens)
                .isBetween(0.46, 0.54);
    }

    @Test
    @DisplayName("Penalty edge favours the weaker team when configured")
    void penaltiesFavourWeakerWhenConfigured() {
        config.getKnockout().setExtraTimeExpectedGoals(0.05);
        config.getKnockout().setPenaltyWeakerTeamWinChance(0.55); // weaker (B) wins 55%
        matchSim.setRandomForTesting(new Random(SEED));
        Random tb = new Random(SEED + 1);

        int pens = 0, weakerWins = 0;
        for (int i = 0; i < 20000; i++) {
            // A is stronger, B is weaker.
            TieResult r = resolver.resolve(STRONG, WEAK, LegFormat.TWO_LEG, tb);
            if (r.penalties()) {
                pens++;
                if (r.teamBWon()) weakerWins++; // B is the weaker side
            }
        }
        assertThat(pens).as("must reach some penalty shootouts").isGreaterThan(100);
        double weakerRate = weakerWins / (double) pens;
        assertThat(weakerRate)
                .as("weaker team should win ~55%% of shootouts (got %.3f over %d)", weakerRate, pens)
                .isBetween(0.50, 0.60);
    }

    @Test
    @DisplayName("Two-leg favours the stronger team more than single-leg (less variance)")
    void twoLegFavoursStronger() {
        double singleRate = strongWinRate(LegFormat.SINGLE_LEG, 10000);
        double twoLegRate = strongWinRate(LegFormat.TWO_LEG, 10000);
        assertThat(twoLegRate)
                .as("two-leg (%.3f) should favour the stronger team at least as much as single-leg (%.3f)",
                        twoLegRate, singleRate)
                .isGreaterThanOrEqualTo(singleRate);
    }

    @Test
    @DisplayName("Deterministic: same seeds → identical results")
    void deterministic() {
        TieResult[] run1 = runSeeded();
        TieResult[] run2 = runSeeded();
        assertThat(run2).as("same seeds must reproduce identical tie results").containsExactly(run1);
    }

    @Test
    @DisplayName("Two-axis decide: unequal aggregate is decided without extra time")
    void twoAxisUnequalAggregateNoExtraTime() {
        TeamProfile a = profile(8000, "ST");
        TeamProfile b = profile(4000, "DC");
        TacticVector neutral = tacticalScoreService.vector(new PersonalizedTactic());

        var d = resolver.decide(a, neutral, b, neutral, 3, 1, new Random(SEED));
        assertThat(d.teamAWon()).isTrue();
        assertThat(d.extraTime()).isFalse();
        assertThat(d.penalties()).isFalse();
    }

    @Test
    @DisplayName("Two-axis decide: level aggregate runs extra time on the attack-vs-defense model")
    void twoAxisLevelAggregateGoesToExtraTimeAndFavoursStronger() {
        TeamProfile strong = profile(9000, "ST");
        TeamProfile weak = profile(4500, "DC");
        TacticVector neutral = tacticalScoreService.vector(new PersonalizedTactic());
        Random rng = new Random(SEED);

        int strongWins = 0, decisiveEt = 0, n = 4000;
        for (int i = 0; i < n; i++) {
            var d = resolver.decide(strong, neutral, weak, neutral, 1, 1, rng);
            // Always decisive (ET then penalties).
            assertThat(d.extraTime()).isTrue();
            if (d.teamAWon()) strongWins++;
            if (!d.penalties()) decisiveEt++;
        }
        assertThat(decisiveEt).as("some level ties are settled in extra time, not just penalties").isGreaterThan(0);
        assertThat(strongWins).as("the stronger squad advances from a level tie more often than not")
                .isGreaterThan(n / 2);
    }

    private TeamProfile profile(double value, String position) {
        return tacticalScoreService.profile(List.of(new StarterValue(position, value)));
    }

    private double strongWinRate(LegFormat format, int n) {
        matchSim.setRandomForTesting(new Random(SEED));
        Random tb = new Random(SEED + 1);
        int strongWins = 0;
        for (int i = 0; i < n; i++) {
            // A is the stronger team.
            if (resolver.resolve(STRONG, WEAK, format, tb).teamAWon()) strongWins++;
        }
        return strongWins / (double) n;
    }

    private TieResult[] runSeeded() {
        matchSim.setRandomForTesting(new Random(SEED));
        Random tb = new Random(SEED + 1);
        TieResult[] out = new TieResult[2000];
        for (int i = 0; i < out.length; i++) {
            LegFormat fmt = (i % 2 == 0) ? LegFormat.SINGLE_LEG : LegFormat.TWO_LEG;
            out[i] = resolver.resolve(STRONG, WEAK, fmt, tb);
        }
        return out;
    }
}
