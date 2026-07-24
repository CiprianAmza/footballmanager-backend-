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
            migrateSnapshotSchema(st);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to restore pre-built data snapshot from " + snapshotPath, e);
        }
    }

    /**
     * The checked-in snapshot is test data, not a schema migration authority. Hibernate creates
     * the current schema before restore, but RUNSCRIPT replaces it with the schema embedded in the
     * snapshot. Keep small forward-compatible additions here so an older snapshot can still boot
     * after additive entity changes without regenerating 2.9 MB of deterministic seed data.
     */
    private static void migrateSnapshotSchema(Statement st) throws SQLException {
        st.execute("""
                ALTER TABLE TEAM
                ADD COLUMN IF NOT EXISTS LAST_MID_SEASON_MANAGER_CHANGE_SEASON
                INTEGER DEFAULT 0 NOT NULL
                """);

        st.execute("ALTER TABLE SCORER ADD COLUMN IF NOT EXISTS ROUND_NUMBER INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE PLAYER_SEASON_STAT ADD COLUMN IF NOT EXISTS CHANCES_CREATED DOUBLE DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE HUMAN ADD COLUMN IF NOT EXISTS ALWAYS_CONTINUE BOOLEAN DEFAULT FALSE NOT NULL");
        migrateStayForwardSnapshotSchema(st);

        st.execute("ALTER TABLE COMPETITION_TEAM_INFO_DETAIL ADD COLUMN IF NOT EXISTS WINNER_TEAM_ID INTEGER");
        st.execute("ALTER TABLE COMPETITION_TEAM_INFO_DETAIL ADD COLUMN IF NOT EXISTS DECIDED_BY VARCHAR(255)");
        st.execute("ALTER TABLE COMPETITION_TEAM_INFO_DETAIL ADD COLUMN IF NOT EXISTS LEG_NUMBER INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE COMPETITION_TEAM_INFO_DETAIL ADD COLUMN IF NOT EXISTS MATCH_INDEX INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE COMPETITION_TEAM_INFO_DETAIL ADD COLUMN IF NOT EXISTS MATCH_DAY INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE COMPETITION_TEAM_INFO_DETAIL ADD COLUMN IF NOT EXISTS TIE_ID BIGINT DEFAULT 0 NOT NULL");

        st.execute("ALTER TABLE COMPETITION_TEAM_INFO_MATCH ADD COLUMN IF NOT EXISTS MATCH_INDEX INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE COMPETITION_TEAM_INFO_MATCH ADD COLUMN IF NOT EXISTS MATCH_DAY INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE COMPETITION_TEAM_INFO_MATCH ADD COLUMN IF NOT EXISTS LEG_NUMBER INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE COMPETITION_TEAM_INFO_MATCH ADD COLUMN IF NOT EXISTS TIE_ID BIGINT DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE COMPETITION_TEAM_INFO_MATCH ADD COLUMN IF NOT EXISTS TEAM1_SCORE INTEGER DEFAULT -1 NOT NULL");
        st.execute("ALTER TABLE COMPETITION_TEAM_INFO_MATCH ADD COLUMN IF NOT EXISTS TEAM2_SCORE INTEGER DEFAULT -1 NOT NULL");
        st.execute("ALTER TABLE COMPETITION_TEAM_INFO_MATCH ADD COLUMN IF NOT EXISTS DISCIPLINE_PROCESSED BOOLEAN DEFAULT FALSE NOT NULL");

        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS POSITION_INDEX INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS FORMATION VARCHAR(255)");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS ROLE VARCHAR(255)");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS DUTY VARCHAR(255)");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS SUBSTITUTE BOOLEAN DEFAULT FALSE NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS PERFORMANCE_RATING DOUBLE DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS GOALS INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS ASSISTS INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS BASE_FACE_ID INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS SKIN_TONE INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS HAIR_STYLE INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS HAIR_COLOR INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS EYE_COLOR INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS FACE_SHAPE INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS NOSE_SHAPE INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS EYE_SHAPE INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS MOUTH_SHAPE INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS BROW_SHAPE INTEGER DEFAULT 0 NOT NULL");
        st.execute("ALTER TABLE MATCH_PLAYER_RATING ADD COLUMN IF NOT EXISTS SPECIES VARCHAR(20) DEFAULT 'human'");
    }

    private static void migrateStayForwardSnapshotSchema(Statement st) throws SQLException {
        st.execute("ALTER TABLE HUMAN ADD COLUMN IF NOT EXISTS STAY_FORWARD BOOLEAN");
        st.execute("""
                EXECUTE IMMEDIATE (
                    SELECT CASE WHEN COUNT(*) = 5 THEN
                        'UPDATE HUMAN SET STAY_FORWARD = TRUE WHERE STAY_FORWARD IS NULL AND ('
                        || '(ID = 107 AND NAME = ''Kvekrpur'' AND TEAM_ID = 14 AND TYPE_ID = 1 AND POSITION = ''ST'') OR '
                        || '(ID = 108 AND NAME = ''Dostoievski'' AND TEAM_ID = 14 AND TYPE_ID = 1 AND POSITION = ''ST'') OR '
                        || '(ID = 4060 AND NAME = ''Shakespeare'' AND TEAM_ID = 13 AND TYPE_ID = 1 AND POSITION = ''ST''))'
                    ELSE 'UPDATE HUMAN SET STAY_FORWARD = STAY_FORWARD WHERE 1 = 0' END
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_NAME = 'HUMAN'
                      AND COLUMN_NAME IN ('ID', 'NAME', 'TEAM_ID', 'TYPE_ID', 'POSITION')
                )
                """);
        st.execute("UPDATE HUMAN SET STAY_FORWARD = FALSE WHERE STAY_FORWARD IS NULL");
        st.execute("ALTER TABLE HUMAN ALTER COLUMN STAY_FORWARD SET DEFAULT FALSE");
        st.execute("ALTER TABLE HUMAN ALTER COLUMN STAY_FORWARD SET NOT NULL");
    }

    /** Render a filesystem path as a safe single-quoted SQL string literal. */
    private static String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
