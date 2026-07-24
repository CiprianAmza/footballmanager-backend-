package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.service.PlayerCapabilityService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class LegacyPlayerContextMigrator {
    private LegacyPlayerContextMigrator() {}

    static List<GameSaveImportService.TableRows> migrate(List<GameSaveImportService.TableRows> tables) {
        List<Map<String, Object>> humanRows = tables.stream()
                .filter(table -> table.spec().tableName().equals("HUMAN"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("HUMAN rows are required before player context migration"))
                .rows().stream()
                .map(GameSaveImportService.RowValues::asMap)
                .sorted((left, right) -> Long.compare(longValue(left.get("ID")), longValue(right.get("ID"))))
                .toList();

        List<GameSaveImportService.RowValues> positions = new ArrayList<>();
        List<GameSaveImportService.RowValues> feet = new ArrayList<>();
        for (Map<String, Object> human : humanRows) {
            if (longValue(human.get("TYPE_ID")) != 1L) {
                continue;
            }
            long playerId = longValue(human.get("ID"));
            PlayerPosition.parse(text(human.get("POSITION"))).ifPresent(position -> {
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                row.put("ID", playerId);
                row.put("PLAYER_ID", playerId);
                row.put("POSITION_CODE", position.code());
                row.put("FAMILIARITY", 20);
                row.put("PRIMARY_POSITION", true);
                row.put("VERSION", 0L);
                positions.add(GameSaveImportService.RowValues.from(row));
            });

            PlayerCapabilityService.FootRatings ratings =
                    PlayerCapabilityService.legacyFootRatings(text(human.get("PREFERRED_FOOT")));
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("ID", playerId);
            row.put("PLAYER_ID", playerId);
            row.put("LEFT_FOOT_RATING", ratings.left());
            row.put("RIGHT_FOOT_RATING", ratings.right());
            row.put("VERSION", 0L);
            feet.add(GameSaveImportService.RowValues.from(row));
        }

        List<GameSaveImportService.TableRows> migrated = new ArrayList<>(tables.size());
        for (GameSaveImportService.TableRows table : tables) {
            String tableName = table.spec().tableName();
            if ("PLAYER_POSITION_FAMILIARITY".equals(tableName)) {
                migrated.add(new GameSaveImportService.TableRows(table.spec(), positions));
            } else if ("PLAYER_ROLE_FAMILIARITY".equals(tableName)) {
                migrated.add(new GameSaveImportService.TableRows(table.spec(), List.of()));
            } else if ("PLAYER_FOOT_PROFILE".equals(tableName)) {
                migrated.add(new GameSaveImportService.TableRows(table.spec(), feet));
            } else {
                migrated.add(table);
            }
        }
        return List.copyOf(migrated);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || value.toString().isBlank()) {
            return 0L;
        }
        return Long.parseLong(value.toString().trim().toUpperCase(Locale.ROOT));
    }
}
