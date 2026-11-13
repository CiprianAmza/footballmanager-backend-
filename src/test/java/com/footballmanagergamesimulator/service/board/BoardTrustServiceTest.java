package com.footballmanagergamesimulator.service.board;

import com.footballmanagergamesimulator.config.BoardTrustConfig;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.ManagerCareerService;
import com.footballmanagergamesimulator.service.NationService;
import com.footballmanagergamesimulator.service.RelationService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import({BoardTrustService.class, BoardTrustConfig.class, RelationService.class, ClubBoardService.class,
        NationService.class, CompositeNameGenerator.class})
class BoardTrustServiceTest {

    @Autowired private BoardTrustService trust;
    @Autowired private ClubBoardService boards;
    @Autowired private ClubBoardRepository boardRows;
    @Autowired private RelationService relations;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
    @Autowired private CompetitionHistoryRepository history;
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
        boards.edit(club, null);
        Human h = new Human();
        h.setName("Kvekrpur");
        h.setTypeId(TypeNames.MANAGER_TYPE);
        h.setTeamId(club);
        manager = humans.save(h);
        when(userContext.isHumanTeam(club)).thenReturn(true);
        when(userContext.getAllHumanTeamIds()).thenReturn(List.of(club));
    }

    @Test
    @DisplayName("""
            GIVEN a club whose ambitious director took office 3 seasons ago (now season 4)
            WHEN the director is appointed and the manager arrives
            THEN the chairman's trust in the director is 50 + 3 x 6 = 68 and the director's in the manager 40
            """)
    void startingTrust() {
        ClubBoard board = boardRows.findByTeamId(club).orElseThrow();
        boards.edit(club, DirectorPersonality.AMBITIOUS);
        board = boardRows.findByTeamId(club).orElseThrow();
        board.setDirectorSinceSeason(1);
        boardRows.save(board);
        trust.directorAppointed(board, 4);
        trust.managerAppointed(manager.getId(), club);

        BoardTrustService.Trust t = trust.trust(club);
        assertEquals(50, t.chairmanInManager());
        assertEquals(68, t.chairmanInDirector());
        assertEquals(40, t.directorInManager());
    }

    @Test
    @DisplayName("""
            GIVEN a club without a director and the chairman's trust at 50
            WHEN the manager wins once, then loses 17 times
            THEN trust goes 52, then down by 2 a loss; when it falls below 20 the chairman sacks the manager, once
            """)
    void lossesEndInTheSack() {
        trust.matchPlayed(club, 2, 0, 4);
        assertEquals(52, trust.trust(club).chairmanInManager());
        for (int i = 0; i < 17; i++) trust.matchPlayed(club, 0, 1, 4);

        assertTrue(trust.trust(club).chairmanInManager() < 20);
        verify(managerCareerService, atLeastOnce()).sackHumanManager(club, 4);
    }

    @Test
    @DisplayName("""
            GIVEN a club whose director the chairman trusts even less (10) than the manager
            WHEN the manager's trust falls below 20
            THEN the chairman dismisses the director instead (a new one takes office) and gives the manager 30
            """)
    void directorPaysFirstWhenTrustedLess() {
        boards.edit(club, DirectorPersonality.NEUTRAL);
        ClubBoard board = boardRows.findByTeamId(club).orElseThrow();
        long oldDirector = board.getDirectorId();
        relations.change(RelationParty.CHAIRMAN, board.getChairmanId(), RelationParty.DIRECTOR, oldDirector,
                10 - relations.relation(RelationParty.CHAIRMAN, board.getChairmanId(), RelationParty.DIRECTOR, oldDirector));
        relations.change(RelationParty.CHAIRMAN, board.getChairmanId(), RelationParty.MANAGER, manager.getId(), -30);

        trust.matchPlayed(club, 0, 3, 4);

        verify(managerCareerService, never()).sackHumanManager(anyLong(), anyInt());
        ClubBoard after = boardRows.findByTeamId(club).orElseThrow();
        assertTrue(after.hasDirector());
        assertNotEquals(oldDirector, after.getDirectorId());
        assertEquals(30, trust.trust(club).chairmanInManager());
    }

    @Test
    @DisplayName("""
            GIVEN a club that won two trophies in season 4 and a kept public promise
            WHEN the season ends
            THEN the chairman's trust in the manager rises by 2 x 15 + 10 = 40, to 90
            """)
    void trophiesAndPromisesLiftTrust() {
        for (int i = 0; i < 2; i++) {
            CompetitionHistory row = new CompetitionHistory();
            row.setTeamId(club);
            row.setSeasonNumber(4);
            row.setCompetitionId(100 + i);
            row.setLastPosition(1);
            history.save(row);
        }
        trust.seasonEnded(4);
        trust.promiseSettled(club, true, 4);

        assertEquals(90, trust.trust(club).chairmanInManager());
    }
}
