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
@Import({BoardMeetingService.class, BoardTrustService.class, BoardTrustConfig.class, RelationService.class,
        ClubBoardService.class, NationService.class, CompositeNameGenerator.class})
class BoardMeetingServiceTest {

    @Autowired private BoardMeetingService meetings;
    @Autowired private BoardTrustService trust;
    @Autowired private ClubBoardService boards;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
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
        Human h = new Human();
        h.setName("Kvekrpur");
        h.setTypeId(TypeNames.MANAGER_TYPE);
        h.setTeamId(club);
        manager = humans.save(h);
        when(userContext.isHumanTeam(club)).thenReturn(true);
        when(userContext.getAllHumanTeamIds()).thenReturn(List.of(club));
    }

    private void arrive(DirectorPersonality personality) {
        boards.edit(club, personality);
        trust.managerAppointed(manager.getId(), club);
        meetings.managerAppointed(manager.getId(), club);
    }

    @Test
    @DisplayName("""
            GIVEN a manager arriving at a club with an ambitious director (his trust starts at 40)
            WHEN the manager opens the board meeting and answers "Yes, I'm with you", then tries to answer again
            THEN the chairman expects the top half and backs him, the director asks; the trust jumps to 60 at once;
             the second answer is refused
            """)
    void answeringTheAmbitiousDirector() {
        arrive(DirectorPersonality.AMBITIOUS);

        BoardMeetingService.Meeting meeting = meetings.meeting(club);
        assertEquals("PENDING", meeting.status());
        assertTrue(meeting.directorAsks());
        assertEquals("I expect us in the top half of the table. You have my backing.", meeting.chairmanWords());

        BoardMeetingService.Meeting after = meetings.answer(club, BoardMeetingService.Answer.WITH_YOU);

        assertEquals("HELD", after.status());
        assertEquals(60, trust.trust(club).directorInManager());
        assertThrows(IllegalStateException.class, () -> meetings.answer(club, BoardMeetingService.Answer.DEPENDS));
    }

    @Test
    @DisplayName("""
            GIVEN an ambitious director's question left unanswered
            WHEN the month passes
            THEN it counts as "we'll see": his trust drops by 5 (40 -> 35) and the meeting is marked evaded
            """)
    void silenceCountsAsWaitAndSee() {
        arrive(DirectorPersonality.AMBITIOUS);

        meetings.monthPassed();

        assertEquals(35, trust.trust(club).directorInManager());
        assertEquals("EVADED", meetings.meeting(club).status());
    }

    @Test
    @DisplayName("""
            GIVEN a club with a neutral director
            WHEN the manager opens the meeting and the month passes
            THEN the director only says a few words and asks nothing; an answer is refused and the meeting ends held
            """)
    void neutralDirectorAsksNothing() {
        arrive(DirectorPersonality.NEUTRAL);

        BoardMeetingService.Meeting meeting = meetings.meeting(club);
        assertFalse(meeting.directorAsks());
        assertTrue(meeting.directorWords().startsWith("I handle the administration"));
        assertThrows(IllegalStateException.class, () -> meetings.answer(club, BoardMeetingService.Answer.WITH_YOU));

        meetings.monthPassed();
        assertEquals("HELD", meetings.meeting(club).status());
        assertEquals(50, trust.trust(club).directorInManager());
    }
}
