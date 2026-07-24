package com.footballmanagergamesimulator.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrebuiltDataServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    void oldSnapshotRestoreAddsStayForwardAndBackfillsStableCanonicalIdentitiesIdempotently()
            throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Path snapshot = tempDir.resolve("old-prebuilt-data.sql");
        Files.writeString(snapshot, auxiliarySnapshotTables() + """
                CREATE TABLE HUMAN (
                    ID BIGINT PRIMARY KEY,
                    NAME VARCHAR(255),
                    TEAM_ID BIGINT,
                    TYPE_ID BIGINT,
                    POSITION VARCHAR(10),
                    AGE INTEGER,
                    SEASON_CREATED BIGINT,
                    RATING DOUBLE PRECISION,
                    RETIRED BOOLEAN
                );
                INSERT INTO HUMAN(ID, NAME, TEAM_ID, TYPE_ID, POSITION, AGE, SEASON_CREATED, RATING, RETIRED) VALUES
                    (107, 'Kvekrpur', 14, 1, 'ST', 34, 1, 367.6487908232764, FALSE),
                    (108, 'Dostoievski', 14, 1, 'ST', 29, 7, 342.25, FALSE),
                    (4060, 'Shakespeare', 13, 1, 'ST', 25, 6, 300, FALSE),
                    (759, 'Kvekrpur', 22, 1, 'ST', 26, 4, 149.85, FALSE),
                    (4061, 'Shakespeare', 13, 1, 'MC', 25, 6, 300, FALSE),
                    (1080, 'Dostoievski', 14, 4, 'ST', 29, 7, 342.25, FALSE);
                """);

        PrebuiltDataService service = new PrebuiltDataService(dataSource);
        ReflectionTestUtils.setField(service, "snapshotPath", snapshot.toString());

        service.restore();
        service.restore();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'HUMAN' AND COLUMN_NAME = 'STAY_FORWARD' AND IS_NULLABLE = 'NO'
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM HUMAN WHERE STAY_FORWARD IS NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM HUMAN WHERE STAY_FORWARD = TRUE", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM HUMAN
                WHERE ID IN (107, 108, 4060) AND STAY_FORWARD = TRUE
                """, Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM HUMAN
                WHERE ID IN (759, 4061, 1080) AND STAY_FORWARD = FALSE
                """, Long.class)).isEqualTo(3L);
    }

    @Test
    void oldSnapshotRestoreFallsBackWhenIdentityColumnsAreAbsent() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Path snapshot = tempDir.resolve("old-prebuilt-data-without-identity-columns.sql");
        Files.writeString(snapshot, auxiliarySnapshotTables() + """
                CREATE TABLE HUMAN (
                    ID BIGINT PRIMARY KEY,
                    NAME VARCHAR(255),
                    RETIRED BOOLEAN
                );
                INSERT INTO HUMAN(ID, NAME, RETIRED) VALUES
                    (107, 'Kvekrpur', FALSE),
                    (108, 'Dostoievski', FALSE),
                    (4060, 'Shakespeare', FALSE);
                """);

        PrebuiltDataService service = new PrebuiltDataService(dataSource);
        ReflectionTestUtils.setField(service, "snapshotPath", snapshot.toString());

        service.restore();
        service.restore();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'HUMAN' AND COLUMN_NAME = 'STAY_FORWARD'
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM HUMAN WHERE STAY_FORWARD = TRUE", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM HUMAN WHERE STAY_FORWARD = FALSE", Long.class)).isEqualTo(3L);
    }

    private static String auxiliarySnapshotTables() {
        return """
                CREATE TABLE TEAM (ID BIGINT PRIMARY KEY);
                CREATE TABLE SCORER (ID BIGINT PRIMARY KEY);
                CREATE TABLE PLAYER_SEASON_STAT (ID BIGINT PRIMARY KEY);
                CREATE TABLE COMPETITION_TEAM_INFO_DETAIL (ID BIGINT PRIMARY KEY);
                CREATE TABLE COMPETITION_TEAM_INFO_MATCH (ID BIGINT PRIMARY KEY);
                CREATE TABLE MATCH_PLAYER_RATING (ID BIGINT PRIMARY KEY);
                """;
    }
}
