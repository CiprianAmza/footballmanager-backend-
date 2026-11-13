package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import({AppointmentArticleService.class, PressFragmentService.class, AnalystService.class, CompositeNameGenerator.class})
class AppointmentArticleServiceTest {

    @Autowired private AppointmentArticleService articles;
    @Autowired private PressFragmentRepository fragments;
    @Autowired private AnalystRepository analysts;
    @Autowired private CompetitionRepository competitions;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
    @Autowired private ManagerInboxRepository inbox;

    @Test
    @DisplayName("""
            GIVEN a 4-club league in Khess and a manager with no background, far below the club's reputation
            WHEN he is appointed at its top club, then appointed again
            THEN the shipped fragments (985) and the league's three pundits are created once; a press article reaches
             the club's inbox with a headline and 9 paragraphs (who, decision, context, 3 league pundits and,
             the club being above 8,000, the 2 international ones, tenure),
             every placeholder filled; the second article opens with a different "who he is" paragraph
            """)
    void appointmentArticleIsWrittenFromRotatedFragments() {
        Competition league = new Competition();
        league.setName("Khess First League");
        league.setTypeId(Competition.LEAGUE);
        league.setNationId(3);
        long leagueId = competitions.save(league).getId();
        Team club = null;
        for (int i = 0; i < 4; i++) {
            Team t = new Team();
            t.setName("Club " + i);
            t.setCompetitionId(leagueId);
            t.setReputation(9_000 - i * 1_000);
            t = teams.save(t);
            if (i == 0) club = t;
        }
        Human manager = new Human();
        manager.setName("Kvekrpur");
        manager.setTypeId(TypeNames.MANAGER_TYPE);
        manager.setAge(41);
        manager.setManagerReputation(1_000);
        manager = humans.save(manager);

        AppointmentArticleService.Article first = articles.publish(manager, club.getId()).orElseThrow();
        AppointmentArticleService.Article second = articles.publish(manager, club.getId()).orElseThrow();

        assertEquals(985, fragments.findAll().stream()
                .filter(f -> PressFragmentService.APPOINTMENT.equals(f.getEventType())).count());
        assertEquals(3, analysts.findAllByCompetitionId(leagueId).size());
        assertEquals(9, first.paragraphs().size());
        assertFalse(first.headline().isBlank());
        assertTrue(first.paragraphs().stream().noneMatch(p -> p.contains("{")), first.body());
        assertNotEquals(first.paragraphs().get(0), second.paragraphs().get(0), "rotation: least used first");
        List<ManagerInbox> press = inbox.findAll().stream().filter(m -> "press".equals(m.getCategory())).toList();
        assertEquals(2, press.size());
        assertEquals(club.getId(), press.get(0).getTeamId());
        System.out.println("=== " + first.headline() + " ===\n" + first.body());
    }

    @Autowired private ClubBoardRepository boards;

    @Test
    @DisplayName("""
            GIVEN the same league, whose top club has an ambitious general director
            WHEN a manager is appointed there
            THEN the article gains a tenth paragraph naming the director and the power struggle he walks into
            """)
    void conflictClubIsPublicKnowledge() {
        Competition league = new Competition();
        league.setName("Khess First League");
        league.setTypeId(Competition.LEAGUE);
        league.setNationId(3);
        long leagueId = competitions.save(league).getId();
        Team club = null;
        for (int i = 0; i < 4; i++) {
            Team t = new Team();
            t.setName("Club " + i);
            t.setCompetitionId(leagueId);
            t.setReputation(9_000 - i * 1_000);
            t = teams.save(t);
            if (i == 0) club = t;
        }
        Human director = new Human();
        director.setName("Grey Eminence");
        director.setTypeId(TypeNames.DIRECTOR_TYPE);
        director = humans.save(director);
        ClubBoard board = new ClubBoard();
        board.setTeamId(club.getId());
        board.setDirectorId(director.getId());
        board.setDirectorPersonality(DirectorPersonality.AMBITIOUS);
        boards.save(board);
        Human manager = new Human();
        manager.setName("Kvekrpur");
        manager.setTypeId(TypeNames.MANAGER_TYPE);
        manager.setManagerReputation(1_000);
        manager = humans.save(manager);

        AppointmentArticleService.Article article = articles.publish(manager, club.getId()).orElseThrow();

        assertEquals(10, article.paragraphs().size());
        assertTrue(article.paragraphs().stream().anyMatch(p -> p.contains("Grey Eminence") && p.contains("Kvekrpur")), article.body());
    }

}
