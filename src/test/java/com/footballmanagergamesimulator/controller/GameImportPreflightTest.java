package com.footballmanagergamesimulator.controller;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GameImportPreflightTest {

    private final GameController controller = new GameController();

    @Test
    void incompatibleSaveFailsBeforeAnyDatabaseDependencyIsTouched() {
        Map<String, Object> result = controller.importGame(Map.of(
                "saveVersion", 99,
                "rounds", List.of(Map.of("id", 1)),
                "gameCalendars", List.of(Map.of("id", 1))));

        assertThat(result).containsEntry("success", false);
        assertThat(result.get("error")).asString().contains("Incompatible save version");
    }

    @Test
    void validatesLegacyAndCurrentManifestBeforeMutation() {
        Map<String, Object> base = Map.of(
                "rounds", List.of(Map.of("id", 1)),
                "gameCalendars", List.of(Map.of("id", 1)),
                "users", List.of(Map.of("id", 1, "username", "manager")));

        assertThat(controller.validateSaveBeforeMutation(withVersion(base, 5))).isEmpty();
        assertThat(controller.validateSaveBeforeMutation(withVersion(base, 6)))
                .contains("Invalid save v6: personProfiles must be a list");
    }

    private Map<String, Object> withVersion(Map<String, Object> source, int version) {
        java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>(source);
        copy.put("saveVersion", version);
        return copy;
    }
}
