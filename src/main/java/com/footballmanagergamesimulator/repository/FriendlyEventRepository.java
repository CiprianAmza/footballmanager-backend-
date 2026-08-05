package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.FriendlyEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FriendlyEventRepository extends JpaRepository<FriendlyEvent, Long> {
    List<FriendlyEvent> findAllBySeasonOrderByStartDayAsc(int season);
    List<FriendlyEvent> findAllByOrganizerTeamIdAndSeasonOrderByStartDayAsc(long organizerTeamId, int season);
    List<FriendlyEvent> findAllByWinnerTeamIdOrderBySeasonDesc(long winnerTeamId);
    List<FriendlyEvent> findAllBySeriesIdOrderBySeasonAscEditionNumberAsc(String seriesId);
}
