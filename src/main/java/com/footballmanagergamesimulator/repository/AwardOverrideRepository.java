package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.AwardOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AwardOverrideRepository extends JpaRepository<AwardOverride, Long> {

    Optional<AwardOverride> findBySeasonNumberAndCompetitionIdAndAwardType(
            int seasonNumber, long competitionId, String awardType);

    void deleteBySeasonNumberAndCompetitionIdAndAwardType(
            int seasonNumber, long competitionId, String awardType);
}
