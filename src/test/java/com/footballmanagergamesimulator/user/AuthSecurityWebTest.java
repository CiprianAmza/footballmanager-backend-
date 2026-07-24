package com.footballmanagergamesimulator.user;

import com.footballmanagergamesimulator.config.WebSecurityConfig;
import com.footballmanagergamesimulator.economy.EconomyApiExceptionHandler;
import com.footballmanagergamesimulator.economy.EconomyConflictException;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.JobOfferService;
import com.footballmanagergamesimulator.economy.RegentEconomyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, CareerOnboardingController.class,
        AuthSecurityWebTest.SaveEndpointStub.class, AuthSecurityWebTest.MarketEndpointStub.class},
        properties = {"regent.enabled=false", "cors.allowed-origins=http://localhost:4200"})
@ContextConfiguration(classes = {AuthController.class, CareerOnboardingController.class,
        CareerOnboardingService.class, AuthSecurityWebTest.SaveEndpointStub.class,
        AuthSecurityWebTest.MarketEndpointStub.class, WebSecurityConfig.class,
        CurrentUserService.class, UserDetailsServiceImpl.class, EconomyApiExceptionHandler.class})
class AuthSecurityWebTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockBean private UserRepository userRepository;
    @MockBean private UserService userService;
    @MockBean private PersonProfileService profileService;
    @MockBean private HumanRepository humanRepository;
    @MockBean private TeamRepository teamRepository;
    @MockBean private RoundRepository roundRepository;
    @MockBean private JobOfferService jobOfferService;
    @MockBean private RegentEconomyProperties regentEconomyProperties;
    @SpyBean private CareerOnboardingService onboardingService;

    private User user;
    private User chairman;
    private User admin;
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
        chairman = account(2, "chairman", "USER", CareerRole.CHAIRMAN);
        admin = account(3, "admin", "ADMIN", CareerRole.MANAGER);
        when(userRepository.findByUsernameIgnoreCase(anyString())).thenAnswer(invocation -> {
            String username = invocation.getArgument(0, String.class);
            return Optional.ofNullable(Map.of(
                    "alice", user,
                    "chairman", chairman,
                    "admin", admin).get(username.toLowerCase()));
        });
        when(userRepository.findByIdForUpdate(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0, Integer.class);
            return Map.of(1, user, 2, chairman, 3, admin).entrySet().stream()
                    .filter(entry -> entry.getKey() == id)
                    .map(Map.Entry::getValue)
                    .findFirst();
        });
        when(profileService.requireForUser(any())).thenAnswer(invocation -> {
            User actor = invocation.getArgument(0, User.class);
            PersonProfile actorProfile = new PersonProfile();
            actorProfile.setId(actor.getId() + 8L);
            actorProfile.setUserId(actor.getId());
            return actorProfile;
        });
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

        doReturn(Map.of("success", true)).when(onboardingService).setupManager(any(), any());
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
        mockMvc.perform(get("/api/me/wealth").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/market/instruments").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGENT_FEATURE_DISABLED"));
        mockMvc.perform(get("/api/clubs").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/club-cash-transfers").session(session).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/users").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void authenticatedCareersCanExportButOnlyAdminCanImportGlobalSave() throws Exception {
        mockMvc.perform(get("/game/export")).andExpect(status().isUnauthorized());

        for (String username : new String[]{"alice", "chairman"}) {
            MockHttpSession session = login(username);
            mockMvc.perform(get("/game/export").session(session)).andExpect(status().isOk());
            mockMvc.perform(post("/game/import").session(session).with(csrf())
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }

        MockHttpSession adminSession = login("admin");
        mockMvc.perform(get("/game/export").session(adminSession)).andExpect(status().isOk());
        mockMvc.perform(post("/game/import").session(adminSession)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/game/import").session(adminSession).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void secondUserGetsHttpConflictWhenTryingToTakeControlledTeam() throws Exception {
        User victim = chairman;
        victim.setTeamId(44L);
        victim.setManagerId(10L);
        Team controlledTeam = new Team();
        controlledTeam.setId(44L);
        when(teamRepository.findByIdForUpdate(44L)).thenReturn(Optional.of(controlledTeam));
        when(userRepository.findAllByTeamId(44L)).thenReturn(java.util.List.of(victim));

        mockMvc.perform(post("/api/career/manager/setup").session(login("alice")).with(csrf())
                        .contentType("application/json")
                        .content("{\"managerName\":\"Attacker\",\"managerAge\":40,\"teamId\":44,\"freeAgent\":false}"))
                .andExpect(status().isConflict());
        assertThat(victim.getTeamId()).isEqualTo(44L);
        assertThat(victim.getManagerId()).isEqualTo(10L);
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
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-Admin-Token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Headers", "X-Admin-Token"));

        mockMvc.perform(options("/api/auth/me")
                        .header("Origin", "https://attacker.invalid")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    private MockHttpSession login() throws Exception {
        return login("alice");
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private User account(int id, String username, String roles, CareerRole careerRole) {
        User account = new User();
        account.setId(id);
        account.setUsername(username);
        account.setEmail(username + "@example.com");
        account.setPassword(passwordEncoder.encode("correct-password"));
        account.setRoles(roles);
        account.setCareerRole(careerRole);
        account.setActive(true);
        return account;
    }

    @RestController
    @RequestMapping("/game")
    static class SaveEndpointStub {
        @GetMapping("/export")
        Map<String, Object> export() {
            return Map.of("saveVersion", 6);
        }

        @PostMapping("/import")
        Map<String, Object> importSave() {
            return Map.of("success", true);
        }
    }

    @RestController
    @RequestMapping("/api/market")
    static class MarketEndpointStub {
        @GetMapping("/instruments")
        Map<String, Object> instruments() {
            throw new EconomyConflictException("REGENT_FEATURE_DISABLED",
                    "Regent market is disabled until the feature flag is enabled");
        }
    }
}
