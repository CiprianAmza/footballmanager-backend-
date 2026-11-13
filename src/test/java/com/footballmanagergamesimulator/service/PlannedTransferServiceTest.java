package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({PlannedTransferService.class, ContractSignature.class})
class PlannedTransferServiceTest {

    @Autowired private PlannedTransferService planned;
    @Autowired private PlannedTransferRepository plans;
    @Autowired private HumanRepository humans;
    @Autowired private TeamRepository teams;
    @Autowired private TransferRepository transfers;
    @Autowired private GameCalendarRepository calendars;
    @Autowired private ManagerInboxRepository inbox;
    @Autowired private TransferOfferRepository offers;
    @MockBean private FinanceService financeService;
    @MockBean private UserContext userContext;
    @MockBean private MatchSimulationOrchestrator matchSimulationOrchestrator;

    private GameCalendar calendar;
    private long seller;
    private long buyer;
    private long humanClub;

    @BeforeEach
    void world() {
        calendar = new GameCalendar();
        calendar.setSeason(3);
        calendar.setCurrentDay(340); // 6 July, season end
        calendar.setCurrentPhase("MORNING");
        calendar = calendars.save(calendar);
        seller = teams.save(team("Kvar", 1_000_000)).getId();
        Team buyerTeam = team("Tik Tok", 5_000_000);
        buyerTeam.setReputation(5_000);
        buyer = teams.save(buyerTeam).getId();
        Team mine = team("Mine", 0);
        mine.setReputation(1_000);
        humanClub = teams.save(mine).getId();
        when(userContext.getAllHumanTeamIds()).thenReturn(List.of(humanClub));
    }

    @Test
    @DisplayName("""
            GIVEN season-relative days 1–365 and the summer window June–September across the season boundary
            WHEN absolute days are split and the window's last day is asked on 6 July and on 1 August
            THEN 6 July of season 3 closes on 30 September of season 4, and 1 August of season 4 on the same day
            """)
    void windowEndsOnThirtySeptemberOfTheNextSeason() {
        int july6 = 3 * 365 + 340;
        assertEquals(3, PlannedTransferService.seasonOf(3 * 365 + 365));
        assertEquals(365, PlannedTransferService.dayOf(3 * 365 + 365));
        assertEquals(4 * 365 + 61, PlannedTransferService.windowLastDay(july6));
        assertEquals(4 * 365 + 61, PlannedTransferService.windowLastDay(4 * 365 + 1));
    }

    @Test
    @DisplayName("""
            GIVEN 10 transfers and 5 days left in the window
            WHEN they are spread
            THEN two happen each day, from tomorrow to the last day
            """)
    void transfersAreSpreadEvenlyOverTheDaysLeft() {
        int today = 100;
        int[] days = new int[10];
        for (int i = 0; i < 10; i++) days[i] = PlannedTransferService.dayFor(i, 10, today, 105);
        assertArrayEquals(new int[]{101, 101, 102, 102, 103, 103, 104, 104, 105, 105}, days);
    }

    @Test
    @DisplayName("""
            GIVEN the window's market planned a 2,000,000 move of a player from Kvar to Tik Tok
            WHEN it is scheduled on 6 July
            THEN nothing moves yet, the player is "wanted", and the move is due on a later day of the window
            """)
    void schedulingDoesNotMoveAnybody() {
        Human player = humans.save(player(seller));
        planned.schedule(List.of(transfer(player, 2_000_000)), Map.of(player.getId(), plannedTerms(player)), 3);

        assertEquals(seller, humans.findById(player.getId()).orElseThrow().getTeamId());
        assertTrue(planned.isWanted(player.getId()));
        PlannedTransfer row = plans.findAll().get(0);
        assertTrue(row.getExecuteOnDay() > 3 * 365 + 340 && row.getExecuteOnDay() <= 4 * 365 + 61);
        assertTrue(planned.executeDue().isEmpty(), "nothing is due on the day it was planned");
    }

