package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingUpdateScheduleBindingTest {

    @Test
    @DisplayName("""
            GIVEN the production application.yml
            WHEN simulation.training is bound
            THEN every league updates on day 1 and no league is overridden
            """)
    void productionYamlBindsTheMonthlyUpdateDay() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("app", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        TrainingUpdateSchedule schedule = Binder.get(environment)
                .bind("simulation.training", TrainingUpdateSchedule.class).get();

        assertEquals(1, schedule.getUpdateDayOfMonth());
        assertTrue(schedule.getUpdateDayByLeague().isEmpty());
    }
}
