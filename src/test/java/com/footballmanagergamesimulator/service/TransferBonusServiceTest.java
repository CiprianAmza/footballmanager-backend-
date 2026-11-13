package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TransferBonus;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DataJpaTest
@Import(TransferBonusService.class)
class TransferBonusServiceTest {

    private static final long PLAYER = 7;

    @Autowired private TransferBonusService bonuses;
    @Autowired private TeamRepository teams;
    @Autowired private ScorerRepository scorers;
    @Autowired private CompetitionHistoryRepository history;
    @MockBean private FinanceService financeService;

    @Test
    @DisplayName("""
            GIVEN a move on 6 July of season 3 with a 500,000 bonus at 20 goals in a season and 300,000 for a league title
            WHEN seasons 4 and 5 end: in season 4 he scores 12 for me and 10 for another club and I win the league,
             in season 5 he scores 21 for me; season 5 is settled twice
            THEN the bonuses count for seasons 4–5; the title bonus is paid after season 4, the goals bonus after
             season 5 (only goals for me count), each once, with the seller's budget up by the same sums
            """)
    void bonusesArePaidOnceInTheSeasonTheyAreEarned() {
        long buyer = teams.save(team(2_000_000)).getId();
        long seller = teams.save(team(0)).getId();
        List<TransferBonus> booked = bonuses.schedule(PLAYER, "Kvekrpur", buyer, seller, 20, 500_000, 300_000, 3 * 365 + 340);
        assertEquals(2, booked.size());
        assertTrue(booked.stream().allMatch(b -> b.getFromSeason() == 4 && b.getUntilSeason() == 5));

        goals(buyer, 4, 12);
        goals(seller, 4, 10);
        leagueFinish(buyer, 4, 1);
        List<TransferBonus> season4 = bonuses.settle(4);
        assertEquals(List.of(TransferBonus.TITLE), season4.stream().map(TransferBonus::getType).toList());

        goals(buyer, 5, 21);
        assertEquals(List.of(TransferBonus.GOALS), bonuses.settle(5).stream().map(TransferBonus::getType).toList());
        assertTrue(bonuses.settle(5).isEmpty());

        verify(financeService).recordExpense(eq(buyer), eq(5), anyInt(), eq("TRANSFER_BONUS"), anyString(), eq(500_000L));
        verify(financeService, times(2)).recordTransaction(eq(seller), anyInt(), anyInt(), eq("TRANSFER_BONUS"), anyString(), anyLong());
        assertEquals(800_000, teams.findById(seller).orElseThrow().getTransferBudget());
        assertEquals(1_200_000, teams.findById(buyer).orElseThrow().getTransferBudget());
    }

    @Test
    @DisplayName("""
            GIVEN a player who scored 10 goals last season and a buyer that finished 2nd in its league
            WHEN the selling club estimates a bonus at 20 goals and a title bonus
            THEN the goals bonus has 75% (50% a season, over 2 seasons) and the title bonus 43.75% (25% a season)
            """)
    void sellerEstimatesTheChances() {
        long buyer = teams.save(team(0)).getId();
        goals(buyer, 2, 10);
        leagueFinish(buyer, 2, 2);

        TransferBonusService.Chances chances = bonuses.chances(PLAYER, 20, buyer, 3);

        assertEquals(0.75, chances.goals(), 1e-9);
        assertEquals(0.4375, chances.title(), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN a stable selling club, a real price of 1,000,000 and an offer of 800,000 now with a 200,000 bonus
             at 20 goals (75% likely) and 100,000 for a title (50% likely)
            WHEN the club weighs it
            THEN it is worth 800,000 + 90% × (150,000 + 50,000) = 980,000 points
            """)
    void bonusesCountLikeInstalmentsTimesTheirChance() {
        TransferPoints.Offer offer = new TransferPoints.Offer(800_000, 0, 0, 0, 0, 20, 200_000, 100_000);
        assertTrue(offer.valid());
        assertEquals(980_000, TransferPoints.points(offer, 1_000_000, TransferPoints.Situation.STABLE, 0.75, 0.5));
        assertFalse(new TransferPoints.Offer(800_000, 0, 0, 0, 0, 0, 200_000, 0).valid(), "a goals bonus needs a target");
    }

    private void goals(long teamId, int season, int goals) {
        Scorer row = new Scorer();
        row.setPlayerId(PLAYER);
        row.setTeamId(teamId);
        row.setSeasonNumber(season);
        row.setGoals(goals);
        scorers.save(row);
    }

    private void leagueFinish(long teamId, int season, long position) {
        CompetitionHistory row = new CompetitionHistory();
        row.setTeamId(teamId);
        row.setSeasonNumber(season);
        row.setCompetitionTypeId(1);
        row.setLastPosition(position);
        history.save(row);
    }

    private static Team team(long budget) {
        Team t = new Team();
        t.setName("T");
        t.setTransferBudget(budget);
        return t;
    }
}
