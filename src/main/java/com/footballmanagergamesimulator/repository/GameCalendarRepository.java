package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.GameCalendar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GameCalendarRepository extends JpaRepository<GameCalendar, Long> {

    List<GameCalendar> findBySeason(int season);

    Optional<GameCalendar> findTopByOrderBySeasonDesc();
}
