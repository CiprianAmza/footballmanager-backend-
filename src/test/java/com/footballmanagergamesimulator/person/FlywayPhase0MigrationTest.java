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
            assertThat(count(statement, "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '2' AND \"success\" = TRUE")).isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '5' AND \"success\" = TRUE")).isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '6' AND \"success\" = TRUE")).isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM person_profile")).isZero();
            assertThat(count(statement, "SELECT COUNT(*) FROM asset_catalog_item")).isEqualTo(8);
            assertThat(count(statement, "SELECT COUNT(*) FROM asset_catalog_item WHERE asset_type='APARTMENT' AND apartment_rooms BETWEEN 1 AND 4")).isEqualTo(4);
            assertThat(count(statement, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='HUMAN' AND COLUMN_NAME='STAY_FORWARD'")).isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='MARKET_INSTRUMENT' AND COLUMN_NAME IN ('RISK_CLASS', 'RISK_CONFIG_VERSION')")).isEqualTo(2);
            assertThat(count(statement, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN ('TRADER_ADVISER_CONTRACT', 'TRADER_ADVICE_RECOMMENDATION')")).isEqualTo(2);
            assertThat(count(statement, "SELECT COUNT(*) FROM market_instrument WHERE code='MEDIA11' AND risk_class='SPECULATIVE' AND risk_config_version='risk-v1'")).isEqualTo(1);
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
            assertThat(count(statement, "SELECT COUNT(*) FROM personal_account")).isEqualTo(4);
            assertThat(count(statement, "SELECT COUNT(*) FROM personal_account a WHERE a.cash_balance <> COALESCE((SELECT SUM(l.signed_amount) FROM personal_ledger_entry l WHERE l.account_id=a.id), 0)")).isZero();
            assertThat(count(statement, "SELECT COUNT(*) FROM human WHERE stay_forward = FALSE")).isEqualTo(2);
        }
    }

    @Test
    void v5StayForwardMigrationBackfillsOnlyStableCanonicalSeedIdentitiesOnce() throws Exception {
        String url = url();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE human (
                        id BIGINT PRIMARY KEY,
                        name VARCHAR(255),
                        team_id BIGINT,
                        type_id BIGINT,
                        position VARCHAR(10),
                        age INTEGER,
                        season_created BIGINT,
                        rating DOUBLE PRECISION,
                        retired BOOLEAN
                    )
                    """);
            statement.execute("""
                    INSERT INTO human(id, name, team_id, type_id, position, age, season_created, rating, retired) VALUES
                    (107, 'Kvekrpur', 14, 1, 'ST', 34, 1, 367.6487908232764, FALSE),
                    (108, 'Dostoievski', 14, 1, 'ST', 29, 7, 342.25, FALSE),
                    (4060, 'Shakespeare', 13, 1, 'ST', 25, 6, 300, FALSE),
                    (759, 'Kvekrpur', 22, 1, 'ST', 26, 4, 149.85, FALSE),
                    (4061, 'Shakespeare', 13, 1, 'MC', 25, 6, 300, FALSE),
                    (1080, 'Dostoievski', 14, 4, 'ST', 29, 7, 342.25, FALSE)
                    """);
        }

        migrate(url);
        migrate(url);

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT COUNT(*) FROM human WHERE stay_forward = TRUE")).isEqualTo(3);
            assertThat(count(statement, "SELECT COUNT(*) FROM human WHERE id IN (107, 108, 4060) AND stay_forward = TRUE")).isEqualTo(3);
            assertThat(count(statement, "SELECT COUNT(*) FROM human WHERE id IN (759, 4061, 1080) AND stay_forward = FALSE")).isEqualTo(3);
        }
    }

    @Test
    void v5StayForwardMigrationFallsBackWhenLegacyIdentityColumnsAreAbsent() throws Exception {
        String url = url();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE human (
                        id BIGINT PRIMARY KEY,
                        name VARCHAR(255),
                        type_id BIGINT,
                        retired BOOLEAN
                    )
                    """);
            statement.execute("""
                    INSERT INTO human(id, name, type_id, retired) VALUES
                    (107, 'Kvekrpur', 1, FALSE),
                    (108, 'Dostoievski', 1, FALSE),
                    (4060, 'Shakespeare', 1, FALSE)
                    """);
        }

        migrate(url);
        migrate(url);

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='HUMAN' AND COLUMN_NAME='STAY_FORWARD'")).isEqualTo(1);
            assertThat(count(statement, "SELECT COUNT(*) FROM human WHERE stay_forward = TRUE")).isZero();
            assertThat(count(statement, "SELECT COUNT(*) FROM human WHERE stay_forward = FALSE")).isEqualTo(3);
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
