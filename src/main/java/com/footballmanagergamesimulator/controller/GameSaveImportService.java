package com.footballmanagergamesimulator.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.person.PersonProfileService;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Builds an immutable, account-neutral import plan and proves it against an
 * isolated copy of the current H2 schema before the live database is touched.
 */
@Service
public class GameSaveImportService {

    static final int LEGACY_SAVE_VERSION = 5;
    static final int CURRENT_SAVE_VERSION = 6;

    private static final List<TableSpec> MANIFEST = List.of(
            new TableSpec("competitionTypes", "COMPETITION_TYPE", CURRENT_SAVE_VERSION),
            new TableSpec("humanTypes", "HUMAN_TYPE", CURRENT_SAVE_VERSION),
            new TableSpec("transferStrategies", "TRANSFER_STRATEGY", CURRENT_SAVE_VERSION),
            new TableSpec("rounds", "ROUND"),
            new TableSpec("competitions", "COMPETITION"),
            new TableSpec("teams", "TEAM"),
            new TableSpec("teamCompetitionRelations", "TEAM_COMPETITION_RELATION", CURRENT_SAVE_VERSION),
            new TableSpec("teamTransferStrategyRelations", "TEAM_TRANSFER_STRATEGY_RELATION", CURRENT_SAVE_VERSION),
            new TableSpec("teamFacilities", "TEAM_FACILITIES"),
            new TableSpec("stadiums", "STADIUM"),
            new TableSpec("gameCalendars", "GAME_CALENDAR"),
            new TableSpec("calendarEvents", "CALENDAR_EVENT"),
            new TableSpec("humans", "HUMAN"),
            new TableSpec("humanTeamRelations", "HUMAN_TEAM_RELATION", CURRENT_SAVE_VERSION),
            new TableSpec("playerSkills", "PLAYER_SKILLS"),
            new TableSpec("youthPlayers", "YOUTH_PLAYER"),
            new TableSpec("playerInteractions", "PLAYER_INTERACTION"),
            new TableSpec("competitionTeamInfos", "COMPETITION_TEAM_INFO"),
            new TableSpec("competitionTeamInfoDetails", "COMPETITION_TEAM_INFO_DETAIL"),
            new TableSpec("competitionTeamInfoMatches", "COMPETITION_TEAM_INFO_MATCH"),
            new TableSpec("teamCompetitionDetails", "TEAM_COMPETITION_DETAIL"),
            new TableSpec("competitionHistories", "COMPETITION_HISTORY"),
            new TableSpec("clubCoefficients", "CLUB_COEFFICIENT"),
            new TableSpec("scorers", "SCORER"),
            new TableSpec("scorerLeaderboard", "SCORER_LEADERBOARD_ENTRY"),
            new TableSpec("matchEvents", "MATCH_EVENT"),
            new TableSpec("matchStats", "MATCH_STATS"),
            new TableSpec("playerSeasonStats", "PLAYER_SEASON_STAT"),
            new TableSpec("matchPlayerRatings", "MATCH_PLAYER_RATING", CURRENT_SAVE_VERSION),
            new TableSpec("matchSquads", "MATCH_SQUAD", CURRENT_SAVE_VERSION),
            new TableSpec("matchPlans", "MATCH_PLAN", CURRENT_SAVE_VERSION),
            new TableSpec("matchPlanGoalSlots", "MATCH_PLAN_GOAL_SLOT", CURRENT_SAVE_VERSION),
            new TableSpec("matchParticipants", "MATCH_PARTICIPANT", CURRENT_SAVE_VERSION),
            new TableSpec("matchAppearances", "MATCH_APPEARANCE", CURRENT_SAVE_VERSION),
            new TableSpec("matchSubstitutions", "MATCH_SUBSTITUTION", CURRENT_SAVE_VERSION),
            new TableSpec("matchAnimationRecipes", "MATCH_ANIMATION_RECIPE", CURRENT_SAVE_VERSION),
            new TableSpec("liveCommitContexts", "LIVE_COMMIT_CONTEXT", CURRENT_SAVE_VERSION),
            new TableSpec("predeterminedScores", "PREDETERMINED_SCORE", CURRENT_SAVE_VERSION),
            new TableSpec("transfers", "TRANSFER"),
            new TableSpec("transferOffers", "TRANSFER_OFFER"),
            new TableSpec("loans", "LOAN"),
            new TableSpec("adminPlayerMovements", "ADMIN_PLAYER_MOVEMENT"),
            new TableSpec("injuries", "INJURY"),
            new TableSpec("suspensions", "SUSPENSION"),
            new TableSpec("sponsorships", "SPONSORSHIP"),
            new TableSpec("boardRequests", "BOARD_REQUEST"),
            new TableSpec("facilityUpgrades", "FACILITY_UPGRADE"),
            new TableSpec("awards", "AWARD"),
            new TableSpec("awardOverrides", "AWARD_OVERRIDE"),
            new TableSpec("seasonObjectives", "SEASON_OBJECTIVE"),
            new TableSpec("managerHistories", "MANAGER_HISTORY"),
            new TableSpec("managerInbox", "MANAGER_INBOX"),
            new TableSpec("pressConferences", "PRESS_CONFERENCE"),
            new TableSpec("nationalTeamCallups", "NATIONAL_TEAM_CALLUP"),
            new TableSpec("trainingSchedules", "TRAINING_SCHEDULE"),
            new TableSpec("personalizedTactics", "PERSONALIZED_TACTIC"),
            new TableSpec("teamPlayerHistorical", "TEAM_PLAYER_HISTORICAL_RELATION"),
            new TableSpec("financialRecords", "FINANCIAL_RECORD"),
            new TableSpec("friendlyMatches", "FRIENDLY_MATCH", CURRENT_SAVE_VERSION),
            new TableSpec("jobOffers", "JOB_OFFER", CURRENT_SAVE_VERSION),
            new TableSpec("scouts", "SCOUT", CURRENT_SAVE_VERSION),
            new TableSpec("scoutAssignments", "SCOUT_ASSIGNMENT", CURRENT_SAVE_VERSION),
            new TableSpec("shortlists", "SHORTLIST", CURRENT_SAVE_VERSION),
            new TableSpec("assets", "ASSET", CURRENT_SAVE_VERSION),
            new TableSpec("clubShareholdings", "CLUB_SHAREHOLDING", CURRENT_SAVE_VERSION),
            new TableSpec("ownerships", "OWNERSHIP", CURRENT_SAVE_VERSION),
            new TableSpec("coachPermissions", "COACH_PERMISSIONS", CURRENT_SAVE_VERSION)
    );

