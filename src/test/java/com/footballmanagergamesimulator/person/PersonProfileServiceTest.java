package com.footballmanagergamesimulator.person;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.user.CareerControlConflictException;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonProfileServiceTest {

    @Test
    void attachingAnotherUsersHumanNeverDeletesOrReassignsVictimProfile() {
        PersonProfileRepository profiles = mock(PersonProfileRepository.class);
        PersonProfileService service = new PersonProfileService(
                profiles, mock(UserRepository.class), mock(HumanRepository.class));
        User attacker = new User();
        attacker.setId(1);
        Human manager = new Human();
        manager.setId(10L);
        PersonProfile victim = new PersonProfile();
        victim.setId(20L);
        victim.setUserId(2);
        victim.setHumanId(10L);
        victim.setDisplayName("Victim");
        when(profiles.findByHumanIdForUpdate(10L)).thenReturn(Optional.of(victim));

        assertThatThrownBy(() -> service.attachManager(attacker, manager))
                .isInstanceOf(CareerControlConflictException.class)
                .hasMessageContaining("another user");
        assertThat(victim.getUserId()).isEqualTo(2);
        assertThat(victim.getHumanId()).isEqualTo(10L);
        verify(profiles, never()).delete(victim);
        verify(profiles, never()).save(victim);
    }

    @Test
    void deterministicBackfillFailsSafeInsteadOfDeletingAnotherUsersProfile() {
        PersonProfileRepository profiles = mock(PersonProfileRepository.class);
        UserRepository users = mock(UserRepository.class);
        HumanRepository humans = mock(HumanRepository.class);
        PersonProfileService service = new PersonProfileService(profiles, users, humans);
        User attacker = new User();
        attacker.setId(1);
        attacker.setManagerId(10L);
        User victimUser = new User();
        victimUser.setId(2);
        victimUser.setManagerId(10L);
        Human manager = new Human();
        manager.setId(10L);
        manager.setName("Victim manager");
        manager.setTypeId(4L);
        PersonProfile attackerProfile = new PersonProfile();
        attackerProfile.setId(11L);
        attackerProfile.setUserId(1);
        PersonProfile victimProfile = new PersonProfile();
        victimProfile.setId(12L);
        victimProfile.setUserId(2);
        victimProfile.setHumanId(10L);
        when(users.findAll()).thenReturn(List.of(attacker, victimUser));
        when(humans.findAll()).thenReturn(List.of(manager));
        when(profiles.findByHumanId(10L)).thenReturn(Optional.of(victimProfile));
        when(profiles.findByUserId(1)).thenReturn(Optional.of(attackerProfile));

        assertThatThrownBy(service::backfill)
                .isInstanceOf(CareerControlConflictException.class)
                .hasMessageContaining("controlled by another user");
        verify(profiles, never()).delete(victimProfile);
    }
}
