package com.footballmanagergamesimulator.economy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.RegisterRequest;
import com.footballmanagergamesimulator.user.User;
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
        "spring.datasource.url=jdbc:h2:mem:regent-phase4c-api;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=update",
        "simulation.matchday.parallel.enabled=false",
        "regent.enabled=true"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegentPhase4CApiIT {

    @Autowired private UserService userService;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MarketInstrumentRepository instrumentRepository;

    @Test
    void apiUsesAuthenticatedPrincipalForAdviserDataAndEmitsTypedDtos() throws Exception {
        registerChairman("phase4c-chairman-a", 2_000_000L);
        registerChairman("phase4c-chairman-b", 2_000_000L);
        MockHttpSession first = login("phase4c-chairman-a");
        MockHttpSession second = login("phase4c-chairman-b");
        MarketInstrument instrument = instrumentRepository.findByCode("FMX").orElseThrow();

        mockMvc.perform(get("/api/market/instruments").session(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].riskClass").isNotEmpty())
                .andExpect(jsonPath("$[0].price.amount").isNumber());

        mockMvc.perform(post("/api/me/market-adviser/hire").session(first).with(csrf())
                        .contentType("application/json")
                        .content("{\"optionCode\":\"ANALYST\",\"idempotencyKey\":\"hire-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adviserCode").value("ANALYST"))
                .andExpect(jsonPath("$.salaryPerDay.amount").value(2500))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/me/market-adviser").session(first).header("X-User-Id", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentContract.adviserCode").value("ANALYST"))
                .andExpect(jsonPath("$.hireOptions[0].optionCode").isNotEmpty());

        mockMvc.perform(get("/api/me/market-adviser").session(second).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentContract").isEmpty());

        mockMvc.perform(post("/api/market/instruments/" + instrument.getId() + "/advice").session(first).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrumentId").value(instrument.getId()))
                .andExpect(jsonPath("$.action").isNotEmpty())
                .andExpect(jsonPath("$.confidence").isNumber())
                .andExpect(jsonPath("$.riskClass").value(instrument.getRiskClass().name()));

        mockMvc.perform(post("/api/market/instruments/" + instrument.getId() + "/advice").session(second).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_ADVISER_REQUIRED"));
    }

    @Test
    void adviserHireEnforcesIdempotencyAndCashCodes() throws Exception {
        registerChairman("phase4c-rich", 2_000_000L);
        registerChairman("phase4c-poor", 1_000L);
        MockHttpSession rich = login("phase4c-rich");
        MockHttpSession poor = login("phase4c-poor");

        mockMvc.perform(post("/api/me/market-adviser/hire").session(rich).with(csrf())
                        .contentType("application/json")
                        .content("{\"optionCode\":\"ANALYST\",\"idempotencyKey\":\"hire-reuse\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/me/market-adviser/hire").session(rich).with(csrf())
                        .contentType("application/json")
                        .content("{\"optionCode\":\"VETERAN\",\"idempotencyKey\":\"hire-reuse\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mockMvc.perform(post("/api/me/market-adviser/hire").session(poor).with(csrf())
                        .contentType("application/json")
                        .content("{\"optionCode\":\"VETERAN\",\"idempotencyKey\":\"hire-poor\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    private User registerChairman(String username, long wealth) {
        return userService.register(new RegisterRequest(username, username + "@example.com",
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