    /** Account/security rows and migration metadata are installation state, never save state. */
    private static final Set<String> PRESERVED_TABLES = Set.of(
            "USERS", "PERSON_PROFILE", "FLYWAY_SCHEMA_HISTORY");

    private static final List<NamedSequence> NAMED_SEQUENCES = List.of(
            new NamedSequence("CTI_SEQ", "COMPETITION_TEAM_INFO"),
            new NamedSequence("SCORER_SEQ", "SCORER"),
            new NamedSequence("PLAYER_SKILLS_SEQ", "PLAYER_SKILLS"),
            new NamedSequence("TPHR_SEQ", "TEAM_PLAYER_HISTORICAL_RELATION")
    );

    private static final Map<String, String> GLOBAL_COLUMN_OVERRIDES = Map.of(
            "substitute", "IS_SUBSTITUTE");

    private static final Map<String, Map<String, String>> TABLE_COLUMN_OVERRIDES = Map.ofEntries(
            Map.entry("AWARD", Map.of("value", "AWARD_VALUE")),
            Map.entry("BOARD_REQUEST", Map.of("day", "REQUEST_DAY", "status", "REQUEST_STATUS")),
            Map.entry("CALENDAR_EVENT", Map.of("day", "EVENT_DAY", "status", "EVENT_STATUS")),
            Map.entry("COMPETITION_TEAM_INFO_MATCH", Map.of(
                    "day", "MATCH_DAY", "team1Score", "TEAM1_SCORE", "team2Score", "TEAM2_SCORE")),
            Map.entry("COMPETITION_TEAM_INFO_DETAIL", Map.of("day", "MATCH_DAY")),
            Map.entry("FINANCIAL_RECORD", Map.of("day", "RECORD_DAY")),
            Map.entry("MATCH_EVENT", Map.of("minute", "EVENT_MINUTE")),
            Map.entry("PLAYER_INTERACTION", Map.of("day", "INTERACTION_DAY")),
            Map.entry("PRESS_CONFERENCE", Map.of("day", "CONFERENCE_DAY")),
            Map.entry("HUMAN", Map.of("isRetired", "RETIRED", "retired", "RETIRED")),
            Map.entry("SCORER_LEADERBOARD_ENTRY", Map.of("isActive", "IS_ACTIVE", "active", "IS_ACTIVE")),
            Map.entry("MANAGER_INBOX", Map.of("isRead", "IS_READ", "read", "IS_READ"))
    );

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final PersonProfileService personProfileService;

