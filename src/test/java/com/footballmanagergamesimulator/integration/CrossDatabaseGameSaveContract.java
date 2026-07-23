package com.footballmanagergamesimulator.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.controller.GameController;
import com.footballmanagergamesimulator.controller.GameSaveImportService;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.service.CalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "simulation.matchday.parallel.enabled=false",
        "regent.enabled=false"
})
abstract class CrossDatabaseGameSaveContract {

    @Autowired private GameController controller;
    @Autowired private GameSaveImportService importService;
    @Autowired private RoundRepository rounds;
    @Autowired private CalendarService calendars;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void fullV6SaveRehearsesImportsAndPreservesInstallationIdentity() {
        Round activeRound = rounds.findById(1L).orElseThrow();
        calendars.getOrCreateCalendar((int) activeRound.getSeason());
        Map<String, Object> save = controller.exportGame();

        assertThat(save).doesNotContainKeys("users", "personProfiles");
        assertThat(save).containsKeys("matchPlans", "matchPlanGoalSlots", "matchParticipants",
                "matchAppearances", "matchSubstitutions", "matchAnimationRecipes", "liveCommitContexts");
        GameSaveImportService.ImportPlan plan = importService.prepare(save);
        assertThat(plan.sourceVersion()).isEqualTo(6);
        assertThat(plan.generatorResets()).isNotEmpty();
        assertThat(plan.generatorResets()).anySatisfy(reset -> {
            assertThat(reset.kind()).isEqualTo(GameSaveImportService.GeneratorKind.IDENTITY);
            assertThat(reset.tableName()).isEqualTo("ROUND");
            assertThat(reset.columnName()).isEqualTo("ID");
        });
        assertThat(plan.generatorResets()).anySatisfy(reset -> {
            assertThat(reset.kind()).isIn(GameSaveImportService.GeneratorKind.SEQUENCE,
                    GameSaveImportService.GeneratorKind.SEQUENCE_TABLE);
            assertThat(reset.generatorName()).isEqualTo("SCORER_SEQ");
        });

        List<Map<String, Object>> accountsBefore = jdbc.queryForList(
                "SELECT id, username, team_id, manager_id FROM users ORDER BY id");
        Map<String, Object> result = controller.importGame(save);
        assertThat(result).containsEntry("success", true);
        assertThat(jdbc.queryForList("SELECT id, username, team_id, manager_id FROM users ORDER BY id"))
                .isEqualTo(accountsBefore);
        assertThat(controller.exportGame().get("saveVersion")).isEqualTo(6);
    }

    @Test
    void invalidImportFailsDuringRollbackRehearsalWithoutChangingWorldOrAccounts() {
        Round activeRound = rounds.findById(1L).orElseThrow();
        calendars.getOrCreateCalendar((int) activeRound.getSeason());
        Map<String, Object> save = objectMapper.convertValue(controller.exportGame(),
                new TypeReference<LinkedHashMap<String, Object>>() { });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> originalRounds = (List<Map<String, Object>>) save.get("rounds");
        List<Map<String, Object>> duplicateRounds = new ArrayList<>(originalRounds);
        duplicateRounds.add(new LinkedHashMap<>(originalRounds.get(0)));
        save.put("rounds", duplicateRounds);

        List<Map<String, Object>> roundsBefore = jdbc.queryForList("SELECT * FROM round ORDER BY id");
        List<Map<String, Object>> accountsBefore = jdbc.queryForList("SELECT * FROM users ORDER BY id");
        assertThatThrownBy(() -> importService.prepare(save))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database preflight failed");
        assertThat(jdbc.queryForList("SELECT * FROM round ORDER BY id")).isEqualTo(roundsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(accountsBefore);
    }
}
