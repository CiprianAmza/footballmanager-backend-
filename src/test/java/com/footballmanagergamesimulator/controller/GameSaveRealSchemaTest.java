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

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-save-real-schema;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({GameSaveImportService.class, GameSaveRealSchemaTest.Config.class})
class GameSaveRealSchemaTest {

    private static final List<String> MANIFEST_KEYS = List.of(
            "rounds", "competitions", "teams", "teamFacilities", "stadiums", "gameCalendars",
            "calendarEvents", "humans", "playerSkills", "youthPlayers", "playerInteractions",
            "competitionTeamInfos", "competitionTeamInfoDetails", "competitionTeamInfoMatches",
            "teamCompetitionDetails", "competitionHistories", "clubCoefficients", "scorers",
            "scorerLeaderboard", "matchEvents", "matchStats", "playerSeasonStats", "transfers",
            "transferOffers", "loans", "adminPlayerMovements", "injuries", "suspensions",
            "sponsorships", "boardRequests", "facilityUpgrades", "awards", "awardOverrides",
            "seasonObjectives", "managerHistories", "managerInbox", "pressConferences",
            "nationalTeamCallups", "trainingSchedules", "personalizedTactics",
            "teamPlayerHistorical", "financialRecords");

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
