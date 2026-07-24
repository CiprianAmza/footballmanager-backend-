package com.footballmanagergamesimulator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.person.PersonProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-save-real-schema;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({GameSaveImportService.class, GameSaveRealSchemaTest.Config.class})
class GameSaveRealSchemaTest {

    private static final List<String> MANIFEST_KEYS = GameSaveImportService.manifestKeys();

    @Autowired private GameSaveImportService importService;
    @MockBean private PersonProfileService personProfileService;

    @Test
    void v6AndMigratedV5FullyPreflightAgainstRealEntitySchema() {
        Map<String, Object> v6 = realSchemaSave(6);
        assertThat(v6).doesNotContainKeys("users", "personProfiles");
        assertThat(importService.prepare(v6).sourceVersion()).isEqualTo(6);

        Map<String, Object> v5 = new LinkedHashMap<>(v6);
        v5.put("saveVersion", 5);
        v5.put("users", List.of(Map.of("id", 999, "teamId", 999, "managerId", 999)));
        assertThat(importService.prepare(v5).sourceVersion()).isEqualTo(5);
    }

    @Test
    void humanStayForwardImportDefaultsLegacyMissingAndNullValuesToFalse() {
        Map<String, Object> v10 = realSchemaSave(10);
        Map<String, Object> missing = humanRow(10L, "Legacy missing");
        Map<String, Object> explicitNull = humanRow(11L, "Legacy null");
        explicitNull.put("stayForward", null);
        Map<String, Object> explicitTrue = humanRow(12L, "Kvekrpur");
        explicitTrue.put("stayForward", true);
        v10.put("humans", List.of(missing, explicitNull, explicitTrue));

        GameSaveImportService.ImportPlan plan = importService.prepare(v10);

        List<Map<String, Object>> humans = plan.tables().stream()
                .filter(table -> table.spec().tableName().equals("HUMAN"))
                .findFirst()
                .orElseThrow()
                .rows().stream()
                .map(GameSaveImportService.RowValues::asMap)
                .toList();

        assertThat(humans).extracting(row -> row.get("STAY_FORWARD"))
                .containsExactly(false, false, true);
    }

    @Test
    void oldSaveImportPromotesOnlyCanonicalSeededStayForwardIdentities() {
        Map<String, Object> v9 = realSchemaSave(9);
        Map<String, Object> kvekrpur = canonicalSeedRow(107L, "Kvekrpur", 14L, 34, 1L, 367.6487908232764);
        Map<String, Object> dostoievski = canonicalSeedRow(108L, "Dostoievski", 14L, 29, 7L, 342.25);
        Map<String, Object> shakespeare = canonicalSeedRow(4060L, "Shakespeare", 13L, 25, 6L, 300.0);
        Map<String, Object> sameNameWrongTeam = canonicalSeedRow(759L, "Kvekrpur", 22L, 26, 4L, 149.85);
        Map<String, Object> sameNameWrongPosition = canonicalSeedRow(4061L, "Shakespeare", 13L, 25, 6L, 300.0);
        sameNameWrongPosition.put("position", "MC");
        Map<String, Object> sameNameWrongType = canonicalSeedRow(1080L, "Dostoievski", 14L, 29, 7L, 342.25);
        sameNameWrongType.put("typeId", 4L);
        v9.put("humans", List.of(kvekrpur, dostoievski, shakespeare,
                sameNameWrongTeam, sameNameWrongPosition, sameNameWrongType));

        GameSaveImportService.ImportPlan plan = importService.prepare(v9);

        assertThat(humanStayForwardById(plan))
                .containsEntry(107L, true)
                .containsEntry(108L, true)
                .containsEntry(4060L, true)
                .containsEntry(759L, false)
                .containsEntry(4061L, false)
                .containsEntry(1080L, false);
    }

