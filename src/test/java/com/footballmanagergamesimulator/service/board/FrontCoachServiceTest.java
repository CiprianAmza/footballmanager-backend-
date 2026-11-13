package com.footballmanagergamesimulator.service.board;

import com.footballmanagergamesimulator.config.BoardTrustConfig;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.FanSocialFeedService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({FrontCoachService.class, LicenceService.class, BoardTrustService.class, BoardTrustConfig.class,
        RelationService.class, ClubBoardService.class, NationService.class, CompositeNameGenerator.class})
class FrontCoachServiceTest {

    @Autowired private FrontCoachService fronts;
    @Autowired private FrontCoachRepository frontRows;
    @Autowired private StaffLoyaltyRepository loyalties;
    @Autowired private PersonalizedTacticRepository tactics;
    @Autowired private CompetitionRepository competitions;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
    @Autowired private ManagerBackgroundRepository backgrounds;
    @Autowired private ManagerInboxRepository inbox;
    @Autowired private RelationService relations;
    @Autowired private GameCalendarRepository calendars;
    @MockBean private TacticController tacticController;
    @MockBean private ManagerCareerService managerCareerService;
    @MockBean private UserContext userContext;

    private long club;
    private Human manager;
    private Human assistant;

    @BeforeEach
    void world() {
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(4);
        calendar.setCurrentDay(10);
        calendar.setCurrentPhase("MORNING");
        calendars.save(calendar);
        Competition league = new Competition();
        league.setName("First League");
        league.setTypeId(Competition.LEAGUE);
        league.setTier(1);
        long leagueId = competitions.save(league).getId();
        Team t = new Team();
        t.setName("Tik Tok");
        t.setCompetitionId(leagueId);
        club = teams.save(t).getId();
        assistant = humans.save(person("Front Man", TypeNames.ASSISTANT_MANAGER_TYPE));
        manager = humans.save(person("Kvekrpur", TypeNames.MANAGER_TYPE));
        ManagerBackground background = new ManagerBackground();
        background.setManagerId(manager.getId());
        background.setCoachingLicence("B");
        backgrounds.save(background);
        StaffLoyalty loyalty = new StaffLoyalty();
        loyalty.setStaffId(assistant.getId());
        loyalty.setTeamId(club);
        loyalty.setLoyalty(32);
        loyalties.save(loyalty);
        PersonalizedTactic tactic = new PersonalizedTactic();
        tactic.setTeamId(club);
        tactic.setTactic("442");
        tactic.setFirst11("MY SHEET");
        tactics.save(tactic);
        when(tacticController.askAssistant(anyLong(), anyString())).thenReturn(List.of());
        when(userContext.isHumanTeam(club)).thenReturn(true);
        when(userContext.getAllHumanTeamIds()).thenReturn(List.of(club));
    }

    @Test
    @DisplayName("""
            GIVEN a B-licence manager in a Pro league, his proud front coach at loyalty 32 and the manager's own team sheet
            WHEN the team loses twice, then the coach's loyalty is back at 45 and the team wins
            THEN the first loss costs 2 extra (30, calm); the second (28) starts the revolt: he fields his own sheet and
             the manager's is kept; above 40 the revolt ends and the manager's sheet is back
            """)
    void proudCoachRevoltsAndComesBack() {
        FrontCoach row = fronts.current(club).orElseThrow();
        assertEquals(assistant.getId(), row.getCoachId());
        row.setProud(true);
        frontRows.save(row);

        fronts.matchPlayed(club, 0, 1);
        assertEquals(30, loyalties.findByStaffId(assistant.getId()).orElseThrow().getLoyalty());
        assertFalse(fronts.isRevolting(club));

        fronts.matchPlayed(club, 0, 1);
        assertTrue(fronts.isRevolting(club));
        assertEquals("[]", tactics.findPersonalizedTacticByTeamId(club).orElseThrow().getFirst11());
        assertEquals("MY SHEET", frontRows.findByTeamId(club).orElseThrow().getSavedFirst11());
        assertTrue(fronts.refusal(club).startsWith("Front Man ignores"));

        StaffLoyalty loyalty = loyalties.findByStaffId(assistant.getId()).orElseThrow();
        loyalty.setLoyalty(45);
        loyalties.save(loyalty);
        fronts.matchPlayed(club, 1, 0);

        assertFalse(fronts.isRevolting(club));
        assertEquals("MY SHEET", tactics.findPersonalizedTacticByTeamId(club).orElseThrow().getFirst11());
    }

    @Test
    @DisplayName("""
            GIVEN a front coach whose secret has not leaked
            WHEN the press finds out
            THEN he is marked discovered, an article and four supporters' posts appear, and the supporters' opinion
             of the manager drops by 10 (50 -> 40)
            """)
    void theSecretLeaks() {
        FrontCoach row = fronts.current(club).orElseThrow();

        fronts.leak(row);

        assertTrue(frontRows.findByTeamId(club).orElseThrow().isDiscovered());
        List<ManagerInbox> messages = inbox.findAll();
        assertEquals(1, messages.stream().filter(m -> "press".equals(m.getCategory())).count());
        assertEquals(4, messages.stream().filter(m -> FanSocialFeedService.CATEGORY.equals(m.getCategory())).count());
        assertEquals(40, relations.relation(RelationParty.CLUB, club, RelationParty.MANAGER, manager.getId()));
    }

    @Test
    @DisplayName("""
            GIVEN a manager who now holds the Pro licence the league requires
            WHEN the front coach is looked up
            THEN there is none
            """)
    void noFrontCoachWhenQualified() {
        ManagerBackground background = backgrounds.findAll().get(0);
        background.setCoachingLicence("PRO");
        backgrounds.save(background);

        assertTrue(fronts.current(club).isEmpty());
    }

    private Human person(String name, long type) {
        Human h = new Human();
        h.setName(name);
        h.setTypeId(type);
        h.setTeamId(club);
        return h;
    }
}
