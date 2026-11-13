package com.footballmanagergamesimulator.service.squad;

import com.footballmanagergamesimulator.config.BoardTrustConfig;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.FinanceService;
import com.footballmanagergamesimulator.service.ManagerCareerService;
import com.footballmanagergamesimulator.service.NationService;
import com.footballmanagergamesimulator.service.RelationService;
import com.footballmanagergamesimulator.service.board.BoardTrustService;
import com.footballmanagergamesimulator.service.board.ClubBoardService;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import({MatchBonusService.class, BoardTrustService.class, BoardTrustConfig.class, RelationService.class,
        ClubBoardService.class, NationService.class, CompositeNameGenerator.class})
class MatchBonusServiceTest {

    @Autowired private MatchBonusService bonuses;
    @Autowired private BoardTrustService trust;
    @Autowired private ClubBoardService boards;
    @Autowired private RelationService relations;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
    @Autowired private GameCalendarRepository calendars;
    @MockBean private FinanceService financeService;
    @MockBean private ManagerCareerService managerCareerService;
    @MockBean private UserContext userContext;

    private long club;
    private Human manager;
    private Human player;

    @BeforeEach
    void world() {
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(4);
        calendar.setCurrentDay(10);
        calendar.setCurrentPhase("MORNING");
        calendars.save(calendar);
        Team t = new Team();
        t.setName("Tik Tok");
        t.setTotalFinances(1_000_000);
        club = teams.save(t).getId();
        boards.ensureBoards();
        manager = person("Kvekrpur", TypeNames.MANAGER_TYPE, 0);
        manager.setWealth(50_000);
        manager = humans.save(manager);
        player = humans.save(person("Striker", TypeNames.PLAYER_TYPE, 40_000));
        humans.save(person("Keeper", TypeNames.PLAYER_TYPE, 40_000));
        trust.managerAppointed(manager.getId(), club);
        when(userContext.isHumanTeam(club)).thenReturn(true);
    }

    @Test
    @DisplayName("""
            GIVEN a chairman trusting the manager 50, a solvent club and two players on 40,000 a week (morale 50)
            WHEN the manager asks for a match bonus, the team wins, and he asks again in the same month
            THEN the bonus is 20,000; morale rises to 55; the win makes the club pay it; the second request
             annoys the chairman (48) and, below 50, he refuses
            """)
    void chairmanGrantsThenTiresOfIt() {
        MatchBonusService.Result first = bonuses.request(club);
        assertEquals(MatchBonusService.Outcome.GRANTED, first.outcome());
        assertEquals(20_000, first.amount());
        assertEquals(55, humans.findById(player.getId()).orElseThrow().getMorale());
        assertEquals("CHAIRMAN", bonuses.status(club).promisedBy());

        bonuses.matchPlayed(club, 2, 1);
        verify(financeService).recordExpense(eq(club), eq(4), anyInt(), eq("SQUAD_BONUS"), anyString(), eq(20_000L));

        MatchBonusService.Result second = bonuses.request(club);
        assertEquals(MatchBonusService.Outcome.REFUSED, second.outcome());
        assertEquals(48, trust.trust(club).chairmanInManager());
        assertTrue(bonuses.status(club).refusedThisMonth());
    }

    @Test
    @DisplayName("""
            GIVEN a chairman who refused (trust 40) and a manager worth 50,000
            WHEN the manager pays the 20,000 bonus himself and the team wins
            THEN each player's relation with him rises by 3, the press writes about it, the chairman's trust is
             unchanged, and 20,000 leaves the manager's own wealth (30,000), not the club's accounts
            """)
    void payingItYourself() {
        trust.chairmanHeard(club, -10);
        assertEquals(MatchBonusService.Outcome.REFUSED, bonuses.request(club).outcome());

        MatchBonusService.Result paid = bonuses.payYourself(club);
        bonuses.matchPlayed(club, 1, 0);

        assertEquals(MatchBonusService.Outcome.PAID_BY_YOU, paid.outcome());
        assertEquals(53, relations.relation(RelationParty.PLAYER, player.getId(), RelationParty.MANAGER, manager.getId()));
        assertEquals(40, trust.trust(club).chairmanInManager());
        assertEquals(30_000, humans.findById(manager.getId()).orElseThrow().getWealth());
        verify(financeService, never()).recordExpense(anyLong(), anyInt(), anyInt(), anyString(), anyString(), anyLong());
    }

    private Human person(String name, long type, long wage) {
        Human h = new Human();
        h.setName(name);
        h.setTypeId(type);
        h.setTeamId(club);
        h.setWage(wage);
        h.setMorale(50);
        return h;
    }
}
