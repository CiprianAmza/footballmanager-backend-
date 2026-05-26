package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.FriendlyMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FriendlyMatchRepository extends JpaRepository<FriendlyMatch, Long> {

    List<FriendlyMatch> findAllBySeasonAndStatus(int season, String status);

    List<FriendlyMatch> findAllBySeasonAndDay(int season, int day);

    List<FriendlyMatch> findAllBySeasonAndHomeTeamIdOrSeasonAndAwayTeamId(
            int season1, long homeTeamId, int season2, long awayTeamId);

    List<FriendlyMatch> findAllByScheduledByTeamIdAndSeason(long teamId, int season);

    List<FriendlyMatch> findAllBySeasonAndDayAndStatus(int season, int day, String status);
}
