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

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import({StaffMeetingService.class, StaffService.class, BoardTrustService.class, BoardTrustConfig.class,
        RelationService.class, ClubBoardService.class, NationService.class, CompositeNameGenerator.class})
class StaffMeetingServiceTest {

    @Autowired private StaffMeetingService meetings;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
    @Autowired private StaffLoyaltyRepository loyalties;
    @Autowired private GameCalendarRepository calendars;
    @MockBean private ManagerCareerService managerCareerService;
    @MockBean private UserContext userContext;

    private long club;
    private Human tactician;
    private Human youthCoach;

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
        tactician = coach("Tactician", TypeNames.FIRST_TEAM_COACH_TYPE, 18, 5);
        youthCoach = coach("Youth Man", TypeNames.YOUTH_COACH_TYPE, 5, 18);
    }

    @Test
    @DisplayName("""
            GIVEN a first-team coach who works tactically and a youth coach who works on fitness, both at loyalty 50
            WHEN the manager asks for tactical training and results first
            THEN the first-team coach gains 5 + 3 and is delighted; the youth coach loses 3 + 3 and objects;
             a second meeting in the season is refused
            """)
    void expectationsMoveLoyalty() {
        StaffMeetingService.StaffMeeting meeting = meetings.hold(club, StaffMeetingService.Emphasis.TACTICAL,
                StaffMeetingService.Philosophy.RESULTS);

        assertEquals(58, loyalties.findByStaffId(tactician.getId()).orElseThrow().getLoyalty());
        assertEquals(44, loyalties.findByStaffId(youthCoach.getId()).orElseThrow().getLoyalty());
        assertEquals("HELD", meeting.status());
        assertEquals("Exactly how I like to work.", reply(meeting, tactician));
        assertEquals("That's not how we've done things here.", reply(meeting, youthCoach));
        assertThrows(IllegalStateException.class, () -> meetings.refuse(club));
    }

    @Test
    @DisplayName("""
            GIVEN two coaches at loyalty 50
            WHEN the manager refuses the staff meeting
            THEN every coach loses 5 (45)
            """)
    void refusingCostsEveryone() {
        StaffMeetingService.StaffMeeting meeting = meetings.refuse(club);

        assertEquals("REFUSED", meeting.status());
        assertEquals(45, loyalties.findByStaffId(tactician.getId()).orElseThrow().getLoyalty());
        assertEquals(45, loyalties.findByStaffId(youthCoach.getId()).orElseThrow().getLoyalty());
    }

    private static String reply(StaffMeetingService.StaffMeeting meeting, Human coach) {
        return meeting.replies().stream().filter(r -> r.staffId() == coach.getId()).findFirst().orElseThrow().reply();
    }

    private Human coach(String name, long type, int tactical, int fitness) {
        Human h = new Human();
        h.setName(name);
        h.setTypeId(type);
        h.setTeamId(club);
        h.setCoachingTactical(tactical);
        h.setCoachingFitness(fitness);
        h = humans.save(h);
        StaffLoyalty row = new StaffLoyalty();
        row.setStaffId(h.getId());
        row.setTeamId(club);
        row.setLoyalty(50);
        loyalties.save(row);
        return h;
    }
}
