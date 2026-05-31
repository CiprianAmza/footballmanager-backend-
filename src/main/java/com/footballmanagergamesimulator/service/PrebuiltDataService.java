package com.footballmanagergamesimulator.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Fast cold-start via a full H2 snapshot, for testing. The first generation can be dumped to a single
 * SQL file ({@code SCRIPT TO}); later boots restore it ({@code DROP ALL OBJECTS} + {@code RUNSCRIPT})
 * instead of re-running the (slow) procedural generation. The dump captures the WHOLE database — every
 * table, row, sequence value and FK — so it is robust to schema changes in the entity model as long as
 * the snapshot is regenerated after such a change (set {@code bootstrap.rebuild-pre-built-data=true} or
 * delete the file).
 *
 * <p>Toggle with {@code bootstrap.use-pre-built-data=true}. Path is {@code bootstrap.snapshot-path}
 * (default {@code prebuilt-data.sql} in the working dir). Defaults live here in Java so no committed
 * {@code application.yml} change is required.
 */
@Service
public class PrebuiltDataService {

    @Value("${bootstrap.snapshot-path:prebuilt-data.sql}")
    private String snapshotPath;

    private final DataSource dataSource;

    public PrebuiltDataService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Absolute snapshot file location (resolved against the working dir). */
    public File snapshotFile() {
        return new File(snapshotPath).getAbsoluteFile();
    }

    public boolean snapshotExists() {
        return snapshotFile().isFile();
    }

    /** Dump the entire current database to the snapshot file (schema + data + sequences). */
    public void save() {
        String path = sqlLiteral(snapshotFile().getPath());
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("SCRIPT TO " + path);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write pre-built data snapshot to " + snapshotPath, e);
        }
    }

    /** Wipe the (empty, Hibernate-created) schema and rebuild it + its data from the snapshot file. */
    public void restore() {
        String path = sqlLiteral(snapshotFile().getPath());
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("RUNSCRIPT FROM " + path);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to restore pre-built data snapshot from " + snapshotPath, e);
        }
    }

    /** Render a filesystem path as a safe single-quoted SQL string literal. */
    private static String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
