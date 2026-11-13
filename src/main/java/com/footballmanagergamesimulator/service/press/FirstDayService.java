package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The first day at a new club, step by step (design DESIGN_START_CARIERA §6, decided 2026-11-13). Until every
 * step is done (or, for the meetings and the press conference, refused), the game does not advance.
 */
@Service
public class FirstDayService {

    public record Step(String key, String label, boolean done, String route) {}

    public record Status(boolean pending, List<Step> steps) {
        public String nextLabel() {
            return steps.stream().filter(s -> !s.done()).map(Step::label).findFirst().orElse(null);
        }
    }

    private final FirstDayRepository firstDayRepository;
    private final ManagerInboxRepository inboxRepository;
    private final MeetingRecordRepository meetingRepository;

    public FirstDayService(FirstDayRepository firstDayRepository, ManagerInboxRepository inboxRepository,
                           MeetingRecordRepository meetingRepository) {
        this.firstDayRepository = firstDayRepository;
        this.inboxRepository = inboxRepository;
        this.meetingRepository = meetingRepository;
    }

    /** After the article and the briefing were written: the first day starts, and the inbox says what it holds. */
    @Transactional
    public void start(Human manager, long teamId, int season) {
        firstDayRepository.findAllByTeamIdAndDoneFalse(teamId).forEach(old -> {
            old.setDone(true);
            firstDayRepository.save(old);
        });
        FirstDay day = new FirstDay();
        day.setTeamId(teamId);
        day.setManagerId(manager.getId());
        day.setSeason(season);
        day.setArticleMessageId(latest(teamId, AppointmentArticleService.CATEGORY));
        day.setBriefingMessageId(latest(teamId, ArrivalBriefingService.CATEGORY));
        firstDayRepository.save(day);

        ManagerInbox note = new ManagerInbox();
        note.setTeamId(teamId);
        note.setSeasonNumber(season);
        note.setTitle("Your first day");
        note.setContent("""
                Before the season moves on, your first day at the club:
                1. Read what the press wrote about your appointment.
                2. Meet the chairman (and the general director, if the club has one): Board Meeting.
                3. Read your assistant's first briefing.
                4. Meet the squad: Squad Meeting (you may refuse).
                5. Meet the staff: Staff Meeting (you may refuse).
                6. Face the press: Press Conference (you may refuse).
                The Today page shows what is left.""");
        note.setCategory("board");
        note.setRead(false);
        note.setCreatedAt(System.currentTimeMillis());
        inboxRepository.save(note);
    }

    @Transactional
    public Status status(long teamId) {
        Optional<FirstDay> open = firstDayRepository.findTopByTeamIdAndDoneFalseOrderByIdDesc(teamId);
        if (open.isEmpty()) return new Status(false, List.of());
        FirstDay day = open.get();
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("ARTICLE", "Read the press article on your appointment", read(day.getArticleMessageId()), "/inbox"));
        steps.add(new Step("BOARD", "Meet the board", meeting(day, MeetingRecord.BOARD, true), "/board-meeting"));
        steps.add(new Step("BRIEFING", "Read your assistant's briefing", read(day.getBriefingMessageId()), "/inbox"));
        steps.add(new Step("SQUAD", "Meet the squad (or refuse)", meeting(day, MeetingRecord.SQUAD, false), "/squad-meeting"));
        steps.add(new Step("STAFF", "Meet the staff (or refuse)", meeting(day, MeetingRecord.STAFF, false), "/staff-meeting"));
        steps.add(new Step("PRESS", "Face the press (or refuse)", meeting(day, MeetingRecord.PRESS, false), "/press-conference"));
        if (steps.stream().allMatch(Step::done)) {
            day.setDone(true);
            firstDayRepository.save(day);
            return new Status(false, steps);
        }
        return new Status(true, steps);
    }

    private boolean read(long messageId) {
        return messageId == 0 || inboxRepository.findById(messageId).map(ManagerInbox::isRead).orElse(true);
    }

    /** The board meeting counts once it is no longer pending; the others once held or refused. */
    private boolean meeting(FirstDay day, String type, boolean mustBeSettled) {
        return meetingRepository.findByTeamIdAndSeasonAndType(day.getTeamId(), day.getSeason(), type)
                .map(r -> !mustBeSettled || !"PENDING".equals(r.getOutcome()))
                .orElse(!mustBeSettled ? false : true);
    }

    private long latest(long teamId, String category) {
        List<ManagerInbox> messages = inboxRepository.findAllByTeamIdAndCategoryOrderByIdDesc(teamId, category);
        return messages.isEmpty() ? 0 : messages.get(0).getId();
    }
}
