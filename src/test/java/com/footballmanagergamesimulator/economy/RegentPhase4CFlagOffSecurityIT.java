package com.footballmanagergamesimulator.economy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.RegisterRequest;
import com.footballmanagergamesimulator.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-phase4c-flagoff;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=update",
        "simulation.matchday.parallel.enabled=false",
        "regent.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegentPhase4CFlagOffSecurityIT {

    @Autowired private UserService userService;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void authenticatedMarketRoutesReachControllerAndReturnTypedFlagOffErrors() throws Exception {
        registerChairman("phase4c-flagoff", 2_000_000L);
        MockHttpSession session = login("phase4c-flagoff");

        mockMvc.perform(get("/api/market/instruments").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGENT_FEATURE_DISABLED"));

        mockMvc.perform(get("/api/me/portfolio").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGENT_FEATURE_DISABLED"));

        mockMvc.perform(post("/api/market/instruments/1/advice").session(session).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGENT_FEATURE_DISABLED"));

        mockMvc.perform(post("/api/me/market-adviser/hire").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"optionCode\":\"ANALYST\",\"idempotencyKey\":\"flag-off-hire\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGENT_FEATURE_DISABLED"));
    }

    @Test
    void unauthenticatedRequestsRemainDeniedWhenFlagIsOff() throws Exception {
        mockMvc.perform(get("/api/market/instruments"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/market/instruments/1/advice").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    private void registerChairman(String username, long wealth) {
        userService.register(new RegisterRequest(username, username + "@example.com",
                "correct-password", username, CareerRole.CHAIRMAN, wealth));
    }

    private MockHttpSession login(String username) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "username", username, "password", "correct-password"));
        return (MockHttpSession) mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
}
