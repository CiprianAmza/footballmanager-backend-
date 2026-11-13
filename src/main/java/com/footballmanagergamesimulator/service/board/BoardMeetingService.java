package com.footballmanagergamesimulator.service.board;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.RelationService;
import com.footballmanagergamesimulator.service.squad.LeaguePredictionService;
import com.footballmanagergamesimulator.service.squad.SquadObjectives;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The meeting with the chairman and the general director on the first day (design DESIGN_START_CARIERA §6.2,
 * decided 2026-11-13). The chairman says what he expects; a director says a few words, and only an ambitious one
 * asks, in private, whether the manager is on his side. The answer moves his trust at once; no answer by the end
 * of the month counts as an evasive one.
 */
@Service
public class BoardMeetingService {

    public static final String PENDING = "PENDING";
    public static final String EVADED = "EVADED";

    public enum Answer {
        WITH_YOU("Yes, I'm with you.", 20),
        DEPENDS("Depends on what you propose.", 5),
        WAIT_AND_SEE("We'll see, I've only just arrived.", -5),
        FOR_THE_CLUB("I work for the club and the chairman.", -15);

        public final String text;
        public final int trust;

        Answer(String text, int trust) {
            this.text = text;
            this.trust = trust;
        }
    }

    public record Meeting(String status, String chairmanName, String chairmanWords, String directorName,
                          String directorWords, boolean directorAsks, String answeredWith) {}

    private final MeetingRecordRepository meetingRepository;
    private final ClubBoardRepository boardRepository;
    private final HumanRepository humanRepository;
    private final TeamRepository teamRepository;
    private final GameCalendarRepository calendarRepository;
    private final RelationService relationService;
    private final BoardTrustService boardTrust;
    private final ObjectProvider<LicenceService> licenceService;
    private final ObjectProvider<LeaguePredictionService> predictions;

    public BoardMeetingService(MeetingRecordRepository meetingRepository, ClubBoardRepository boardRepository,
                               HumanRepository humanRepository, TeamRepository teamRepository,
                               GameCalendarRepository calendarRepository, RelationService relationService,
                               BoardTrustService boardTrust, ObjectProvider<LicenceService> licenceService,
                               ObjectProvider<LeaguePredictionService> predictions) {
        this.meetingRepository = meetingRepository;
        this.boardRepository = boardRepository;
        this.humanRepository = humanRepository;
        this.teamRepository = teamRepository;
        this.calendarRepository = calendarRepository;
        this.relationService = relationService;
        this.boardTrust = boardTrust;
        this.licenceService = licenceService;
        this.predictions = predictions;
    }

    /** The manager arrives: the meeting is set for him (after the appointment article). */
    @Transactional
    public void managerAppointed(long managerId, long teamId) {
        int season = season();
        MeetingRecord record = meetingRepository.findByTeamIdAndSeasonAndType(teamId, season, MeetingRecord.BOARD)
                .orElseGet(MeetingRecord::new);
        record.setTeamId(teamId);
        record.setManagerId(managerId);
        record.setSeason(season);
        record.setType(MeetingRecord.BOARD);
        record.setOutcome(PENDING);
        meetingRepository.save(record);
    }

    public Meeting meeting(long teamId) {
        int season = season();
        Optional<MeetingRecord> record = meetingRepository.findByTeamIdAndSeasonAndType(teamId, season, MeetingRecord.BOARD);
        ClubBoard board = boardRepository.findByTeamId(teamId).orElse(null);
        if (record.isEmpty() || board == null) return new Meeting("NONE", null, null, null, null, false, null);
        String status = record.get().getOutcome();
        String chairman = name(board.getChairmanId(), "The chairman");
        String director = board.hasDirector() ? name(board.getDirectorId(), "The director") : null;
        boolean asks = board.isConflict();
        String answered = status.startsWith("ANSWER:") ? Answer.valueOf(status.substring(7)).text
                : EVADED.equals(status) ? "You never answered." : null;
        return new Meeting(status.startsWith("ANSWER:") ? "HELD" : status, chairman, chairmanWords(teamId, record.get().getManagerId()),
                director, director == null ? null : directorWords(board.getDirectorPersonality()), asks, answered);
    }

