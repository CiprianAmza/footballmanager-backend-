package com.footballmanagergamesimulator.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayPhase0CrossDatabaseIT {

    @Test
    void migratesAndBackfillsLegacyPostgreSql() throws Exception {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")) {
            database.start();
            verifyLegacyMigration(database.getJdbcUrl(), database.getUsername(), database.getPassword(),
                    "classpath:db/migration/postgresql", "PostgreSQL");
        }
    }

    @Test
    void migratesAndBackfillsLegacyMySql() throws Exception {
        try (MySQLContainer<?> database = new MySQLContainer<>("mysql:8.0")) {
            database.start();
            verifyLegacyMigration(database.getJdbcUrl(), database.getUsername(), database.getPassword(),
                    "classpath:db/migration/mysql", "MySQL");
        }
    }

    private void verifyLegacyMigration(String url, String username, String password,
                                       String location, String expectedProduct) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).containsIgnoringCase(expectedProduct);
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, username VARCHAR(64), manager_id BIGINT)");
            statement.execute("CREATE TABLE human (id BIGINT PRIMARY KEY, name VARCHAR(255), "
                    + "type_id BIGINT, retired BOOLEAN)");
            statement.executeUpdate("INSERT INTO users(id, username, manager_id) VALUES "
                    + "(1, 'manager', 10), (2, 'chair', NULL), (3, 'duplicate-link', 10)");
            statement.executeUpdate("INSERT INTO human(id, name, type_id, retired) VALUES "
                    + "(10, 'Manager Human', 4, FALSE), (11, 'AI Player', 1, FALSE)");
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations(location)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT COUNT(*) FROM person_profile")).isEqualTo(4);
            assertThat(count(statement, "SELECT COUNT(*) FROM person_profile WHERE user_id=1 AND human_id=10"))
                    .isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM person_profile WHERE user_id=2 AND human_id IS NULL"))
                    .isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM person_profile WHERE user_id=3 AND human_id IS NULL"))
                    .isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM person_profile WHERE human_id=11 AND user_id IS NULL"))
                    .isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM users WHERE password LIKE '$2%' AND email IS NOT NULL"))
                    .isEqualTo(3);
            assertThat(count(statement, "SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE version='1' AND success=TRUE")).isEqualTo(1);
        }
    }

    private long count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
