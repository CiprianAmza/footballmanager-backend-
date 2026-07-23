package com.footballmanagergamesimulator.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-save-manifest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GameSaveManifestCoverageTest {

    @Autowired private DataSource dataSource;

    @Test
    void everyPersistedEntityTableHasExactlyOneVersionedDisposition() throws Exception {
        Set<String> actual = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet tables = connection.getMetaData().getTables(null, "PUBLIC", null,
                     new String[]{"TABLE"})) {
            while (tables.next()) actual.add(tables.getString("TABLE_NAME").toUpperCase());
        }

        Set<String> preservedInstallationState = Set.of("USERS", "PERSON_PROFILE");
        Set<String> expected = new HashSet<>(GameSaveImportService.manifestTableNames());
        expected.addAll(preservedInstallationState);

        assertThat(GameSaveImportService.manifestTableNames()).hasSize(80);
        assertThat(GameSaveImportService.manifestKeys()).hasSize(80).doesNotHaveDuplicates();
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
