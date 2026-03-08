package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.TrainingSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrainingScheduleRepository extends JpaRepository<TrainingSchedule, Long> {

    List<TrainingSchedule> findAllByTeamId(long teamId);
    List<TrainingSchedule> findAllByTeamIdAndDayOfWeek(long teamId, int dayOfWeek);

}
