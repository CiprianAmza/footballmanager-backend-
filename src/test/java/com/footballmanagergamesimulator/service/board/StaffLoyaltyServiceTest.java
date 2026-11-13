package com.footballmanagergamesimulator.service.board;

import com.footballmanagergamesimulator.config.BoardTrustConfig;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.ManagerCareerService;
import com.footballmanagergamesimulator.service.NationService;
import com.footballmanagergamesimulator.service.RelationService;
import com.footballmanagergamesimulator.service.StaffService;
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
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({StaffLoyaltyService.class, StaffService.class, BoardTrustService.class, BoardTrustConfig.class,
        RelationService.class, ClubBoardService.class, NationService.class, CompositeNameGenerator.class})
class StaffLoyaltyServiceTest {

    @Autowired private StaffLoyaltyService loyalty;
    @Autowired private ClubBoardService boards;
    @Autowired private ClubBoardRepository boardRows;
    @Autowired private RelationService relations;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
    @Autowired private StaffLoyaltyRepository rows;
    @Autowired private GameCalendarRepository calendars;
    @MockBean private ManagerCareerService managerCareerService;
    @MockBean private UserContext userContext;

    private long club;
    private Human manager;

    @BeforeEach
    void world() {
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(4);
        calendar.setCurrentDay(10);
        calendar.setCurrentPhase("MORNING");
        calendars.save(calendar);
        Team t = new Team();
        t.setName("Tik Tok");
        club = teams.save(t).getId();
        boards.ensureBoards();
        manager = humans.save(person("Kvekrpur", TypeNames.MANAGER_TYPE, club));
        when(userContext.isHumanTeam(club)).thenReturn(true);
        when(userContext.getAllHumanTeamIds()).thenReturn(List.of(club));
    }

    @Test
    @DisplayName("""
            GIVEN the starting rule
            WHEN a man is the manager's own hire, found at a club without a director, or found after 0, 3 or 8 seasons
            THEN he starts at 100, 100, then 50, 35 and 15 (never below 15); the assistant's read follows the value
            """)
    void startingValues() {
        assertEquals(100, StaffLoyaltyService.starting(true, true, 8));
        assertEquals(100, StaffLoyaltyService.starting(false, false, 8));
        assertEquals(50, StaffLoyaltyService.starting(false, true, 0));
        assertEquals(35, StaffLoyaltyService.starting(false, true, 3));
        assertEquals(15, StaffLoyaltyService.starting(false, true, 8));
        assertEquals("Firmly in your corner.", StaffLoyaltyService.read(70));
        assertEquals("Spends a lot of time in the director's office.", StaffLoyaltyService.read(30));
    }

    @Test
    @DisplayName("""
            GIVEN a club with an ambitious director and two coaches found there, plus one the manager hires
            WHEN the manager arrives, wins, praises a coach twice in one season and a month passes
            THEN the found coaches start at 50 - 5 x tenure, the hire at 100; a win gives +1 each, only the first
             praise +5, and the month takes 2 from everyone
            """)
    void eventsMoveLoyalty() {
        boards.edit(club, DirectorPersonality.AMBITIOUS);
        Human a = humans.save(person("Coach A", TypeNames.FIRST_TEAM_COACH_TYPE, club));
        Human b = humans.save(person("Coach B", TypeNames.FITNESS_COACH_TYPE, club));
        loyalty.managerAppointed(club);
        Human mine = humans.save(person("My Man", TypeNames.GK_COACH_TYPE, club));
        loyalty.hired(mine.getId(), club);

        StaffLoyalty rowA = rows.findByStaffId(a.getId()).orElseThrow();
        int startA = Math.max(15, 50 - 5 * rowA.getTenureSeasons());
        assertEquals(startA, rowA.getLoyalty());
        assertEquals(100, rows.findByStaffId(mine.getId()).orElseThrow().getLoyalty());

        loyalty.matchPlayed(club, 2, 1);
        assertTrue(loyalty.praise(club, a.getId()).counted());
        assertFalse(loyalty.praise(club, a.getId()).counted());
        loyalty.monthPassed();

        assertEquals(startA + 1 + 5 - 2, loyalty.loyalty(a.getId()).getAsInt());
        assertEquals(100 + 0 - 2, loyalty.loyalty(mine.getId()).getAsInt(), "capped at 100 after the win");
        assertTrue(loyalty.loyalty(b.getId()).isPresent());
    }

    @Test
    @DisplayName("""
            GIVEN an ambitious director whose man (loyalty 20) the manager wants to fire, and an assistant on the manager's side
            WHEN the manager reads his assistant, fires the man, then takes it to the chairman who trusts him more
            THEN the assistant warns about him; the director first insists he stays; the chairman backs the manager,
             the man leaves and the director's trust in the manager drops by 10
            """)
    void directorFightsForHisMan() {
        boards.edit(club, DirectorPersonality.AMBITIOUS);
        Human assistant = humans.save(person("Assistant", TypeNames.ASSISTANT_MANAGER_TYPE, club));
        Human spy = humans.save(person("Spy", TypeNames.FIRST_TEAM_COACH_TYPE, club));
        loyalty.managerAppointed(club);
        loyalty.hired(assistant.getId(), club);
        StaffLoyalty row = rows.findByStaffId(spy.getId()).orElseThrow();
        row.setLoyalty(20);
        rows.save(row);
        ClubBoard board = boardRows.findByTeamId(club).orElseThrow();
        relations.change(RelationParty.CHAIRMAN, board.getChairmanId(), RelationParty.MANAGER, manager.getId(), 20);
        int before = relations.relation(RelationParty.DIRECTOR, board.getDirectorId(), RelationParty.MANAGER, manager.getId());

        Map<Long, String> reads = loyalty.assistantReads(club);
        StaffLoyaltyService.Dismissal first = loyalty.fire(club, spy.getId());
        StaffLoyaltyService.Dismissal appeal = loyalty.appeal(club, spy.getId());

        assertEquals("I would not tell him anything you don't want the director to hear.", reads.get(spy.getId()));
        assertTrue(first.directorInsists());
        assertFalse(first.done());
        assertTrue(appeal.done());
        assertEquals(0L, humans.findById(spy.getId()).orElseThrow().getTeamId());
        assertEquals(before - 10,
                relations.relation(RelationParty.DIRECTOR, board.getDirectorId(), RelationParty.MANAGER, manager.getId()));
    }

    private static Human person(String name, long type, long club) {
        Human h = new Human();
        h.setName(name);
        h.setTypeId(type);
        h.setTeamId(club);
        return h;
    }
}
