package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.controller.GameController;
import com.footballmanagergamesimulator.controller.GameSaveImportService;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.service.CalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-full-save-preflight;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "simulation.matchday.parallel.enabled=false",
        "regent.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GameSavePreflightIT {

    @Autowired private GameController controller;
    @Autowired private GameSaveImportService importService;
    @Autowired private RoundRepository rounds;
    @Autowired private CalendarService calendars;

    @Test
    void completeLiveWorldExportPreflightsAsV6() {
        Round activeRound = rounds.findById(1L).orElseThrow();
        calendars.getOrCreateCalendar((int) activeRound.getSeason());
        Map<String, Object> v6 = controller.exportGame();

        assertThat(v6).doesNotContainKeys("users", "personProfiles");
        assertThat(importService.prepare(v6).sourceVersion()).isEqualTo(6);
    }
}
