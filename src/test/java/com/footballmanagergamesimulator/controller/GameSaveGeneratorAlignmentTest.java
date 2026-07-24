package com.footballmanagergamesimulator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.person.PersonProfileService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringJUnitConfig(GameSaveGeneratorAlignmentTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GameSaveGeneratorAlignmentTest {

    private static final Map<String, String> SEQUENCES = Map.of(
            "COMPETITION_TEAM_INFO", "CTI_SEQ",
            "SCORER", "SCORER_SEQ",
            "PLAYER_SKILLS", "PLAYER_SKILLS_SEQ",
            "TEAM_PLAYER_HISTORICAL_RELATION", "TPHR_SEQ");
    private static final Set<String> POST_V8_IDENTITY_TABLES = Set.of(
            "CLUB_FINANCIAL_OBLIGATION", "CLUB_CAP_TABLE_STATE", "TAKEOVER_QUOTE",
            "TAKEOVER_EXECUTION", "CLUB_CASH_TRANSFER", "TRADER_ADVISER_CONTRACT",
            "TRADER_ADVICE_RECOMMENDATION");

    @jakarta.annotation.Resource private GameSaveImportService service;
    @jakarta.annotation.Resource private JdbcTemplate jdbc;
    @jakarta.annotation.Resource private PersonProfileService profiles;

    @Test
    void explicitCrossInstanceIdsAdvanceEveryIdentityAndNamedSequencePastImportedMax() {
        GameSaveImportService.ImportPlan plan = service.prepare(highIdSave());
        service.alignGeneratorsBeforeApply(plan);
        service.apply(plan, false);

        for (String table : GameSaveImportService.manifestTableNames()) {
            jdbc.update("INSERT INTO \"" + table + "\" DEFAULT VALUES");
            long generated = jdbc.queryForObject(
                    "SELECT MAX(\"ID\") FROM \"" + table + "\"", Long.class);
            if (POST_V8_IDENTITY_TABLES.contains(table)) {
                assertThat(generated).as(table + " remains absent from a v8 save").isGreaterThan(1L);
            } else {
                assertThat(generated).as(table + " next generated id").isGreaterThan(1000L);
            }
        }
        assertThat(plan.generatorResets()).hasSize(GameSaveImportService.manifestTableNames().size());
        assertThat(plan.generatorResets().stream()
                .filter(reset -> reset.kind() == GameSaveImportService.GeneratorKind.SEQUENCE)
                .map(GameSaveImportService.GeneratorReset::generatorName))
                .containsExactlyInAnyOrder("CTI_SEQ", "SCORER_SEQ", "PLAYER_SKILLS_SEQ", "TPHR_SEQ");
    }

    @Test
    void v11AdviserIdentitiesAdvancePastTheirImportedMaximum() {
        GameSaveImportService.ImportPlan plan = service.prepare(highIdV11Save());
        service.alignGeneratorsBeforeApply(plan);
        service.apply(plan, false);

        for (String table : List.of("TRADER_ADVISER_CONTRACT", "TRADER_ADVICE_RECOMMENDATION")) {
            jdbc.update("INSERT INTO \"" + table + "\" DEFAULT VALUES");
            assertThat(jdbc.queryForObject("SELECT MAX(\"ID\") FROM \"" + table + "\"", Long.class))
                    .as(table + " next generated id after a v11 import")
                    .isGreaterThan(1000L);
        }
    }

    @Test
    void successfulAlignmentThenFailedReplacementRollsBackWorldAndLeavesCounterSafelyAdvanced() {
        GameSaveImportService.ImportPlan plan = service.prepare(highIdSave());
        service.alignGeneratorsBeforeApply(plan);
        doThrow(new IllegalStateException("forced after replacement DML started"))
                .when(profiles).backfill();

        assertThatThrownBy(() -> service.apply(plan, false))
                .hasMessageContaining("forced after replacement DML started");
        verify(profiles).backfill();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM WHERE ID = 1", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM WHERE ID = 1000", Integer.class)).isZero();

        jdbc.update("INSERT INTO TEAM DEFAULT VALUES");
        long generated = jdbc.queryForObject("SELECT MAX(ID) FROM TEAM", Long.class);
        assertThat(generated).isGreaterThan(1000L);
    }

    @Test
    void failedReplacementNeverRewindsIdentityBelowTheExistingWorldMaximum() {
        jdbc.update("INSERT INTO TEAM (ID) VALUES (2000)");
        GameSaveImportService.ImportPlan plan = service.prepare(highIdSave());
        service.alignGeneratorsBeforeApply(plan);
        doThrow(new IllegalStateException("forced after replacement DML started"))
                .when(profiles).backfill();

        assertThatThrownBy(() -> service.apply(plan, false))
                .hasMessageContaining("forced after replacement DML started");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM WHERE ID = 2000", Integer.class)).isOne();

        jdbc.update("INSERT INTO TEAM DEFAULT VALUES");
        long generated = jdbc.queryForObject("SELECT MAX(ID) FROM TEAM", Long.class);
        assertThat(generated).isGreaterThan(2000L);
    }

    private Map<String, Object> highIdSave() {
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("saveVersion", 8);
        for (String key : GameSaveImportService.manifestKeys()) {
            save.put(key, List.of(Map.of("id", 1000L)));
        }
        save.put("rounds", List.of(
                Map.of("id", 1L, "season", 1L),
                Map.of("id", 1000L, "season", 1L)));
        save.put("gameCalendars", List.of(Map.of("id", 1000L, "season", 1)));
        save.put("humans", List.of(Map.of(
                "id", 1000L, "typeId", 4L, "retired", false, "name", "Imported manager")));
        save.put("personalAccounts", List.of(Map.of(
                "ID", 1000L, "PROFILE_ID", 1000L, "OWNER_HUMAN_ID", 1000L,
                "CASH_BALANCE", 0L, "LIFETIME_CAREER_EARNINGS", 0L,
                "REALIZED_INVESTMENT_GAIN", 0L, "VERSION", 0L)));
        save.put("assetCatalogItems", List.of(Map.of(
                "ID", 1000L, "CODE", "GENERATOR_TEST", "ASSET_TYPE", "CAR",
                "NAME", "Generator test", "ICON_KEY", "test", "PURCHASE_PRICE", 1L,
                "RESALE_HAIRCUT_BPS", 0, "ACTIVE", true, "VERSION", 0L)));
        save.put("ownedAssets", List.of(Map.ofEntries(
                Map.entry("ID", 1000L), Map.entry("ACCOUNT_ID", 1000L),
                Map.entry("PROFILE_ID", 1000L), Map.entry("CATALOG_ITEM_ID", 1000L),
                Map.entry("PURCHASE_PRICE", 0L), Map.entry("CURRENT_VALUE", 0L),
                Map.entry("PURCHASE_SEASON", 0), Map.entry("PURCHASE_DAY", 0),
                Map.entry("STATUS", "OWNED"), Map.entry("PURCHASE_IDEMPOTENCY_KEY", "generator-test"),
                Map.entry("VERSION", 0L))));
        save.put("personalLedgerEntries", List.of(Map.ofEntries(
                Map.entry("ID", 1000L), Map.entry("ACCOUNT_ID", 1000L),
                Map.entry("PROFILE_ID", 1000L), Map.entry("SEASON_NUMBER", 0),
                Map.entry("GAME_DAY", 0), Map.entry("ENTRY_TYPE", "MIGRATION_OPENING"),
                Map.entry("SIGNED_AMOUNT", 0L), Map.entry("CAREER_EARNINGS_DELTA", 0L),
                Map.entry("BALANCE_AFTER", 0L), Map.entry("CORRELATION_ID", "generator-test"),
                Map.entry("IDEMPOTENCY_KEY", "generator-test"), Map.entry("DESCRIPTION", "generator test"),
                Map.entry("CREATED_AT", 0L))));
        save.put("marketInstruments", List.of(Map.ofEntries(
                Map.entry("ID", 1000L), Map.entry("CODE", "GENERATOR_MARKET"),
                Map.entry("INSTRUMENT_TYPE", "COMPANY"), Map.entry("NAME", "Generator market"),
                Map.entry("TOTAL_SUPPLY", 10L), Map.entry("AVAILABLE_SUPPLY", 9L),
                Map.entry("CURRENT_PRICE", 100L), Map.entry("PRICE_SEED", 42L),
                Map.entry("DAILY_LIMIT_BPS", 500), Map.entry("WEEKLY_LIMIT_BPS", 1500),
                Map.entry("ACTIVE", true), Map.entry("VERSION", 0L))));
        save.put("marketPriceSnapshots", List.of(Map.ofEntries(
                Map.entry("ID", 1000L), Map.entry("INSTRUMENT_ID", 1000L),
                Map.entry("SEASON_NUMBER", 1), Map.entry("GAME_DAY", 1),
                Map.entry("PREVIOUS_CLOSE", 100L), Map.entry("CLOSE_PRICE", 100L),
                Map.entry("WEEKLY_ANCHOR_PRICE", 100L), Map.entry("DAILY_CHANGE_BPS", 0))));
        save.put("portfolioPositions", List.of(Map.ofEntries(
                Map.entry("ID", 1000L), Map.entry("ACCOUNT_ID", 1000L),
                Map.entry("PROFILE_ID", 1000L), Map.entry("INSTRUMENT_ID", 1000L),
                Map.entry("QUANTITY", 1L), Map.entry("TOTAL_COST_BASIS", 100L),
                Map.entry("VERSION", 0L))));
        save.put("marketTrades", List.of(Map.ofEntries(
                Map.entry("ID", 1000L), Map.entry("ACCOUNT_ID", 1000L),
                Map.entry("PROFILE_ID", 1000L), Map.entry("INSTRUMENT_ID", 1000L),
                Map.entry("SIDE", "BUY"), Map.entry("QUANTITY", 1L),
                Map.entry("UNIT_PRICE", 100L), Map.entry("GROSS_AMOUNT", 100L),
                Map.entry("COST_BASIS_AMOUNT", 100L), Map.entry("REALIZED_GAIN", 0L),
                Map.entry("SEASON_NUMBER", 1), Map.entry("GAME_DAY", 1),
                Map.entry("IDEMPOTENCY_KEY", "generator-market"),
                Map.entry("CORRELATION_ID", "generator-market"),
                Map.entry("CASH_BALANCE_AFTER", 0L), Map.entry("QUANTITY_AFTER", 1L),
                Map.entry("COST_BASIS_AFTER", 100L))));
        return save;
    }

    private Map<String, Object> highIdV11Save() {
        Map<String, Object> save = highIdSave();
        save.put("saveVersion", 11);
        save.put("marketInstruments", List.of(Map.ofEntries(
                Map.entry("ID", 1000L), Map.entry("CODE", "GENERATOR_CLUB"),
                Map.entry("INSTRUMENT_TYPE", "CLUB"), Map.entry("TEAM_ID", 1000L),
                Map.entry("NAME", "Generator club"), Map.entry("TOTAL_SUPPLY", 10L),
                Map.entry("AVAILABLE_SUPPLY", 9L), Map.entry("CURRENT_PRICE", 100L),
                Map.entry("PRICE_SEED", 42L), Map.entry("PRICE_ALGORITHM_VERSION", "market-v1"),
                Map.entry("RISK_CLASS", "CLUB_EQUITY"), Map.entry("RISK_CONFIG_VERSION", "risk-v1"),
                Map.entry("DAILY_LIMIT_BPS", 500), Map.entry("WEEKLY_LIMIT_BPS", 1500),
                Map.entry("ACTIVE", true), Map.entry("VERSION", 0L))));
        save.put("clubCapTableStates", List.of(Map.of(
                "ID", 1000L, "INSTRUMENT_ID", 1000L, "TEAM_ID", 1000L,
                "CONTROL_THRESHOLD_BPS", 5001, "VERSION", 0L)));
        save.put("takeoverQuotes", List.of(Map.ofEntries(
                Map.entry("ID", 1000L), Map.entry("BUYER_ACCOUNT_ID", 1000L),
                Map.entry("BUYER_PROFILE_ID", 1000L), Map.entry("INSTRUMENT_ID", 1000L),
                Map.entry("TEAM_ID", 1000L), Map.entry("SHARES_TO_ACQUIRE", 1L),
                Map.entry("UNIT_PRICE", 100L), Map.entry("TOTAL_CONSIDERATION", 100L),
                Map.entry("STATUS", "EXECUTED"))));
        save.put("takeoverExecutions", List.of(Map.ofEntries(
                Map.entry("ID", 1000L), Map.entry("QUOTE_ID", 1000L),
                Map.entry("BUYER_ACCOUNT_ID", 1000L), Map.entry("BUYER_PROFILE_ID", 1000L),
                Map.entry("INSTRUMENT_ID", 1000L), Map.entry("TEAM_ID", 1000L),
                Map.entry("SHARES_ACQUIRED", 1L), Map.entry("UNIT_PRICE", 100L),
                Map.entry("TOTAL_CONSIDERATION", 100L))));
        save.put("clubCashTransfers", List.of(Map.ofEntries(
                Map.entry("ID", 1000L), Map.entry("ACCOUNT_ID", 1000L),
                Map.entry("PROFILE_ID", 1000L), Map.entry("TEAM_ID", 1000L),
                Map.entry("AMOUNT", 1L), Map.entry("PERSONAL_BALANCE_AFTER", 0L),
                Map.entry("CLUB_BALANCE_AFTER", 0L), Map.entry("CORRELATION_ID", "generator-test"))));
        save.put("financialRecords", List.of(Map.of(
                "ID", 1000L, "TEAM_ID", 1000L, "DESCRIPTION", "generator-test")));
        save.put("traderAdviserContracts", List.of(Map.ofEntries(
                Map.entry("ID", 1000L), Map.entry("ACCOUNT_ID", 1000L),
                Map.entry("PROFILE_ID", 1000L), Map.entry("SKILL", 70),
                Map.entry("REPUTATION", 65), Map.entry("SALARY_PER_DAY", 7500L),
                Map.entry("CONTRACT_START_ABSOLUTE_DAY", 1L),
                Map.entry("CONTRACT_END_ABSOLUTE_DAY", 30L),
                Map.entry("LAST_PAID_ABSOLUTE_DAY", 0L))));
        save.put("traderAdviceRecommendations", List.of(Map.ofEntries(
                Map.entry("ID", 1000L), Map.entry("CONTRACT_ID", 1000L),
                Map.entry("INSTRUMENT_ID", 1000L), Map.entry("SEASON_NUMBER", 1),
                Map.entry("GAME_DAY", 1), Map.entry("ACTION", "HOLD"),
                Map.entry("RISK_CLASS", "CLUB_EQUITY"), Map.entry("CONFIDENCE", 0.5),
                Map.entry("RISK", 0.5), Map.entry("OBSERVED_VOLATILITY", 0.1))));
        return save;
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Config {
        @Bean
        DataSource dataSource() throws Exception {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:regent-generator-cross-instance-" + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
            dataSource.setUser("sa");
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                for (String sequence : Set.copyOf(SEQUENCES.values())) {
                    statement.execute("CREATE SEQUENCE \"" + sequence + "\" START WITH 1 INCREMENT BY 1");
                }
                for (String table : GameSaveImportService.manifestTableNames()) {
                    String defaultValue = SEQUENCES.containsKey(table)
                            ? " DEFAULT NEXT VALUE FOR \"" + SEQUENCES.get(table) + "\"" : "";
                    String extra = switch (table) {
                        case "ROUND" -> ", SEASON BIGINT";
                        case "GAME_CALENDAR" -> ", SEASON INTEGER";
                        case "HUMAN" -> ", TYPE_ID BIGINT, RETIRED BOOLEAN, NAME VARCHAR(255)";
                        case "PERSONAL_ACCOUNT" -> ", PROFILE_ID BIGINT DEFAULT 0, OWNER_USER_ID INTEGER, "
                                + "OWNER_HUMAN_ID BIGINT, CASH_BALANCE BIGINT DEFAULT 0, "
                                + "LIFETIME_CAREER_EARNINGS BIGINT DEFAULT 0, REALIZED_INVESTMENT_GAIN BIGINT DEFAULT 0, "
                                + "VERSION BIGINT DEFAULT 0";
                        case "ASSET_CATALOG_ITEM" -> ", CODE VARCHAR(60) DEFAULT 'TEST', "
                                + "ASSET_TYPE VARCHAR(20) DEFAULT 'CAR', APARTMENT_ROOMS INTEGER, "
                                + "NAME VARCHAR(120) DEFAULT 'Test', ICON_KEY VARCHAR(80) DEFAULT 'test', "
                                + "PURCHASE_PRICE BIGINT DEFAULT 1, RESALE_HAIRCUT_BPS INTEGER DEFAULT 0, "
                                + "ACTIVE BOOLEAN DEFAULT TRUE, VERSION BIGINT DEFAULT 0";
                        case "OWNED_ASSET" -> ", ACCOUNT_ID BIGINT DEFAULT 1, PROFILE_ID BIGINT DEFAULT 0, "
                                + "CATALOG_ITEM_ID BIGINT DEFAULT 1, PURCHASE_PRICE BIGINT DEFAULT 0, "
                                + "CURRENT_VALUE BIGINT DEFAULT 0, PURCHASE_SEASON INTEGER DEFAULT 0, "
                                + "PURCHASE_DAY INTEGER DEFAULT 0, STATUS VARCHAR(20) DEFAULT 'OWNED', "
                                + "PURCHASE_IDEMPOTENCY_KEY VARCHAR(160) DEFAULT 'test', SALE_IDEMPOTENCY_KEY VARCHAR(160), "
                                + "SALE_PRICE BIGINT, SALE_SEASON INTEGER, SALE_DAY INTEGER, VERSION BIGINT DEFAULT 0";
                        case "PERSONAL_LEDGER_ENTRY" -> ", ACCOUNT_ID BIGINT DEFAULT 1, PROFILE_ID BIGINT DEFAULT 0, "
                                + "SEASON_NUMBER INTEGER DEFAULT 0, GAME_DAY INTEGER DEFAULT 0, "
                                + "ENTRY_TYPE VARCHAR(40) DEFAULT 'MIGRATION_OPENING', SIGNED_AMOUNT BIGINT DEFAULT 0, "
                                + "CAREER_EARNINGS_DELTA BIGINT DEFAULT 0, BALANCE_AFTER BIGINT DEFAULT 0, "
                                + "CORRELATION_ID VARCHAR(120) DEFAULT 'test', IDEMPOTENCY_KEY VARCHAR(160) DEFAULT 'test', "
                                + "COUNTERPART_TEAM_ID BIGINT, COUNTERPART_ASSET_ID BIGINT, "
                                + "DESCRIPTION VARCHAR(300) DEFAULT 'test', CREATED_AT BIGINT DEFAULT 0";
                        case "MARKET_INSTRUMENT" -> ", CODE VARCHAR(80) DEFAULT 'TEST', "
                                + "INSTRUMENT_TYPE VARCHAR(20) DEFAULT 'COMPANY', TEAM_ID BIGINT, "
                                + "NAME VARCHAR(160) DEFAULT 'Test', TOTAL_SUPPLY BIGINT DEFAULT 1, "
                                + "AVAILABLE_SUPPLY BIGINT DEFAULT 1, CURRENT_PRICE BIGINT DEFAULT 1, "
                                + "PRICE_SEED BIGINT DEFAULT 1, PRICE_ALGORITHM_VERSION VARCHAR(32) DEFAULT 'market-v1', "
                                + "RISK_CLASS VARCHAR(24) DEFAULT 'SAFE_COMPANY', "
                                + "RISK_CONFIG_VERSION VARCHAR(32) DEFAULT 'risk-v1', "
                                + "DAILY_LIMIT_BPS INTEGER DEFAULT 500, "
                                + "WEEKLY_LIMIT_BPS INTEGER DEFAULT 1500, ACTIVE BOOLEAN DEFAULT TRUE, "
                                + "VERSION BIGINT DEFAULT 0";
                        case "MARKET_PRICE_SNAPSHOT" -> ", INSTRUMENT_ID BIGINT DEFAULT 1, "
                                + "SEASON_NUMBER INTEGER DEFAULT 0, GAME_DAY INTEGER DEFAULT 0, "
                                + "PREVIOUS_CLOSE BIGINT DEFAULT 1, CLOSE_PRICE BIGINT DEFAULT 1, "
                                + "WEEKLY_ANCHOR_PRICE BIGINT DEFAULT 1, DAILY_CHANGE_BPS INTEGER DEFAULT 0, "
                                + "ALGORITHM_VERSION VARCHAR(32) DEFAULT 'market-v1', DETERMINISTIC_HASH BIGINT DEFAULT 0";
                        case "PORTFOLIO_POSITION" -> ", ACCOUNT_ID BIGINT DEFAULT 1, PROFILE_ID BIGINT DEFAULT 0, "
                                + "INSTRUMENT_ID BIGINT DEFAULT 1, QUANTITY BIGINT DEFAULT 0, "
                                + "TOTAL_COST_BASIS BIGINT DEFAULT 0, VERSION BIGINT DEFAULT 0";
                        case "MARKET_TRADE" -> ", ACCOUNT_ID BIGINT DEFAULT 1, PROFILE_ID BIGINT DEFAULT 0, "
                                + "INSTRUMENT_ID BIGINT DEFAULT 1, SIDE VARCHAR(10) DEFAULT 'BUY', "
                                + "QUANTITY BIGINT DEFAULT 1, UNIT_PRICE BIGINT DEFAULT 1, "
                                + "GROSS_AMOUNT BIGINT DEFAULT 1, COST_BASIS_AMOUNT BIGINT DEFAULT 1, "
                                + "REALIZED_GAIN BIGINT DEFAULT 0, SEASON_NUMBER INTEGER DEFAULT 0, "
                                + "GAME_DAY INTEGER DEFAULT 0, IDEMPOTENCY_KEY VARCHAR(160) DEFAULT 'test', "
                                + "CORRELATION_ID VARCHAR(160) DEFAULT 'test', CASH_BALANCE_AFTER BIGINT DEFAULT 0, "
                                + "QUANTITY_AFTER BIGINT DEFAULT 1, COST_BASIS_AFTER BIGINT DEFAULT 1";
                        case "CLUB_CAP_TABLE_STATE" -> ", INSTRUMENT_ID BIGINT DEFAULT 1, "
                                + "TEAM_ID BIGINT DEFAULT 1, CONTROLLING_ACCOUNT_ID BIGINT, "
                                + "CONTROL_THRESHOLD_BPS INTEGER DEFAULT 5001, VERSION BIGINT DEFAULT 0";
                        case "TAKEOVER_QUOTE" -> ", BUYER_ACCOUNT_ID BIGINT DEFAULT 1, "
                                + "BUYER_PROFILE_ID BIGINT DEFAULT 0, INSTRUMENT_ID BIGINT DEFAULT 1, "
                                + "TEAM_ID BIGINT DEFAULT 1, SHARES_TO_ACQUIRE BIGINT DEFAULT 1, "
                                + "UNIT_PRICE BIGINT DEFAULT 1, TOTAL_CONSIDERATION BIGINT DEFAULT 1, "
                                + "STATUS VARCHAR(16) DEFAULT 'EXECUTED'";
                        case "TAKEOVER_EXECUTION" -> ", QUOTE_ID BIGINT DEFAULT 1, "
                                + "BUYER_ACCOUNT_ID BIGINT DEFAULT 1, BUYER_PROFILE_ID BIGINT DEFAULT 0, "
                                + "INSTRUMENT_ID BIGINT DEFAULT 1, TEAM_ID BIGINT DEFAULT 1, "
                                + "SHARES_ACQUIRED BIGINT DEFAULT 1, UNIT_PRICE BIGINT DEFAULT 1, "
                                + "TOTAL_CONSIDERATION BIGINT DEFAULT 1";
                        case "CLUB_CASH_TRANSFER" -> ", ACCOUNT_ID BIGINT DEFAULT 1, "
                                + "PROFILE_ID BIGINT DEFAULT 0, TEAM_ID BIGINT DEFAULT 1, AMOUNT BIGINT DEFAULT 1, "
                                + "PERSONAL_BALANCE_AFTER BIGINT DEFAULT 0, CLUB_BALANCE_AFTER BIGINT DEFAULT 0, "
                                + "CORRELATION_ID VARCHAR(120) DEFAULT 'test'";
                        case "FINANCIAL_RECORD" -> ", TEAM_ID BIGINT DEFAULT 1, "
                                + "DESCRIPTION VARCHAR(300) DEFAULT 'test'";
                        case "TRADER_ADVISER_CONTRACT" -> ", ACCOUNT_ID BIGINT DEFAULT 1, "
                                + "PROFILE_ID BIGINT DEFAULT 0, SKILL INTEGER DEFAULT 50, "
                                + "REPUTATION INTEGER DEFAULT 50, SALARY_PER_DAY BIGINT DEFAULT 0, "
                                + "CONTRACT_START_ABSOLUTE_DAY BIGINT DEFAULT 1, "
                                + "CONTRACT_END_ABSOLUTE_DAY BIGINT DEFAULT 1, "
                                + "LAST_PAID_ABSOLUTE_DAY BIGINT DEFAULT 0";
                        case "TRADER_ADVICE_RECOMMENDATION" -> ", CONTRACT_ID BIGINT DEFAULT 1, "
                                + "INSTRUMENT_ID BIGINT DEFAULT 1, SEASON_NUMBER INTEGER DEFAULT 1, "
                                + "GAME_DAY INTEGER DEFAULT 1, ACTION VARCHAR(8) DEFAULT 'HOLD', "
                                + "RISK_CLASS VARCHAR(24) DEFAULT 'SAFE_COMPANY', CONFIDENCE DECIMAL(8,4) DEFAULT 0.5, "
                                + "RISK DECIMAL(8,4) DEFAULT 0.5, OBSERVED_VOLATILITY DECIMAL(20,8) DEFAULT 0";
                        default -> "";
                    };
                    statement.execute("CREATE TABLE \"" + table + "\" (ID BIGINT" + defaultValue
                            + (defaultValue.isEmpty() ? " GENERATED BY DEFAULT AS IDENTITY" : "")
                            + " PRIMARY KEY" + extra + ")");
                    statement.execute("INSERT INTO \"" + table + "\" DEFAULT VALUES");
                }
                statement.execute("CREATE TABLE USERS (ID INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                        + "TEAM_ID BIGINT, LAST_TEAM_ID BIGINT, MANAGER_ID BIGINT)");
                statement.execute("CREATE TABLE PERSON_PROFILE ("
                        + "ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, USER_ID INT UNIQUE, "
                        + "HUMAN_ID BIGINT UNIQUE, CAREER_TYPE VARCHAR(20) NOT NULL, "
                        + "CONTROL_TYPE VARCHAR(20) NOT NULL, DISPLAY_NAME VARCHAR(255) NOT NULL, "
                        + "CREATED_SEASON INT NOT NULL, CREATED_DAY INT NOT NULL, "
                        + "ACTIVE BOOLEAN NOT NULL, RETIRED BOOLEAN NOT NULL)");
            }
            return dataSource;
        }

        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean PersonProfileService personProfileService() { return mock(PersonProfileService.class); }
        @Bean GameSaveImportService gameSaveImportService(DataSource dataSource, ObjectMapper mapper,
                                                           PersonProfileService profiles) {
            return new GameSaveImportService(dataSource, mapper, profiles);
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
