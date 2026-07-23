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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@SpringJUnitConfig(GameImportPreflightTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GameImportPreflightTest {

    private static final List<String> SIMPLE_TABLES = List.of(
            "COMPETITION_TYPE", "HUMAN_TYPE", "TRANSFER_STRATEGY", "COMPETITION",
            "TEAM_COMPETITION_RELATION", "TEAM_TRANSFER_STRATEGY_RELATION",
            "TEAM_FACILITIES", "STADIUM", "CALENDAR_EVENT", "HUMAN_TEAM_RELATION", "PLAYER_SKILLS",
            "YOUTH_PLAYER", "PLAYER_INTERACTION", "COMPETITION_TEAM_INFO",
            "COMPETITION_TEAM_INFO_DETAIL", "COMPETITION_TEAM_INFO_MATCH", "TEAM_COMPETITION_DETAIL",
            "COMPETITION_HISTORY", "CLUB_COEFFICIENT", "SCORER", "SCORER_LEADERBOARD_ENTRY",
            "MATCH_EVENT", "MATCH_STATS", "PLAYER_SEASON_STAT", "MATCH_PLAYER_RATING", "MATCH_SQUAD",
            "MATCH_PLAN", "MATCH_PLAN_GOAL_SLOT", "MATCH_PARTICIPANT", "MATCH_APPEARANCE",
            "MATCH_SUBSTITUTION", "MATCH_ANIMATION_RECIPE", "LIVE_COMMIT_CONTEXT", "PREDETERMINED_SCORE",
            "TRANSFER", "TRANSFER_OFFER",
            "LOAN", "ADMIN_PLAYER_MOVEMENT", "INJURY", "SUSPENSION", "SPONSORSHIP",
            "BOARD_REQUEST", "FACILITY_UPGRADE", "AWARD", "AWARD_OVERRIDE", "SEASON_OBJECTIVE",
            "MANAGER_HISTORY", "MANAGER_INBOX", "PRESS_CONFERENCE", "NATIONAL_TEAM_CALLUP",
            "TRAINING_SCHEDULE", "PERSONALIZED_TACTIC", "TEAM_PLAYER_HISTORICAL_RELATION",
            "FINANCIAL_RECORD", "FRIENDLY_MATCH", "JOB_OFFER", "SCOUT", "SCOUT_ASSIGNMENT",
            "SHORTLIST", "ASSET", "CLUB_SHAREHOLDING", "OWNERSHIP", "COACH_PERMISSIONS");

    private static final List<String> MANIFEST_KEYS = GameSaveImportService.manifestKeys();

    @jakarta.annotation.Resource private GameSaveImportService service;
    @jakarta.annotation.Resource private JdbcTemplate jdbc;
    @jakarta.annotation.Resource private PersonProfileService profiles;

    @Test
    void malformedAndIncompleteV6FailsBeforeSentinelMutation() {
        Map<String, Object> malformed = validSave(6);
        malformed.put("rounds", List.of(Map.of("id", 999, "season", 2)));
        malformed.put("gameCalendars", List.of(Map.of("id", 999, "season", 1)));

        assertThatThrownBy(() -> service.prepare(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("round id 1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM WHERE ID = 99", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT DISPLAY_NAME FROM PERSON_PROFILE WHERE USER_ID = 1", String.class))
                .isEqualTo("Victim profile");
    }

    @Test
    void unexpectedFailureAfterReplacementRollsBackEverySentinelRow() {
        GameSaveImportService.ImportPlan plan = service.prepare(validSave(6));
        doThrow(new IllegalStateException("forced post-insert failure")).when(profiles).backfill();

        assertThatThrownBy(() -> service.apply(plan, false))
                .hasMessageContaining("forced post-insert failure");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM WHERE ID = 99", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ROUND WHERE ID = 77", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT DISPLAY_NAME FROM PERSON_PROFILE WHERE USER_ID = 1", String.class))
                .isEqualTo("Victim profile");
    }

    @Test
    void cleanV5AndV6RoundTripPreservesWorldAndIgnoresIdentityPayloads() {
        Map<String, Object> v5 = validSave(5);
        v5.put("users", List.of(Map.of("id", 1, "teamId", 999, "managerId", 999)));
        GameSaveImportService.ImportPlan v5Plan = service.prepare(v5);
        service.apply(v5Plan, false);
        service.alignGeneratorsAfterCommit(v5Plan);
        assertImportedStateAndIdentity();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM MATCH_PLAN", Integer.class)).isZero();

        Map<String, Object> v6 = validSave(6);
        v6.put("users", List.of(Map.of("id", 1, "teamId", 999, "managerId", 999)));
        v6.put("personProfiles", List.of(Map.of(
                "id", 500, "userId", 1, "humanId", 999,
                "careerType", "CHAIRMAN", "controlType", "USER", "displayName", "Attacker")));
        GameSaveImportService.ImportPlan v6Plan = service.prepare(v6);
        service.apply(v6Plan, false);
        service.alignGeneratorsAfterCommit(v6Plan);
        assertImportedStateAndIdentity();
    }

    private void assertImportedStateAndIdentity() {
        assertThat(jdbc.queryForObject("SELECT SEASON FROM ROUND WHERE ID = 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT TEAM_ID FROM USERS WHERE ID = 1", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT MANAGER_ID FROM USERS WHERE ID = 1", Long.class)).isEqualTo(10L);
        assertThat(jdbc.queryForObject("SELECT HUMAN_ID FROM PERSON_PROFILE WHERE USER_ID = 1", Long.class))
                .isEqualTo(10L);
        assertThat(jdbc.queryForObject("SELECT DISPLAY_NAME FROM PERSON_PROFILE WHERE USER_ID = 1", String.class))
                .isEqualTo("Victim profile");
    }

    private Map<String, Object> validSave(int version) {
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("saveVersion", version);
        MANIFEST_KEYS.forEach(key -> save.put(key, List.of()));
        save.put("rounds", List.of(Map.of("id", 1, "round", 3, "season", 1)));
        save.put("gameCalendars", List.of(Map.of("id", 1, "season", 1)));
        save.put("teams", List.of(Map.of("id", 1)));
        save.put("humans", List.of(Map.of(
                "id", 10, "teamId", 1, "typeId", 4, "retired", false, "name", "Victim manager")));
        return save;
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Config {
        @Bean
        DataSource dataSource() throws Exception {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:regent-import-test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE SEQUENCE CTI_SEQ START WITH 1 INCREMENT BY 1");
                statement.execute("CREATE SEQUENCE SCORER_SEQ START WITH 1 INCREMENT BY 1");
                statement.execute("CREATE SEQUENCE PLAYER_SKILLS_SEQ START WITH 1 INCREMENT BY 1");
                statement.execute("CREATE SEQUENCE TPHR_SEQ START WITH 1 INCREMENT BY 1");
                statement.execute("CREATE TABLE ROUND (ID BIGINT PRIMARY KEY, ROUND BIGINT, SEASON BIGINT)");
                statement.execute("CREATE TABLE TEAM (ID BIGINT PRIMARY KEY)");
                statement.execute("CREATE TABLE GAME_CALENDAR (ID BIGINT PRIMARY KEY, SEASON INT)");
                statement.execute("CREATE TABLE HUMAN (ID BIGINT PRIMARY KEY, TEAM_ID BIGINT, TYPE_ID BIGINT NOT NULL, RETIRED BOOLEAN NOT NULL, NAME VARCHAR(255))");
                statement.execute("CREATE TABLE USERS (ID INT PRIMARY KEY, TEAM_ID BIGINT, LAST_TEAM_ID BIGINT, MANAGER_ID BIGINT)");
                statement.execute("CREATE TABLE PERSON_PROFILE (ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, USER_ID INT UNIQUE, HUMAN_ID BIGINT UNIQUE, CAREER_TYPE VARCHAR(20) NOT NULL, CONTROL_TYPE VARCHAR(20) NOT NULL, DISPLAY_NAME VARCHAR(255) NOT NULL, CREATED_SEASON INT NOT NULL, CREATED_DAY INT NOT NULL, ACTIVE BOOLEAN NOT NULL, RETIRED BOOLEAN NOT NULL)");
                for (String table : SIMPLE_TABLES) {
                    String sequence = switch (table) {
                        case "COMPETITION_TEAM_INFO" -> "CTI_SEQ";
                        case "SCORER" -> "SCORER_SEQ";
                        case "PLAYER_SKILLS" -> "PLAYER_SKILLS_SEQ";
                        case "TEAM_PLAYER_HISTORICAL_RELATION" -> "TPHR_SEQ";
                        default -> null;
                    };
                    String id = sequence == null ? "ID BIGINT PRIMARY KEY"
                            : "ID BIGINT DEFAULT NEXT VALUE FOR " + sequence + " PRIMARY KEY";
                    statement.execute("CREATE TABLE \"" + table + "\" (" + id + ")");
                }
                statement.execute("INSERT INTO TEAM VALUES (1), (99)");
                statement.execute("INSERT INTO HUMAN VALUES (10, 1, 4, FALSE, 'Victim manager')");
                statement.execute("INSERT INTO ROUND VALUES (77, 8, 1)");
                statement.execute("INSERT INTO GAME_CALENDAR VALUES (77, 1)");
                statement.execute("INSERT INTO USERS VALUES (1, 1, 1, 10)");
                statement.execute("INSERT INTO PERSON_PROFILE (ID, USER_ID, HUMAN_ID, CAREER_TYPE, CONTROL_TYPE, DISPLAY_NAME, CREATED_SEASON, CREATED_DAY, ACTIVE, RETIRED) VALUES (1, 1, 10, 'MANAGER', 'USER', 'Victim profile', 0, 0, TRUE, FALSE)");
                statement.execute("INSERT INTO MATCH_PLAN (ID) VALUES (77)");
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
