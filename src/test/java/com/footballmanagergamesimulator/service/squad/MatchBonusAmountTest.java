package com.footballmanagergamesimulator.service.squad;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.FinanceService;
import com.footballmanagergamesimulator.service.RelationService;
import com.footballmanagergamesimulator.service.board.BoardTrustService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchBonusAmountTest {

    @Test
    @DisplayName("""
            GIVEN two players on 260,000 a month each (wages are monthly: 120,000 a week for both) and a retired one
            WHEN the extra match bonus is priced
            THEN it is 25% of the weekly wages, 30,000, not 25% of the monthly ones (130,000)
            """)
    void theBonusIsAQuarterOfTheWeeklyWages() {
        HumanRepository humans = mock(HumanRepository.class);
        MatchBonusService bonuses = new MatchBonusService(mock(MatchBonusRepository.class), humans,
                mock(TeamRepository.class), mock(GameCalendarRepository.class), mock(ManagerInboxRepository.class),
                mock(RelationService.class), mock(FinanceService.class), mock(BoardTrustService.class));
        Human retired = player(500_000);
        retired.setRetired(true);
        when(humans.findAllByTeamIdAndTypeId(7L, TypeNames.PLAYER_TYPE))
                .thenReturn(List.of(player(260_000), player(260_000), retired));

        assertEquals(30_000, bonuses.amount(7));
    }

    private static Human player(long monthlyWage) {
        Human h = new Human();
        h.setTypeId(TypeNames.PLAYER_TYPE);
        h.setWage(monthlyWage);
        return h;
    }
}
