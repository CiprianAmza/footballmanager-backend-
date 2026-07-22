package com.footballmanagergamesimulator.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.person.PersonProfileService;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
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

/**
 * Builds an immutable, account-neutral import plan and proves it against an
 * isolated copy of the current H2 schema before the live database is touched.
 */
@Service
public class GameSaveImportService {

    static final int LEGACY_SAVE_VERSION = 5;
    static final int CURRENT_SAVE_VERSION = 6;

    private static final List<TableSpec> MANIFEST = List.of(
            new TableSpec("rounds", "ROUND"),
            new TableSpec("competitions", "COMPETITION"),
            new TableSpec("teams", "TEAM"),
            new TableSpec("teamFacilities", "TEAM_FACILITIES"),
            new TableSpec("stadiums", "STADIUM"),
            new TableSpec("gameCalendars", "GAME_CALENDAR"),
            new TableSpec("calendarEvents", "CALENDAR_EVENT"),
            new TableSpec("humans", "HUMAN"),
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
            new TableSpec("financialRecords", "FINANCIAL_RECORD")
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
            Map<String, Set<String>> schemaColumns = readSchemaColumns(live);
            List<TableRows> tables = new ArrayList<>();
            for (TableSpec spec : MANIFEST) {
                Object rawSection = save.get(spec.jsonKey());
                if (!(rawSection instanceof List<?> rows)) {
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

            ImportPlan plan = new ImportPlan(version, List.copyOf(tables));
            validateAccountCompatibility(live, plan);
            validateOnIsolatedH2(live, plan);
            return plan;
        } catch (SQLException exception) {
            throw invalid("H2 preflight failed: " + rootMessage(exception), exception);
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

    private void validateOnIsolatedH2(Connection live, ImportPlan plan) throws SQLException {
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
        }
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

    private List<String> readSchemaScript(Connection connection) throws SQLException {
        List<String> script = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SCRIPT NODATA")) {
            while (result.next()) script.add(result.getString(1));
        }
        return script;
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

    public record ImportPlan(int sourceVersion, List<TableRows> tables) {
        public ImportPlan {
            tables = List.copyOf(tables);
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

    public record TableSpec(String jsonKey, String tableName) { }

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
