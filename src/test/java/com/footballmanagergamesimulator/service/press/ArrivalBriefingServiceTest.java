package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.TrainingDelegationService;
import com.footballmanagergamesimulator.transfermarket.TransferStrategyUtil;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import(ArrivalBriefingService.class)
class ArrivalBriefingServiceTest {

    @Autowired private ArrivalBriefingService briefings;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
    @Autowired private TransferOfferRepository offers;
    @Autowired private FriendlyEventRepository events;
    @Autowired private GameCalendarRepository calendars;
    @Autowired private ManagerInboxRepository inbox;
    @MockBean private TrainingDelegationService trainingDelegationService;

    @Test
    @DisplayName("""
            GIVEN on day 40 of season 3 an academy club with talks open for a striker, an AI offer for one of its players,
             a contract ending this season, a player who wants to leave, a camp from day 50 and one already over
            WHEN the new manager arrives
            THEN the assistant's briefing reaches the club's inbox listing the talks, the offer, the expiring
             contract, the unhappy player, only the coming camp, and the academy philosophy
            """)
    void briefingTellsWhereThingsStand() {
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(3);
        calendar.setCurrentDay(40);
        calendar.setCurrentPhase("MORNING");
        calendars.save(calendar);
        Team club = new Team();
        club.setName("Tik Tok");
        club.setStrategy(TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY);
        long clubId = teams.save(club).getId();
        humans.save(player(clubId, "Kvekrpur", 3, false, 80));
        humans.save(player(clubId, "Kabutov", 6, true, 40));
        humans.save(player(clubId, "Steady", 6, false, 90));
        offers.save(offer(clubId, 99, "Target", "Kvar", TransferOffer.STATUS_TALKS, "outgoing"));
        offers.save(offer(77, clubId, "Kabutov", "Mine", "pending", "incoming"));
        events.save(camp(clubId, "Mountain camp", 50, 56));
        events.save(camp(clubId, "Old camp", 10, 15));
        when(trainingDelegationService.overview(clubId)).thenReturn(List.of());

        String body = briefings.publish(new Human(), clubId).orElseThrow();

        assertTrue(body.contains("We are after Target (Kvar): talks with the club are open."), body);
        assertTrue(body.contains("Kvar want Kabutov"), body);
        assertTrue(body.contains("Kvekrpur (ST, 30): contract ends this season."), body);
        assertTrue(body.contains("Kabutov (ST): wants to leave"), body);
        assertTrue(body.contains("Mountain camp") && !body.contains("Old camp"), body);
        assertTrue(body.contains("We grow our own."), body);
        assertTrue(!body.contains("Steady"), "a happy player with a long contract is not mentioned");
        assertEquals(1, inbox.findAll().stream().filter(m -> "assistant".equals(m.getCategory())).count());
    }

    private static Human player(long teamId, String name, int contractEnd, boolean wantsTransfer, double morale) {
        Human h = new Human();
        h.setName(name);
        h.setTypeId(TypeNames.PLAYER_TYPE);
        h.setTeamId(teamId);
        h.setPosition("ST");
        h.setAge(30);
        h.setContractEndSeason(contractEnd);
        h.setWantsTransfer(wantsTransfer);
        h.setMorale(morale);
        return h;
    }

    private static TransferOffer offer(long from, long to, String player, String otherClub, String status, String direction) {
        TransferOffer o = new TransferOffer();
        o.setFromTeamId(from);
        o.setToTeamId(to);
        o.setPlayerName(player);
        o.setFromTeamName(direction.equals("incoming") ? "Kvar" : "Mine");
        o.setToTeamName(otherClub);
        o.setStatus(status);
        o.setDirection(direction);
        o.setOfferAmount(1_000_000);
        return o;
    }

    private static FriendlyEvent camp(long clubId, String name, int start, int end) {
        FriendlyEvent e = new FriendlyEvent();
        e.setSeason(3);
        e.setOrganizerTeamId(clubId);
        e.setName(name);
        e.setEventType("TRAINING_CAMP");
        e.setStartDay(start);
        e.setEndDay(end);
        return e;
    }
}
