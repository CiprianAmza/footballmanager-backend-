package com.footballmanagergamesimulator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.person.PersonProfileService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringJUnitConfig(GameSaveGeneratorAlignmentTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GameSaveGeneratorAlignmentTest {

    private static final Map<String, String> SEQUENCES = Map.of(
            "COMPETITION_TEAM_INFO", "CTI_SEQ",
            "SCORER", "SCORER_SEQ",
            "PLAYER_SKILLS", "PLAYER_SKILLS_SEQ",
            "TEAM_PLAYER_HISTORICAL_RELATION", "TPHR_SEQ");

    @jakarta.annotation.Resource private GameSaveImportService service;
    @jakarta.annotation.Resource private JdbcTemplate jdbc;
    @jakarta.annotation.Resource private PersonProfileService profiles;

    @Test
    void explicitCrossInstanceIdsAdvanceEveryIdentityAndNamedSequencePastImportedMax() {
        GameSaveImportService.ImportPlan plan = service.prepare(highIdSave());
        service.alignGeneratorsBeforeApply(plan);
        service.apply(plan, false);

        for (String table : GameSaveImportService.manifestTableNames()) {
            jdbc.update("INSERT INTO \"" + table + "\" DEFAULT VALUES");
            long generated = jdbc.queryForObject(
                    "SELECT MAX(\"ID\") FROM \"" + table + "\"", Long.class);
            assertThat(generated).as(table + " next generated id").isGreaterThan(1000L);
        }
        assertThat(plan.generatorResets()).hasSize(GameSaveImportService.manifestTableNames().size());
        assertThat(plan.generatorResets().stream()
                .filter(reset -> reset.kind() == GameSaveImportService.GeneratorKind.SEQUENCE)
                .map(GameSaveImportService.GeneratorReset::generatorName))
                .containsExactlyInAnyOrder("CTI_SEQ", "SCORER_SEQ", "PLAYER_SKILLS_SEQ", "TPHR_SEQ");
    }

    @Test
    void successfulAlignmentThenFailedReplacementRollsBackWorldAndLeavesCounterSafelyAdvanced() {
        GameSaveImportService.ImportPlan plan = service.prepare(highIdSave());
        service.alignGeneratorsBeforeApply(plan);
        doThrow(new IllegalStateException("forced after replacement DML started"))
                .when(profiles).backfill();

        assertThatThrownBy(() -> service.apply(plan, false))
                .hasMessageContaining("forced after replacement DML started");
        verify(profiles).backfill();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM WHERE ID = 1", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM WHERE ID = 1000", Integer.class)).isZero();

        jdbc.update("INSERT INTO TEAM DEFAULT VALUES");
        long generated = jdbc.queryForObject("SELECT MAX(ID) FROM TEAM", Long.class);
        assertThat(generated).isGreaterThan(1000L);
    }

    @Test
    void failedReplacementNeverRewindsIdentityBelowTheExistingWorldMaximum() {
        jdbc.update("INSERT INTO TEAM (ID) VALUES (2000)");
        GameSaveImportService.ImportPlan plan = service.prepare(highIdSave());
        service.alignGeneratorsBeforeApply(plan);
        doThrow(new IllegalStateException("forced after replacement DML started"))
                .when(profiles).backfill();

        assertThatThrownBy(() -> service.apply(plan, false))
                .hasMessageContaining("forced after replacement DML started");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM WHERE ID = 2000", Integer.class)).isOne();

        jdbc.update("INSERT INTO TEAM DEFAULT VALUES");
        long generated = jdbc.queryForObject("SELECT MAX(ID) FROM TEAM", Long.class);
        assertThat(generated).isGreaterThan(2000L);
    }

    private Map<String, Object> highIdSave() {
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("saveVersion", 6);
        for (String key : GameSaveImportService.manifestKeys()) {
            save.put(key, List.of(Map.of("id", 1000L)));
        }
        save.put("rounds", List.of(
                Map.of("id", 1L, "season", 1L),
                Map.of("id", 1000L, "season", 1L)));
        save.put("gameCalendars", List.of(Map.of("id", 1000L, "season", 1)));
        save.put("humans", List.of(Map.of(
                "id", 1000L, "typeId", 4L, "retired", false, "name", "Imported manager")));
        return save;
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Config {
        @Bean
        DataSource dataSource() throws Exception {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:regent-generator-cross-instance-" + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
            dataSource.setUser("sa");
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                for (String sequence : Set.copyOf(SEQUENCES.values())) {
                    statement.execute("CREATE SEQUENCE \"" + sequence + "\" START WITH 1 INCREMENT BY 1");
                }
                for (String table : GameSaveImportService.manifestTableNames()) {
                    String defaultValue = SEQUENCES.containsKey(table)
                            ? " DEFAULT NEXT VALUE FOR \"" + SEQUENCES.get(table) + "\"" : "";
                    String extra = switch (table) {
                        case "ROUND" -> ", SEASON BIGINT";
                        case "GAME_CALENDAR" -> ", SEASON INTEGER";
                        case "HUMAN" -> ", TYPE_ID BIGINT, RETIRED BOOLEAN, NAME VARCHAR(255)";
                        default -> "";
                    };
                    statement.execute("CREATE TABLE \"" + table + "\" (ID BIGINT" + defaultValue
                            + (defaultValue.isEmpty() ? " GENERATED BY DEFAULT AS IDENTITY" : "")
                            + " PRIMARY KEY" + extra + ")");
                    statement.execute("INSERT INTO \"" + table + "\" DEFAULT VALUES");
                }
                statement.execute("CREATE TABLE USERS (ID INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                        + "TEAM_ID BIGINT, LAST_TEAM_ID BIGINT, MANAGER_ID BIGINT)");
                statement.execute("CREATE TABLE PERSON_PROFILE ("
                        + "ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, USER_ID INT UNIQUE, "
                        + "HUMAN_ID BIGINT UNIQUE, CAREER_TYPE VARCHAR(20) NOT NULL, "
                        + "CONTROL_TYPE VARCHAR(20) NOT NULL, DISPLAY_NAME VARCHAR(255) NOT NULL, "
                        + "CREATED_SEASON INT NOT NULL, CREATED_DAY INT NOT NULL, "
                        + "ACTIVE BOOLEAN NOT NULL, RETIRED BOOLEAN NOT NULL)");
            }
            return dataSource;
        }

        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean PersonProfileService personProfileService() { return mock(PersonProfileService.class); }
        @Bean GameSaveImportService gameSaveImportService(DataSource dataSource, ObjectMapper mapper,
                                                           PersonProfileService profiles) {
            return new GameSaveImportService(dataSource, mapper, profiles);
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
