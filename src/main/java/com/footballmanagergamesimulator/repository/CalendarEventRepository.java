package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CalendarEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

    List<CalendarEvent> findAllBySeasonAndDay(int season, int day);

    List<CalendarEvent> findAllBySeasonAndDayAndPhase(int season, int day, String phase);

    List<CalendarEvent> findAllBySeasonAndDayAndStatus(int season, int day, String status);

    List<CalendarEvent> findAllBySeasonAndStatus(int season, String status);

    CalendarEvent findFirstBySeasonAndStatusOrderByDayAscPriorityAsc(int season, String status);
}
