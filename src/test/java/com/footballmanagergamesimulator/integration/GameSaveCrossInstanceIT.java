package com.footballmanagergamesimulator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.controller.GameController;
import com.footballmanagergamesimulator.controller.GameSaveImportService;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.service.CalendarService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-cross-instance-source;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "simulation.matchday.parallel.enabled=false",
        "regent.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GameSaveCrossInstanceIT {

    private static final String FIXTURE = "REGENT:CROSS_INSTANCE";

    @Autowired private DataSource sourceDataSource;
    @Autowired private JdbcTemplate source;
    @Autowired private GameController controller;
    @Autowired private RoundRepository rounds;
    @Autowired private CalendarService calendars;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void v6CanonicalStateRoundTripsIntoDivergentSecondH2AndFeedsRecoveryData() throws Exception {
        Round activeRound = rounds.findById(1L).orElseThrow();
        calendars.getOrCreateCalendar((int) activeRound.getSeason());
        seedCanonicalSource(source);
        Map<String, Object> save = controller.exportGame();

        assertThat(save).containsKeys("matchPlans", "matchPlanGoalSlots", "matchParticipants",
                "matchAppearances", "matchSubstitutions", "matchAnimationRecipes", "liveCommitContexts");

        JdbcDataSource targetDataSource = cloneEmptySchema();
        JdbcTemplate target = new JdbcTemplate(targetDataSource);
        seedDivergentTarget(target);
        GameSaveImportService targetService = new GameSaveImportService(
                targetDataSource, objectMapper, mock(PersonProfileService.class));
        GameSaveImportService.ImportPlan plan = targetService.prepare(save);
        new TransactionTemplate(new DataSourceTransactionManager(targetDataSource))
                .executeWithoutResult(status -> targetService.apply(plan, false));
        targetService.alignGeneratorsAfterCommit(plan);

        assertThat(target.queryForObject(
                "SELECT COUNT(*) FROM MATCH_PLAN WHERE FIXTURE_KEY = 'TARGET:STALE'", Integer.class)).isZero();
        assertSemanticRowsEqual(source, target, "MATCH_PLAN",
                "FIXTURE_KEY, SEED, ALGORITHM_VERSION, HOME_TEAM_ID, AWAY_TEAM_ID, "
                        + "HOME_SCORE90, AWAY_SCORE90, HOME_SCOREET, AWAY_SCOREET, "
                        + "HOME_SHOOTOUT, AWAY_SHOOTOUT, STATUS",
                "FIXTURE_KEY = '" + FIXTURE + "'", "FIXTURE_KEY");
        assertSemanticRowsEqual(source, target, "MATCH_PLAN_GOAL_SLOT",
                "SLOT_INDEX, TEAM_ID, GOAL_MINUTE, PHASE, GOAL_TYPE, SCORER_ID, ASSIST_ID, RESOLVED",
                "MATCH_PLAN_ID = 9000", "SLOT_INDEX");
        assertSemanticRowsEqual(source, target, "MATCH_PARTICIPANT",
                "TEAM_ID, PLAYER_ID, PARTICIPANT_INDEX, NAME, POSITION, STARTER, RATING, FITNESS, "
                        + "FINISHING, PASSING, VISION, PENALTY_TAKER, FREE_KICK_TAKER",
                "MATCH_PLAN_ID = 9000", "TEAM_ID, PARTICIPANT_INDEX");
        assertSemanticRowsEqual(source, target, "MATCH_SUBSTITUTION",
                "TEAM_ID, SUB_INDEX, SUB_MINUTE, OFF_PLAYER_ID, ON_PLAYER_ID",
                "MATCH_PLAN_ID = 9000", "TEAM_ID, SUB_INDEX");
        assertSemanticRowsEqual(source, target, "MATCH_APPEARANCE",
                "TEAM_ID, PLAYER_ID, START_MINUTE, EXIT_MINUTE, MINUTES_PLAYED",
                "MATCH_PLAN_ID = 9000", "TEAM_ID, PLAYER_ID");
        assertSemanticRowsEqual(source, target, "MATCH_ANIMATION_RECIPE",
                "FIXTURE_KEY, SLOT_INDEX, GENERATOR_VERSION, EVENT_MINUTE, CAST(RECIPE_JSON AS VARCHAR) RECIPE_JSON",
                "FIXTURE_KEY = '" + FIXTURE + "'", "SLOT_INDEX");
        assertSemanticRowsEqual(source, target, "LIVE_COMMIT_CONTEXT",
                "LIVE_KEY, MATCH_ROW_ID, HOME_TACTIC, AWAY_TACTIC, HOME_POWER, AWAY_POWER, "
                        + "KNOCKOUT, LEG_NUMBER, TIE_ID, MATCH_INDEX, CHECKPOINT_MINUTE, "
                        + "RED_CARD_PLAYER_IDS, CHECKPOINT_RANDOM_STATE, "
                        + "CAST(CHECKPOINT_JSON AS VARCHAR) CHECKPOINT_JSON",
                "LIVE_KEY = 'REGENT:LIVE'", "LIVE_KEY");

        List<Map<String, Object>> sameMinuteSlots = target.queryForList(
                "SELECT SLOT_INDEX, GOAL_MINUTE FROM MATCH_PLAN_GOAL_SLOT "
                        + "WHERE MATCH_PLAN_ID = 9000 ORDER BY SLOT_INDEX");
        assertThat(sameMinuteSlots).extracting(row -> ((Number) row.get("SLOT_INDEX")).intValue())
                .containsExactly(3, 7);
        assertThat(sameMinuteSlots).extracting(row -> ((Number) row.get("GOAL_MINUTE")).intValue())
                .containsOnly(42);

        // These are the exact durable inputs consumed by animation replay and
        // cold live continuation; target-local divergent payloads cannot survive.
        assertThat(target.queryForObject(
                "SELECT CAST(RECIPE_JSON AS VARCHAR) FROM MATCH_ANIMATION_RECIPE "
                        + "WHERE FIXTURE_KEY = ? AND SLOT_INDEX = 7", String.class, FIXTURE))
                .isEqualTo("{\"source\":\"slot-7\",\"version\":1}");
        assertThat(target.queryForMap(
                "SELECT CHECKPOINT_MINUTE, CHECKPOINT_RANDOM_STATE, CAST(CHECKPOINT_JSON AS VARCHAR) CHECKPOINT_JSON "
                        + "FROM LIVE_COMMIT_CONTEXT WHERE LIVE_KEY = 'REGENT:LIVE'"))
                .containsEntry("CHECKPOINT_MINUTE", 67)
                .containsEntry("CHECKPOINT_RANDOM_STATE", 987654321L)
                .containsEntry("CHECKPOINT_JSON", "{\"source\":\"checkpoint\",\"minute\":67}");
    }

    private JdbcDataSource cloneEmptySchema() throws Exception {
        JdbcDataSource target = new JdbcDataSource();
        target.setURL("jdbc:h2:mem:regent-cross-instance-target-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        target.setUser("sa");
        try (Connection sourceConnection = sourceDataSource.getConnection();
             Statement script = sourceConnection.createStatement();
             ResultSet statements = script.executeQuery("SCRIPT NODATA");
             Connection targetConnection = target.getConnection();
             Statement targetStatement = targetConnection.createStatement()) {
            while (statements.next()) {
                String sql = statements.getString(1);
                if (!sql.stripLeading().toUpperCase().startsWith("SET DB_CLOSE_DELAY")) {
                    targetStatement.execute(sql);
                }
            }
        }
        return target;
    }

    private static void seedCanonicalSource(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO MATCH_PLAN
                    (ID, FIXTURE_KEY, SEED, ALGORITHM_VERSION, HOME_TEAM_ID, AWAY_TEAM_ID,
                     HOME_SCORE90, AWAY_SCORE90, HOME_SCOREET, AWAY_SCOREET,
                     HOME_SHOOTOUT, AWAY_SHOOTOUT, STATUS)
                VALUES (9000, ?, 123456789, 'canonical-v1', 1, 2, 2, 0, -1, -1, -1, -1, 'IN_PROGRESS')
                """, FIXTURE);
        jdbc.update("""
                INSERT INTO MATCH_PLAN_GOAL_SLOT
                    (ID, MATCH_PLAN_ID, SLOT_INDEX, TEAM_ID, GOAL_MINUTE, PHASE,
                     GOAL_TYPE, SCORER_ID, ASSIST_ID, RESOLVED)
                VALUES
                    (9100, 9000, 3, 1, 42, 'REGULAR_TIME', 'OPEN_PLAY', 101, 102, TRUE),
                    (9101, 9000, 7, 1, 42, 'REGULAR_TIME', 'OPEN_PLAY', 101, NULL, TRUE)
                """);
        jdbc.update("""
                INSERT INTO MATCH_PARTICIPANT
                    (ID, MATCH_PLAN_ID, TEAM_ID, PLAYER_ID, PARTICIPANT_INDEX, NAME, POSITION,
                     STARTER, RATING, FITNESS, FINISHING, PASSING, VISION, PENALTY_TAKER, FREE_KICK_TAKER)
                VALUES
                    (9200, 9000, 1, 101, 0, 'Source starter', 'ST', TRUE, 150, 92, 18, 14, 13, TRUE, FALSE),
                    (9201, 9000, 1, 102, 1, 'Source substitute', 'ST', FALSE, 140, 88, 16, 12, 11, FALSE, FALSE),
                    (9202, 9000, 2, 201, 0, 'Away starter', 'GK', TRUE, 145, 90, 3, 10, 9, FALSE, FALSE)
                """);
        jdbc.update("""
                INSERT INTO MATCH_SUBSTITUTION
                    (ID, MATCH_PLAN_ID, TEAM_ID, SUB_INDEX, SUB_MINUTE, OFF_PLAYER_ID, ON_PLAYER_ID)
                VALUES (9300, 9000, 1, 0, 60, 101, 102)
                """);
        jdbc.update("""
                INSERT INTO MATCH_APPEARANCE
                    (ID, MATCH_PLAN_ID, TEAM_ID, PLAYER_ID, START_MINUTE, EXIT_MINUTE, MINUTES_PLAYED)
                VALUES
                    (9400, 9000, 1, 101, 0, 60, 60),
                    (9401, 9000, 1, 102, 60, NULL, 30),
                    (9402, 9000, 2, 201, 0, NULL, 90)
                """);
        jdbc.update("""
                INSERT INTO MATCH_ANIMATION_RECIPE
                    (ID, FIXTURE_KEY, SLOT_INDEX, GENERATOR_VERSION, EVENT_MINUTE, RECIPE_JSON)
                VALUES
                    (9500, ?, 3, 1, 42, '{"source":"slot-3","version":1}'),
                    (9501, ?, 7, 1, 42, '{"source":"slot-7","version":1}')
                """, FIXTURE, FIXTURE);
        jdbc.update("""
                INSERT INTO LIVE_COMMIT_CONTEXT
                    (ID, LIVE_KEY, MATCH_ROW_ID, HOME_TACTIC, AWAY_TACTIC, HOME_POWER, AWAY_POWER,
                     KNOCKOUT, LEG_NUMBER, TIE_ID, MATCH_INDEX, CHECKPOINT_MINUTE,
                     RED_CARD_PLAYER_IDS, CHECKPOINT_RANDOM_STATE, CHECKPOINT_JSON)
                VALUES
                    (9600, 'REGENT:LIVE', 777, '4-4-2', '4-3-3', 1.25, 1.15,
                     TRUE, 2, 88, 4, 67, '201', 987654321,
                     '{"source":"checkpoint","minute":67}')
                """);
    }

    private static void seedDivergentTarget(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO MATCH_PLAN
                    (ID, FIXTURE_KEY, SEED, ALGORITHM_VERSION, HOME_TEAM_ID, AWAY_TEAM_ID,
                     HOME_SCORE90, AWAY_SCORE90, HOME_SCOREET, AWAY_SCOREET,
                     HOME_SHOOTOUT, AWAY_SHOOTOUT, STATUS)
                VALUES (1, 'TARGET:STALE', 1, 'stale', 9, 10, 0, 0, -1, -1, -1, -1, 'PLANNED')
                """);
        jdbc.update("""
                INSERT INTO MATCH_PLAN_GOAL_SLOT
                    (ID, MATCH_PLAN_ID, SLOT_INDEX, TEAM_ID, GOAL_MINUTE, PHASE,
                     GOAL_TYPE, SCORER_ID, ASSIST_ID, RESOLVED)
                VALUES (1, 1, 0, 9, 1, 'REGULAR_TIME', 'OPEN_PLAY', NULL, NULL, FALSE)
                """);
        jdbc.update("""
                INSERT INTO MATCH_ANIMATION_RECIPE
                    (ID, FIXTURE_KEY, SLOT_INDEX, GENERATOR_VERSION, EVENT_MINUTE, RECIPE_JSON)
                VALUES (1, 'TARGET:STALE', 0, 99, 1, '{"target":"stale"}')
                """);
    }

    private static void assertSemanticRowsEqual(JdbcTemplate source, JdbcTemplate target,
                                                String table, String columns,
                                                String where, String orderBy) {
        String sql = "SELECT " + columns + " FROM " + table + " WHERE " + where + " ORDER BY " + orderBy;
        assertThat(target.queryForList(sql)).as(table).isEqualTo(source.queryForList(sql));
    }
}