    @Test
    @DisplayName("""
            GIVEN a planned 2,000,000 move from Kvar to Tik Tok, and a second planned move of a player
             who has meanwhile joined my club
            WHEN their day comes
            THEN the first player joins Tik Tok on the planned terms, the fee is booked for both clubs,
             the transfer is recorded and announced to me; the second move is dropped
            """)
    void dueTransfersHappenAndStaleOnesAreDropped() {
        Human player = humans.save(player(seller));
        Human gone = humans.save(player(seller));
        planned.schedule(List.of(transfer(player, 2_000_000), transfer(gone, 500_000)),
                Map.of(player.getId(), plannedTerms(player), gone.getId(), plannedTerms(gone)), 3);
        gone.setTeamId(humanClub);
        humans.save(gone);
        calendar.setSeason(4);
        calendar.setCurrentDay(61);
        calendars.save(calendar);

        List<Transfer> done = planned.executeDue();

        assertEquals(1, done.size());
        Human moved = humans.findById(player.getId()).orElseThrow();
        assertEquals(buyer, moved.getTeamId());
        assertEquals(9_000, moved.getWage());
        assertEquals(6, moved.getContractEndSeason());
        assertEquals(4, moved.getContractSignedSeason());
        verify(financeService).recordTransaction(eq(buyer), eq(4), eq(61), eq("TRANSFER_BUY"), anyString(), eq(-2_000_000L));
        verify(financeService).recordTransaction(eq(seller), eq(4), eq(61), eq("TRANSFER_SALE"), anyString(), eq(2_000_000L));
        assertEquals(1, transfers.findAll().size());
        assertFalse(planned.isWanted(player.getId()));
        assertEquals(humanClub, humans.findById(gone.getId()).orElseThrow().getTeamId());
        assertEquals(PlannedTransfer.CANCELLED, plans.findAll().stream()
                .filter(p -> p.getPlayerId() == gone.getId()).findFirst().orElseThrow().getStatus());
        assertEquals(1, inbox.findAll().stream().filter(m -> m.getTeamId() == humanClub).count());
    }

    @Test
    @DisplayName("""
            GIVEN a planned AI move of a player due tomorrow, on 6 July
            WHEN I talk to his club or his agent three times
            THEN each time it waits 3 more days (9 in all), but never past 30 September
            """)
    void managerTalksPostponeThePlannedMove() {
        Human player = humans.save(player(seller));
        planned.schedule(List.of(transfer(player, 1_000)), Map.of(), 3);
        int first = plans.findAll().get(0).getExecuteOnDay();

        for (int i = 0; i < 3; i++) planned.postpone(player.getId());
        assertEquals(first + 9, plans.findAll().get(0).getExecuteOnDay());

        for (int i = 0; i < 100; i++) planned.postpone(player.getId());
        assertEquals(4 * 365 + 61, plans.findAll().get(0).getExecuteOnDay());
    }

    @Test
    @DisplayName("""
            GIVEN a planned AI move due today, 1 August, while I am still in fee talks for the player
            WHEN the day's moves are carried out
            THEN his move waits 3 more days instead of happening
            """)
    void aLiveBidMakesTheMoveWaitBeforeTheLastDay() {
        Human player = humans.save(player(seller));
        planned.schedule(List.of(transfer(player, 1_000)), Map.of(), 3);
        bid(player, TransferOffer.STATUS_TALKS);
        dueOn(4, 1);

        assertTrue(planned.executeDue().isEmpty());
        PlannedTransfer row = plans.findAll().get(0);
        assertEquals(PlannedTransfer.PLANNED, row.getStatus());
        assertEquals(4 * 365 + 4, row.getExecuteOnDay());
        assertEquals(seller, humans.findById(player.getId()).orElseThrow().getTeamId());
    }

