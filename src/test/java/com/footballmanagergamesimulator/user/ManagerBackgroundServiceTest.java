package com.footballmanagergamesimulator.user;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import(ManagerBackgroundService.class)
class ManagerBackgroundServiceTest {

    @Autowired private ManagerBackgroundService backgrounds;
    @Autowired private HumanRepository humans;
    @Autowired private TeamRepository teams;

    @Test
    @DisplayName("""
            GIVEN a new manager born on 14 March in Khess, a Tik Tok fan, preferring 433, a former international
             with a Pro licence, two seasons as a player and a face built in the editor
            WHEN his story is applied
            THEN he starts with reputation 8,000, 433 is his preferred and first known tactic, he has that face
             (with the Khess species),
             and his profile shows the birthday, nation, club, career, licence and both seasons
            """)
    void storyIsStoredAndSetsTheStartingReputation() {
        Team club = new Team();
        club.setName("Tik Tok");
        long clubId = teams.save(club).getId();
        Human manager = new Human();
        manager.setName("Kvekrpur");
        manager.setTypeId(TypeNames.MANAGER_TYPE);
        manager.setKnownTactics("442,4231");
        manager = humans.save(manager);

        backgrounds.apply(manager, request(14, 3, 3L, clubId, "433", List.of(
                new ManagerSetupRequest.PlayingSeason("Kvar", 4, 2),
                new ManagerSetupRequest.PlayingSeason("Tik Tok", 11, 5))));

        assertEquals(8_000, manager.getManagerReputation());
        assertEquals("433", manager.getTacticStyle());
        assertEquals("433,442,4231", manager.getKnownTactics());
        assertEquals(7, manager.getHairStyle());
        assertEquals(5, manager.getBrowShape());
        assertEquals("saurian", manager.getSpecies(), "born in Khess: the Khess species, as for players");
        Map<String, Object> view = backgrounds.view(manager.getId());
        assertEquals(14, view.get("birthDay"));
        assertEquals(3, view.get("birthMonth"));
        assertEquals("Khess", view.get("birthNation"));
        assertEquals("Tik Tok", view.get("favoriteTeamName"));
        assertEquals("International", view.get("playingCareer"));
        assertEquals("Pro licence", view.get("coachingLicence"));
        assertEquals(2, ((List<?>) view.get("playingSeasons")).size());
        assertTrue(backgrounds.view(manager.getId() + 1000).isEmpty(), "an AI manager has no story");
    }

    @Test
    @DisplayName("""
            GIVEN requests with 31 February, an unknown nation, an unknown tactic, and only a day of birth
            WHEN they are validated
            THEN each is rejected; a request with no story at all is accepted
            """)
    void impossibleStoriesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> backgrounds.validate(request(31, 2, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> backgrounds.validate(request(1, 1, 99L, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> backgrounds.validate(request(1, 1, null, null, "999", null)));
        assertThrows(IllegalArgumentException.class, () -> backgrounds.validate(request(1, null, null, null, null, null)));
        backgrounds.validate(new ManagerSetupRequest("Kvekrpur", 40, null, true));
    }

    private static ManagerSetupRequest request(Integer day, Integer month, Long nation, Long favourite, String tactic,
                                               List<ManagerSetupRequest.PlayingSeason> seasons) {
        return new ManagerSetupRequest("Kvekrpur", 40, null, true, day, month, nation, favourite, tactic,
                PlayingCareer.INTERNATIONAL, CoachingLicence.PRO, seasons,
                new ManagerSetupRequest.Face(3, 2, 7, 1, 0, 4, 2, 3, 1, 5));
    }
}
