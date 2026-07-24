package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.controller.GameController;
import com.footballmanagergamesimulator.controller.GameSaveImportService;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.service.CalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-full-save-preflight;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "simulation.matchday.parallel.enabled=false",
        "regent.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GameSavePreflightIT {

    @Autowired private GameController controller;
    @SpyBean private GameSaveImportService importService;
    @Autowired private RoundRepository rounds;
    @Autowired private CalendarService calendars;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;

    @Test
    void completeLiveWorldExportPreflightsAsV10() {
        Round activeRound = rounds.findById(1L).orElseThrow();
        calendars.getOrCreateCalendar((int) activeRound.getSeason());
        Map<String, Object> v10 = controller.exportGame();

        assertThat(v10).doesNotContainKeys("users", "personProfiles");
        assertThat(v10).containsKeys("marketInstruments", "marketPriceSnapshots",
                "portfolioPositions", "marketTrades", "clubCapTableStates",
                "takeoverQuotes", "takeoverExecutions", "clubCashTransfers");
        assertThat(importService.prepare(v10).sourceVersion()).isEqualTo(10);
    }

    @Test
    @WithMockUser(username = "atlas", roles = "ADMIN")
    void generatorFailureOnRealHttpPathReturnsFailureBeforeApplyAndPreservesSentinel() throws Exception {
        Round activeRound = rounds.findById(1L).orElseThrow();
        calendars.getOrCreateCalendar((int) activeRound.getSeason());
        GameSaveImportService.ImportPlan valid = importService.prepare(controller.exportGame());
        GameSaveImportService.GeneratorReset missingSequence = new GameSaveImportService.GeneratorReset(
                GameSaveImportService.GeneratorKind.SEQUENCE,
                "ATLAS_MISSING_SEQUENCE", "ROUND", null, 1L);
        GameSaveImportService.ImportPlan broken = new GameSaveImportService.ImportPlan(
                valid.sourceVersion(), valid.tables(), List.of(missingSequence));

        jdbc.update("""
                INSERT INTO MATCH_PLAN
                    (ID, FIXTURE_KEY, SEED, ALGORITHM_VERSION, HOME_TEAM_ID, AWAY_TEAM_ID,
                     HOME_SCORE90, AWAY_SCORE90, HOME_SCOREET, AWAY_SCOREET,
                     HOME_SHOOTOUT, AWAY_SHOOTOUT, STATUS)
                VALUES (99001, 'ATLAS:WORLD_SENTINEL', 1, 'sentinel', 1, 2,
                        0, 0, -1, -1, -1, -1, 'PLANNED')
                """);
        int roundsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM ROUND", Integer.class);
        doReturn(broken).when(importService).prepare(any());

        mockMvc.perform(post("/game/import").with(csrf())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(
                        "world was not modified")));

        verify(importService, never()).apply(any(), anyBoolean());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM MATCH_PLAN WHERE FIXTURE_KEY = 'ATLAS:WORLD_SENTINEL'",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ROUND", Integer.class))
                .isEqualTo(roundsBefore);
    }
}
