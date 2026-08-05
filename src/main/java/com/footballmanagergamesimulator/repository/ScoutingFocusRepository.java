package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ScoutingFocus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScoutingFocusRepository extends JpaRepository<ScoutingFocus, Long> {
    List<ScoutingFocus> findAllByTeamIdOrderByIdDesc(long teamId);
    List<ScoutingFocus> findAllByTeamIdAndStatusOrderByIdDesc(long teamId, String status);
    List<ScoutingFocus> findAllByScoutIdAndStatus(long scoutId, String status);
    List<ScoutingFocus> findAllBySeasonAndStatusAndEndDayLessThanEqual(int season, String status, int endDay);
}
