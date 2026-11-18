package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalPlayerContextAdapter;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The in-match side of tempo: what a player brings to the scoring of a match played at that tempo. */
class FitnessInMatchTest {

    private static final double FRESH = 80.0;
    private final MatchEngineConfig config = new MatchEngineConfig(); // shipped defaults = application.yml

    @Test
    void standardAndSlowerTemposLeaveThePreMatchFitnessExactlyAsItIs() {
        for (String tempo : new String[]{"Standard", "Lower", "Much Lower", null, "unknown"}) {
            for (double pre : new double[]{100, 80, 60, 25}) {
                for (int stamina : new int[]{1, 10, 20}) {
                    assertThat(FitnessFactor.inMatch(config, pre, tempo, stamina, FRESH)).as(tempo + " " + pre)
                            .isEqualTo(pre);
                }
            }
        }
    }

    @Test
    void aboveStandardThePlayerCannotCarryThePaceFromTheFreshLevelDown() {
        // Much Higher at stamina 10: drain 22, Standard 8, extra 14; half of it during the match.
        assertThat(FitnessFactor.inMatch(config, 100, "Much Higher", 10, FRESH)).isCloseTo(73.0, within(1e-9));
        assertThat(FitnessFactor.inMatch(config, 80, "Much Higher", 10, FRESH)).isCloseTo(73.0, within(1e-9));
        assertThat(FitnessFactor.inMatch(config, 60, "Much Higher", 10, FRESH)).isCloseTo(53.0, within(1e-9));
        // Higher: drain 16.4, extra 8.4.
        assertThat(FitnessFactor.inMatch(config, 100, "Higher", 10, FRESH)).isCloseTo(75.8, within(1e-9));
        // Even a fully fresh player ends a Much Higher match below the fresh level: the scoring sees
        // the match average (half the extra drain), so the end of the match is twice as far down.
        double average = FitnessFactor.inMatch(config, 100, "Much Higher", 10, FRESH);
        assertThat(FRESH - 2 * (FRESH - average)).isCloseTo(66.0, within(1e-9)).isLessThan(FRESH);
    }

    @Test
    void staminaSoftensTheInMatchCostAndTheRuleCanBeSwitchedOff() {
        // Stamina 20: extra 14 × 0.85 = 11.9.
        assertThat(FitnessFactor.inMatch(config, 90, "Much Higher", 20, FRESH)).isCloseTo(80 - 5.95, within(1e-9));
        config.getFitness().setInMatchFromFresh(false);
        assertThat(FitnessFactor.inMatch(config, 100, "Much Higher", 10, FRESH)).isCloseTo(93.0, within(1e-9));
        config.getFitness().setInMatchShare(0.0);
        assertThat(FitnessFactor.inMatch(config, 100, "Much Higher", 10, FRESH)).isEqualTo(100.0);
    }

    @Test
    void scoringAdapterAppliesTheRuleFromTheTeamTempo() {
        CompartmentEngineConfig compartment = CompartmentConfigFixture.load();
        CanonicalPlayerContextAdapter adapter = new CanonicalPlayerContextAdapter(compartment, config);
        CanonicalLineupPlayer player = player(100.0);
        double standard = score(adapter, player, "Standard");
        double lower = score(adapter, player, "Lower");
        double muchHigher = score(adapter, player, "Much Higher");
        // Fresh at Standard: factor 1.0, the same score as a player with no fitness rule at all.
        CanonicalPlayerContextAdapter withoutRule;
        MatchEngineConfig off = new MatchEngineConfig();
        off.getFitness().setInMatchShare(0.0);
        withoutRule = new CanonicalPlayerContextAdapter(compartment, off);
        assertThat(standard).isEqualTo(score(withoutRule, player, "Standard"));
        assertThat(muchHigher / score(withoutRule, player, "Much Higher"))
                .isCloseTo(FitnessFactor.of(73.0, compartment), within(1e-9));
        assertThat(FitnessFactor.of(73.0, compartment)).isCloseTo(0.9475, within(1e-12));
        assertThat(lower).isEqualTo(score(withoutRule, player, "Lower"));
    }

    private static double score(CanonicalPlayerContextAdapter adapter, CanonicalLineupPlayer player, String tempo) {
        TacticalContextInput context = new TacticalContextInput("Balanced", tempo, "Normal", "Standard", "Normal",
                "Balanced", "Standard", TacticalContextInput.DEFAULT_IN_POSSESSION,
                TacticalContextInput.DEFAULT_TIME_WASTING, TacticalContextInput.DEFAULT_DRIBBLING,
                TacticalContextInput.DEFAULT_WIDE_PLAY, TacticalContextInput.DEFAULT_TRANSITION, List.of());
        return adapter.evaluate(player, context).rating().compartments().values().stream()
                .mapToDouble(ContextualPlayerRating.CompartmentBreakdown::finalScore).sum();
    }

    private static CanonicalLineupPlayer player(double fitness) {
        Map<PlayerAttribute, Integer> attributes = new EnumMap<>(PlayerAttribute.class);
        for (PlayerAttribute a : PlayerAttribute.values()) attributes.put(a, 13);
        attributes.put(PlayerAttribute.STAMINA, 10);
        PlayerCapabilitySnapshot capability = new PlayerCapabilitySnapshot(7L, PlayerPosition.MC,
                Map.of(PlayerPosition.MC, 20), Map.of(), 10, 10, false, false, false);
        return new CanonicalLineupPlayer(7L, PlayerPosition.MC, 1, null, Duty.SUPPORT, attributes, fitness, 70,
                capability, 50.0, Set.of(), ForwardInstruction.DEFAULT);
    }
}
