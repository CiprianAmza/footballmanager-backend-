package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.chairman.command.ChairmanCommandCentreService;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateService;
import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ClubControllerTest {
    private final CurrentUserService currentUsers = mock(CurrentUserService.class);
    private final PersonProfileService profiles = mock(PersonProfileService.class);
    private final PersonalAccountingService accounting = mock(PersonalAccountingService.class);
    private final ClubQueryService query = mock(ClubQueryService.class);
    private final TakeoverService takeovers = mock(TakeoverService.class);
    private final ClubTreasuryService treasury = mock(ClubTreasuryService.class);
    private final ChairmanCommandCentreService commandCentre = mock(ChairmanCommandCentreService.class);
    private final ChairmanTacticalMandateService tacticalMandates = mock(ChairmanTacticalMandateService.class);
    private final ClubController controller = new ClubController(currentUsers, profiles, accounting,
            query, takeovers, treasury, commandCentre, tacticalMandates);

    @Test
    void catalogUsesAuthenticatedProfileAndForwardsScopeWithoutActorIds() {
        User user = new User();
        user.setId(4);
        PersonProfile manager = profile(44L, CareerType.MANAGER);
        when(currentUsers.requireUser()).thenReturn(user);
        when(profiles.requireForUser(user)).thenReturn(manager);
        when(query.clubs(ClubCatalogScope.HELD, 44L)).thenReturn(List.of());

        controller.clubs(ClubCatalogScope.HELD);

        verify(query).clubs(ClubCatalogScope.HELD, 44L);
        verifyNoInteractions(takeovers, treasury);
    }

    @Test
    void managerCanReadCatalogButCannotReadPrivateDashboard() {
        User user = new User();
        PersonProfile manager = profile(44L, CareerType.MANAGER);
        when(currentUsers.requireUser()).thenReturn(user);
        when(profiles.requireForUser(user)).thenReturn(manager);

        controller.clubs(ClubCatalogScope.ALL);
        assertThatThrownBy(() -> controller.dashboard(1L))
                .isInstanceOf(EconomyConflictException.class)
                .hasFieldOrPropertyWithValue("code", "CHAIRMAN_REQUIRED");
        verify(query, never()).dashboard(anyLong(), any(PersonProfile.class));
    }

    @Test
    void chairmanDashboardPassesCanonicalProfileToQueryService() {
        User user = new User();
        PersonProfile chairman = profile(55L, CareerType.CHAIRMAN);
        when(currentUsers.requireUser()).thenReturn(user);
        when(profiles.requireForUser(user)).thenReturn(chairman);

        controller.dashboard(9L);

        verify(query).dashboard(9L, chairman);
    }

    @Test
    void commandCentreUsesAuthenticatedProfileAndRouteTeamIdOnly() {
        User user = new User();
        PersonProfile chairman = profile(55L, CareerType.CHAIRMAN);
        when(currentUsers.requireUser()).thenReturn(user);
        when(profiles.requireForUser(user)).thenReturn(chairman);

        assertThat(controller.commandCentre(9L)).isNull();
        verify(commandCentre).commandCentre(9L, chairman);
        verifyNoMoreInteractions(commandCentre);
    }

    @Test
    void tacticalMandateUsesAuthenticatedProfileAndRouteTeamIdOnly() {
        User user = new User();
        PersonProfile chairman = profile(55L, CareerType.CHAIRMAN);
        when(currentUsers.requireUser()).thenReturn(user);
        when(profiles.requireForUser(user)).thenReturn(chairman);

        controller.tacticalMandate(9L);

        verify(tacticalMandates).get(9L, chairman);
        verifyNoInteractions(query, takeovers, treasury);
    }

    private static PersonProfile profile(long id, CareerType type) {
        PersonProfile profile = new PersonProfile();
        profile.setId(id);
        profile.setCareerType(type);
        return profile;
    }
}
