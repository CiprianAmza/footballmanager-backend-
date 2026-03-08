package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.GameCalendar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GameCalendarRepository extends JpaRepository<GameCalendar, Long> {

    List<GameCalendar> findBySeason(int season);
}
