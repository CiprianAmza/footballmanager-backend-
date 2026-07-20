package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Award;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AwardRepository extends JpaRepository<Award, Long> {

    List<Award> findAllBySeasonNumber(int seasonNumber);

    List<Award> findAllByWinnerId(long winnerId);

    List<Award> findAllByAwardTypeOrderBySeasonNumberDesc(String awardType);

    List<Award> findAllByAwardTypeAndCompetitionIdOrderBySeasonNumberDesc(
            String awardType, long competitionId);

    List<Award> findAllByCompetitionIdOrderBySeasonNumberDescAwardTypeAsc(long competitionId);

    List<Award> findAllByCompetitionIdAndSeasonNumber(long competitionId, int seasonNumber);

    boolean existsBySeasonNumberAndAwardType(int seasonNumber, String awardType);

    boolean existsBySeasonNumberAndCompetitionIdAndAwardType(
            int seasonNumber, long competitionId, String awardType);

    Optional<Award> findFirstBySeasonNumberAndCompetitionIdAndAwardType(
            int seasonNumber, long competitionId, String awardType);

    long countByWinnerIdAndAwardType(long winnerId, String awardType);
}
