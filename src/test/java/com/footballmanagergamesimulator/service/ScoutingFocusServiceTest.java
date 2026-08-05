package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScoutingFocusServiceTest {

    private final ScoutingFocusRepository focuses = mock(ScoutingFocusRepository.class);
    private final ScoutingFocusResultRepository results = mock(ScoutingFocusResultRepository.class);
    private final ScoutRepository scouts = mock(ScoutRepository.class);
    private final ScoutAssignmentRepository assignments = mock(ScoutAssignmentRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final CompetitionRepository competitions = mock(CompetitionRepository.class);
    private final HumanRepository humans = mock(HumanRepository.class);
    private final PlayerSkillsRepository skills = mock(PlayerSkillsRepository.class);
    private final GameCalendarRepository calendars = mock(GameCalendarRepository.class);
    private final RoundRepository rounds = mock(RoundRepository.class);
    private final ManagerInboxRepository inboxes = mock(ManagerInboxRepository.class);
    private final FinanceService finances = mock(FinanceService.class);
    private final NationService nations = mock(NationService.class);
    private ScoutingFocusService service;

    @BeforeEach
    void setUp() {
        reset(focuses, results, scouts, assignments, teams, competitions, humans, skills,
                calendars, rounds, inboxes, finances, nations);
        service = new ScoutingFocusService(focuses, results, scouts, assignments, teams,
                competitions, humans, skills, calendars, rounds, inboxes, finances, nations);
    }

    @Test
    void createsAFilteredClubFocusAndChargesTheBudget() {
        Scout scout = scout(4L, 86L);
        Team owner = team(86L, "Sherlock FC", 8L);
        owner.setTransferBudget(100_000);
        Team target = team(90L, "Target FC", 8L);
        Round round = new Round(); round.setSeason(3);
        GameCalendar calendar = new GameCalendar(); calendar.setSeason(3); calendar.setCurrentDay(12);

        when(scouts.findByIdForUpdate(4L)).thenReturn(Optional.of(scout));
        when(assignments.findAllByScoutIdAndStatus(4L, "in_progress")).thenReturn(List.of());
        when(focuses.findAllByScoutIdAndStatus(4L, "in_progress")).thenReturn(List.of());
        when(teams.findById(90L)).thenReturn(Optional.of(target));
        when(teams.findByIdForUpdate(86L)).thenReturn(Optional.of(owner));
        when(rounds.findById(1L)).thenReturn(Optional.of(round));
        when(calendars.findBySeason(3)).thenReturn(List.of(calendar));
        when(humans.findDistinctActivePlayerPositions()).thenReturn(List.of("ST", "GK"));
        when(focuses.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ScoutingFocus created = service.create(86L, new ScoutingFocusService.FocusRequest(
                4L, "TEAM", 90L, "ST", 140d, 220d, 18, 25,
                List.of("Pace", "Finishing"), 14, "KEY_ATTRIBUTES"));

        assertThat(created.getTargetName()).isEqualTo("Target FC");
        assertThat(created.getKeyAttributes()).isEqualTo("Pace,Finishing");
        assertThat(created.getStatus()).isEqualTo("in_progress");
        assertThat(created.getEndDay()).isGreaterThan(12);
        assertThat(owner.getTransferBudget()).isEqualTo(85_000);
        verify(finances).recordExpense(86L, 3, 12, "SCOUT_COST", "Recruitment focus: Target FC", 15_000L);
    }

    @Test
    void oneScoutCannotRunAnIndividualAndBroadAssignmentTogether() {
        Scout scout = scout(4L, 86L);
        when(scouts.findByIdForUpdate(4L)).thenReturn(Optional.of(scout));
        when(assignments.findAllByScoutIdAndStatus(4L, "in_progress"))
                .thenReturn(List.of(new ScoutAssignment()));

        assertThatThrownBy(() -> service.create(86L, new ScoutingFocusService.FocusRequest(
                4L, "NATION", 1L, "ANY", 0d, 300d, 15, 40,
                List.of(), 1, "BALANCED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already on an assignment");
        verifyNoInteractions(finances);
    }

    @Test
    void completionReturnsOnlyPlayersWhoMeetEveryKeyAttribute() {
        ScoutingFocus focus = new ScoutingFocus();
        focus.setId(10L); focus.setTeamId(86L); focus.setScoutId(4L); focus.setScoutName("Ada Scout");
        focus.setTargetType("TEAM"); focus.setTargetId(90L); focus.setTargetName("Target FC");
        focus.setPosition("ST"); focus.setMinRating(100); focus.setMaxRating(250);
        focus.setMinAge(16); focus.setMaxAge(29); focus.setKeyAttributes("Pace,Finishing");
        focus.setMinimumAttribute(14); focus.setEmphasis("KEY_ATTRIBUTES"); focus.setSeason(3); focus.setEndDay(20);
        focus.setStatus("in_progress");

        Human match = player(101L, 90L, "Fast Finisher", 21, 180, 88, 4_000_000);
        Human rejected = player(102L, 90L, "Slow Finisher", 22, 190, 90, 5_000_000);
        PlayerSkills good = playerSkills(101L, 17, 16);
        PlayerSkills poor = playerSkills(102L, 10, 18);

        when(focuses.findAllBySeasonAndStatusAndEndDayLessThanEqual(3, "in_progress", 20))
                .thenReturn(List.of(focus));
        when(humans.findAllByTeamIdInAndTypeIdAndRetiredFalse(anySet(), eq(1L)))
                .thenReturn(List.of(match, rejected));
        when(skills.findAllByPlayerIdIn(anyCollection())).thenReturn(List.of(good, poor));
        when(teams.findAllById(anySet())).thenReturn(List.of(team(90L, "Target FC", 8L)));
        when(scouts.findById(4L)).thenReturn(Optional.of(scout(4L, 86L)));

        service.processCompleted(3, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScoutingFocusResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(results).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(ScoutingFocusResult::getPlayerName)
                .containsExactly("Fast Finisher");
        assertThat(captor.getValue().get(0).getMatchedAttributes()).contains("Pace:17", "Finishing:16");
        assertThat(focus.getStatus()).isEqualTo("completed");
        assertThat(focus.getCandidatesFound()).isOne();
        verify(inboxes).save(any(ManagerInbox.class));
    }

    private static Scout scout(long id, long teamId) {
        Scout scout = new Scout(); scout.setId(id); scout.setName("Ada Scout"); scout.setTeamId(teamId);
        scout.setExperience(10); scout.setScoutingAbility(16); scout.setJudgingPotential(15); return scout;
    }

    private static Team team(long id, String name, long competitionId) {
        Team team = new Team(); team.setId(id); team.setName(name); team.setCompetitionId(competitionId); return team;
    }

    private static Human player(long id, long teamId, String name, int age, double rating,
                                int potential, long value) {
        Human player = new Human(); player.setId(id); player.setTeamId(teamId); player.setName(name);
        player.setPosition("ST"); player.setAge(age); player.setRating(rating);
        player.setPotentialAbility(potential); player.setTransferValue(value); return player;
    }

    private static PlayerSkills playerSkills(long playerId, int pace, int finishing) {
        PlayerSkills skills = new PlayerSkills(); skills.setPlayerId(playerId);
        skills.setPace(pace); skills.setFinishing(finishing); return skills;
    }
}
