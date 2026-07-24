package com.footballmanagergamesimulator.chairman.mandate;

import com.footballmanagergamesimulator.economy.ClubQueryService;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.TacticService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ChairmanTacticalMandateServiceTest {
    private final ChairmanTacticalMandateRepository mandates = mock(ChairmanTacticalMandateRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final HumanRepository players = mock(HumanRepository.class);
    private final GameCalendarRepository calendars = mock(GameCalendarRepository.class);
    private final ClubQueryService clubs = mock(ClubQueryService.class);
    private final TacticService tactics = mock(TacticService.class);
    private final ChairmanTacticalMandateService service = new ChairmanTacticalMandateService(
            mandates, teams, players, calendars, clubs, tactics);
    private final PersonProfile chairman = profile(7L);

    @BeforeEach
    void setUp() {
        when(teams.findByIdForUpdate(10L)).thenReturn(Optional.of(new Team()));
        when(mandates.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tactics.isKnownFormation(anyString())).thenReturn(false);
        when(tactics.isKnownFormation("442")).thenReturn(true);
        when(tactics.getAllExistingTactics()).thenReturn(java.util.List.of("442"));
        when(tactics.getFormationGridIndicesExact("442")).thenReturn(new int[]{1, 3, 5});
        GameCalendar calendar = new GameCalendar();
        calendar.setSeason(2); calendar.setCurrentDay(12);
        when(calendars.findTopByOrderBySeasonDesc()).thenReturn(Optional.of(calendar));
    }

    @Test
    void missingMandateReadsEmptyAndEmptyUpdatePersists() {
        when(mandates.findByTeamId(10L)).thenReturn(Optional.empty());
        assertThat(service.get(10L, chairman).lockedSlots()).isEmpty();
        var saved = service.update(10L, chairman, new ChairmanTacticalMandateDtos.UpdateRequest(null, null, 0));
        assertThat(saved.teamId()).isEqualTo(10L);
        assertThat(saved.requiredFormation()).isNull();
        assertThat(saved.version()).isEqualTo(1);
    }

    @Test
    void validatesAuthorizationBeforePlayerValidation() {
        when(mandates.findByTeamId(10L)).thenReturn(Optional.empty());
        doThrow(new ChairmanTacticalMandateException("CLUB_CONTROL_REQUIRED", "not controlled"))
                .when(clubs).dashboard(10L, chairman);
        assertThatThrownBy(() -> service.update(10L, chairman,
                new ChairmanTacticalMandateDtos.UpdateRequest("missing", null, 0)))
                .hasFieldOrPropertyWithValue("code", "CLUB_CONTROL_REQUIRED");
        verifyNoInteractions(players);
    }

    @Test
    void nonChairmanIsRejectedBeforeTeamLockOrRequestValidation() {
        PersonProfile manager = profile(8L);
        manager.setCareerType(CareerType.MANAGER);
        assertThatThrownBy(() -> service.update(10L, manager,
                new ChairmanTacticalMandateDtos.UpdateRequest("unknown", null, 0)))
                .hasFieldOrPropertyWithValue("code", "CHAIRMAN_REQUIRED");
        verifyNoInteractions(teams, players, tactics, clubs);
    }

    @Test
    void nullPrincipalIsRejectedBeforeTeamLock() {
        assertThatThrownBy(() -> service.update(10L, null, null))
                .hasFieldOrPropertyWithValue("code", "CHAIRMAN_REQUIRED");
        verifyNoInteractions(teams, players, tactics, clubs);
    }

    @Test
    void validatesValidPlayerAndRejectsForeignRetiredAndNonPlayer() {
        when(mandates.findByTeamId(10L)).thenReturn(Optional.empty());
        Human valid = human(100L, 10L, 1L, false);
        when(players.findById(100L)).thenReturn(Optional.of(valid));
        var result = service.update(10L, chairman, new ChairmanTacticalMandateDtos.UpdateRequest("442",
                java.util.List.of(new ChairmanTacticalMandateDtos.LockedSlot(1, 100L)), 0));
        assertThat(result.lockedSlots()).containsExactly(new ChairmanTacticalMandateDtos.LockedSlot(1, 100L));

        Human foreign = human(101L, 11L, 1L, false);
        when(players.findById(101L)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.update(10L, chairman, new ChairmanTacticalMandateDtos.UpdateRequest("442",
                java.util.List.of(new ChairmanTacticalMandateDtos.LockedSlot(1, 101L)), 0)))
                .hasFieldOrPropertyWithValue("code", "MANDATED_PLAYER_NOT_ELIGIBLE");
    }

    @Test
    void staleWriterLosesAfterFirstVersionedWrite() {
        when(mandates.findByTeamId(10L)).thenReturn(Optional.empty());
        var first = service.update(10L, chairman,
                new ChairmanTacticalMandateDtos.UpdateRequest(null, null, 0));
        assertThat(first.version()).isEqualTo(1);
        ChairmanTacticalMandate existing = new ChairmanTacticalMandate();
        existing.setTeamId(10L);
        when(mandates.findByTeamId(10L)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.update(10L, chairman,
                new ChairmanTacticalMandateDtos.UpdateRequest(null, null, 0)))
                .hasFieldOrPropertyWithValue("code", "TACTICAL_MANDATE_STALE");
    }

    @Test
    void currentApiVersionIsAcceptedAndEntityVersionIsMappedOneBased() {
        ChairmanTacticalMandate existing = new ChairmanTacticalMandate();
        existing.setTeamId(10L);
        existing.setVersion(0L);
        when(mandates.findByTeamId(10L)).thenReturn(Optional.of(existing));
        var updated = service.update(10L, chairman,
                new ChairmanTacticalMandateDtos.UpdateRequest(null, null, 1));
        assertThat(updated.version()).isEqualTo(1);
        assertThat(existing.getVersion()).isZero();
    }

    @Test
    void requiredFormationNullRejectsLocksWithNoCommonFormation() {
        when(tactics.getAllExactFormationGridNames()).thenReturn(java.util.List.of("442", "433"));
        when(tactics.getFormationGridIndicesExact("433")).thenReturn(new int[]{1, 3, 7});
        when(tactics.getFormationGridIndicesExact("442")).thenReturn(new int[]{1, 3, 5});
        when(mandates.findByTeamId(10L)).thenReturn(Optional.empty());
        when(players.findById(100L)).thenReturn(Optional.of(human(100L, 10L, 1L, false)));
        when(players.findById(101L)).thenReturn(Optional.of(human(101L, 10L, 1L, false)));

        assertThatThrownBy(() -> service.update(10L, chairman,
                new ChairmanTacticalMandateDtos.UpdateRequest(null,
                        java.util.List.of(new ChairmanTacticalMandateDtos.LockedSlot(5, 100L),
                                new ChairmanTacticalMandateDtos.LockedSlot(7, 101L)), 0)))
                .hasFieldOrPropertyWithValue("code", "TACTICAL_MANDATE_INVALID");
    }

    private static PersonProfile profile(long id) {
        PersonProfile profile = new PersonProfile(); profile.setId(id); profile.setCareerType(CareerType.CHAIRMAN); return profile;
    }

    private static Human human(long id, long teamId, long typeId, boolean retired) {
        Human human = new Human(); human.setId(id); human.setTeamId(teamId); human.setTypeId(typeId); human.setRetired(retired); return human;
    }
}
