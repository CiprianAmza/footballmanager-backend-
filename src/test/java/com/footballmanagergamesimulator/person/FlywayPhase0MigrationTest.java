package com.footballmanagergamesimulator.person;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayPhase0MigrationTest {

    @Test
    void migratesCleanH2Schema() throws Exception {
        String url = url();
        migrate(url);

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '1' AND \"success\" = TRUE")).isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM person_profile")).isZero();
        }
    }

    @Test
    void upgradesLegacyH2AndBackfillsExactlyOneProfilePerIdentity() throws Exception {
        String url = url();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id INT PRIMARY KEY, username VARCHAR(64), manager_id BIGINT)");
            statement.execute("CREATE TABLE human (id BIGINT PRIMARY KEY, name VARCHAR(255), type_id BIGINT, retired BOOLEAN)");
            statement.execute("INSERT INTO users(id, username, manager_id) VALUES "
                    + "(1, 'manager', 10), (2, 'chair', NULL), (3, 'duplicate-link', 10)");
            statement.execute("INSERT INTO human(id, name, type_id, retired) VALUES (10, 'Manager Human', 4, FALSE), (11, 'AI Player', 1, FALSE)");
        }

        migrate(url);
        migrate(url);

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT COUNT(*) FROM person_profile")).isEqualTo(4);
            assertThat(count(statement, "SELECT COUNT(*) FROM person_profile WHERE user_id=1 AND human_id=10")).isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM person_profile WHERE user_id=2 AND human_id IS NULL")).isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM person_profile WHERE user_id=3 AND human_id IS NULL")).isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM person_profile WHERE human_id=11 AND user_id IS NULL")).isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM users WHERE password LIKE '$2%' AND email IS NOT NULL")).isEqualTo(3);
        }
    }

    private void migrate(String url) {
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/h2")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
    }

    private long count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String url() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }
}
