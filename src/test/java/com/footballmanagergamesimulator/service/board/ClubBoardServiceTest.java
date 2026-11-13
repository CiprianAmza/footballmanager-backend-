package com.footballmanagergamesimulator.service.board;

import com.footballmanagergamesimulator.model.ClubBoard;
import com.footballmanagergamesimulator.model.DirectorPersonality;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.ClubBoardRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.NationService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import({ClubBoardService.class, NationService.class, CompositeNameGenerator.class})
class ClubBoardServiceTest {

    @Autowired private ClubBoardService boards;
    @Autowired private ClubBoardRepository boardRows;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;

    @Test
    @DisplayName("""
            GIVEN 100 clubs ranked by reputation
            WHEN their boards are created (twice)
            THEN every club gets a chairman, once; about 30% get a general director (between 15 and 45 here),
             the most famous half more often than the other half
            """)
    void everyClubGetsAChairmanAndSomeADirector() {
        for (int i = 0; i < 100; i++) {
            Team t = new Team();
            t.setName("C" + i);
            t.setReputation(10_000 - i * 50);
            teams.save(t);
        }

        assertEquals(100, boards.ensureBoards());
        assertEquals(0, boards.ensureBoards());

        assertEquals(100, humans.findAllByTypeId(TypeNames.CHAIRMAN_TYPE).size());
        long directors = boardRows.findAll().stream().filter(ClubBoard::hasDirector).count();
        assertTrue(directors >= 15 && directors <= 45, "directors: " + directors);
        assertEquals(directors, humans.findAllByTypeId(TypeNames.DIRECTOR_TYPE).size());
        assertEquals(0.60, ClubBoards.directorChance(0, 100), 1e-9);
        assertEquals(0.0, ClubBoards.directorChance(99, 100), 1e-9);
        assertEquals(DirectorPersonality.NEUTRAL, ClubBoards.personality(0.39));
        assertEquals(DirectorPersonality.AMBITIOUS, ClubBoards.personality(0.85));
    }

    @Test
    @DisplayName("""
            GIVEN a club whose board has been created
            WHEN the editor gives it an ambitious director, then removes him
            THEN it becomes a public conflict club (the description says so), then a club where the chairman
             deals with the manager directly
            """)
    void editorSetsTheDirector() {
        Team t = new Team();
        t.setName("Tik Tok");
        long club = teams.save(t).getId();
        boards.ensureBoards();

        ClubBoardService.BoardView conflict = boards.edit(club, DirectorPersonality.AMBITIOUS);
        assertTrue(conflict.conflict() && conflict.hasDirector());
        assertTrue(conflict.description().contains("own agenda"), conflict.description());
        assertNotNull(conflict.chairmanName());

        ClubBoardService.BoardView none = boards.edit(club, null);
        assertFalse(none.hasDirector() || none.conflict());
        assertEquals("The chairman deals with the manager directly.", none.description());
    }
}
