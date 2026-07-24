package com.footballmanagergamesimulator.economy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface ClubFinancialObligationRepository extends JpaRepository<ClubFinancialObligation, Long> {
    List<ClubFinancialObligation> findAllByTeamIdAndSettledFalseOrderByDueSeasonAscDueDayAscIdAsc(long teamId);
    List<ClubFinancialObligation> findAllByTeamIdInAndSettledFalseOrderByTeamIdAscDueSeasonAscDueDayAscIdAsc(Collection<Long> teamIds);
}