    @Test
    @DisplayName("""
            GIVEN the last day of the window, my fee agreed for a MONEY player whom the AI club would pay 9000,
             and my squad's wage hierarchy that would pay him 20000
            WHEN the day's moves are carried out
            THEN he chooses me: the AI move is dropped, he stays put for personal terms with me, and I am told
            """)
    void onTheLastDayTheMoneyPlayerChoosesTheBetterWage() {
        Human player = player(seller);
        player.setPlayerPersonality(PlayerPersonality.MONEY);
        player = humans.save(player);
        Human star = player(humanClub);
        star.setRating(250);
        star.setWage(20_000);
        humans.save(star);
        planned.schedule(List.of(transfer(player, 1_000)), Map.of(player.getId(), plannedTerms(player)), 3);
        bid(player, TransferOffer.STATUS_FEE_AGREED);
        dueOn(4, 61);

        assertTrue(planned.executeDue().isEmpty());
        assertEquals(PlannedTransfer.CANCELLED, plans.findAll().get(0).getStatus());
        assertEquals(seller, humans.findById(player.getId()).orElseThrow().getTeamId());
        assertTrue(inbox.findAll().stream().anyMatch(m -> m.getTitle().startsWith("Player Choice")));
    }

    @Test
    @DisplayName("""
            GIVEN the last day of the window and my fee agreed for a FAME player, the AI club being more famous
            WHEN the day's moves are carried out
            THEN he joins the AI club, my fee agreement fails and I am told
            """)
    void onTheLastDayTheFamePlayerChoosesTheBiggerClub() {
        Human player = player(seller);
        player.setPlayerPersonality(PlayerPersonality.FAME);
        player = humans.save(player);
        planned.schedule(List.of(transfer(player, 1_000)), Map.of(player.getId(), plannedTerms(player)), 3);
        TransferOffer myBid = bid(player, TransferOffer.STATUS_FEE_AGREED);
        dueOn(4, 61);

        assertEquals(1, planned.executeDue().size());
        assertEquals(buyer, humans.findById(player.getId()).orElseThrow().getTeamId());
        assertEquals(TransferOffer.STATUS_TERMS_FAILED, offers.findById(myBid.getId()).orElseThrow().getStatus());
        assertTrue(inbox.findAll().stream().anyMatch(m -> m.getTitle().startsWith("Transfer Lost")));
    }

    @Test
    @DisplayName("""
            GIVEN a player sold earlier with a 20% sell-on to my club, now planned to move from Kvar to Tik Tok
             for 1,000,000
            WHEN the move happens
            THEN my club gets 200,000, Kvar gets the other 800,000, and the sell-on is used up
            """)
    void aiSalePaysTheSellOn() {
        Human player = player(seller);
        player.setSellOnPercentage(20);
        player.setSellOnClubId(humanClub);
        player = humans.save(player);
        planned.schedule(List.of(transfer(player, 1_000_000)), Map.of(), 3);
        dueOn(4, 61);

        planned.executeDue();

        verify(financeService).recordTransaction(eq(humanClub), eq(4), eq(61), eq("SELL_ON_FEE"), anyString(), eq(200_000L));
        verify(financeService).recordTransaction(eq(seller), eq(4), eq(61), eq("TRANSFER_SALE"), anyString(), eq(800_000L));
        assertEquals(0, humans.findById(player.getId()).orElseThrow().getSellOnPercentage());
    }

    @Test
    @DisplayName("""
            GIVEN an AI club (Tik Tok) holding a 500,000 buy-back clause on a player now at my club, worth much more,
             and a player whose clause has expired
            WHEN the window opens and the next day comes
            THEN Tik Tok takes the first one back from my club for 500,000 and the clause is used up;
             the expired clause is ignored
            """)
    void aiClubUsesItsBuyBackClause() {
        Human player = player(humanClub);
        player.setAge(24);
        player.setBuyBackClubId(buyer);
        player.setBuyBackPrice(500_000);
        player.setBuyBackUntilSeason(4);
        player = humans.save(player);
        Human expired = player(humanClub);
        expired.setAge(24);
        expired.setBuyBackClubId(buyer);
        expired.setBuyBackPrice(500_000);
        expired.setBuyBackUntilSeason(2);
        humans.save(expired);

        assertEquals(1, planned.planBuyBacks(3).size());
        calendar.setCurrentDay(341);
        calendars.save(calendar);
        assertEquals(1, planned.executeDue().size());

        Human back = humans.findById(player.getId()).orElseThrow();
        assertEquals(buyer, back.getTeamId());
        assertEquals(0, back.getBuyBackClubId());
        verify(financeService).recordTransaction(eq(humanClub), anyInt(), anyInt(), eq("TRANSFER_SALE"), anyString(), eq(500_000L));
    }

