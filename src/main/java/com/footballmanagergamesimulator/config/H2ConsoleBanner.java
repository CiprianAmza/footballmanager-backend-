package com.footballmanagergamesimulator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * DEV-ONLY. Prints the live H2 console connection details once the app is up.
 *
 * <p>The local datasource URL is {@code jdbc:h2:mem:${random.uuid}}, so the
 * database name changes on every boot and cannot be typed into the console
 * form from memory. This reads the URL back off a real connection — whatever
 * it turned out to be — and logs it ready to copy.
 *
 * <p>Gated on the same flag as the console servlet itself, so a production
 * boot never instantiates this class.
 */
@Component
@ConditionalOnProperty(name = "spring.h2.console.enabled", havingValue = "true")
public class H2ConsoleBanner {

    private static final Logger log = LoggerFactory.getLogger(H2ConsoleBanner.class);

    private final DataSource dataSource;
    private final String port;
    private final String consolePath;
    private final String username;

    public H2ConsoleBanner(DataSource dataSource,
                           @Value("${server.port:8080}") String port,
                           @Value("${spring.h2.console.path:/h2-console}") String consolePath,
                           @Value("${spring.datasource.username:sa}") String username) {
        this.dataSource = dataSource;
        this.port = port;
        this.consolePath = consolePath;
        this.username = username;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logConnectionDetails() {
        String url;
        try (Connection connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL();
        } catch (SQLException exception) {
            log.warn("H2 console is enabled but the datasource URL could not be read", exception);
            return;
        }
        log.info("""

                ┌───────────────────────────────────────────────────────────────
                │ H2 console  http://localhost:{}{}
                │ JDBC URL    {}
                │ User        {}          Password  (empty)
                └───────────────────────────────────────────────────────────────""",
                port, consolePath, url, username);
    }
}