    public GameSaveImportService(DataSource dataSource,
                                 ObjectMapper objectMapper,
                                 PersonProfileService personProfileService) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.personProfileService = personProfileService;
    }

    static List<String> manifestKeys() {
        return MANIFEST.stream().map(TableSpec::jsonKey).toList();
    }

    static Set<String> manifestTableNames() {
        Set<String> tables = new java.util.TreeSet<>();
        MANIFEST.forEach(spec -> tables.add(spec.tableName()));
        return Set.copyOf(tables);
    }

    /**
     * Parses and migrates v5/v6 into a complete immutable game-only plan. The
     * legacy users/personProfiles sections are deliberately never part of it.
     */
    public ImportPlan prepare(Map<String, Object> save) {
        if (save == null) throw invalid("payload is required");
        int version = parseVersion(save.get("saveVersion"));
        validateLegacyIdentitySections(save);

        Connection live = DataSourceUtils.getConnection(dataSource);
        try {
            requireH2(live);
            validateSchemaDisposition(live);
            Map<String, Set<String>> schemaColumns = readSchemaColumns(live);
            List<TableRows> tables = new ArrayList<>();
            for (TableSpec spec : MANIFEST) {
                Object rawSection = save.get(spec.jsonKey());
                List<?> rows;
                if (version < spec.introducedVersion()) {
                    // V5 predates every canonical/additional V6 section. Its
                    // deterministic migration is an empty source set, which
                    // explicitly removes divergent target rows during apply.
                    rows = List.of();
                } else if (rawSection instanceof List<?> list) {
                    rows = list;
                } else {
                    throw invalid("missing or non-list manifest section '" + spec.jsonKey() + "'");
                }
                if (("rounds".equals(spec.jsonKey()) || "gameCalendars".equals(spec.jsonKey()))
                        && rows.isEmpty()) {
                    throw invalid("manifest section '" + spec.jsonKey() + "' must not be empty");
                }
                Set<String> columns = schemaColumns.get(spec.tableName());
                if (columns == null || columns.isEmpty()) {
                    throw invalid("current H2 schema is missing table " + spec.tableName());
                }
                tables.add(new TableRows(spec, parseRows(spec, rows, columns)));
            }

            ImportPlan parsed = new ImportPlan(version, List.copyOf(tables), List.of());
            validateAccountCompatibility(live, parsed);
            List<GeneratorReset> generators = validateOnIsolatedH2(live, parsed);
            return new ImportPlan(version, parsed.tables(), generators);
        } catch (SQLException exception) {
            throw invalid("H2 preflight failed: " + rootMessage(exception), exception);
        } finally {
            DataSourceUtils.releaseConnection(live, dataSource);
        }
    }

    /**
     * Exports every V6 world-state table that is not already emitted by the
     * legacy controller contract. Rows use physical column names so no entity
     * or animation implementation has to be modified merely to make save state
     * exhaustive.
     */
    public Map<String, Object> exportVersion6State() {
        Connection live = DataSourceUtils.getConnection(dataSource);
        try {
            requireH2(live);
            validateSchemaDisposition(live);
            Map<String, Object> result = new LinkedHashMap<>();
            for (TableSpec spec : MANIFEST) {
                if (spec.introducedVersion() == CURRENT_SAVE_VERSION) {
                    result.put(spec.jsonKey(), readRows(live, spec.tableName()));
                }
            }
            return Collections.unmodifiableMap(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot export complete H2 save: "
                    + rootMessage(exception), exception);
        } finally {
            DataSourceUtils.releaseConnection(live, dataSource);
        }
    }

    /**
     * Applies only already-preflighted rows. Every live operation is DML in a
     * single transaction, so any insert, profile migration or validation error
     * restores all sentinel rows automatically.
     */
    @Transactional(rollbackFor = Exception.class)
    public void apply(ImportPlan plan, boolean playerAvailabilityDisabled) {
        Objects.requireNonNull(plan, "import plan");
        Connection live = DataSourceUtils.getConnection(dataSource);
        try {
            requireH2(live);
            validateAccountCompatibility(live, plan);
            deleteImportedWorld(live, plan);
            insertPlan(live, plan);
            reconcileAiProfiles(live);
            if (playerAvailabilityDisabled) {
                executeUpdate(live, "UPDATE SUSPENSION SET ACTIVE = FALSE");
                executeUpdate(live, "UPDATE INJURY SET DAYS_REMAINING = 0");
                executeUpdate(live, "UPDATE HUMAN SET CURRENT_STATUS = 'Available' "
                        + "WHERE LOWER(CURRENT_STATUS) LIKE 'injur%'");
            }
            personProfileService.backfill();
            validateWorld(live);
            validateAccountCompatibility(live, plan);
        } catch (SQLException exception) {
            throw new IllegalStateException("Atomic H2 import failed: " + rootMessage(exception), exception);
        } finally {
            DataSourceUtils.releaseConnection(live, dataSource);
        }
    }

    /**
     * H2's generator-alignment statements are DDL and therefore implicitly
     * commit. They are deliberately executed only after {@link #apply} has
     * committed its fully validated DML transaction. Every exact target and
     * statement shape was already executed on the isolated H2 clone in
     * {@link #prepare}; this phase never participates in rollback semantics.
     */
    public void alignGeneratorsAfterCommit(ImportPlan plan) {
        Objects.requireNonNull(plan, "import plan");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("H2 generator DDL must run after the import transaction commits");
        }
        Connection live = DataSourceUtils.getConnection(dataSource);
        try {
            requireH2(live);
            for (GeneratorReset reset : plan.generatorResets()) {
                // The imported DML state is identical to the isolated plan, so
                // execute the exact statement that prepare already proved.
                executeUpdate(live, reset.sql(reset.minimumNextValue()));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Imported world committed, but H2 generator alignment failed: "
                    + rootMessage(exception), exception);
        } finally {
            DataSourceUtils.releaseConnection(live, dataSource);
        }
    }

    private List<RowValues> parseRows(TableSpec spec, List<?> rows, Set<String> validColumns) {
        List<RowValues> parsed = new ArrayList<>(rows.size());
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Object rowObject = rows.get(rowIndex);
            if (!(rowObject instanceof Map<?, ?>) && !isPojo(rowObject)) {
                throw invalid(spec.jsonKey() + " row " + rowIndex + " is not an object");
            }
            Map<String, Object> source;
            try {
                source = objectMapper.convertValue(rowObject,
                        new TypeReference<LinkedHashMap<String, Object>>() { });
            } catch (IllegalArgumentException exception) {
                throw invalid(spec.jsonKey() + " row " + rowIndex + " cannot be parsed", exception);
            }
            LinkedHashMap<String, Object> mapped = new LinkedHashMap<>();
            Map<String, String> overrides = TABLE_COLUMN_OVERRIDES.getOrDefault(spec.tableName(), Map.of());
            for (Map.Entry<String, Object> field : source.entrySet()) {
                String column = overrides.getOrDefault(field.getKey(),
                        GLOBAL_COLUMN_OVERRIDES.getOrDefault(field.getKey(), camelToSnake(field.getKey())));
                column = column.toUpperCase(Locale.ROOT);
                if (!validColumns.contains(column)) continue;
                if (mapped.containsKey(column) && !Objects.equals(mapped.get(column), field.getValue())) {
                    throw invalid(spec.jsonKey() + " row " + rowIndex
                            + " contains conflicting values for " + column);
                }
                mapped.putIfAbsent(column, field.getValue());
            }
            if (mapped.isEmpty()) {
                throw invalid(spec.jsonKey() + " row " + rowIndex + " has no persisted fields");
            }
            if (validColumns.contains("ID") && !mapped.containsKey("ID")) {
                throw invalid(spec.jsonKey() + " row " + rowIndex + " has no id");
            }
            parsed.add(RowValues.from(mapped));
        }
        return List.copyOf(parsed);
    }

    private List<GeneratorReset> validateOnIsolatedH2(Connection live, ImportPlan plan) throws SQLException {
        List<String> schema = readSchemaScript(live);
        String database = "jdbc:h2:mem:regent-save-preflight-" + UUID.randomUUID();
        try (Connection isolated = DriverManager.getConnection(database, "sa", "")) {
            try (Statement statement = isolated.createStatement()) {
                for (String ddl : schema) {
                    String normalized = ddl.stripLeading().toUpperCase(Locale.ROOT);
                    if (normalized.startsWith("SET DB_CLOSE_DELAY")) continue;
                    statement.execute(ddl);
                }
            }
            copyRows(live, isolated, "USERS", null);
            copyRows(live, isolated, "PERSON_PROFILE", "USER_ID IS NOT NULL");
            insertPlan(isolated, plan);
            reconcileAiProfiles(isolated);
            validateWorld(isolated);
            validateAccountCompatibility(isolated, plan);
            List<GeneratorReset> resets = discoverGeneratorResets(isolated);
            for (GeneratorReset reset : resets) {
                executeUpdate(isolated, reset.sql(reset.minimumNextValue()));
            }
            return List.copyOf(resets);
        }
    }

    private List<GeneratorReset> discoverGeneratorResets(Connection connection) throws SQLException {
        Set<String> worldTables = new HashSet<>();
        MANIFEST.forEach(spec -> worldTables.add(spec.tableName()));
        List<GeneratorReset> resets = new ArrayList<>();
        DatabaseMetaData metadata = connection.getMetaData();
        for (String table : worldTables) {
            try (ResultSet columns = metadata.getColumns(null, "PUBLIC", table, "ID")) {
                if (columns.next() && "YES".equalsIgnoreCase(columns.getString("IS_AUTOINCREMENT"))) {
                    long next = scalarLong(connection,
                            "SELECT COALESCE(MAX(\"ID\"), 0) + 1 FROM \"" + table + "\"");
                    resets.add(GeneratorReset.identity(table, "ID", next));
                }
            }
        }
        for (NamedSequence sequence : NAMED_SEQUENCES) {
            long next = scalarLong(connection,
                    "SELECT COALESCE(MAX(\"ID\"), 0) + 1 FROM \"" + sequence.tableName() + "\"");
            resets.add(GeneratorReset.sequence(sequence.sequenceName(), sequence.tableName(), next));
        }
        resets.sort(java.util.Comparator.comparing(GeneratorReset::sortKey));
        return resets;
    }

    private void deleteImportedWorld(Connection connection, ImportPlan plan) throws SQLException {
        List<TableRows> reverse = new ArrayList<>(plan.tables());
        Collections.reverse(reverse);
        for (TableRows table : reverse) {
            executeUpdate(connection, "DELETE FROM \"" + table.spec().tableName() + "\"");
        }
    }

    private void insertPlan(Connection connection, ImportPlan plan) throws SQLException {
        for (TableRows table : plan.tables()) {
            Map<List<String>, List<RowValues>> rowsByShape = new LinkedHashMap<>();
            for (RowValues row : table.rows()) {
                rowsByShape.computeIfAbsent(row.columns(), ignored -> new ArrayList<>()).add(row);
            }
            for (Map.Entry<List<String>, List<RowValues>> group : rowsByShape.entrySet()) {
                List<String> columns = group.getKey();
                String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
                String sql = "INSERT INTO \"" + table.spec().tableName() + "\" (\""
                        + String.join("\", \"", columns) + "\") VALUES (" + placeholders + ")";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (RowValues row : group.getValue()) {
                        for (int index = 0; index < row.values().size(); index++) {
                            statement.setObject(index + 1, row.values().get(index));
                        }
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            }
        }
    }

    private void reconcileAiProfiles(Connection connection) throws SQLException {
        executeUpdate(connection, "DELETE FROM PERSON_PROFILE WHERE USER_ID IS NULL AND "
                + "(HUMAN_ID IS NULL OR NOT EXISTS (SELECT 1 FROM HUMAN WHERE HUMAN.ID = PERSON_PROFILE.HUMAN_ID))");
        executeUpdate(connection, """
                INSERT INTO PERSON_PROFILE
                    (HUMAN_ID, CAREER_TYPE, CONTROL_TYPE, DISPLAY_NAME, CREATED_SEASON, CREATED_DAY, ACTIVE, RETIRED)
                SELECT h.ID, CASE WHEN h.TYPE_ID = 4 THEN 'MANAGER' ELSE 'PLAYER' END, 'AI',
                       COALESCE(NULLIF(TRIM(h.NAME), ''), CONCAT('human-', h.ID)), 0, 0, NOT h.RETIRED, h.RETIRED
                FROM HUMAN h
                WHERE NOT EXISTS (SELECT 1 FROM PERSON_PROFILE p WHERE p.HUMAN_ID = h.ID)
                """);
    }

    private void validateWorld(Connection connection) throws SQLException {
        long rounds = scalarLong(connection, "SELECT COUNT(*) FROM ROUND");
        long calendars = scalarLong(connection, "SELECT COUNT(*) FROM GAME_CALENDAR");
        if (rounds == 0 || calendars == 0) {
            throw invalid("rounds and gameCalendars are required");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM ROUND r
                WHERE r.ID = 1 AND EXISTS (
                    SELECT 1 FROM GAME_CALENDAR c WHERE c.SEASON = r.SEASON
                )
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getLong(1) != 1) {
                    throw invalid("round id 1 must reference an imported calendar season");
                }
            }
        }
    }

    private void validateAccountCompatibility(Connection connection, ImportPlan plan) throws SQLException {
        Set<Long> teamIds = plan.idsFor("TEAM");
        Set<Long> humanIds = plan.idsFor("HUMAN");
        try (Statement statement = connection.createStatement();
             ResultSet users = statement.executeQuery(
                     "SELECT ID, TEAM_ID, LAST_TEAM_ID, MANAGER_ID FROM USERS")) {
            while (users.next()) {
                long userId = users.getLong("ID");
                requireReference("user " + userId + " team", users, "TEAM_ID", teamIds);
                requireReference("user " + userId + " last team", users, "LAST_TEAM_ID", teamIds);
                requireReference("user " + userId + " manager", users, "MANAGER_ID", humanIds);
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet profiles = statement.executeQuery(
                     "SELECT ID, USER_ID, HUMAN_ID FROM PERSON_PROFILE WHERE USER_ID IS NOT NULL")) {
            while (profiles.next()) {
                long profileId = profiles.getLong("ID");
                requireReference("account profile " + profileId + " human", profiles, "HUMAN_ID", humanIds);
            }
        }
    }

    private void requireReference(String label, ResultSet row, String column, Set<Long> validIds) throws SQLException {
        long value = row.getLong(column);
        if (!row.wasNull() && value > 0 && !validIds.contains(value)) {
            throw invalid(label + " " + value + " does not exist in the imported world");
        }
    }

    private Map<String, Set<String>> readSchemaColumns(Connection connection) throws SQLException {
        Map<String, Set<String>> columns = new LinkedHashMap<>();
        DatabaseMetaData metadata = connection.getMetaData();
        for (TableSpec spec : MANIFEST) {
            Set<String> names = new HashSet<>();
            try (ResultSet result = metadata.getColumns(null, "PUBLIC", spec.tableName(), null)) {
                while (result.next()) names.add(result.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
            }
            columns.put(spec.tableName(), Set.copyOf(names));
        }
        return columns;
    }

    private void validateSchemaDisposition(Connection connection) throws SQLException {
        Set<String> actual = new HashSet<>();
        try (ResultSet tables = connection.getMetaData().getTables(null, "PUBLIC", null,
                new String[]{"TABLE"})) {
            while (tables.next()) actual.add(tables.getString("TABLE_NAME").toUpperCase(Locale.ROOT));
        }
        Set<String> disposed = new HashSet<>(PRESERVED_TABLES);
        MANIFEST.forEach(spec -> disposed.add(spec.tableName()));
        Set<String> undisposed = new java.util.TreeSet<>(actual);
        undisposed.removeAll(disposed);
        if (!undisposed.isEmpty()) {
            throw invalid("current H2 schema contains persisted tables without a V6 disposition: "
                    + undisposed);
        }
    }

    private List<String> readSchemaScript(Connection connection) throws SQLException {
        List<String> script = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SCRIPT NODATA")) {
            while (result.next()) script.add(result.getString(1));
        }
        return script;
    }

    private List<Map<String, Object>> readRows(Connection connection, String table) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean hasId;
        try (ResultSet id = connection.getMetaData().getColumns(null, "PUBLIC", table, "ID")) {
            hasId = id.next();
        }
        String orderBy = hasId ? " ORDER BY \"ID\"" : "";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM \"" + table + "\"" + orderBy)) {
            ResultSetMetaData metadata = result.getMetaData();
            while (result.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    row.put(metadata.getColumnName(index), exportValue(result.getObject(index)));
                }
                rows.add(Collections.unmodifiableMap(row));
            }
        }
        return List.copyOf(rows);
    }

    private Object exportValue(Object value) throws SQLException {
        if (value instanceof Clob clob) {
            return clob.getSubString(1, Math.toIntExact(clob.length()));
        }
        if (value instanceof Blob blob) {
            return blob.getBytes(1, Math.toIntExact(blob.length()));
        }
        return value;
    }

    private void copyRows(Connection source, Connection target, String table, String where) throws SQLException {
        String select = "SELECT * FROM \"" + table + "\"" + (where == null ? "" : " WHERE " + where);
        try (Statement query = source.createStatement(); ResultSet rows = query.executeQuery(select)) {
            ResultSetMetaData metadata = rows.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                columns.add(metadata.getColumnName(index));
            }
            String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
            String insert = "INSERT INTO \"" + table + "\" (\""
                    + String.join("\", \"", columns) + "\") VALUES (" + placeholders + ")";
            while (rows.next()) {
                try (PreparedStatement statement = target.prepareStatement(insert)) {
                    for (int index = 1; index <= columns.size(); index++) {
                        statement.setObject(index, rows.getObject(index));
                    }
                    statement.executeUpdate();
                }
            }
        }
    }

    private void validateLegacyIdentitySections(Map<String, Object> save) {
        for (String section : List.of("users", "personProfiles")) {
            Object raw = save.get(section);
            if (raw == null) continue;
            if (!(raw instanceof List<?> rows)
                    || rows.stream().anyMatch(row -> !(row instanceof Map<?, ?>) && !isPojo(row))) {
                throw invalid("legacy identity section '" + section + "' must be an object list");
            }
        }
    }

    private int parseVersion(Object raw) {
        if (!(raw instanceof Number number)
                || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw invalid("saveVersion must be integer 5 or 6");
        }
        int version = number.intValue();
        if (version != LEGACY_SAVE_VERSION && version != CURRENT_SAVE_VERSION) {
            throw invalid("incompatible save version; expected 5 or 6");
        }
        return version;
    }

    private void requireH2(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        if (!"H2".equalsIgnoreCase(product)) {
            throw new IllegalStateException("REGENT Phase 0 save import is temporarily H2-only");
        }
    }

    private long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private void executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static boolean isPojo(Object value) {
        return value != null && !(value instanceof String) && !(value instanceof Number)
                && !(value instanceof Boolean) && !(value instanceof List<?>);
    }

    private static String camelToSnake(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isUpperCase(character)) {
                if (index > 0 && Character.isLowerCase(value.charAt(index - 1))) result.append('_');
                result.append(Character.toLowerCase(character));
            } else {
                result.append(character);
            }
        }
        return result.toString().toUpperCase(Locale.ROOT);
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("Invalid save: " + detail);
    }

    private static IllegalArgumentException invalid(String detail, Exception cause) {
        return new IllegalArgumentException("Invalid save: " + detail, cause);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    public record ImportPlan(int sourceVersion, List<TableRows> tables,
                             List<GeneratorReset> generatorResets) {
        public ImportPlan {
            tables = List.copyOf(tables);
            generatorResets = List.copyOf(generatorResets);
        }

        Set<Long> idsFor(String tableName) {
            Set<Long> ids = new HashSet<>();
            tables.stream()
                    .filter(table -> table.spec().tableName().equals(tableName))
                    .flatMap(table -> table.rows().stream())
                    .map(RowValues::asMap)
                    .map(row -> row.get("ID"))
                    .filter(Number.class::isInstance)
                    .map(Number.class::cast)
                    .map(Number::longValue)
                    .forEach(ids::add);
            return Set.copyOf(ids);
        }
    }

    public record TableRows(TableSpec spec, List<RowValues> rows) {
        public TableRows {
            rows = List.copyOf(rows);
        }
    }

    public record TableSpec(String jsonKey, String tableName, int introducedVersion) {
        TableSpec(String jsonKey, String tableName) {
            this(jsonKey, tableName, LEGACY_SAVE_VERSION);
        }
    }

    private record NamedSequence(String sequenceName, String tableName) { }

    public record GeneratorReset(GeneratorKind kind, String generatorName, String tableName,
                                 String columnName, long minimumNextValue) {
        static GeneratorReset identity(String tableName, String columnName, long minimumNextValue) {
            return new GeneratorReset(GeneratorKind.IDENTITY, tableName + "." + columnName,
                    tableName, columnName, minimumNextValue);
        }

        static GeneratorReset sequence(String sequenceName, String tableName, long minimumNextValue) {
            return new GeneratorReset(GeneratorKind.SEQUENCE, sequenceName,
                    tableName, null, minimumNextValue);
        }

        String sql(long nextValue) {
            if (kind == GeneratorKind.IDENTITY) {
                return "ALTER TABLE \"" + tableName + "\" ALTER COLUMN \"" + columnName
                        + "\" RESTART WITH " + nextValue;
            }
            return "ALTER SEQUENCE \"PUBLIC\".\"" + generatorName + "\" RESTART WITH " + nextValue;
        }

        String sortKey() {
            return kind + ":" + generatorName;
        }
    }

    public enum GeneratorKind { IDENTITY, SEQUENCE }

    public record RowValues(List<String> columns, List<Object> values) {
        public RowValues {
            columns = List.copyOf(columns);
            values = Collections.unmodifiableList(new ArrayList<>(values));
            if (columns.size() != values.size()) throw new IllegalArgumentException("column/value mismatch");
        }

        static RowValues from(LinkedHashMap<String, Object> mapped) {
            return new RowValues(new ArrayList<>(mapped.keySet()), new ArrayList<>(mapped.values()));
        }

        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            for (int index = 0; index < columns.size(); index++) result.put(columns.get(index), values.get(index));
            return result;
        }
    }
}
