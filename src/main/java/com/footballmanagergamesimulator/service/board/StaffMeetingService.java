package com.footballmanagergamesimulator.service.board;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.StaffService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The first staff meeting of a season (design DESIGN_START_CARIERA §6.4, decided 2026-11-13). The manager states
 * a training emphasis and a philosophy; each coach's hidden loyalty (§5.2) moves with how far that is from his own
 * way of working, and his reply is the first hint of where he stands. Refusing the meeting costs everyone 5.
 */
@Service
public class StaffMeetingService {

    public enum Emphasis { ATTACKING, DEFENDING, TACTICAL, TECHNICAL, PHYSICAL }

    public enum Philosophy { YOUTH, RESULTS }

    public static final int SAME_WAY = 5;
    public static final int OTHER_WAY = -3;
    public static final int REFUSAL = -5;

    public record Reply(long staffId, String name, String role, String reply) {}

    public record StaffMeeting(int season, String status, String emphasis, String philosophy, List<Reply> replies) {}

    private final StaffService staffService;
    private final StaffLoyaltyRepository loyaltyRepository;
    private final MeetingRecordRepository meetingRepository;
    private final GameCalendarRepository calendarRepository;
    private final BoardTrustService boardTrust;

    public StaffMeetingService(StaffService staffService, StaffLoyaltyRepository loyaltyRepository,
                               MeetingRecordRepository meetingRepository, GameCalendarRepository calendarRepository,
                               BoardTrustService boardTrust) {
        this.staffService = staffService;
        this.loyaltyRepository = loyaltyRepository;
        this.meetingRepository = meetingRepository;
        this.calendarRepository = calendarRepository;
        this.boardTrust = boardTrust;
    }

    /** A coach's own way of working: his strongest coaching attribute. */
    public static Emphasis way(Human coach) {
        Map<Emphasis, Integer> values = new EnumMap<>(Emphasis.class);
        values.put(Emphasis.ATTACKING, coach.getCoachingAttacking());
        values.put(Emphasis.DEFENDING, coach.getCoachingDefending());
        values.put(Emphasis.TACTICAL, coach.getCoachingTactical());
        values.put(Emphasis.TECHNICAL, coach.getCoachingTechnical());
        values.put(Emphasis.PHYSICAL, coach.getCoachingFitness());
        return values.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Emphasis.TACTICAL);
    }

    /** How a coach takes what the manager expects. */
    public static int delta(Human coach, Emphasis emphasis, Philosophy philosophy) {
        int delta = way(coach) == emphasis ? SAME_WAY : OTHER_WAY;
        long type = coach.getTypeId();
        boolean youth = type == TypeNames.YOUTH_COACH_TYPE || type == TypeNames.HOYD_TYPE;
        boolean firstTeam = type == TypeNames.ASSISTANT_MANAGER_TYPE || type == TypeNames.FIRST_TEAM_COACH_TYPE;
        if (philosophy == Philosophy.YOUTH) delta += youth ? 5 : firstTeam ? -2 : 0;
        else delta += youth ? -3 : firstTeam ? 3 : 0;
        return delta;
    }

    public static String reply(int delta) {
        if (delta > 0) return "Exactly how I like to work.";
        if (delta >= OTHER_WAY) return "I'll adapt.";
        return "That's not how we've done things here.";
    }

    public StaffMeeting meeting(long teamId) {
        int season = season();
        String status = meetingRepository.findByTeamIdAndSeasonAndType(teamId, season, MeetingRecord.STAFF)
                .map(MeetingRecord::getOutcome).orElse("PENDING");
        if (status.startsWith(MeetingRecord.HELD + ":")) {
            String[] parts = status.split(":");
            Emphasis emphasis = Emphasis.valueOf(parts[1]);
            Philosophy philosophy = Philosophy.valueOf(parts[2]);
            return new StaffMeeting(season, MeetingRecord.HELD, emphasis.name(), philosophy.name(), replies(teamId, emphasis, philosophy));
        }
        return new StaffMeeting(season, status, null, null, List.of());
    }

    /** Holds the meeting: every coach's loyalty moves at once and he answers. Once a season. */
    @Transactional
    public StaffMeeting hold(long teamId, Emphasis emphasis, Philosophy philosophy) {
        int season = season();
        requireNotHeld(teamId, season);
        for (Human coach : staffService.getTeamStaff(teamId)) move(coach.getId(), delta(coach, emphasis, philosophy));
        record(teamId, season, MeetingRecord.HELD + ":" + emphasis.name() + ":" + philosophy.name());
        return meeting(teamId);
    }

    @Transactional
    public StaffMeeting refuse(long teamId) {
        int season = season();
        requireNotHeld(teamId, season);
        for (StaffLoyalty row : loyaltyRepository.findAllByTeamId(teamId)) move(row.getStaffId(), REFUSAL);
        record(teamId, season, MeetingRecord.REFUSED);
        return meeting(teamId);
    }

    private List<Reply> replies(long teamId, Emphasis emphasis, Philosophy philosophy) {
        return staffService.getTeamStaff(teamId).stream()
                .map(c -> new Reply(c.getId(), c.getName(), TypeNames.coachTypeName(c.getTypeId()),
                        reply(delta(c, emphasis, philosophy))))
                .toList();
    }

    private void requireNotHeld(long teamId, int season) {
        if (meetingRepository.findByTeamIdAndSeasonAndType(teamId, season, MeetingRecord.STAFF).isPresent())
            throw new IllegalStateException("You have already met your staff this season.");
    }

    private void move(long staffId, int delta) {
        loyaltyRepository.findByStaffId(staffId).ifPresent(row -> {
            row.setLoyalty(Math.max(0, Math.min(100, row.getLoyalty() + delta)));
            loyaltyRepository.save(row);
        });
    }

    private void record(long teamId, int season, String outcome) {
        MeetingRecord record = new MeetingRecord();
        record.setTeamId(teamId);
        record.setManagerId(boardTrust.managerOf(teamId));
        record.setSeason(season);
        record.setType(MeetingRecord.STAFF);
        record.setOutcome(outcome);
        meetingRepository.save(record);
    }

    private int season() {
        return calendarRepository.findTopByOrderBySeasonDesc().map(GameCalendar::getSeason).orElse(1);
    }
}
