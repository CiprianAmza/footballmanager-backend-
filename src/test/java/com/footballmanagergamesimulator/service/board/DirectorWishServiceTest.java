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
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({DirectorWishService.class, BoardTrustService.class, BoardTrustConfig.class, RelationService.class,
        ClubBoardService.class, NationService.class, CompositeNameGenerator.class})
class DirectorWishServiceTest {

    @Autowired private DirectorWishService wishes;
    @Autowired private BoardTrustService trust;
    @Autowired private ClubBoardService boards;
    @Autowired private ClubBoardRepository boardRows;
    @Autowired private DirectorWishRepository wishRows;
    @Autowired private RelationService relations;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
    @Autowired private PersonalizedTacticRepository tactics;
    @Autowired private StaffLoyaltyRepository loyalties;
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
        boards.edit(club, DirectorPersonality.NEUTRAL);
        Human h = new Human();
        h.setName("Kvekrpur");
        h.setTypeId(TypeNames.MANAGER_TYPE);
        h.setTeamId(club);
        manager = humans.save(h);
        trust.managerAppointed(manager.getId(), club);
        PersonalizedTactic tactic = new PersonalizedTactic();
        tactic.setTeamId(club);
        tactic.setTactic("442");
        tactics.save(tactic);
        when(userContext.isHumanTeam(club)).thenReturn(true);
        when(userContext.getAllHumanTeamIds()).thenReturn(List.of(club));
    }

    @Test
    @DisplayName("""
            GIVEN a neutral director's formation wish (the chairman's) and a director's own sale wish
            WHEN the manager follows the formation, ignores the sale and the month is settled
            THEN the chairman's trust rises by 3 (50 -> 53) and the director's falls by 5 (50 -> 45)
            """)
    void obeyingMovesTheRightTrust() {
        wishRows.save(wish(DirectorWish.FORMATION, "4231", 0, true));
        Human player = player("Old Boy", 30);
        wishRows.save(wish(DirectorWish.SELL, null, player.getId(), false));
        PersonalizedTactic tactic = tactics.findPersonalizedTacticByTeamId(club).orElseThrow();
        tactic.setTactic("4231");
        tactics.save(tactic);

        wishes.settle(club, 4);

        BoardTrustService.Trust t = trust.trust(club);
        assertEquals(53, t.chairmanInManager());
        assertEquals(45, t.directorInManager());
        assertTrue(wishRows.findAllByTeamIdAndSettledFalse(club).isEmpty());
    }

    @Test
    @DisplayName("""
            GIVEN a director's own wish
            WHEN the manager asks the chairman twice in the same season
            THEN the first answer reveals it was not the chairman's and costs 5 of the director's trust; the second is refused
            """)
    void askingTheChairman() {
        DirectorWish w = wishRows.save(wish(DirectorWish.FORMATION, "4231", 0, false));
        DirectorWish other = wishRows.save(wish(DirectorWish.FORMATION, "433", 0, true));

        DirectorWishService.Answer first = wishes.askChairman(club, w.getId());
        DirectorWishService.Answer second = wishes.askChairman(club, other.getId());

        assertTrue(first.ok());
        assertTrue(first.message().contains("never asked"));
        assertEquals(45, trust.trust(club).directorInManager());
        assertFalse(second.ok());
    }

    @Test
    @DisplayName("""
            GIVEN a chairman trusting the manager 50 and the director 50
            WHEN the manager asks for the director's dismissal, then again with trust 65 and 2 of 3 staff loyal
            THEN the first request fails and costs 10 (50 -> 40); after the chairman's trust climbs 25 to 65 and with
             most of the staff behind him, a new director takes office
            """)
    void dismissingTheDirector() {
        ClubBoard board = boardRows.findByTeamId(club).orElseThrow();
        long oldDirector = board.getDirectorId();
        relations.change(RelationParty.CHAIRMAN, board.getChairmanId(), RelationParty.DIRECTOR, oldDirector,
                50 - relations.relation(RelationParty.CHAIRMAN, board.getChairmanId(), RelationParty.DIRECTOR, oldDirector));

        assertFalse(wishes.requestDirectorDismissal(club).ok());
        assertEquals(40, trust.trust(club).chairmanInManager());

        relations.change(RelationParty.CHAIRMAN, board.getChairmanId(), RelationParty.MANAGER, manager.getId(), 25);
        for (int loyalty : new int[]{90, 80, 20}) {
            StaffLoyalty row = new StaffLoyalty();
            row.setStaffId(1000 + loyalty);
            row.setTeamId(club);
            row.setLoyalty(loyalty);
            loyalties.save(row);
        }
        assertTrue(wishes.requestDirectorDismissal(club).ok());
        assertNotEquals(oldDirector, boardRows.findByTeamId(club).orElseThrow().getDirectorId());
    }

    @Test
    @DisplayName("""
            GIVEN a squad with a 19-year-old who has not played
            WHEN the month passes for a club with a neutral director
            THEN a wish is issued, always presented as the chairman's and actually his
            """)
    void monthIssuesAWish() {
        player("Kid", 19);
        wishes.monthPassed();

        List<DirectorWish> issued = wishRows.findAllByTeamIdAndSettledFalse(club);
        assertEquals(1, issued.size());
        assertTrue(issued.get(0).getText().startsWith("The chairman would like"));
        assertTrue(issued.get(0).isFromChairman());
    }

    private DirectorWish wish(String kind, String formation, long playerId, boolean fromChairman) {
        DirectorWish w = new DirectorWish();
        w.setTeamId(club);
        w.setSeason(4);
        w.setKind(kind);
        w.setFormation(formation);
        w.setPlayerId(playerId);
        w.setText("The chairman would like ...");
        w.setFromChairman(fromChairman);
        return w;
    }

    private Human player(String name, int age) {
        Human p = new Human();
        p.setName(name);
        p.setTypeId(TypeNames.PLAYER_TYPE);
        p.setTeamId(club);
        p.setAge(age);
        return humans.save(p);
    }
}
