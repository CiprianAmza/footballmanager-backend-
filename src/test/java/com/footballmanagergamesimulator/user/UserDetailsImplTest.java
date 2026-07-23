package com.footballmanagergamesimulator.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserDetailsImplTest {

    @Test
    void readsPersistedSecurityRolesWithoutImplicitAdmin() {
        User user = new User();
        user.setUsername("manager");
        user.setPassword("hash");
        user.setActive(true);
        user.setRoles("USER");
        user.setCareerRole(CareerRole.CHAIRMAN);

        UserDetailsImpl details = new UserDetailsImpl(user);

        assertThat(details.getAuthorities()).extracting("authority")
                .containsExactly("ROLE_USER")
                .doesNotContain("ROLE_ADMIN", "ROLE_CHAIRMAN");
    }
}
