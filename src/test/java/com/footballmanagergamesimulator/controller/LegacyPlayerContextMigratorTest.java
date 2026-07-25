package com.footballmanagergamesimulator.controller;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyPlayerContextMigratorTest {
    @Test
    void migratesPositionsAndPreferredFootDeterministicallyForPlayersOnly() {
        List<GameSaveImportService.TableRows> first = LegacyPlayerContextMigrator.migrate(tables(List.of(
                human(3L, 1L, "ST", "Right"),
                human(1L, 1L, " mc ", "Left"),
                human(4L, 1L, "DMC", "Both"),
                human(2L, 4L, "GK", "Right"),
                human(5L, 1L, null, "Unknown"))));
        List<GameSaveImportService.TableRows> second = LegacyPlayerContextMigrator.migrate(tables(List.of(
                human(5L, 1L, null, "Unknown"),
                human(2L, 4L, "GK", "Right"),
                human(4L, 1L, "DMC", "Both"),
                human(1L, 1L, " mc ", "Left"),
                human(3L, 1L, "ST", "Right"))));

        assertThat(rows(first, "PLAYER_POSITION_FAMILIARITY"))
                .containsExactly(
                        Map.of("ID", 1L, "PLAYER_ID", 1L, "POSITION_CODE", "MC",
                                "FAMILIARITY", 20, "PRIMARY_POSITION", true, "VERSION", 0L),
                        Map.of("ID", 3L, "PLAYER_ID", 3L, "POSITION_CODE", "ST",
                                "FAMILIARITY", 20, "PRIMARY_POSITION", true, "VERSION", 0L));
        assertThat(rows(first, "PLAYER_ROLE_FAMILIARITY")).isEmpty();
        assertThat(rows(first, "PLAYER_FOOT_PROFILE"))
                .containsExactly(
                        Map.of("ID", 1L, "PLAYER_ID", 1L, "LEFT_FOOT_RATING", 20,
                                "RIGHT_FOOT_RATING", 8, "VERSION", 0L),
                        Map.of("ID", 3L, "PLAYER_ID", 3L, "LEFT_FOOT_RATING", 8,
                                "RIGHT_FOOT_RATING", 20, "VERSION", 0L),
                        Map.of("ID", 4L, "PLAYER_ID", 4L, "LEFT_FOOT_RATING", 16,
                                "RIGHT_FOOT_RATING", 16, "VERSION", 0L),
                        Map.of("ID", 5L, "PLAYER_ID", 5L, "LEFT_FOOT_RATING", 8,
                                "RIGHT_FOOT_RATING", 20, "VERSION", 0L));
        assertThat(rows(second, "PLAYER_POSITION_FAMILIARITY"))
                .isEqualTo(rows(first, "PLAYER_POSITION_FAMILIARITY"));
        assertThat(rows(second, "PLAYER_ROLE_FAMILIARITY"))
                .isEqualTo(rows(first, "PLAYER_ROLE_FAMILIARITY"));
        assertThat(rows(second, "PLAYER_FOOT_PROFILE"))
                .isEqualTo(rows(first, "PLAYER_FOOT_PROFILE"));
        assertThat(rows(LegacyPlayerContextMigrator.migrate(first), "PLAYER_POSITION_FAMILIARITY"))
                .isEqualTo(rows(first, "PLAYER_POSITION_FAMILIARITY"));
        assertThat(rows(LegacyPlayerContextMigrator.migrate(first), "PLAYER_FOOT_PROFILE"))
                .isEqualTo(rows(first, "PLAYER_FOOT_PROFILE"));
    }

    private static List<GameSaveImportService.TableRows> tables(List<Map<String, Object>> humans) {
        return List.of(
                table("HUMAN", "humans", humans),
                table("PLAYER_POSITION_FAMILIARITY", "playerPositionFamiliarities", List.of()),
                table("PLAYER_ROLE_FAMILIARITY", "playerRoleFamiliarities", List.of()),
                table("PLAYER_FOOT_PROFILE", "playerFootProfiles", List.of()));
    }

    private static GameSaveImportService.TableRows table(String tableName, String key, List<Map<String, Object>> rows) {
        return new GameSaveImportService.TableRows(
                new GameSaveImportService.TableSpec(key, tableName, 12),
                rows.stream().map(row -> GameSaveImportService.RowValues.from(new LinkedHashMap<>(row))).toList());
    }

    private static Map<String, Object> human(long id, long typeId, String position, String preferredFoot) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("ID", id);
        row.put("TYPE_ID", typeId);
        row.put("POSITION", position);
        row.put("PREFERRED_FOOT", preferredFoot);
        return row;
    }

    private static List<Map<String, Object>> rows(List<GameSaveImportService.TableRows> tables, String tableName) {
        return tables.stream()
                .filter(table -> table.spec().tableName().equals(tableName))
                .findFirst()
                .orElseThrow()
                .rows().stream()
                .map(GameSaveImportService.RowValues::asMap)
                .toList();
    }
}
