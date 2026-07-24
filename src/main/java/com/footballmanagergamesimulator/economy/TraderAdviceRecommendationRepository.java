package com.footballmanagergamesimulator.economy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TraderAdviceRecommendationRepository extends JpaRepository<TraderAdviceRecommendation, Long> {
    Optional<TraderAdviceRecommendation> findByContractIdAndInstrumentIdAndSeasonNumberAndGameDay(
            long contractId, long instrumentId, int seasonNumber, int gameDay);
}
