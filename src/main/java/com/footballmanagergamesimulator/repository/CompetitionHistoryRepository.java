package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompetitionHistoryRepository extends JpaRepository<CompetitionHistory, Long> {

    List<CompetitionHistory> findByCompetitionId(long competitionId);
    List<CompetitionHistory> findByTeamIdAndSeasonNumber(long teamId, long seasonNumber);
}
