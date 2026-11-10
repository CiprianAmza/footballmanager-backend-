package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CompetitionSeasonExpectation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompetitionSeasonExpectationRepository extends JpaRepository<CompetitionSeasonExpectation, Long> {
    List<CompetitionSeasonExpectation> findAllBySeasonNumber(int seasonNumber);
    List<CompetitionSeasonExpectation> findAllBySeasonNumberAndCompetitionId(int seasonNumber, long competitionId);
    Optional<CompetitionSeasonExpectation> findBySeasonNumberAndCompetitionIdAndTeamId(
            int seasonNumber, long competitionId, long teamId);
}
