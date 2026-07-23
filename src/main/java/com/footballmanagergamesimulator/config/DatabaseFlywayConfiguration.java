package com.footballmanagergamesimulator.config;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/** Selects the one Phase 0 migration tree matching the actual JDBC database. */
@Configuration
public class DatabaseFlywayConfiguration {

    @Bean
    FlywayConfigurationCustomizer databaseSpecificFlywayLocations(DataSource dataSource) {
        return configuration -> configuration.locations("classpath:db/migration/" + vendor(dataSource));
    }

    private String vendor(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (product.contains("h2")) return "h2";
            if (product.contains("postgresql")) return "postgresql";
            if (product.contains("mysql") || product.contains("mariadb")) return "mysql";
            throw new IllegalStateException("No REGENT Phase 0 Flyway migration for database "
                    + connection.getMetaData().getDatabaseProductName());
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot detect database vendor for Flyway", exception);
        }
    }
}
