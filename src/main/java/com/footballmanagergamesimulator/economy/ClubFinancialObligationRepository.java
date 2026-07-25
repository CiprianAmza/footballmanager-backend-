package com.footballmanagergamesimulator.economy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;

public interface ClubFinancialObligationRepository extends JpaRepository<ClubFinancialObligation, Long> {
    List<ClubFinancialObligation> findAllByTeamIdAndSettledFalseOrderByDueSeasonAscDueDayAscIdAsc(long teamId);
    List<ClubFinancialObligation> findAllByTeamIdInAndSettledFalseOrderByTeamIdAscDueSeasonAscDueDayAscIdAsc(Collection<Long> teamIds);

    @Query("""
            select obligation.teamId as teamId,
                   sum(case when obligation.amountRemaining > 0 then obligation.amountRemaining else 0 end) as totalAmount
              from ClubFinancialObligation obligation
             where obligation.teamId in :teamIds and obligation.settled = false
             group by obligation.teamId
            """)
    List<TeamObligationTotal> sumUnsettledAmountByTeamIds(@Param("teamIds") Collection<Long> teamIds);

    interface TeamObligationTotal {
        Long getTeamId();
        Long getTotalAmount();
    }
}
