package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerTrainingCounter;
import com.footballmanagergamesimulator.model.TrainingSchedule;
import com.footballmanagergamesimulator.repository.PlayerTrainingCounterRepository;
import com.footballmanagergamesimulator.repository.TrainingScheduleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a human club's weekly training schedule means for a calendar day (rest or training, how
 * hard), and how much of the default schedule it actually trained since its last monthly update
 * (the training load that scales growth and decline). AI clubs follow the default schedule: their
 * load is always 1 and nothing is stored for them.
 *
 * <p>Storage: two team-level rows of {@code player_training_counter} (player id 0, attributes
 * {@value #TRAINED} and {@value #DEFAULT}). They are reset with the club's other counters after the
 * monthly update ({@link TrainingCounterService#resetCounts}) and travel with saves, so no new
 * table or column is needed; a club without them has load 1.
 */
@Service
public class TrainingLoadService {

    static final long TEAM_ROW = 0L;
    static final String TRAINED = "#load:trained-sessions";
    static final String DEFAULT = "#load:default-sessions";

    @Autowired TrainingScheduleRepository scheduleRepository;
    @Autowired PlayerTrainingCounterRepository counterRepository;

    /** The club's plan for this calendar day (its own schedule, or the default one when it has none). */
    public FitnessEconomy.DayPlan planFor(long teamId, int calendarDay) {
        List<TrainingSchedule> schedule = scheduleRepository.findAllByTeamId(teamId);
        return FitnessEconomy.dayPlan(schedule, FitnessEconomy.weekday(calendarDay));
    }

    /** One training day of a human club: its trained sessions against the default schedule's. */
    @Transactional
    public void recordTrainingDay(long teamId, int calendarDay, FitnessEconomy.DayPlan plan) {
        int defaults = FitnessEconomy.defaultDayPlan(FitnessEconomy.weekday(calendarDay)).sessions();
        Map<String, PlayerTrainingCounter> rows = rows(teamId);
        PlayerTrainingCounter trained = rows.computeIfAbsent(TRAINED, name -> newRow(teamId, name));
        PlayerTrainingCounter scheduled = rows.computeIfAbsent(DEFAULT, name -> newRow(teamId, name));
        trained.setSessions(trained.getSessions() + plan.sessions());
        scheduled.setSessions(scheduled.getSessions() + defaults);
        counterRepository.saveAll(List.of(trained, scheduled));
    }

    /** Trained ÷ default sessions since the last update, capped at 1; 1 when nothing is recorded. */
    public double load(long teamId) {
        Map<String, PlayerTrainingCounter> rows = rows(teamId);
        PlayerTrainingCounter trained = rows.get(TRAINED);
        PlayerTrainingCounter scheduled = rows.get(DEFAULT);
        if (trained == null || scheduled == null) return 1.0;
        return FitnessEconomy.trainingLoad(trained.getSessions(), scheduled.getSessions());
    }

    private Map<String, PlayerTrainingCounter> rows(long teamId) {
        Map<String, PlayerTrainingCounter> rows = new HashMap<>();
        for (PlayerTrainingCounter row : counterRepository.findAllByTeamIdAndPlayerIdIn(teamId, List.of(TEAM_ROW))) {
            rows.put(row.getAttribute(), row);
        }
        return rows;
    }

    private static PlayerTrainingCounter newRow(long teamId, String name) {
        PlayerTrainingCounter row = new PlayerTrainingCounter();
        row.setPlayerId(TEAM_ROW);
        row.setTeamId(teamId);
        row.setAttribute(name);
        row.setSessions(0);
        return row;
    }
}
