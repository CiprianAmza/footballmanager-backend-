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

    @TestConfiguration
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
