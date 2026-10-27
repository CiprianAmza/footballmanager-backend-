package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CHECKPOINT_TEST_DB_URL", matches = ".+")
@ActiveProfiles("fast-sim")
@SpringBootTest(properties = {
        "match.engine.match-plan.enabled=true",
        "chairman.enabled=false",
        "bootstrap.use-pre-built-data=false"
})
class MemoryCheckpointIT {
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("game.checkpoint.postgres-url", () -> System.getenv("CHECKPOINT_TEST_DB_URL"));
        registry.add("game.checkpoint.postgres-username", () -> System.getenv("CHECKPOINT_TEST_DB_USERNAME"));
        registry.add("game.checkpoint.postgres-password", () -> System.getenv("CHECKPOINT_TEST_DB_PASSWORD"));
    }

    @Autowired MemoryCheckpointService checkpoints;
    @Autowired JdbcTemplate activeDatabase;
    @Autowired GameLock gameLock;
    @Autowired CalendarService calendarService;

    @Test
    void configuredIntervalCapturesInMemoryStateAndRestoresTheCompletedSnapshot() throws Exception {
        int originalSeason = activeDatabase.queryForObject(
                "SELECT season FROM round WHERE id=1", Integer.class);
        assertThat(originalSeason).isEqualTo(1);
        calendarService.getOrCreateCalendar(originalSeason);
        checkpoints.setInterval(2);

        Map<String, Object> matchday = Map.of("eventsProcessed", List.of(Map.of("type", "MATCH_DAY")));
        checkpoints.afterAdvance(matchday);
        assertThat(checkpoints.status().matchdaysSinceCheckpoint()).isEqualTo(1);

        gameLock.lock();
        try {
            checkpoints.afterAdvance(matchday);
        } finally {
            gameLock.unlock();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while ("SAVING".equals(checkpoints.status().state()) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(checkpoints.status().state()).isEqualTo("SAVED");
        assertThat(checkpoints.status().matchdaysSinceCheckpoint()).isZero();

        activeDatabase.update("UPDATE round SET season=99 WHERE id=1");
        assertThat(activeDatabase.queryForObject("SELECT season FROM round WHERE id=1", Integer.class))
                .isEqualTo(99);
        checkpoints.restoreLatest();
        assertThat(activeDatabase.queryForObject("SELECT season FROM round WHERE id=1", Integer.class))
                .isEqualTo(originalSeason);
        assertThat(checkpoints.status().intervalMatchdays()).isEqualTo(2);
    }
}
