package com.footballmanagergamesimulator.user;

import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.JobOfferService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
        CareerOnboardingService service = service(mock(UserRepository.class), mock(PersonProfileService.class));
        User chairman = new User();
        chairman.setCareerRole(CareerRole.CHAIRMAN);

        assertThatThrownBy(() -> service.setupManager(chairman,
                new ManagerSetupRequest("Spoofed", 40, 1L, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    private CareerOnboardingService service(UserRepository users, PersonProfileService profiles) {
        return new CareerOnboardingService(users, mock(HumanRepository.class), mock(TeamRepository.class),
                mock(RoundRepository.class), mock(JobOfferService.class), profiles);
    }
}
