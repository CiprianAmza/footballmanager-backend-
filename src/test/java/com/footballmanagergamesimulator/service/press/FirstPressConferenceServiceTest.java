package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.RelationService;
import com.footballmanagergamesimulator.service.squad.LeaguePredictionService;
import com.footballmanagergamesimulator.service.squad.SquadMeetingService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({FirstPressConferenceService.class, PublicPromiseService.class, AnalystService.class,
        AppointmentArticleService.class, PressFragmentService.class, CompositeNameGenerator.class,
        SquadMeetingService.class, RelationService.class,
        com.footballmanagergamesimulator.service.board.BoardTrustService.class,
        com.footballmanagergamesimulator.config.BoardTrustConfig.class,
        com.footballmanagergamesimulator.service.board.ClubBoardService.class,
        com.footballmanagergamesimulator.service.NationService.class})
class FirstPressConferenceServiceTest {

    @Autowired private FirstPressConferenceService conferences;
    @Autowired private PublicPromiseService promises;
    @Autowired private AppointmentArticleService articles;
    @Autowired private RelationService relations;
    @Autowired private AnalystRepository analysts;
    @Autowired private CompetitionRepository competitions;
    @Autowired private CompetitionTeamInfoRepository memberships;
    @Autowired private CompetitionHistoryRepository history;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
    @Autowired private GameCalendarRepository calendars;
    @MockBean private LeaguePredictionService predictions;
    @MockBean private com.footballmanagergamesimulator.service.ManagerCareerService managerCareerService;
    @MockBean private com.footballmanagergamesimulator.user.UserContext userContext;
    @Autowired private ClubBoardRepository boards;
    @Autowired private MeetingRecordRepository meetingRecords;

    private long league;
    private long club;
    private Human manager;

    @BeforeEach
    void world() {
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(1);
        calendar.setCurrentDay(1);
        calendar.setCurrentPhase("MORNING");
        calendars.save(calendar);
        Competition c = new Competition();
        c.setName("Khess First League");
        c.setTypeId(Competition.LEAGUE);
        c.setNationId(3);
        league = competitions.save(c).getId();
        for (int i = 0; i < 4; i++) {
            Team t = new Team();
            t.setName("Club " + i);
            t.setCompetitionId(league);
            t.setReputation(5_000 - i * 500);
            long id = teams.save(t).getId();
            CompetitionTeamInfo m = new CompetitionTeamInfo();
            m.setTeamId(id);
            m.setCompetitionId(league);
            m.setSeasonNumber(1);
            m.setRound(1);
            memberships.save(m);
            if (i == 0) club = id;
        }
        Human h = new Human();
        h.setName("Kvekrpur");
        h.setTypeId(TypeNames.MANAGER_TYPE);
        h.setTeamId(club);
        h.setManagerReputation(2_000);
        manager = humans.save(h);
        when(predictions.predictedFinish(anyLong(), anyLong())).thenReturn(new int[]{2, 4});
        articles.publish(manager, club);
    }

    @Test
    @DisplayName("""
            GIVEN a new manager (reputation 2,000) whose appointment article quoted the league's pundits
            WHEN at his first press conference the harshest pundit's words are read and he attacks him,
             then promises the title
            THEN the question quotes that pundit from the article; reputation -30; the pundit's relation with him
             drops to 30 (one step harsher); the title becomes a public promise worth 150; no second conference
            """)
    void answeringThePunditAndMakingAPromise() {
        FirstPressConferenceService.Conference before = conferences.conference(club);
        assertEquals("PENDING", before.status());
        assertNotNull(before.pundit());
        assertTrue(before.pundit().quote().startsWith("“"), before.pundit().quote());

        FirstPressConferenceService.Conference after = conferences.hold(club,
                FirstPressConferenceService.ArticleAnswer.ATTACK, Map.of(league, "TITLE"));

        assertEquals(MeetingRecord.HELD, after.status());
        assertEquals(1_970, humans.findById(manager.getId()).orElseThrow().getManagerReputation());
        assertEquals(30, relations.relation(RelationParty.ANALYST, before.pundit().analystId(), RelationParty.MANAGER, manager.getId()));
        assertEquals("TITLE", after.targets().get(0).promised());
        assertThrows(IllegalStateException.class, () -> conferences.refuse(club));
    }

