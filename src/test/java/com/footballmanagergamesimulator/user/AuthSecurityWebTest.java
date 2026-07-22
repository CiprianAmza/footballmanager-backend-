package com.footballmanagergamesimulator.user;

import com.footballmanagergamesimulator.config.WebSecurityConfig;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, CareerOnboardingController.class},
        properties = {"regent.enabled=false", "cors.allowed-origins=http://localhost:4200"})
@ContextConfiguration(classes = {AuthController.class, CareerOnboardingController.class,
        WebSecurityConfig.class, CurrentUserService.class, UserDetailsServiceImpl.class})
class AuthSecurityWebTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockBean private UserRepository userRepository;
    @MockBean private UserService userService;
    @MockBean private PersonProfileService profileService;
    @MockBean private CareerOnboardingService onboardingService;

    private User user;
    private PersonProfile profile;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPassword(passwordEncoder.encode("correct-password"));
        user.setRoles("USER");
        user.setCareerRole(CareerRole.MANAGER);
        user.setActive(true);
        profile = new PersonProfile();
        profile.setId(9L);
        profile.setUserId(1);
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        when(profileService.requireForUser(user)).thenReturn(profile);
    }

    @Test
    void cookieMutationRequiresCsrfAndLoginChecksPassword() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"correct-password\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"));

        user.setActive(false);
        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"correct-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"));
    }

    @Test
    void sessionPrincipalIgnoresSpoofedHeaderAndBodyActorIds() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(get("/api/auth/me").session(session).header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist());

        mockMvc.perform(get("/game/isSetupComplete").session(session).param("userId", "999"))
                .andExpect(status().isForbidden());

        when(onboardingService.setupManager(any(), any())).thenReturn(Map.of("success", true));
        mockMvc.perform(post("/api/career/manager/setup").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"managerName\":\"Alice\",\"managerAge\":35,\"teamId\":2,\"freeAgent\":false,\"userId\":999}"))
                .andExpect(status().isOk());
        verify(onboardingService).setupManager(argThat(actor -> actor.getId() == 1), any());
    }

    @Test
    void boardroomIsUnavailableAndUserIsNotAdminWhileFeatureIsOff() throws Exception {
        MockHttpSession session = login();
        mockMvc.perform(get("/boardroom/humans").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/users").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void logoutInvalidatesServerSession() throws Exception {
        MockHttpSession session = login();
        mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        org.assertj.core.api.Assertions.assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void corsAllowsOnlyConfiguredCredentialedOrigin() throws Exception {
        mockMvc.perform(options("/api/auth/me")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        mockMvc.perform(options("/api/auth/me")
                        .header("Origin", "https://attacker.invalid")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
