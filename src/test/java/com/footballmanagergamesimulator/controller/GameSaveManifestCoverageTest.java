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

        // Read the preserved set from the service instead of restating it here: a local
        // copy silently went stale when the multiplayer room tables were added, so the
        // test passed while /game/export failed on the very check it is meant to mirror.
        Set<String> disposed = new HashSet<>(GameSaveImportService.manifestTableNames());
        disposed.addAll(GameSaveImportService.preservedTableNames());

        assertThat(GameSaveImportService.manifestTableNames()).hasSize(87);
        assertThat(GameSaveImportService.manifestKeys()).hasSize(87).doesNotHaveDuplicates();
        // The export-time invariant: no live table may lack a disposition.
        assertThat(actual).isSubsetOf(disposed);
        // Flyway is disabled here, so FLYWAY_SCHEMA_HISTORY is legitimately absent; every
        // manifest table, however, must be a real table.
        assertThat(actual).containsAll(GameSaveImportService.manifestTableNames());
    }
}