    @Test
    @DisplayName("""
            GIVEN the same new manager
            WHEN he refuses his first press conference
            THEN his reputation drops by 100 and every pundit's relation with him falls to 30 (one step harsher)
            """)
    void refusingTheConference() {
        conferences.refuse(club);

        assertEquals(1_900, humans.findById(manager.getId()).orElseThrow().getManagerReputation());
        for (Analyst analyst : analysts.findAll()) {
            assertEquals(30, relations.relation(RelationParty.ANALYST, analyst.getId(), RelationParty.MANAGER, manager.getId()));
        }
        assertEquals(Analyst.Temperament.CRITIC, AnalystStance.effective(Analyst.Temperament.BALANCED, 30));
        assertEquals(Analyst.Temperament.SUPPORTIVE, AnalystStance.effective(Analyst.Temperament.SUPPORTIVE, 90));
    }

    @Test
    @DisplayName("""
            GIVEN a public promise of the title, and the club finished 2nd of 4
            WHEN the season is settled (twice)
            THEN the promise is missed once: reputation 2,000 - 150 = 1,850
            """)
    void brokenPromiseCostsReputation() {
        conferences.hold(club, FirstPressConferenceService.ArticleAnswer.NO_COMMENT, Map.of(league, "TITLE"));
        for (int place = 1; place <= 4; place++) {
            CompetitionHistory row = new CompetitionHistory();
            row.setCompetitionId(league);
            row.setSeasonNumber(1);
            row.setTeamId(place == 2 ? club : 1_000 + place);
            row.setLastPosition(place);
            history.save(row);
        }

        assertEquals(-150, promises.settle(1));
        assertEquals(0, promises.settle(1));
        assertEquals(1_850, humans.findById(manager.getId()).orElseThrow().getManagerReputation());
    }

    @Test
    @DisplayName("""
            GIVEN a club whose ambitious director heard "I'm with you" in private at the board meeting
            WHEN at the press conference the manager answers the puppet question "I work for the club"
            THEN the question is asked; the chairman's trust rises by 3 (53) and the director's falls by 5,
             plus 5 for feeling lied to (40)
            """)
    void thePuppetQuestion() {
        when(userContext.isHumanTeam(club)).thenReturn(true);
        Human chairman = humans.save(person("Chairman", TypeNames.CHAIRMAN_TYPE));
        Human director = humans.save(person("Director", TypeNames.DIRECTOR_TYPE));
        ClubBoard board = new ClubBoard();
        board.setTeamId(club);
        board.setChairmanId(chairman.getId());
        board.setDirectorId(director.getId());
        board.setDirectorPersonality(DirectorPersonality.AMBITIOUS);
        boards.save(board);
        MeetingRecord privateWord = new MeetingRecord();
        privateWord.setTeamId(club);
        privateWord.setManagerId(manager.getId());
        privateWord.setSeason(1);
        privateWord.setType(MeetingRecord.BOARD);
        privateWord.setOutcome("ANSWER:WITH_YOU");
        meetingRecords.save(privateWord);

        assertTrue(conferences.conference(club).puppetQuestion());
        conferences.hold(club, null, Map.of(), FirstPressConferenceService.PuppetAnswer.FOR_THE_CLUB);

        assertEquals(53, relations.relation(RelationParty.CHAIRMAN, chairman.getId(), RelationParty.MANAGER, manager.getId()));
        assertEquals(40, relations.relation(RelationParty.DIRECTOR, director.getId(), RelationParty.MANAGER, manager.getId()));
    }

    private Human person(String name, long type) {
        Human h = new Human();
        h.setName(name);
        h.setTypeId(type);
        h.setTeamId(club);
        return h;
    }

}
