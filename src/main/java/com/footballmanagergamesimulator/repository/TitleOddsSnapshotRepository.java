package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.TitleOddsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TitleOddsSnapshotRepository extends JpaRepository<TitleOddsSnapshot, Long> {

    List<TitleOddsSnapshot> findAllByCompetitionIdAndSeasonNumber(long competitionId, int seasonNumber);

    List<TitleOddsSnapshot> findAllByCompetitionIdAndSeasonNumberAndGamesPlayed(long competitionId, int seasonNumber,
                                                                                int gamesPlayed);
}
