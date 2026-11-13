package com.footballmanagergamesimulator.service.squad;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.FinanceService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DataJpaTest
@Import(SquadBonusService.class)
class SquadBonusServiceTest {

    private static final long CLUB = 10;

    @Autowired private SquadBonusService bonuses;
    @Autowired private SquadObjectiveRepository objectives;
    @Autowired private CompetitionHistoryRepository history;
    @Autowired private CompetitionRepository competitions;
    @Autowired private HumanRepository humans;
    @MockBean private FinanceService financeService;

    @Test
    @DisplayName("""
            GIVEN wage targets: the league "top 3" with the maximum bonus (finished 2nd of 12) and a cup "final" with
             the minimum (out in the semi-finals, place 3); the squad earns 40,000 a month
            WHEN the season is settled, twice
            THEN the league bonus pays a full month (40,000) once; the cup target was missed and pays nothing;
             places and targets follow the ladders (avoid relegation = not in the bottom three)
            """)
    void reachedTargetsPayTheirBonusOnce() {
        humans.save(player(25_000));
        humans.save(player(15_000));
        long league = competition("League", Competition.LEAGUE);
        long cup = competition("Cup", 2);
        for (int place = 1; place <= 12; place++) table(league, place == 2 ? CLUB : 100 + place, place);
        table(cup, CLUB, 3);
        table(cup, 200, 1);
        objective(league, "TOP_3", "MAX");
        objective(cup, "FINAL", "MIN");

        assertEquals(40_000, bonuses.settle(4));
        assertEquals(0, bonuses.settle(4));

        verify(financeService).recordExpense(eq(CLUB), eq(4), anyInt(), eq("SQUAD_BONUS"), anyString(), eq(40_000L));
        verify(financeService, never()).recordExpense(eq(CLUB), eq(4), anyInt(), eq("SQUAD_BONUS"), anyString(), eq(10_000L));
        assertTrue(SquadObjectives.reached(true, "AVOID_RELEGATION", 9, 12));
        assertFalse(SquadObjectives.reached(true, "AVOID_RELEGATION", 10, 12));
        assertTrue(SquadObjectives.reached(false, "QUARTER_FINAL", 5, 16));
    }

    private long competition(String name, long type) {
        Competition c = new Competition();
        c.setName(name);
        c.setTypeId(type);
        return competitions.save(c).getId();
    }

    private void table(long competitionId, long teamId, long place) {
        CompetitionHistory row = new CompetitionHistory();
        row.setCompetitionId(competitionId);
        row.setTeamId(teamId);
        row.setSeasonNumber(4);
        row.setLastPosition(place);
        history.save(row);
    }

    private void objective(long competitionId, String target, String bonus) {
        SquadObjective o = new SquadObjective();
        o.setTeamId(CLUB);
        o.setSeason(4);
        o.setCompetitionId(competitionId);
        o.setCompetitionName("C" + competitionId);
        o.setManagerTarget(target);
        o.setPlayersWish(target);
        o.setBonusLevel(bonus);
        objectives.save(o);
    }

    private static Human player(long wage) {
        Human h = new Human();
        h.setName("P");
        h.setTypeId(TypeNames.PLAYER_TYPE);
        h.setTeamId(CLUB);
        h.setWage(wage);
        return h;
    }
}
