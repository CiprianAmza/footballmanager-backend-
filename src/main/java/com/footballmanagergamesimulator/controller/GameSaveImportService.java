package com.footballmanagergamesimulator.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.economy.PersonalEconomyBootstrapService;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Builds an immutable, account-neutral import plan and proves it with a
 * rollback-only rehearsal against the current database before live state is touched.
 */
@Service
public class GameSaveImportService {

    static final int LEGACY_SAVE_VERSION = 5;
    static final int SAVE_VERSION_6 = 6;
    static final int CURRENT_SAVE_VERSION = 7;

    private static final List<TableSpec> MANIFEST = List.of(
            new TableSpec("competitionTypes", "COMPETITION_TYPE", SAVE_VERSION_6),
            new TableSpec("humanTypes", "HUMAN_TYPE", SAVE_VERSION_6),
            new TableSpec("transferStrategies", "TRANSFER_STRATEGY", SAVE_VERSION_6),
            new TableSpec("rounds", "ROUND"),
            new TableSpec("competitions", "COMPETITION"),
            new TableSpec("teams", "TEAM"),
            new TableSpec("teamCompetitionRelations", "TEAM_COMPETITION_RELATION", SAVE_VERSION_6),
            new TableSpec("teamTransferStrategyRelations", "TEAM_TRANSFER_STRATEGY_RELATION", SAVE_VERSION_6),
            new TableSpec("teamFacilities", "TEAM_FACILITIES"),
            new TableSpec("stadiums", "STADIUM"),
            new TableSpec("gameCalendars", "GAME_CALENDAR"),
            new TableSpec("calendarEvents", "CALENDAR_EVENT"),
            new TableSpec("humans", "HUMAN"),
            new TableSpec("humanTeamRelations", "HUMAN_TEAM_RELATION", SAVE_VERSION_6),
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
            new TableSpec("matchPlayerRatings", "MATCH_PLAYER_RATING", SAVE_VERSION_6),
            new TableSpec("matchSquads", "MATCH_SQUAD", SAVE_VERSION_6),
            new TableSpec("matchPlans", "MATCH_PLAN", SAVE_VERSION_6),
            new TableSpec("matchPlanGoalSlots", "MATCH_PLAN_GOAL_SLOT", SAVE_VERSION_6),
            new TableSpec("matchParticipants", "MATCH_PARTICIPANT", SAVE_VERSION_6),
            new TableSpec("matchAppearances", "MATCH_APPEARANCE", SAVE_VERSION_6),
            new TableSpec("matchSubstitutions", "MATCH_SUBSTITUTION", SAVE_VERSION_6),
            new TableSpec("matchAnimationRecipes", "MATCH_ANIMATION_RECIPE", SAVE_VERSION_6),
            new TableSpec("liveCommitContexts", "LIVE_COMMIT_CONTEXT", SAVE_VERSION_6),
            new TableSpec("predeterminedScores", "PREDETERMINED_SCORE", SAVE_VERSION_6),
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
            new TableSpec("friendlyMatches", "FRIENDLY_MATCH", SAVE_VERSION_6),
            new TableSpec("jobOffers", "JOB_OFFER", SAVE_VERSION_6),
            new TableSpec("scouts", "SCOUT", SAVE_VERSION_6),
            new TableSpec("scoutAssignments", "SCOUT_ASSIGNMENT", SAVE_VERSION_6),
            new TableSpec("shortlists", "SHORTLIST", SAVE_VERSION_6),
            new TableSpec("assets", "ASSET", SAVE_VERSION_6),
            new TableSpec("clubShareholdings", "CLUB_SHAREHOLDING", SAVE_VERSION_6),
            new TableSpec("ownerships", "OWNERSHIP", SAVE_VERSION_6),
            new TableSpec("coachPermissions", "COACH_PERMISSIONS", SAVE_VERSION_6),
            new TableSpec("personalAccounts", "PERSONAL_ACCOUNT", CURRENT_SAVE_VERSION),
            new TableSpec("assetCatalogItems", "ASSET_CATALOG_ITEM", CURRENT_SAVE_VERSION),
            new TableSpec("ownedAssets", "OWNED_ASSET", CURRENT_SAVE_VERSION),
            new TableSpec("personalLedgerEntries", "PERSONAL_LEDGER_ENTRY", CURRENT_SAVE_VERSION)
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
    private final PersonalEconomyBootstrapService economyBootstrapService;

    @Autowired
    public GameSaveImportService(DataSource dataSource,
                                 ObjectMapper objectMapper,
                                 PersonProfileService personProfileService,
                                 Optional<PersonalEconomyBootstrapService> economyBootstrapService) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.personProfileService = personProfileService;
        this.economyBootstrapService = economyBootstrapService.orElse(null);
    }

