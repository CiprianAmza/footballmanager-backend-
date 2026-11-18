package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.controller.TrainingController;
import com.footballmanagergamesimulator.model.TrainingSchedule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FitnessEconomyTest {

    private final MatchEngineConfig config = new MatchEngineConfig();
    private final MatchEngineConfig.Fitness fitness = config.getFitness();

    // ------------------------------------------------------------------ match drain

    @Test
    void drainFollowsTheTeamTempo() {
        assertThat(FitnessEconomy.matchDrain(config, "Much Lower", 10)).isCloseTo(5.2, within(1e-9));
        assertThat(FitnessEconomy.matchDrain(config, "Lower", 10)).isCloseTo(6.4, within(1e-9));
        assertThat(FitnessEconomy.matchDrain(config, "Standard", 10)).isCloseTo(8.0, within(1e-9));
        assertThat(FitnessEconomy.matchDrain(config, "Higher", 10)).isCloseTo(16.4, within(1e-9));
        assertThat(FitnessEconomy.matchDrain(config, "Much Higher", 10)).isCloseTo(22.0, within(1e-9));
    }

    @Test
    void clubWithoutATacticOrWithAnUnknownTempoPlaysStandard() {
        assertThat(FitnessEconomy.matchDrain(config, null, 10)).isCloseTo(8.0, within(1e-9));
        assertThat(FitnessEconomy.matchDrain(config, "", 10)).isCloseTo(8.0, within(1e-9));
        assertThat(FitnessEconomy.matchDrain(config, "Breakneck", 10)).isCloseTo(8.0, within(1e-9));
    }

    @Test
    void staminaSoftensOrSharpensTheDrainMildly() {
        assertThat(FitnessEconomy.staminaFactor(fitness, 20)).isCloseTo(0.85, within(1e-12));
        assertThat(FitnessEconomy.staminaFactor(fitness, 10)).isCloseTo(1.00, within(1e-12));
        assertThat(FitnessEconomy.staminaFactor(fitness, 1)).isCloseTo(1.135, within(1e-12));
        assertThat(FitnessEconomy.matchDrain(config, "Much Higher", 20)).isCloseTo(22.0 * 0.85, within(1e-9));
    }

    @Test
    void drainNeverTakesAPlayerBelowThePostMatchFloor() {
        assertThat(FitnessEconomy.afterMatch(config, 90, "Standard", 10)).isCloseTo(82.0, within(1e-9));
        assertThat(FitnessEconomy.afterMatch(config, 25, "Much Higher", 10)).isEqualTo(20.0);
    }

    // ------------------------------------------------------------------ days

    @Test
    void defaultScheduleHasNoRestDayAndItsIntensityScalesTheDailyRecovery() {
        for (int weekday = 0; weekday < 7; weekday++) {
            assertThat(FitnessEconomy.defaultDayPlan(weekday).rest()).as("weekday " + weekday).isFalse();
        }
        // Monday: Endurance 80 + Possession 60 + Team Bonding 10 = 150 / 300.
        FitnessEconomy.DayPlan monday = FitnessEconomy.defaultDayPlan(0);
        assertThat(monday.sessions()).isEqualTo(3);
        assertThat(monday.intensity()).isCloseTo(0.5, within(1e-12));
        assertThat(FitnessEconomy.dayGain(fitness, monday)).isCloseTo(2.4 * 0.75, within(1e-12));
        // A schedule with no rows is the default schedule.
        assertThat(FitnessEconomy.dayPlan(List.of(), 0)).isEqualTo(monday);
    }

    @Test
    void dailyRecoveryUsesNaturalFitnessAndStopsAtOneHundred() {
        FitnessEconomy.DayPlan monday = FitnessEconomy.defaultDayPlan(0);
        double gain = FitnessEconomy.dayGain(fitness, monday);
        assertThat(FitnessEconomy.recover(fitness, 70, monday, 10)).isCloseTo(70 + gain, within(1e-12));
        assertThat(FitnessEconomy.recover(fitness, 70, monday, 20)).isCloseTo(70 + gain * 1.25, within(1e-12));
        assertThat(FitnessEconomy.recover(fitness, 70, monday, 1)).isCloseTo(70 + gain * 0.775, within(1e-12));
        assertThat(FitnessEconomy.recover(fitness, 99.5, monday, 20)).isEqualTo(100.0);
        assertThat(FitnessEconomy.recover(fitness, 100, monday, 20)).isEqualTo(100.0);
    }

    @Test
    void restDayRecoversMoreThanAnyTrainingDayAndTrainsNothing() {
        List<TrainingSchedule> schedule = withRest(1); // Tuesday off
        FitnessEconomy.DayPlan tuesday = FitnessEconomy.dayPlan(schedule, 1);
        FitnessEconomy.DayPlan wednesday = FitnessEconomy.dayPlan(schedule, 2);
        assertThat(tuesday.rest()).isTrue();
        assertThat(tuesday.sessions()).isZero();
        assertThat(wednesday.rest()).isFalse();
        assertThat(FitnessEconomy.dayGain(fitness, tuesday)).isEqualTo(4.8);
        for (int weekday = 0; weekday < 7; weekday++) {
            assertThat(FitnessEconomy.dayGain(fitness, FitnessEconomy.defaultDayPlan(weekday)))
                    .isLessThan(FitnessEconomy.dayGain(fitness, tuesday));
        }
    }

    @Test
    void sessionsAtIntensityZeroCountAsRestAndHarderDaysRecoverLess() {
        List<TrainingSchedule> schedule = new ArrayList<>(List.of(
                row(3, 0, "Physical", 0), row(3, 1, "General", 0),
                row(4, 0, "Physical", 90), row(4, 1, "Match", 90), row(4, 2, "General", 90)));
        assertThat(FitnessEconomy.dayPlan(schedule, 3).rest()).isTrue();
        FitnessEconomy.DayPlan hard = FitnessEconomy.dayPlan(schedule, 4);
        assertThat(hard.intensity()).isCloseTo(0.9, within(1e-12));
        assertThat(FitnessEconomy.dayGain(fitness, hard)).isCloseTo(2.4 * 0.55, within(1e-12));
        // A weekday the club left empty is a rest day too.
        assertThat(FitnessEconomy.dayPlan(schedule, 0).rest()).isTrue();
    }

    @Test
    void weekdayMatchesTheCalendarDisplay() {
        // CalendarService.getDayOfWeek(day) = DAYS_OF_WEEK[day % 7], Monday first.
        assertThat(FitnessEconomy.weekday(7)).isZero();   // Monday
        assertThat(FitnessEconomy.weekday(1)).isEqualTo(1); // Tuesday
        assertThat(FitnessEconomy.weekday(13)).isEqualTo(6); // Sunday
    }

    // ------------------------------------------------------------------ training load

    @Test
    void trainingLoadIsTrainedOverDefaultSessionsCappedAtOne() {
        assertThat(FitnessEconomy.trainingLoad(8, 12)).isCloseTo(2.0 / 3.0, within(1e-12));
        assertThat(FitnessEconomy.trainingLoad(12, 12)).isEqualTo(1.0);
        assertThat(FitnessEconomy.trainingLoad(20, 12)).isEqualTo(1.0);
        assertThat(FitnessEconomy.trainingLoad(0, 0)).isEqualTo(1.0);
    }

    @Test
    void defaultScheduleTrainsExactlyItsOwnSessionsSoItsLoadIsOne() {
        int trained = 0, defaults = 0;
        for (int weekday = 0; weekday < 7; weekday++) {
            trained += FitnessEconomy.dayPlan(TrainingController.buildDefaultSchedule(5), weekday).sessions();
            defaults += FitnessEconomy.defaultDayPlan(weekday).sessions();
        }
        assertThat(FitnessEconomy.trainingLoad(trained, defaults)).isEqualTo(1.0);
        assertThat(FitnessEconomy.declineMultiplier(1.0)).isEqualTo(1.0);
    }

    @Test
    void declineChancesGrowFromOneToTwoAsTheLoadFalls() {
        assertThat(FitnessEconomy.declineMultiplier(0.5)).isEqualTo(1.5);
        assertThat(FitnessEconomy.declineMultiplier(0.0)).isEqualTo(2.0);
        assertThat(FitnessEconomy.declineMultiplier(1.7)).isEqualTo(1.0);
    }

    private static List<TrainingSchedule> withRest(int weekday) {
        List<TrainingSchedule> rows = new ArrayList<>();
        for (TrainingSchedule row : TrainingController.buildDefaultSchedule(1)) {
            if (row.getDayOfWeek() == weekday) {
                row.setSessionType("Rest");
                row.setSessionName("Rest");
                row.setIntensity(0);
            }
            rows.add(row);
        }
        return rows;
    }

    private static TrainingSchedule row(int day, int slot, String type, int intensity) {
        TrainingSchedule row = new TrainingSchedule();
        row.setDayOfWeek(day);
        row.setSessionSlot(slot);
        row.setSessionType(type);
        row.setSessionName(type);
        row.setIntensity(intensity);
        return row;
    }
}
