package com.footballmanagergamesimulator.service.squad;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.RelationService;
import com.footballmanagergamesimulator.util.TypeNames;
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
@Import({SquadMeetingService.class, RelationService.class})
class SquadMeetingServiceTest {

    @Autowired private SquadMeetingService meetings;
    @Autowired private RelationService relations;
    @Autowired private CompetitionRepository competitions;
    @Autowired private CompetitionTeamInfoRepository memberships;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
    @Autowired private GameCalendarRepository calendars;
    @MockBean private LeaguePredictionService predictions;

    @Test
    @DisplayName("""
            GIVEN ladders of four (league) and five (cup) targets
            WHEN the manager's target is compared with the players'
            THEN equal +3, one step more ambitious +5, two or more -5, less ambitious -5 per step;
             the players expect the title when predicted 1st, top half when 5th of 12, a final when 2nd by reputation
            """)
    void relationFollowsTheGapBetweenTargets() {
        assertEquals(3, SquadObjectives.relationDelta(SquadObjectives.LEAGUE, "TOP_3", "TOP_3"));
        assertEquals(5, SquadObjectives.relationDelta(SquadObjectives.LEAGUE, "TOP_3", "TOP_HALF"));
        assertEquals(-5, SquadObjectives.relationDelta(SquadObjectives.LEAGUE, "TITLE", "TOP_HALF"));
        assertEquals(-10, SquadObjectives.relationDelta(SquadObjectives.CUP, "QUARTER_FINAL", "FINAL"));
        assertEquals("TITLE", SquadObjectives.leagueWish(1, 12));
        assertEquals("TOP_HALF", SquadObjectives.leagueWish(5, 12));
        assertEquals("FINAL", SquadObjectives.cupWish(2));
    }

    @Test
    @DisplayName("""
            GIVEN a club in a league (predicted 2nd of 12) and a cup where it has the 3rd best reputation
            WHEN the manager aims for the title and a final
            THEN the players expected top 3 and a semi-final: +5 and +5, so every player's relation with him goes
             from 50 to 60; two maximum bonuses lift morale from 70 to 76; the meeting cannot be held twice
            """)
    void meetingMovesEveryPlayersRelationOnce() {
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(2);
        calendar.setCurrentDay(5);
        calendar.setCurrentPhase("MORNING");
        calendars.save(calendar);
        Competition league = competition("League", Competition.LEAGUE);
        Competition cup = competition("Cup", 2);
        long club = 0;
        for (int i = 0; i < 4; i++) {
            Team t = new Team();
            t.setName("C" + i);
            t.setReputation(9_000 - i * 1_000);
            long id = teams.save(t).getId();
            member(id, cup.getId());
            if (i == 2) club = id;
        }
        member(club, league.getId());
        when(predictions.predictedFinish(league.getId(), club)).thenReturn(new int[]{2, 12});
        Human manager = human(club, TypeNames.MANAGER_TYPE);
        Human player = human(club, TypeNames.PLAYER_TYPE);

        SquadMeetingService.Agenda agenda = meetings.agenda(club);
        assertEquals(List.of("TOP_3", "SEMI_FINAL"), agenda.items().stream().map(SquadMeetingService.Item::playersWish).toList());

        SquadMeetingService.Agenda held = meetings.hold(club, Map.of(league.getId(), "TITLE", cup.getId(), "FINAL"),
                Map.of(league.getId(), "MAX", cup.getId(), "MAX"));

        assertTrue(held.held());
        assertEquals(60, relations.relation(RelationParty.PLAYER, player.getId(), RelationParty.MANAGER, manager.getId()));
        assertEquals(76.0, humans.findById(player.getId()).orElseThrow().getMorale(), "two maximum bonuses: +3 each");
        assertEquals(List.of("MAX", "MAX"), held.items().stream().map(SquadMeetingService.Item::bonusLevel).toList());
        long finalClub = club;
        assertThrows(IllegalStateException.class, () -> meetings.hold(finalClub, Map.of()));
    }

    @Test
    @DisplayName("""
            GIVEN a club with a manager and a player at relation 50 and morale 70
            WHEN the manager refuses the squad meeting
            THEN the player's relation drops to 45 and his morale to 65; the meeting counts as done (refused),
             so it can be neither held nor refused again this season
            """)
    void refusingTheMeetingCostsTrustAndMorale() {
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(2);
        calendar.setCurrentDay(5);
        calendar.setCurrentPhase("MORNING");
        calendars.save(calendar);
        Team t = new Team();
        t.setName("Club");
        long club = teams.save(t).getId();
        Human manager = human(club, TypeNames.MANAGER_TYPE);
        Human player = human(club, TypeNames.PLAYER_TYPE);

        SquadMeetingService.Agenda agenda = meetings.refuse(club);

        assertTrue(agenda.refused() && agenda.held());
        assertEquals(45, relations.relation(RelationParty.PLAYER, player.getId(), RelationParty.MANAGER, manager.getId()));
        assertEquals(65.0, humans.findById(player.getId()).orElseThrow().getMorale());
        assertThrows(IllegalStateException.class, () -> meetings.refuse(club));
        assertThrows(IllegalStateException.class, () -> meetings.hold(club, Map.of()));
    }

    private Competition competition(String name, long type) {
        Competition c = new Competition();
        c.setName(name);
        c.setTypeId(type);
        return competitions.save(c);
    }

    private void member(long teamId, long competitionId) {
        CompetitionTeamInfo m = new CompetitionTeamInfo();
        m.setTeamId(teamId);
        m.setCompetitionId(competitionId);
        m.setSeasonNumber(2);
        m.setRound(1);
        memberships.save(m);
    }

    private Human human(long teamId, long type) {
        Human h = new Human();
        h.setName("H");
        h.setTypeId(type);
        h.setTeamId(teamId);
        h.setMorale(70);
        return humans.save(h);
    }
}