    public GameSaveImportService(DataSource dataSource,
                                 ObjectMapper objectMapper,
                                 PersonProfileService personProfileService) {
        this(dataSource, objectMapper, personProfileService, Optional.empty());
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
     * Parses and migrates v5/v6/v7 into a complete immutable game-only plan. The
     * legacy users/personProfiles sections are deliberately never part of it.
     */
    public ImportPlan prepare(Map<String, Object> save) {
        if (save == null) throw invalid("payload is required");
        int version = parseVersion(save.get("saveVersion"));
        validateLegacyIdentitySections(save);

        Connection live = DataSourceUtils.getConnection(dataSource);
        try {
            DatabaseDialect dialect = DatabaseDialect.detect(live);
            SchemaCatalog schema = SchemaCatalog.inspect(live, dialect);
            validateSchemaDisposition(schema);
            Map<String, Set<String>> schemaColumns = readSchemaColumns(schema);
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
                    throw invalid("current " + dialect.label() + " schema is missing table " + spec.tableName());
                }
                tables.add(new TableRows(spec, parseRows(spec, rows, columns)));
            }

            ImportPlan parsed = new ImportPlan(version, List.copyOf(tables), List.of());
            validateAccountCompatibility(live, parsed, schema);
            List<GeneratorReset> generators = validateInRollbackSandbox(parsed);
            return new ImportPlan(version, parsed.tables(), generators);
        } catch (SQLException exception) {
            throw invalid("database preflight failed: " + rootMessage(exception), exception);
        } finally {
            DataSourceUtils.releaseConnection(live, dataSource);
        }
    }

    /**
     * Exports every versioned world-state table introduced in V6 or later that is not already emitted by the
     * legacy controller contract. Rows use physical column names so no entity
     * or animation implementation has to be modified merely to make save state
     * exhaustive.
     */
    public Map<String, Object> exportVersion6State() {
        Connection live = DataSourceUtils.getConnection(dataSource);
        try {
            DatabaseDialect dialect = DatabaseDialect.detect(live);
            SchemaCatalog schema = SchemaCatalog.inspect(live, dialect);
            validateSchemaDisposition(schema);
            Map<String, Object> result = new LinkedHashMap<>();
            for (TableSpec spec : MANIFEST) {
                if (spec.introducedVersion() >= SAVE_VERSION_6) {
                    result.put(spec.jsonKey(), readRows(live, schema, spec.tableName()));
                }
            }
            return Collections.unmodifiableMap(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot export complete database save: "
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
            DatabaseDialect dialect = DatabaseDialect.detect(live);
            SchemaCatalog schema = SchemaCatalog.inspect(live, dialect);
            validateAccountCompatibility(live, plan, schema);
            deleteImportedWorld(live, plan, schema);
            insertPlan(live, plan, schema);
            reconcileAiProfiles(live, schema);
            if (playerAvailabilityDisabled) {
                executeUpdate(live, "UPDATE " + schema.table("SUSPENSION") + " SET "
                        + schema.column("SUSPENSION", "ACTIVE") + " = FALSE");
                executeUpdate(live, "UPDATE " + schema.table("INJURY") + " SET "
                        + schema.column("INJURY", "DAYS_REMAINING") + " = 0");
                executeUpdate(live, "UPDATE " + schema.table("HUMAN") + " SET "
                        + schema.column("HUMAN", "CURRENT_STATUS") + " = 'Available' WHERE LOWER("
                        + schema.column("HUMAN", "CURRENT_STATUS") + ") LIKE 'injur%'");
            }
            personProfileService.backfill();
            if (economyBootstrapService != null) economyBootstrapService.ensureAllAccounts();
            validateWorld(live, schema);
            validateAccountCompatibility(live, plan, schema);
        } catch (SQLException exception) {
            throw new IllegalStateException("Atomic database import failed: " + rootMessage(exception), exception);
        } finally {
            DataSourceUtils.releaseConnection(live, dataSource);
        }
    }

    /**
     * H2/MySQL generator-alignment statements can implicitly commit and
     * PostgreSQL sequence changes are not transactional. They are deliberately
     * executed before {@link #apply}: if alignment fails, live DML never starts
     * and the old world remains intact. If apply later rolls back, only counters
     * may remain changed, and their precomputed floor is beyond both the old and
     * imported row maxima.
     */
    public void alignGeneratorsBeforeApply(ImportPlan plan) {
        Objects.requireNonNull(plan, "import plan");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("generator alignment must run before the import transaction starts");
        }
        Connection live = DataSourceUtils.getConnection(dataSource);
        try {
            DatabaseDialect dialect = DatabaseDialect.detect(live);
            SchemaCatalog schema = SchemaCatalog.inspect(live, dialect);
            for (GeneratorReset reset : plan.generatorResets()) {
                alignGenerator(live, schema, dialect, reset);
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new IllegalStateException("generator alignment failed before import; world was not modified: "
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

    /**
     * Rehearses the complete DML import on a dedicated connection and always
     * rolls it back. DELETE/INSERT are transactional on H2, PostgreSQL and
     * InnoDB, so the rehearsal exercises the real schema, constraints and
     * JDBC conversions without vendor-specific schema cloning or DDL.
     */
    private List<GeneratorReset> validateInRollbackSandbox(ImportPlan plan) throws SQLException {
        try (Connection sandbox = dataSource.getConnection()) {
            boolean originalAutoCommit = sandbox.getAutoCommit();
            sandbox.setAutoCommit(false);
            try {
                DatabaseDialect dialect = DatabaseDialect.detect(sandbox);
                SchemaCatalog schema = SchemaCatalog.inspect(sandbox, dialect);
                Map<String, GeneratorReset> liveResets = new HashMap<>();
                for (GeneratorReset reset : discoverGeneratorResets(sandbox, schema, dialect)) {
                    liveResets.put(reset.sortKey(), reset);
                }
                validateAccountCompatibility(sandbox, plan, schema);
                deleteImportedWorld(sandbox, plan, schema);
                insertPlan(sandbox, plan, schema);
                reconcileAiProfiles(sandbox, schema);
                validateWorld(sandbox, schema);
                validateAccountCompatibility(sandbox, plan, schema);
                return discoverGeneratorResets(sandbox, schema, dialect).stream()
                        .map(reset -> reset.withMinimumNextValue(Math.max(
                                reset.minimumNextValue(),
                                liveResets.getOrDefault(reset.sortKey(), reset).minimumNextValue())))
                        .toList();
            } finally {
                sandbox.rollback();
                sandbox.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private List<GeneratorReset> discoverGeneratorResets(Connection connection,
                                                          SchemaCatalog schema,
                                                          DatabaseDialect dialect) throws SQLException {
        Set<String> worldTables = new HashSet<>();
        MANIFEST.forEach(spec -> worldTables.add(spec.tableName()));
        List<GeneratorReset> resets = new ArrayList<>();
        DatabaseMetaData metadata = connection.getMetaData();
        for (String table : worldTables) {
            if (dialect == DatabaseDialect.POSTGRESQL) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT pg_get_serial_sequence(?, ?)")) {
                    statement.setString(1, schema.postgresqlRelation(table));
                    statement.setString(2, schema.rawColumn(table, "ID"));
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        if (result.getString(1) != null) {
                            long next = scalarLong(connection,
                                    "SELECT COALESCE(MAX(" + schema.column(table, "ID") + "), 0) + 1 FROM "
                                            + schema.table(table));
                            resets.add(GeneratorReset.identity(table, "ID", next));
                        }
                    }
                }
                continue;
            }
            try (ResultSet columns = metadata.getColumns(schema.catalogName(), schema.schemaName(),
                    schema.rawTable(table), schema.rawColumn(table, "ID"))) {
                if (columns.next() && "YES".equalsIgnoreCase(columns.getString("IS_AUTOINCREMENT"))) {
                    long next = scalarLong(connection,
                            "SELECT COALESCE(MAX(" + schema.column(table, "ID") + "), 0) + 1 FROM "
                                    + schema.table(table));
                    resets.add(GeneratorReset.identity(table, "ID", next));
                }
            }
        }
        for (NamedSequence sequence : NAMED_SEQUENCES) {
            long next = scalarLong(connection,
                    "SELECT COALESCE(MAX(" + schema.column(sequence.tableName(), "ID") + "), 0) + 1 FROM "
                            + schema.table(sequence.tableName()));
            if (dialect == DatabaseDialect.MYSQL) {
                if (schema.hasTable(sequence.sequenceName())) {
                    resets.add(GeneratorReset.sequenceTable(sequence.sequenceName(), sequence.tableName(), next));
                }
            } else {
                resets.add(GeneratorReset.sequence(sequence.sequenceName(), sequence.tableName(), next));
            }
        }
        resets.sort(java.util.Comparator.comparing(GeneratorReset::sortKey));
        return resets;
    }

    private void deleteImportedWorld(Connection connection, ImportPlan plan,
                                     SchemaCatalog schema) throws SQLException {
        List<TableRows> reverse = new ArrayList<>(plan.tables());
        Collections.reverse(reverse);
        for (TableRows table : reverse) {
            executeUpdate(connection, "DELETE FROM " + schema.table(table.spec().tableName()));
        }
    }

    private void insertPlan(Connection connection, ImportPlan plan,
                            SchemaCatalog schema) throws SQLException {
        for (TableRows table : plan.tables()) {
            List<RowValues> rowsToInsert = rowsForInsert(connection, schema, table);
            Map<List<String>, List<RowValues>> rowsByShape = new LinkedHashMap<>();
            for (RowValues row : rowsToInsert) {
                rowsByShape.computeIfAbsent(row.columns(), ignored -> new ArrayList<>()).add(row);
            }
            for (Map.Entry<List<String>, List<RowValues>> group : rowsByShape.entrySet()) {
                List<String> columns = group.getKey();
                String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
                String sql = "INSERT INTO " + schema.table(table.spec().tableName()) + " ("
                        + columns.stream().map(column -> schema.column(table.spec().tableName(), column))
                        .collect(java.util.stream.Collectors.joining(", ")) + ") VALUES (" + placeholders + ")";
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

    /**
     * PersonProfile is installation identity, while Phase-1 balances are world
     * state. Remap imported account/profile references by stable user/human
     * identity after imported Humans exist, rather than trusting source-local
     * PersonProfile ids.
     */
    private List<RowValues> rowsForInsert(Connection connection, SchemaCatalog schema, TableRows table) throws SQLException {
        String tableName = table.spec().tableName();
        if ("PERSONAL_ACCOUNT".equals(tableName)) reconcileAiProfiles(connection, schema);
        if (!Set.of("PERSONAL_ACCOUNT", "OWNED_ASSET", "PERSONAL_LEDGER_ENTRY").contains(tableName)) {
            return table.rows();
        }
        List<RowValues> remapped = new ArrayList<>();
        for (RowValues row : table.rows()) {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>(row.asMap());
            long profileId;
            if ("PERSONAL_ACCOUNT".equals(tableName)) {
                Long userId = nullableLong(values.get("OWNER_USER_ID"));
                Long humanId = nullableLong(values.get("OWNER_HUMAN_ID"));
                profileId = resolveProfileId(connection, userId, humanId);
            } else {
                Long accountId = nullableLong(values.get("ACCOUNT_ID"));
                if (accountId == null) throw invalid(table.spec().jsonKey() + " row has no account id");
                profileId = queryRequiredLong(connection,
                        "SELECT PROFILE_ID FROM PERSONAL_ACCOUNT WHERE ID = ?", accountId,
                        table.spec().jsonKey() + " references missing account " + accountId);
            }
            values.put("PROFILE_ID", profileId);
            remapped.add(RowValues.from(values));
        }
        return List.copyOf(remapped);
    }

    private long resolveProfileId(Connection connection, Long userId, Long humanId) throws SQLException {
        if (userId != null) {
            return queryRequiredLong(connection, "SELECT ID FROM PERSON_PROFILE WHERE USER_ID = ?", userId,
                    "personal account references missing user identity " + userId);
        }
        if (humanId != null) {
            return queryRequiredLong(connection, "SELECT ID FROM PERSON_PROFILE WHERE HUMAN_ID = ?", humanId,
                    "personal account references missing Human identity " + humanId);
        }
        throw invalid("personal account has neither ownerUserId nor ownerHumanId");
    }

    private long queryRequiredLong(Connection connection, String sql, long parameter, String message)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw invalid(message);
                return result.getLong(1);
            }
        }
    }

    private static Long nullableLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(value.toString()); }
        catch (NumberFormatException exception) { throw invalid("identity reference is not numeric"); }
    }

    private void reconcileAiProfiles(Connection connection, SchemaCatalog schema) throws SQLException {
        String profile = schema.table("PERSON_PROFILE");
        String human = schema.table("HUMAN");
        executeUpdate(connection, "DELETE FROM " + profile + " WHERE "
                + schema.column("PERSON_PROFILE", "USER_ID") + " IS NULL AND ("
                + schema.column("PERSON_PROFILE", "HUMAN_ID") + " IS NULL OR NOT EXISTS (SELECT 1 FROM "
                + human + " h WHERE h." + schema.columnRaw("HUMAN", "ID") + " = "
                + profile + "." + schema.columnRawQuoted("PERSON_PROFILE", "HUMAN_ID") + "))");
        executeUpdate(connection, "INSERT INTO " + profile + " ("
                + schema.columns("PERSON_PROFILE", "HUMAN_ID", "CAREER_TYPE", "CONTROL_TYPE", "DISPLAY_NAME",
                "CREATED_SEASON", "CREATED_DAY", "ACTIVE", "RETIRED") + ") SELECT h."
                + schema.columnRawQuoted("HUMAN", "ID")
                + ", CASE WHEN h." + schema.columnRawQuoted("HUMAN", "TYPE_ID")
                + " = 4 THEN 'MANAGER' ELSE 'PLAYER' END, 'AI', COALESCE(NULLIF(TRIM(h."
                + schema.columnRawQuoted("HUMAN", "NAME") + "), ''), CONCAT('human-', h."
                + schema.columnRawQuoted("HUMAN", "ID") + ")), 0, 0, NOT h."
                + schema.columnRawQuoted("HUMAN", "RETIRED") + ", h."
                + schema.columnRawQuoted("HUMAN", "RETIRED") + " FROM " + human + " h WHERE NOT EXISTS (SELECT 1 FROM "
                + profile + " p WHERE p." + schema.columnRawQuoted("PERSON_PROFILE", "HUMAN_ID")
                + " = h." + schema.columnRawQuoted("HUMAN", "ID") + ")");
    }

    private void validateWorld(Connection connection, SchemaCatalog schema) throws SQLException {
        long rounds = scalarLong(connection, "SELECT COUNT(*) FROM " + schema.table("ROUND"));
        long calendars = scalarLong(connection, "SELECT COUNT(*) FROM " + schema.table("GAME_CALENDAR"));
        if (rounds == 0 || calendars == 0) {
            throw invalid("rounds and gameCalendars are required");
        }
        String sql = "SELECT COUNT(*) FROM " + schema.table("ROUND") + " r WHERE r."
                + schema.columnRawQuoted("ROUND", "ID") + " = 1 AND EXISTS (SELECT 1 FROM "
                + schema.table("GAME_CALENDAR") + " c WHERE c."
                + schema.columnRawQuoted("GAME_CALENDAR", "SEASON") + " = r."
                + schema.columnRawQuoted("ROUND", "SEASON") + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getLong(1) != 1) {
                    throw invalid("round id 1 must reference an imported calendar season");
                }
            }
        }
        validateEconomy(connection);
    }

    private void validateEconomy(Connection connection) throws SQLException {
        long invalidAccounts = scalarLong(connection, """
                SELECT COUNT(*) FROM PERSONAL_ACCOUNT a
                WHERE a.CASH_BALANCE < 0 OR a.LIFETIME_CAREER_EARNINGS < 0
                   OR a.CASH_BALANCE <> COALESCE((
                       SELECT SUM(l.SIGNED_AMOUNT) FROM PERSONAL_LEDGER_ENTRY l WHERE l.ACCOUNT_ID = a.ID), 0)
                   OR a.LIFETIME_CAREER_EARNINGS <> COALESCE((
                       SELECT SUM(l.CAREER_EARNINGS_DELTA) FROM PERSONAL_LEDGER_ENTRY l WHERE l.ACCOUNT_ID = a.ID), 0)
                """);
        if (invalidAccounts != 0) throw invalid("personal account does not reconcile with its ledger");
        long invalidAssets = scalarLong(connection, """
                SELECT COUNT(*) FROM OWNED_ASSET owned
                LEFT JOIN PERSONAL_ACCOUNT account ON account.ID = owned.ACCOUNT_ID
                LEFT JOIN ASSET_CATALOG_ITEM catalog ON catalog.ID = owned.CATALOG_ITEM_ID
                WHERE account.ID IS NULL OR catalog.ID IS NULL
                   OR owned.PROFILE_ID <> account.PROFILE_ID
                   OR owned.PURCHASE_PRICE < 0 OR owned.CURRENT_VALUE < 0
                   OR (owned.STATUS = 'OWNED' AND owned.SALE_PRICE IS NOT NULL)
                """);
        if (invalidAssets != 0) throw invalid("owned asset state is inconsistent");
    }

    private void validateAccountCompatibility(Connection connection, ImportPlan plan,
                                              SchemaCatalog schema) throws SQLException {
        Set<Long> teamIds = plan.idsFor("TEAM");
        Set<Long> humanIds = plan.idsFor("HUMAN");
        try (Statement statement = connection.createStatement();
             ResultSet users = statement.executeQuery("SELECT "
                     + schema.columns("USERS", "ID", "TEAM_ID", "LAST_TEAM_ID", "MANAGER_ID")
                     + " FROM " + schema.table("USERS"))) {
            while (users.next()) {
                long userId = users.getLong("ID");
                requireReference("user " + userId + " team", users, "TEAM_ID", teamIds);
                requireReference("user " + userId + " last team", users, "LAST_TEAM_ID", teamIds);
                requireReference("user " + userId + " manager", users, "MANAGER_ID", humanIds);
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet profiles = statement.executeQuery("SELECT "
                     + schema.columns("PERSON_PROFILE", "ID", "USER_ID", "HUMAN_ID")
                     + " FROM " + schema.table("PERSON_PROFILE") + " WHERE "
                     + schema.column("PERSON_PROFILE", "USER_ID") + " IS NOT NULL")) {
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

    private Map<String, Set<String>> readSchemaColumns(SchemaCatalog schema) {
        Map<String, Set<String>> columns = new LinkedHashMap<>();
        for (TableSpec spec : MANIFEST) {
            columns.put(spec.tableName(), schema.canonicalColumns(spec.tableName()));
        }
        return columns;
    }

    private void validateSchemaDisposition(SchemaCatalog schema) {
        Set<String> actual = new HashSet<>(schema.canonicalTableNames());
        Set<String> disposed = new HashSet<>(PRESERVED_TABLES);
        MANIFEST.forEach(spec -> disposed.add(spec.tableName()));
        NAMED_SEQUENCES.forEach(sequence -> disposed.add(sequence.sequenceName().toUpperCase(Locale.ROOT)));
        Set<String> undisposed = new java.util.TreeSet<>(actual);
        undisposed.removeAll(disposed);
        if (!undisposed.isEmpty()) {
            throw invalid("current database schema contains persisted tables without a V6 disposition: "
                    + undisposed);
        }
    }

    private List<Map<String, Object>> readRows(Connection connection, SchemaCatalog schema,
                                               String table) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean hasId = schema.hasColumn(table, "ID");
        String orderBy = hasId ? " ORDER BY " + schema.column(table, "ID") : "";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + schema.table(table) + orderBy)) {
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
            throw invalid("saveVersion must be integer 5, 6 or 7");
        }
        int version = number.intValue();
        if (version != LEGACY_SAVE_VERSION && version != SAVE_VERSION_6
                && version != CURRENT_SAVE_VERSION) {
            throw invalid("incompatible save version; expected 5, 6 or 7");
        }
        return version;
    }

    private void alignGenerator(Connection connection, SchemaCatalog schema,
                                DatabaseDialect dialect, GeneratorReset reset) throws SQLException {
        long next = reset.minimumNextValue();
        if (reset.kind() == GeneratorKind.IDENTITY) {
            if (dialect == DatabaseDialect.POSTGRESQL) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT setval(pg_get_serial_sequence(?, ?), ?, false)")) {
                    statement.setString(1, schema.postgresqlRelation(reset.tableName()));
                    statement.setString(2, schema.rawColumn(reset.tableName(), reset.columnName()));
                    statement.setLong(3, next);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        if (result.getObject(1) == null) {
                            throw new SQLException("PostgreSQL generator is missing for "
                                    + reset.tableName() + "." + reset.columnName());
                        }
                    }
                }
                return;
            }
            String sql = switch (dialect) {
                case MYSQL -> "ALTER TABLE " + schema.table(reset.tableName()) + " AUTO_INCREMENT = " + next;
                case H2 -> "ALTER TABLE " + schema.table(reset.tableName()) + " ALTER COLUMN "
                        + schema.column(reset.tableName(), reset.columnName()) + " RESTART WITH " + next;
                case POSTGRESQL -> throw new IllegalStateException("PostgreSQL identity handled above");
            };
            executeUpdate(connection, sql);
            return;
        }
        if (reset.kind() == GeneratorKind.SEQUENCE) {
            executeUpdate(connection, "ALTER SEQUENCE " + schema.sequence(reset.generatorName())
                    + " RESTART WITH " + next);
            return;
        }
        String generatorTable = schema.table(reset.generatorName());
        String nextValue = schema.column(reset.generatorName(), "NEXT_VAL");
        int updated;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + generatorTable + " SET " + nextValue + " = ?")) {
            statement.setLong(1, next);
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + generatorTable + " (" + nextValue + ") VALUES (?)")) {
                statement.setLong(1, next);
                statement.executeUpdate();
            }
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

        static GeneratorReset sequenceTable(String sequenceName, String tableName, long minimumNextValue) {
            return new GeneratorReset(GeneratorKind.SEQUENCE_TABLE, sequenceName,
                    tableName, null, minimumNextValue);
        }

        String sortKey() {
            return kind + ":" + generatorName;
        }

        GeneratorReset withMinimumNextValue(long nextValue) {
            return new GeneratorReset(kind, generatorName, tableName, columnName, nextValue);
        }
    }

    public enum GeneratorKind { IDENTITY, SEQUENCE, SEQUENCE_TABLE }

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

    enum DatabaseDialect {
        H2("H2"), POSTGRESQL("PostgreSQL"), MYSQL("MySQL/MariaDB");

        private final String label;

        DatabaseDialect(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        static DatabaseDialect detect(Connection connection) throws SQLException {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (product.contains("h2")) return H2;
            if (product.contains("postgresql")) return POSTGRESQL;
            if (product.contains("mysql") || product.contains("mariadb")) return MYSQL;
            throw new IllegalStateException("REGENT Phase 0 save import does not support database "
                    + connection.getMetaData().getDatabaseProductName());
        }
    }

    static final class SchemaCatalog {
        private final DatabaseDialect dialect;
        private final String catalogName;
        private final String schemaName;
        private final String quote;
        private final Map<String, String> tables;
        private final Map<String, Map<String, String>> columns;
        private final Map<String, String> sequences;

        private SchemaCatalog(DatabaseDialect dialect, String catalogName, String schemaName, String quote,
                              Map<String, String> tables, Map<String, Map<String, String>> columns,
                              Map<String, String> sequences) {
            this.dialect = dialect;
            this.catalogName = catalogName;
            this.schemaName = schemaName;
            this.quote = quote;
            this.tables = Map.copyOf(tables);
            Map<String, Map<String, String>> immutableColumns = new HashMap<>();
            columns.forEach((table, names) -> immutableColumns.put(table, Map.copyOf(names)));
            this.columns = Map.copyOf(immutableColumns);
            this.sequences = Map.copyOf(sequences);
        }

        static SchemaCatalog inspect(Connection connection, DatabaseDialect dialect) throws SQLException {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = dialect == DatabaseDialect.MYSQL ? null : connection.getSchema();
            String quote = metadata.getIdentifierQuoteString();
            if (quote == null || quote.isBlank()) quote = "\"";

            Map<String, String> tables = new LinkedHashMap<>();
            try (ResultSet result = metadata.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
                while (result.next()) {
                    String name = result.getString("TABLE_NAME");
                    tables.put(name.toUpperCase(Locale.ROOT), name);
                }
            }
            Map<String, Map<String, String>> columns = new LinkedHashMap<>();
            for (Map.Entry<String, String> table : tables.entrySet()) {
                Map<String, String> tableColumns = new LinkedHashMap<>();
                try (ResultSet result = metadata.getColumns(catalog, schema, table.getValue(), "%")) {
                    while (result.next()) {
                        String name = result.getString("COLUMN_NAME");
                        tableColumns.put(name.toUpperCase(Locale.ROOT), name);
                    }
                }
                columns.put(table.getKey(), tableColumns);
            }

            Map<String, String> sequences = new LinkedHashMap<>();
            if (dialect != DatabaseDialect.MYSQL) {
                String sql = "SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, schema);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            String name = result.getString(1);
                            sequences.put(name.toUpperCase(Locale.ROOT), name);
                        }
                    }
                }
            }
            return new SchemaCatalog(dialect, catalog, schema, quote, tables, columns, sequences);
        }

        String catalogName() { return catalogName; }
        String schemaName() { return schemaName; }

        Set<String> canonicalTableNames() { return tables.keySet(); }

        Set<String> canonicalColumns(String table) {
            return columns.getOrDefault(table.toUpperCase(Locale.ROOT), Map.of()).keySet();
        }

        boolean hasTable(String table) { return tables.containsKey(table.toUpperCase(Locale.ROOT)); }

        boolean hasColumn(String table, String column) {
            return columns.getOrDefault(table.toUpperCase(Locale.ROOT), Map.of())
                    .containsKey(column.toUpperCase(Locale.ROOT));
        }

        String rawTable(String table) {
            String actual = tables.get(table.toUpperCase(Locale.ROOT));
            if (actual == null) throw invalid("current " + dialect.label() + " schema is missing table " + table);
            return actual;
        }

        String rawColumn(String table, String column) {
            String actual = columns.getOrDefault(table.toUpperCase(Locale.ROOT), Map.of())
                    .get(column.toUpperCase(Locale.ROOT));
            if (actual == null) throw invalid("current " + dialect.label() + " schema is missing column "
                    + table + "." + column);
            return actual;
        }

        String postgresqlRelation(String table) {
            if (dialect != DatabaseDialect.POSTGRESQL) {
                throw new IllegalStateException("PostgreSQL relation requested for " + dialect.label());
            }
            return quoted(schemaName) + "." + quoted(rawTable(table));
        }

        String table(String table) { return quoted(rawTable(table)); }
        String column(String table, String column) { return quoted(rawColumn(table, column)); }
        String columnRaw(String table, String column) { return rawColumn(table, column); }
        String columnRawQuoted(String table, String column) { return quoted(rawColumn(table, column)); }

        String columns(String table, String... names) {
            return java.util.Arrays.stream(names).map(name -> column(table, name))
                    .collect(java.util.stream.Collectors.joining(", "));
        }

        String sequence(String sequence) {
            String actual = sequences.get(sequence.toUpperCase(Locale.ROOT));
            if (actual == null) throw invalid("current " + dialect.label() + " schema is missing sequence " + sequence);
            return quoted(actual);
        }

        private String quoted(String identifier) {
            return quote + identifier.replace(quote, quote + quote) + quote;
        }
    }
}
