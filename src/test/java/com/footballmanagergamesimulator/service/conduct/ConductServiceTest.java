package com.footballmanagergamesimulator.service.conduct;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.service.FinanceService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DataJpaTest
@Import(ConductService.class)
class ConductServiceTest {

    private static final long CLUB = 10;

    @Autowired private ConductService conduct;
    @Autowired private HumanRepository humans;
    @Autowired private MatchEventRepository events;
    @MockBean private FinanceService financeService;

    @Test
    @DisplayName("""
            GIVEN a new club code (standard: yellow nothing, red 1 week, missed training 1 week) and a squad at morale 70
            WHEN the red-card fine is raised to 2 weeks, then lowered to half a week, then raised to 1 week again,
             and a 3-week fine is attempted
            THEN the raise above the standard costs every player 4 morale (2 half-weeks); lowering and going back
             up to the standard cost nothing; 3 weeks is not a choice
            """)
    void raisingFinesAboveTheStandardUpsetsTheSquadOnce() {
        Human player = humans.save(player(5_200));
        assertEquals(1.0, conduct.fineWeeks(CLUB, ConductService.Offence.RED_CARD));

        assertEquals(4.0, conduct.update(CLUB, Map.of("RED_CARD", 2.0)).moraleDrop());
        assertEquals(66.0, humans.findById(player.getId()).orElseThrow().getMorale());
        assertEquals(0.0, conduct.update(CLUB, Map.of("RED_CARD", 0.5)).moraleDrop());
        assertEquals(0.0, conduct.update(CLUB, Map.of("RED_CARD", 1.0)).moraleDrop());
        assertThrows(IllegalArgumentException.class, () -> conduct.update(CLUB, Map.of("YELLOW_CARD", 2.5)));
    }

    @Test
    @DisplayName("""
            GIVEN a player on 5,200 a month and a red-card fine of 2 weeks
            WHEN he is sent off, and the same card is processed again, and a yellow card (no fine by standard) follows
            THEN 2 weeks of wages (5,200 x 12 / 52 x 2 = 2,400) go to the club once; the yellow costs nothing
            """)
    void aBookedPlayerPaysTheFineOnce() {
        Human player = humans.save(player(5_200));
        conduct.update(CLUB, Map.of("RED_CARD", 2.0));
        MatchEvent red = events.save(card(player.getId(), "red_card"));
        MatchEvent yellow = events.save(card(player.getId(), "yellow_card"));

        assertEquals(2_400, conduct.fine(red, CLUB, 3, 7));
        assertEquals(0, conduct.fine(red, CLUB, 3, 7));
        assertEquals(0, conduct.fine(yellow, CLUB, 3, 7));
        verify(financeService, times(1)).recordTransaction(eq(CLUB), eq(3), eq(7), eq("FINE"), anyString(), eq(2_400L));
    }

    private static Human player(long wage) {
        Human h = new Human();
        h.setName("Kvekrpur");
        h.setTypeId(TypeNames.PLAYER_TYPE);
        h.setTeamId(CLUB);
        h.setWage(wage);
        h.setMorale(70);
        return h;
    }

    private static MatchEvent card(long playerId, String type) {
        MatchEvent e = new MatchEvent();
        e.setPlayerId(playerId);
        e.setTeamId(CLUB);
        e.setEventType(type);
        return e;
    }
}
