package com.footballmanagergamesimulator.economy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClubFinancialObligationRepository extends JpaRepository<ClubFinancialObligation, Long> {
    List<ClubFinancialObligation> findAllByTeamIdAndSettledFalseOrderByDueSeasonAscDueDayAscIdAsc(long teamId);
}