    @Test
    @DisplayName("""
            GIVEN on 6 July two planned moves, one tomorrow and one in the middle of the window,
             and Mine also able to sign the second player
            WHEN "wanted" is asked today, 5 days before the second move, and after that move is postponed
            THEN only the first player is wanted at first; from 5 days before its day the second one is too,
             listing Tik Tok and Mine, and postponing does not hide him again
            """)
    void wantedShowsFiveDaysBeforeWithEveryClubThatHadAChance() {
        Human soon = humans.save(player(seller));
        Human late = humans.save(player(seller));
        planned.schedule(List.of(transfer(soon, 1_000), transfer(late, 1_000)), Map.of(), 3,
                Map.of(late.getId(), java.util.Set.of(humanClub)));
        PlannedTransfer lateRow = plans.findAll().stream().filter(p -> p.getPlayerId() == late.getId()).findFirst().orElseThrow();
        int lateDay = lateRow.getExecuteOnDay();
        assertTrue(lateDay > 3 * 365 + 340 + 30);

        assertEquals(java.util.Set.of(soon.getId()), planned.wantedPlayerIds());
        assertEquals(List.of(buyer), planned.wantedBy(soon.getId()));
        assertTrue(planned.wantedBy(late.getId()).isEmpty());

        calendar.setSeason(PlannedTransferService.seasonOf(lateDay - 5));
        calendar.setCurrentDay(PlannedTransferService.dayOf(lateDay - 5));
        calendars.save(calendar);
        planned.postpone(late.getId());
        planned.postpone(late.getId());
        assertEquals(lateDay + 6, plans.findById(lateRow.getId()).orElseThrow().getExecuteOnDay());

        assertTrue(planned.isWanted(late.getId()));
        assertEquals(java.util.stream.Stream.of(buyer, humanClub).sorted().toList(), planned.wantedBy(late.getId()));
        assertTrue(planned.hasPlannedMove(late.getId()));
    }

    private TransferOffer bid(Human player, String status) {
        TransferOffer offer = new TransferOffer();
        offer.setPlayerId(player.getId());
        offer.setPlayerName(player.getName());
        offer.setFromTeamId(humanClub);
        offer.setFromTeamName("Mine");
        offer.setToTeamId(seller);
        offer.setDirection("outgoing");
        offer.setStatus(status);
        return offers.save(offer);
    }

    private void dueOn(int season, int day) {
        calendar.setSeason(season);
        calendar.setCurrentDay(day);
        calendars.save(calendar);
        plans.findAll().forEach(p -> {
            p.setExecuteOnDay(Math.min(p.getExecuteOnDay(), season * 365 + day));
            plans.save(p);
        });
    }

    private Transfer transfer(Human player, long fee) {
        Transfer t = new Transfer();
        t.setPlayerId(player.getId());
        t.setPlayerName(player.getName());
        t.setSellTeamId(seller);
        t.setBuyTeamId(buyer);
        t.setPlayerTransferValue(fee);
        t.setSeasonNumber(3);
        return t;
    }

    /** The player as the market left him in memory: new wage and contract. */
    private static Human plannedTerms(Human player) {
        Human terms = new Human();
        terms.setId(player.getId());
        terms.setWage(9_000);
        terms.setContractEndSeason(6);
        return terms;
    }

    private static Human player(long teamId) {
        Human h = new Human();
        h.setName("P");
        h.setTypeId(TypeNames.PLAYER_TYPE);
        h.setTeamId(teamId);
        h.setRating(200);
        h.setWage(4_000);
        h.setContractEndSeason(4);
        h.setPosition("ST");
        return h;
    }

    private static Team team(String name, long budget) {
        Team t = new Team();
        t.setName(name);
        t.setTransferBudget(budget);
        return t;
    }
}
