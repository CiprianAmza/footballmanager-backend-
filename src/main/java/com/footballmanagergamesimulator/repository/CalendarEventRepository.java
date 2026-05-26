package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CalendarEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

    List<CalendarEvent> findAllBySeasonAndDay(int season, int day);

    List<CalendarEvent> findAllBySeasonAndDayAndPhase(int season, int day, String phase);

    List<CalendarEvent> findAllBySeasonAndDayAndStatus(int season, int day, String status);

    List<CalendarEvent> findAllBySeasonAndStatus(int season, String status);

    CalendarEvent findFirstBySeasonAndStatusOrderByDayAscPriorityAsc(int season, String status);

    List<CalendarEvent> findBySeasonAndCompetitionIdAndMatchday(int season, long competitionId, int matchday);

    // Atomic claim: only one thread can transition PENDING -> PROCESSING for a given event.
    // Returns 1 if this caller claimed it, 0 if another thread already did.
    @Modifying
    @Transactional
    @Query("UPDATE CalendarEvent e SET e.status = 'PROCESSING' WHERE e.id = :id AND e.status = 'PENDING'")
    int claimEvent(@Param("id") long id);

    // Recovery for events that were claimed but never completed (process died, exception,
    // etc). Called at the start of advance() while no other thread is processing
    // (advance is synchronized) so it's safe to flip them back to PENDING for retry.
    @Modifying
    @Transactional
    @Query("UPDATE CalendarEvent e SET e.status = 'PENDING' WHERE e.season = :season AND e.status = 'PROCESSING'")
    int releaseStuckEvents(@Param("season") int season);

    // Release a single event back to PENDING — used in exception handlers around event
    // processing so the next advance() can retry it instead of leaving it stuck.
    @Modifying
    @Transactional
    @Query("UPDATE CalendarEvent e SET e.status = 'PENDING' WHERE e.id = :id AND e.status = 'PROCESSING'")
    int releaseEvent(@Param("id") long id);
}