    @Test
    void v10ExplicitFalseRoundTripsWithoutLegacyPromotion() {
        Map<String, Object> v10 = realSchemaSave(10);
        Map<String, Object> kvekrpurFalse = canonicalSeedRow(
                107L, "Kvekrpur", 14L, 34, 1L, 367.6487908232764);
        kvekrpurFalse.put("stayForward", false);
        v10.put("humans", List.of(kvekrpurFalse));

        GameSaveImportService.ImportPlan plan = importService.prepare(v10);

        assertThat(humanStayForwardById(plan)).containsEntry(107L, false);
    }

    @Test
    void v7PersonalEconomyPreflightAcceptsReconciledStateAndRejectsLedgerDrift() {
        Map<String, Object> v7 = realSchemaSave(7);
        v7.put("personalAccounts", List.of(Map.of(
                "ID", 700L, "PROFILE_ID", 999L, "OWNER_HUMAN_ID", 10L,
                "CASH_BALANCE", 1_000L, "LIFETIME_CAREER_EARNINGS", 0L,
                "REALIZED_INVESTMENT_GAIN", 0L, "VERSION", 0L)));
        v7.put("assetCatalogItems", List.of(Map.of(
                "ID", 701L, "CODE", "ROUNDTRIP_CAR", "ASSET_TYPE", "CAR",
                "NAME", "Round-trip car", "ICON_KEY", "car", "PURCHASE_PRICE", 500L,
                "RESALE_HAIRCUT_BPS", 1000, "ACTIVE", true, "VERSION", 0L)));
        v7.put("ownedAssets", List.of(Map.ofEntries(
                Map.entry("ID", 702L), Map.entry("ACCOUNT_ID", 700L),
                Map.entry("PROFILE_ID", 999L), Map.entry("CATALOG_ITEM_ID", 701L),
                Map.entry("PURCHASE_PRICE", 500L), Map.entry("CURRENT_VALUE", 500L),
                Map.entry("PURCHASE_SEASON", 1), Map.entry("PURCHASE_DAY", 2),
                Map.entry("STATUS", "OWNED"), Map.entry("PURCHASE_IDEMPOTENCY_KEY", "save-buy"),
                Map.entry("VERSION", 0L))));
        v7.put("personalLedgerEntries", List.of(Map.ofEntries(
                Map.entry("ID", 703L), Map.entry("ACCOUNT_ID", 700L), Map.entry("PROFILE_ID", 999L),
                Map.entry("SEASON_NUMBER", 1), Map.entry("GAME_DAY", 2),
                Map.entry("ENTRY_TYPE", "MIGRATION_OPENING"), Map.entry("SIGNED_AMOUNT", 1_000L),
                Map.entry("CAREER_EARNINGS_DELTA", 0L), Map.entry("BALANCE_AFTER", 1_000L),
                Map.entry("CORRELATION_ID", "save-open"), Map.entry("IDEMPOTENCY_KEY", "save-open"),
                Map.entry("DESCRIPTION", "Save opening"), Map.entry("CREATED_AT", 1L))));

        assertThat(importService.prepare(v7).sourceVersion()).isEqualTo(7);

        Map<String, Object> corrupt = new LinkedHashMap<>(v7);
        corrupt.put("personalLedgerEntries", List.of(Map.ofEntries(
                Map.entry("ID", 703L), Map.entry("ACCOUNT_ID", 700L), Map.entry("PROFILE_ID", 999L),
                Map.entry("SEASON_NUMBER", 1), Map.entry("GAME_DAY", 2),
                Map.entry("ENTRY_TYPE", "MIGRATION_OPENING"), Map.entry("SIGNED_AMOUNT", 999L),
                Map.entry("CAREER_EARNINGS_DELTA", 0L), Map.entry("BALANCE_AFTER", 999L),
                Map.entry("CORRELATION_ID", "save-open"), Map.entry("IDEMPOTENCY_KEY", "save-open"),
                Map.entry("DESCRIPTION", "Save opening"), Map.entry("CREATED_AT", 1L))));
        assertThatThrownBy(() -> importService.prepare(corrupt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not reconcile");
    }

    @Test
    void v8MarketPreflightAcceptsConservedSupplyAndRejectsMintedShares() {
        Map<String, Object> v8 = realSchemaSave(8);
        v8.put("personalAccounts", List.of(Map.of(
                "ID", 700L, "PROFILE_ID", 999L, "OWNER_HUMAN_ID", 10L,
                "CASH_BALANCE", 900L, "LIFETIME_CAREER_EARNINGS", 0L,
                "REALIZED_INVESTMENT_GAIN", 0L, "VERSION", 0L)));
        v8.put("personalLedgerEntries", List.of(
                Map.ofEntries(
                        Map.entry("ID", 703L), Map.entry("ACCOUNT_ID", 700L),
                        Map.entry("PROFILE_ID", 999L), Map.entry("SEASON_NUMBER", 0),
                        Map.entry("GAME_DAY", 0), Map.entry("ENTRY_TYPE", "MIGRATION_OPENING"),
                        Map.entry("SIGNED_AMOUNT", 1_000L), Map.entry("CAREER_EARNINGS_DELTA", 0L),
                        Map.entry("BALANCE_AFTER", 1_000L), Map.entry("CORRELATION_ID", "save-open"),
                        Map.entry("IDEMPOTENCY_KEY", "save-open"), Map.entry("DESCRIPTION", "Save opening"),
                        Map.entry("CREATED_AT", 1L)),
                Map.ofEntries(
                        Map.entry("ID", 704L), Map.entry("ACCOUNT_ID", 700L),
                        Map.entry("PROFILE_ID", 999L), Map.entry("SEASON_NUMBER", 1),
                        Map.entry("GAME_DAY", 1), Map.entry("ENTRY_TYPE", "INVESTMENT_BUY"),
                        Map.entry("SIGNED_AMOUNT", -100L), Map.entry("CAREER_EARNINGS_DELTA", 0L),
                        Map.entry("BALANCE_AFTER", 900L), Map.entry("CORRELATION_ID", "MARKET-TRADE:save-buy"),
                        Map.entry("IDEMPOTENCY_KEY", "MARKET:save-buy"), Map.entry("DESCRIPTION", "Saved buy"),
                        Map.entry("CREATED_AT", 2L))));
        v8.put("marketInstruments", List.of(Map.ofEntries(
                Map.entry("ID", 800L), Map.entry("CODE", "SAVE-CO"),
                Map.entry("INSTRUMENT_TYPE", "COMPANY"), Map.entry("NAME", "Save Company"),
                Map.entry("TOTAL_SUPPLY", 1_000L), Map.entry("AVAILABLE_SUPPLY", 900L),
                Map.entry("CURRENT_PRICE", 1L), Map.entry("PRICE_SEED", 42L),
                Map.entry("PRICE_ALGORITHM_VERSION", "market-v1"),
                Map.entry("DAILY_LIMIT_BPS", 500), Map.entry("WEEKLY_LIMIT_BPS", 1_500),
                Map.entry("ACTIVE", true), Map.entry("VERSION", 0L))));
        v8.put("marketPriceSnapshots", List.of(Map.of(
                "ID", 801L, "INSTRUMENT_ID", 800L, "SEASON_NUMBER", 1, "GAME_DAY", 1,
                "PREVIOUS_CLOSE", 1L, "CLOSE_PRICE", 1L, "WEEKLY_ANCHOR_PRICE", 1L,
                "DAILY_CHANGE_BPS", 0, "ALGORITHM_VERSION", "market-v1", "DETERMINISTIC_HASH", 42L)));
        v8.put("portfolioPositions", List.of(Map.of(
                "ID", 802L, "ACCOUNT_ID", 700L, "PROFILE_ID", 999L,
                "INSTRUMENT_ID", 800L, "QUANTITY", 100L,
                "TOTAL_COST_BASIS", 100L, "VERSION", 0L)));
        v8.put("marketTrades", List.of(Map.ofEntries(
                Map.entry("ID", 803L), Map.entry("ACCOUNT_ID", 700L),
                Map.entry("PROFILE_ID", 999L), Map.entry("INSTRUMENT_ID", 800L),
                Map.entry("SIDE", "BUY"), Map.entry("QUANTITY", 100L),
                Map.entry("UNIT_PRICE", 1L), Map.entry("GROSS_AMOUNT", 100L),
                Map.entry("COST_BASIS_AMOUNT", 100L), Map.entry("REALIZED_GAIN", 0L),
                Map.entry("SEASON_NUMBER", 1), Map.entry("GAME_DAY", 1),
                Map.entry("IDEMPOTENCY_KEY", "save-buy"),
                Map.entry("CORRELATION_ID", "MARKET-TRADE:save-buy"),
                Map.entry("CASH_BALANCE_AFTER", 900L), Map.entry("QUANTITY_AFTER", 100L),
                Map.entry("COST_BASIS_AFTER", 100L))));

        assertThat(importService.prepare(v8).sourceVersion()).isEqualTo(8);

        Map<String, Object> minted = new LinkedHashMap<>(v8);
        minted.put("marketInstruments", List.of(Map.ofEntries(
                Map.entry("ID", 800L), Map.entry("CODE", "SAVE-CO"),
                Map.entry("INSTRUMENT_TYPE", "COMPANY"), Map.entry("NAME", "Save Company"),
                Map.entry("TOTAL_SUPPLY", 1_000L), Map.entry("AVAILABLE_SUPPLY", 901L),
                Map.entry("CURRENT_PRICE", 1L), Map.entry("PRICE_SEED", 42L),
                Map.entry("PRICE_ALGORITHM_VERSION", "market-v1"),
                Map.entry("DAILY_LIMIT_BPS", 500), Map.entry("WEEKLY_LIMIT_BPS", 1_500),
                Map.entry("ACTIVE", true), Map.entry("VERSION", 0L))));
        assertThatThrownBy(() -> importService.prepare(minted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supply does not reconcile");
    }

    private Map<String, Object> realSchemaSave(int version) {
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("saveVersion", version);
        MANIFEST_KEYS.forEach(key -> save.put(key, List.of()));

        Round round = new Round();
        round.setId(1L);
        round.setSeason(1);
        GameCalendar calendar = new GameCalendar();
        calendar.setId(1L);
        calendar.setSeason(1);
        calendar.setCurrentDay(1);
        calendar.setCurrentPhase("MORNING");
        calendar.setSeasonPhase("PRE_SEASON");
        Team team = new Team();
        team.setId(1L);
        team.setName("Schema FC");
        Human human = new Human();
        human.setId(10L);
        human.setName("Schema manager");
        human.setTeamId(1L);
        human.setTypeId(4L);

        save.put("rounds", List.of(round));
        save.put("gameCalendars", List.of(calendar));
        save.put("teams", List.of(team));
        save.put("humans", List.of(human));
        return save;
    }

    private Map<String, Object> humanRow(long id, String name) {
        Map<String, Object> human = new LinkedHashMap<>();
        human.put("id", id);
        human.put("name", name);
        human.put("teamId", 1L);
        human.put("typeId", 1L);
        human.put("retired", false);
        return human;
    }

    private Map<String, Object> canonicalSeedRow(long id, String name, long teamId,
                                                 int age, long seasonCreated, double rating) {
        Map<String, Object> human = humanRow(id, name);
        human.put("teamId", teamId);
        human.put("position", "ST");
        human.put("age", age);
        human.put("seasonCreated", seasonCreated);
        human.put("rating", rating);
        return human;
    }

    private Map<Long, Boolean> humanStayForwardById(GameSaveImportService.ImportPlan plan) {
        Map<Long, Boolean> result = new LinkedHashMap<>();
        plan.tables().stream()
                .filter(table -> table.spec().tableName().equals("HUMAN"))
                .findFirst()
                .orElseThrow()
                .rows().stream()
                .map(GameSaveImportService.RowValues::asMap)
                .forEach(row -> result.put(((Number) row.get("ID")).longValue(),
                        Boolean.TRUE.equals(row.get("STAY_FORWARD"))));
        return result;
    }

    @TestConfiguration
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
