package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pace dynamics on the REAL live engine — the counterpart to
 * {@code EngineDynamicsIT#batchPowerIgnoresPace}, which pins that pace is absent
 * from the batch/AI scoring path. Here we prove pace genuinely moves the live
 * engine in three places, exactly as the handoff notes describe:
 *
 * <ul>
 *   <li><b>Attack</b>: {@link LiveMatchSimulationService#pickWeightedAttacker}
 *       tilts shots toward quicker attackers.</li>
 *   <li><b>Fouls</b>: {@link LiveMatchSimulationService#pickFouler} weights the
 *       foul inversely to pace (a slow defender mistimes more challenges).</li>
 *   <li><b>Stamina</b>: {@link LiveMatchSimulationService#applyStaminaTick}
 *       discounts high-tempo drain for pacy players, so they retain more
 *       stamina over a match.</li>
 * </ul>
 *
 * <p>Lives in the {@code service} package so the package-private engine methods
 * and {@code PlayerMatchState} are reachable. Deterministic: the picks use a
 * seeded {@link Random}; the stamina tick is RNG-free. Runs in the default
 * {@code mvn verify} suite.
 */
@SpringBootTest
@DisplayName("Pace dynamics on the live engine: attack / foul / stamina")
class LivePaceDynamicsIT {

    @Autowired private LiveMatchSimulationService liveService;

    private static final long SEED = 20260528L;
    private static final long FAST_ID = 1L;
    private static final long SLOW_ID = 2L;

    private Human player(long id, String position) {
        Human h = new Human();
        h.setId(id);
        h.setPosition(position);
        h.setRating(150.0); // identical so only pace differentiates the two players
        return h;
    }

    private LiveMatchSimulationService.PlayerMatchState state(long id, String position, int pace, double stamina) {
        LiveMatchSimulationService.PlayerMatchState st = new LiveMatchSimulationService.PlayerMatchState();
        st.playerId = id;
        st.position = position;
        st.pace = pace;
        st.currentStamina = stamina;
        st.staminaAttr = 10;
        st.naturalFitness = 10;
        st.isOnPitch = true;
        return st;
    }

    @Test
    @DisplayName("A quicker attacker is picked to shoot more often than a slow one")
    void paceTiltsAttackerSelection() {
        Human fast = player(FAST_ID, "ST");
        Human slow = player(SLOW_ID, "ST");
        Map<Long, LiveMatchSimulationService.PlayerMatchState> states = new HashMap<>();
        states.put(FAST_ID, state(FAST_ID, "ST", 20, 100.0));
        states.put(SLOW_ID, state(SLOW_ID, "ST", 1, 100.0));

        Random rng = new Random(SEED);
        int fastPicks = 0, slowPicks = 0, trials = 20000;
        for (int i = 0; i < trials; i++) {
            // Minute 70 (>= fatigue threshold) so pace influence applies.
            Human picked = liveService.pickWeightedAttacker(List.of(fast, slow), states, 70, rng);
            if (picked.getId() == FAST_ID) fastPicks++; else slowPicks++;
        }

        assertThat(fastPicks)
                .as("pace-20 attacker must be picked more than pace-1 (fast=%d, slow=%d of %d)",
                        fastPicks, slowPicks, trials)
                .isGreaterThan(slowPicks);
    }

    @Test
    @DisplayName("A slower defender commits more fouls than a quick one")
    void paceInverselyDrivesFoulSelection() {
        Human fast = player(FAST_ID, "DC");
        Human slow = player(SLOW_ID, "DC");
        Map<Long, LiveMatchSimulationService.PlayerMatchState> states = new HashMap<>();
        states.put(FAST_ID, state(FAST_ID, "DC", 20, 100.0));
        states.put(SLOW_ID, state(SLOW_ID, "DC", 1, 100.0));

        Random rng = new Random(SEED);
        int fastFouls = 0, slowFouls = 0, trials = 20000;
        for (int i = 0; i < trials; i++) {
            Human fouler = liveService.pickFouler(List.of(fast, slow), states, rng);
            if (fouler.getId() == FAST_ID) fastFouls++; else slowFouls++;
        }

        assertThat(slowFouls)
                .as("pace-1 defender must foul more than pace-20 (slow=%d, fast=%d of %d)",
                        slowFouls, fastFouls, trials)
                .isGreaterThan(fastFouls);
    }

    @Test
    @DisplayName("At high tempo a pacy player retains more stamina than a plodder")
    void paceDiscountsHighTempoStaminaDrain() {
        long fastId = FAST_ID, slowId = SLOW_ID;
        LiveMatchSimulationService.PlayerMatchState fast = state(fastId, "MC", 20, 100.0);
        LiveMatchSimulationService.PlayerMatchState slow = state(slowId, "MC", 1, 100.0);
        Map<Long, LiveMatchSimulationService.PlayerMatchState> states = new HashMap<>();
        states.put(fastId, fast);
        states.put(slowId, slow);

        // Both on the same team so they share the same (high) tempo multiplier — the only
        // difference in drain comes from the pace discount on the high-tempo surcharge.
        Set<Long> team1 = Set.of(fastId, slowId);
        double highTempo = liveService.tempoMultiplier("High");
        assertThat(highTempo).as("High tempo must surcharge stamina cost").isGreaterThan(1.0);

        for (int minute = 0; minute < 45; minute++) {
            liveService.applyStaminaTick(states, team1, Set.of(), highTempo, 1.0);
        }

        assertThat(fast.currentStamina)
                .as("pacy player must retain more stamina at high tempo (fast=%.3f, slow=%.3f)",
                        fast.currentStamina, slow.currentStamina)
                .isGreaterThan(slow.currentStamina);
    }
}
