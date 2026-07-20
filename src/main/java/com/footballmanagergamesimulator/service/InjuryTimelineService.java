package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.GameplayFeatureConfig;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.InjuryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Absolute injury calendar. Active injuries retain their original duration for
 * save compatibility and are completed only when their return date is reached.
 */
@Service
public class InjuryTimelineService {

    // SEASON_TRANSITION is processed on day 360; days 361-365 are archived and
    // therefore must not count toward an injury's recovery duration.
    public static final int DAYS_PER_SEASON = 360;

    @Autowired private InjuryRepository injuryRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private GameCalendarRepository gameCalendarRepository;
    @Autowired(required = false) private GameplayFeatureConfig gameplayFeatures;

    public record GameDate(int season, int day) {}

    /** Populate an injury's absolute return date without persisting it. */
    public Injury schedule(Injury injury, int season, int day, int durationDays) {
        int safeSeason = Math.max(1, season);
        int safeDay = Math.max(1, Math.min(DAYS_PER_SEASON, day));
        int duration = Math.max(1, durationDays);
        long returnOrdinal = ordinal(safeSeason, safeDay) + duration;

        injury.setSeasonNumber(safeSeason);
        injury.setDaysRemaining(duration);
        injury.setReturnSeason((int) (returnOrdinal / DAYS_PER_SEASON) + 1);
        injury.setReturnDay((int) (returnOrdinal % DAYS_PER_SEASON) + 1);
        return injury;
    }

    /** Schedule from the currently active calendar (used by match helpers without a day argument). */
    public Injury scheduleFromCurrentDate(Injury injury, int season, int durationDays) {
        GameDate current = currentDate();
        int day = current.season() == season ? current.day() : 1;
        return schedule(injury, season, day, durationDays);
    }

    /** Remaining calendar days, computed rather than written once per day. */
    public int remainingDays(Injury injury, int season, int day) {
        if (injury == null || injury.getDaysRemaining() <= 0) return 0;
        if (injury.getReturnSeason() <= 0 || injury.getReturnDay() <= 0) {
            return Math.max(0, injury.getDaysRemaining());
        }
        long remaining = ordinal(injury.getReturnSeason(), injury.getReturnDay())
                - ordinal(Math.max(1, season), Math.max(1, Math.min(DAYS_PER_SEASON, day)));
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, remaining));
    }

    public GameDate currentDate() {
        return gameCalendarRepository.findAll().stream()
                .max(Comparator.comparingInt(GameCalendar::getSeason))
                .map(calendar -> new GameDate(calendar.getSeason(), calendar.getCurrentDay()))
                .orElse(new GameDate(1, 1));
    }

    /**
     * Recover only injuries whose absolute return date has arrived. Legacy save
     * rows are assigned a return date once, using their current remaining value.
     */
    @Transactional
    public int processRecoveries(int season, int day) {
        // When availability is globally disabled, do not even inspect the
        // historical injury table. Long-running saves can contain thousands of
        // inactive rows, and this method is called once per simulated day.
        if (availabilityDisabled()) return 0;

        List<Injury> legacy = injuryRepository
                .findAllByDaysRemainingGreaterThanAndReturnSeasonLessThanEqual(0, 0);
        if (!legacy.isEmpty()) {
            for (Injury injury : legacy) {
                schedule(injury, season, day, injury.getDaysRemaining());
            }
            injuryRepository.saveAll(legacy);
        }

        List<Injury> due = injuryRepository.findAllDueForRecovery(season, day);
        if (due.isEmpty()) return 0;

        Set<Long> playerIds = due.stream().map(Injury::getPlayerId).collect(Collectors.toSet());
        for (Injury injury : due) injury.setDaysRemaining(0);
        injuryRepository.saveAll(due);

        Set<Long> stillInjured = injuryRepository
                .findAllByPlayerIdInAndDaysRemainingGreaterThan(playerIds, 0)
                .stream()
                .map(Injury::getPlayerId)
                .collect(Collectors.toCollection(HashSet::new));

        List<Human> healedPlayers = new ArrayList<>();
        for (Human player : humanRepository.findAllById(playerIds)) {
            if (stillInjured.contains(player.getId())) continue;
            player.setCurrentStatus("Available");
            healedPlayers.add(player);
        }
        if (!healedPlayers.isEmpty()) humanRepository.saveAll(healedPlayers);
        return healedPlayers.size();
    }

    private boolean availabilityDisabled() {
        return gameplayFeatures != null && gameplayFeatures.isPlayerAvailabilityDisabled();
    }

    private long ordinal(int season, int day) {
        return (long) (season - 1) * DAYS_PER_SEASON + (day - 1L);
    }
}
