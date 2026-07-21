package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface CompetitionHistoryRepository extends JpaRepository<CompetitionHistory, Long> {

    List<CompetitionHistory> findByCompetitionId(long competitionId);
    List<CompetitionHistory> findByTeamIdAndSeasonNumber(long teamId, long seasonNumber);
    List<CompetitionHistory> findByTeamId(long teamId);
    List<CompetitionHistory> findAllBySeasonNumber(long seasonNumber);
    List<CompetitionHistory> findAllByCompetitionIdAndSeasonNumber(long competitionId, long seasonNumber);
    List<CompetitionHistory> findAllByCompetitionIdIn(Collection<Long> competitionIds);
}
