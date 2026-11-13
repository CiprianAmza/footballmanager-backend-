package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import(FirstDayService.class)
class FirstDayServiceTest {

    @Autowired private FirstDayService firstDays;
    @Autowired private ManagerInboxRepository inbox;
    @Autowired private MeetingRecordRepository meetings;
    @Autowired private HumanRepository humans;

    @Test
    @DisplayName("""
            GIVEN a manager whose appointment article and briefing are in the inbox, and a pending board meeting
            WHEN his first day starts, then he reads both, the board meeting ends, and the three meetings are held
             or refused one by one
            THEN the first day is pending with the article as the next step and a note in the inbox; it stays
             pending until the press conference is settled, then it is over
            """)
    void firstDayBlocksUntilEveryStepIsDone() {
        long club = 93;
        ManagerInbox article = message(club, AppointmentArticleService.CATEGORY);
        ManagerInbox briefing = message(club, ArrivalBriefingService.CATEGORY);
        meeting(club, MeetingRecord.BOARD, "PENDING");
        Human manager = new Human();
        manager.setName("Kvekrpur");
        manager.setTypeId(TypeNames.MANAGER_TYPE);
        manager = humans.save(manager);

        firstDays.start(manager, club, 1);
        FirstDayService.Status start = firstDays.status(club);
        assertTrue(start.pending());
        assertEquals("Read the press article on your appointment", start.nextLabel());
        assertTrue(inbox.findAll().stream().anyMatch(m -> "Your first day".equals(m.getTitle())));

        article.setRead(true);
        briefing.setRead(true);
        inbox.save(article);
        inbox.save(briefing);
        MeetingRecord board = meetings.findByTeamIdAndSeasonAndType(club, 1, MeetingRecord.BOARD).orElseThrow();
        board.setOutcome(MeetingRecord.HELD);
        meetings.save(board);
        meeting(club, MeetingRecord.SQUAD, MeetingRecord.HELD);
        meeting(club, MeetingRecord.STAFF, MeetingRecord.REFUSED);
        assertEquals("Face the press (or refuse)", firstDays.status(club).nextLabel());

        meeting(club, MeetingRecord.PRESS, MeetingRecord.HELD);
        assertFalse(firstDays.status(club).pending());
        assertFalse(firstDays.status(club).pending(), "stays over");
    }

    private ManagerInbox message(long club, String category) {
        ManagerInbox m = new ManagerInbox();
        m.setTeamId(club);
        m.setSeasonNumber(1);
        m.setTitle(category);
        m.setCategory(category);
        m.setRead(false);
        return inbox.save(m);
    }

    private void meeting(long club, String type, String outcome) {
        MeetingRecord r = new MeetingRecord();
        r.setTeamId(club);
        r.setSeason(1);
        r.setType(type);
        r.setOutcome(outcome);
        meetings.save(r);
    }
}
