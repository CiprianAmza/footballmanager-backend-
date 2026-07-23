package com.footballmanagergamesimulator.user;

import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.JobOfferService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CareerOnboardingServiceTest {

    @Test
    void chairmanSetupNeverAssignsManagerOrTeam() {
        UserRepository users = mock(UserRepository.class);
        PersonProfileService profiles = mock(PersonProfileService.class);
        CareerOnboardingService service = service(users, profiles);
        User chairman = new User();
        chairman.setId(4);
        chairman.setCareerRole(CareerRole.CHAIRMAN);
        chairman.setTeamId(99L);
        chairman.setManagerId(77L);
        PersonProfile profile = new PersonProfile();
        profile.setId(12L);
        when(profiles.requireForUser(chairman)).thenReturn(profile);

        Map<String, Object> result = service.setupChairman(chairman);

        assertThat(chairman.getTeamId()).isNull();
        assertThat(chairman.getManagerId()).isNull();
        assertThat(result.get("profileId")).isEqualTo(12L);
        verify(users).save(chairman);
    }

    @Test
    void managerAndChairmanFlowsCannotBeCrossed() {
        UserRepository users = mock(UserRepository.class);
        CareerOnboardingService service = service(users, mock(TeamRepository.class), mock(PersonProfileService.class));
        User chairman = new User();
        chairman.setId(7);
        chairman.setCareerRole(CareerRole.CHAIRMAN);
        when(users.findByIdForUpdate(7)).thenReturn(Optional.of(chairman));

        assertThatThrownBy(() -> service.setupManager(chairman,
                new ManagerSetupRequest("Spoofed", 40, 1L, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void managerCannotSelectTeamControlledByAnotherUser() {
        UserRepository users = mock(UserRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        PersonProfileService profiles = mock(PersonProfileService.class);
        CareerOnboardingService service = service(users, teams, profiles);
        User attacker = managerUser(1);
        User victim = managerUser(2);
        victim.setTeamId(44L);
        Team team = new Team();
        team.setId(44L);
        when(users.findByIdForUpdate(1)).thenReturn(Optional.of(attacker));
        when(teams.findByIdForUpdate(44L)).thenReturn(Optional.of(team));
        when(users.findAllByTeamId(44L)).thenReturn(List.of(victim));

        assertThatThrownBy(() -> service.setupManager(attacker,
                new ManagerSetupRequest("Attacker", 40, 44L, false)))
                .isInstanceOf(CareerControlConflictException.class)
                .hasMessageContaining("another user");
        assertThat(victim.getTeamId()).isEqualTo(44L);
        verify(profiles, never()).attachManager(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private CareerOnboardingService service(UserRepository users, PersonProfileService profiles) {
        return service(users, mock(TeamRepository.class), profiles);
    }

    private CareerOnboardingService service(UserRepository users, TeamRepository teams,
                                             PersonProfileService profiles) {
        return new CareerOnboardingService(users, mock(HumanRepository.class), teams,
                mock(RoundRepository.class), mock(JobOfferService.class), profiles);
    }

    private User managerUser(int id) {
        User user = new User();
        user.setId(id);
        user.setCareerRole(CareerRole.MANAGER);
        return user;
    }
}
