package com.footballmanagergamesimulator.user;

import com.footballmanagergamesimulator.person.PersonProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private UserRepository repository;
    private PersonProfileService profileService;
    private UserService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        profileService = mock(PersonProfileService.class);
        service = new UserService(repository, new BCryptPasswordEncoder(4), profileService);
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(11);
            return user;
        });
    }

    @Test
    void registersChairmanWithBcryptAndNoManagerIdentity() {
        User user = service.register(new RegisterRequest("Chair.One", "CHAIR@EXAMPLE.COM",
                "correct horse battery", "Chair One", CareerRole.CHAIRMAN));

        assertThat(user.getUsername()).isEqualTo("chair.one");
        assertThat(user.getEmail()).isEqualTo("chair@example.com");
        assertThat(user.getPassword()).startsWith("$2");
        assertThat(new BCryptPasswordEncoder().matches("correct horse battery", user.getPassword())).isTrue();
        assertThat(user.getRoles()).isEqualTo("USER");
        assertThat(user.getCareerRole()).isEqualTo(CareerRole.CHAIRMAN);
        assertThat(user.getTeamId()).isNull();
        assertThat(user.getManagerId()).isNull();
        verify(profileService).createForUser(user, "Chair One");
    }

    @Test
    void rejectsDuplicateUsernameAndEmail() {
        when(repository.existsByUsernameIgnoreCase("taken")).thenReturn(true);
        RegisterRequest usernameDuplicate = new RegisterRequest("taken", "one@example.com",
                "password-long", "Taken User", CareerRole.MANAGER);
        assertThatThrownBy(() -> service.register(usernameDuplicate))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Username already taken");

        when(repository.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);
        RegisterRequest emailDuplicate = new RegisterRequest("free", "taken@example.com",
                "password-long", "Free User", CareerRole.MANAGER);
        assertThatThrownBy(() -> service.register(emailDuplicate))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Email already registered");
    }
}