    /** The private answer to the ambitious director: his trust moves at once. Once. */
    @Transactional
    public Meeting answer(long teamId, Answer answer) {
        MeetingRecord record = meetingRepository.findByTeamIdAndSeasonAndType(teamId, season(), MeetingRecord.BOARD)
                .orElseThrow(() -> new IllegalStateException("There is no meeting with the board."));
        ClubBoard board = boardRepository.findByTeamId(teamId).orElseThrow();
        if (!PENDING.equals(record.getOutcome())) throw new IllegalStateException("You have already answered.");
        if (!board.isConflict()) throw new IllegalStateException("The director did not ask you anything.");
        moveDirectorTrust(board, record.getManagerId(), answer.trust);
        record.setOutcome("ANSWER:" + answer.name());
        meetingRepository.save(record);
        return meeting(teamId);
    }

    /** A meeting without a question: the manager thanks them and leaves. */
    @Transactional
    public Meeting close(long teamId) {
        MeetingRecord record = meetingRepository.findByTeamIdAndSeasonAndType(teamId, season(), MeetingRecord.BOARD)
                .orElseThrow(() -> new IllegalStateException("There is no meeting with the board."));
        boolean asks = boardRepository.findByTeamId(teamId).map(ClubBoard::isConflict).orElse(false);
        if (asks && PENDING.equals(record.getOutcome()))
            throw new IllegalStateException("The director is waiting for your answer.");
        if (PENDING.equals(record.getOutcome())) {
            record.setOutcome(MeetingRecord.HELD);
            meetingRepository.save(record);
        }
        return meeting(teamId);
    }

    /** Monthly: a question left unanswered counts as "we'll see"; a meeting without a question is simply held. */
    @Transactional
    public void monthPassed() {
        for (MeetingRecord record : meetingRepository.findAllByTypeAndOutcome(MeetingRecord.BOARD, PENDING)) {
            ClubBoard board = boardRepository.findByTeamId(record.getTeamId()).orElse(null);
            if (board != null && board.isConflict()) {
                moveDirectorTrust(board, record.getManagerId(), Answer.WAIT_AND_SEE.trust);
                record.setOutcome(EVADED);
            } else {
                record.setOutcome(MeetingRecord.HELD);
            }
            meetingRepository.save(record);
        }
    }

    String chairmanWords(long teamId, long managerId) {
        String wish = "TOP_HALF";
        Team team = teamRepository.findById(teamId).orElse(null);
        LeaguePredictionService prediction = predictions.getIfAvailable();
        if (team != null && prediction != null && team.getCompetitionId() > 0) {
            int[] finish = prediction.predictedFinish(team.getCompetitionId(), teamId);
            if (finish[0] > 0) wish = SquadObjectives.leagueWish(finish[0], finish[1]);
        }
        String expectation = switch (wish) {
            case "TITLE" -> "This club plays for the title. Anything less is a failure.";
            case "TOP_3" -> "I expect us among the top three.";
            case "AVOID_RELEGATION" -> "Keep us up. That is all I ask this season.";
            default -> "I expect us in the top half of the table.";
        };
        int trust = boardTrust.trust(teamId).chairmanInManager();
        String patience = trust >= 50 ? "You have my backing." : "I will be watching closely.";
        LicenceService licences = licenceService.getIfAvailable();
        if (licences != null && !licences.qualifies(managerId, teamId))
            patience = "You don't have the licence this league asks for. I signed you anyway, so don't make me regret it.";
        return expectation + " " + patience;
    }

    static String directorWords(DirectorPersonality personality) {
        return switch (personality) {
            case SUPPORTIVE -> "Whatever you need, come to me first. I want this to work for you.";
            case OWNER_LOYAL -> "The chairman's wishes come through me. Follow them and we will get along.";
            case AMBITIOUS -> "I have my own ideas for this club. Before we start, one question: are you on my side?";
            default -> "I handle the administration. My door is open if you need anything.";
        };
    }

    private void moveDirectorTrust(ClubBoard board, long managerId, int delta) {
        if (board.hasDirector() && managerId > 0)
            relationService.change(RelationParty.DIRECTOR, board.getDirectorId(), RelationParty.MANAGER, managerId, delta);
    }

    private String name(long id, String fallback) {
        return humanRepository.findById(id).map(Human::getName).orElse(fallback);
    }

    private int season() {
        return calendarRepository.findTopByOrderBySeasonDesc().map(GameCalendar::getSeason).orElse(1);
    }
}
