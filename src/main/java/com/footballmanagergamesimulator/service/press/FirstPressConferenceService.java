package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.RelationService;
import com.footballmanagergamesimulator.service.squad.SquadMeetingService;
import com.footballmanagergamesimulator.service.squad.SquadObjectives;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The first press conference at a new club (design DESIGN_START_CARIERA §6.4, decided 2026-11-12):
 * <ol>
 *   <li>the harshest pundit's words from the appointment article are read out; the answer moves the manager's
 *       reputation and that pundit's memory of him (his tone in later articles);</li>
 *   <li>the league target, and</li>
 *   <li>the cup targets, which become public promises settled at season end.</li>
 * </ol>
 * Refusing costs 100 reputation and turns every pundit one step more critical of him.
 */
@Service
public class FirstPressConferenceService {

    public static final int REFUSAL_REPUTATION = -100;
    public static final int LEAGUE_STAKE = 150;
    public static final int CUP_STAKE = 75;

    /** Answers to the pundit: reputation change and the pundit's relation change. */
    public enum ArticleAnswer {
        PROVE_WRONG(30, 0), FAIR_POINT(0, AnalystStance.STEP), NO_COMMENT(0, 0), ATTACK(-30, -AnalystStance.STEP);

        public final int reputation;
        public final int relation;

        ArticleAnswer(int reputation, int relation) {
            this.reputation = reputation;
            this.relation = relation;
        }
    }

    public record PunditQuestion(long analystId, String analystName, String quote) {}

    public record TargetQuestion(long competitionId, String competitionName, boolean league, List<String> ladder,
                                 String promised) {}

    public record Conference(int season, String status, PunditQuestion pundit, List<TargetQuestion> targets,
                             boolean puppetQuestion) {}

    /**
     * At a club whose ambitious director clashes with the chairman: "are you the chairman's puppet?" Public, so it
     * reaches both (design DESIGN_START_CARIERA §6.4, decided 2026-11-13): chairman's and director's trust change.
     */
    public enum PuppetAnswer {
        FOR_THE_CLUB("I work for the club, and the chairman put his faith in me.", 3, -5),
        WITH_THE_DIRECTOR("The director and I work well together.", -3, 5),
        MY_DRESSING_ROOM("I make the decisions in the dressing room.", 0, -3),
        NO_COMMENT("No comment.", 0, 0);

        public final String text;
        public final int chairman;
        public final int director;

        PuppetAnswer(String text, int chairman, int director) {
            this.text = text;
            this.chairman = chairman;
            this.director = director;
        }
    }

    /** Told the director in private "I'm with you", then said this in public: he feels lied to. */
    public static final int LIED_TO = -5;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.footballmanagergamesimulator.service.board.BoardTrustService> boardTrust;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.footballmanagergamesimulator.repository.ClubBoardRepository boardRepository;

    private final AnalystService analysts;
    private final AppointmentArticleService articles;
    private final SquadMeetingService squadMeetings;
    private final RelationService relationService;
    private final TeamRepository teamRepository;
    private final HumanRepository humanRepository;
    private final ManagerInboxRepository inboxRepository;
    private final MeetingRecordRepository meetingRecordRepository;
    private final PublicPromiseRepository promiseRepository;

    public FirstPressConferenceService(AnalystService analysts, AppointmentArticleService articles,
                                       SquadMeetingService squadMeetings, RelationService relationService,
                                       TeamRepository teamRepository, HumanRepository humanRepository,
                                       ManagerInboxRepository inboxRepository,
                                       MeetingRecordRepository meetingRecordRepository,
                                       PublicPromiseRepository promiseRepository) {
        this.analysts = analysts;
        this.articles = articles;
        this.squadMeetings = squadMeetings;
        this.relationService = relationService;
        this.teamRepository = teamRepository;
        this.humanRepository = humanRepository;
        this.inboxRepository = inboxRepository;
        this.meetingRecordRepository = meetingRecordRepository;
        this.promiseRepository = promiseRepository;
    }

    public Conference conference(long teamId) {
        int season = squadMeetings.currentSeason();
        String status = meetingRecordRepository.findByTeamIdAndSeasonAndType(teamId, season, MeetingRecord.PRESS)
                .map(MeetingRecord::getOutcome).orElse("PENDING");
        Map<Long, String> promised = new HashMap<>();
        promiseRepository.findAllByTeamIdAndSeason(teamId, season).forEach(p -> promised.put(p.getCompetitionId(), p.getTarget()));
        List<TargetQuestion> targets = squadMeetings.agenda(teamId).items().stream()
                .map(i -> new TargetQuestion(i.competitionId(), i.competitionName(), i.league(), i.ladder(),
                        promised.get(i.competitionId())))
                .toList();
        return new Conference(season, status, punditQuestion(teamId), targets, conflictClub(teamId));
    }

    /** The harshest pundit commenting on this club, and his words from the latest appointment article. */
    PunditQuestion punditQuestion(long teamId) {
        Team club = teamRepository.findById(teamId).orElse(null);
        long managerId = managerOf(teamId);
        if (club == null) return null;
        Analyst harshest = analysts.commentatorsFor(club).stream()
                .min(Comparator.comparingInt((Analyst a) -> articles.stance(a, managerId).ordinal())
                        .thenComparingLong(Analyst::getId))
                .orElse(null);
        if (harshest == null) return null;
        String quote = inboxRepository.findAll().stream()
                .filter(m -> m.getTeamId() == teamId && "press".equals(m.getCategory()) && m.getContent() != null)
                .max(Comparator.comparingLong(ManagerInbox::getId))
                .flatMap(m -> Arrays.stream(m.getContent().split("\n\n"))
                        .filter(p -> p.startsWith(harshest.getName() + " ("))
                        .findFirst())
                .map(p -> p.substring(p.indexOf(':') + 1).trim())
                .orElse("\u201cWe will see what he is made of.\u201d");
        return new PunditQuestion(harshest.getId(), harshest.getName(), quote);
    }

    private boolean conflictClub(long teamId) {
        return boardRepository != null
                && boardRepository.findByTeamId(teamId).map(ClubBoard::isConflict).orElse(false);
    }

    @Transactional
    public Conference hold(long teamId, ArticleAnswer answer, Map<Long, String> targets) {
        return hold(teamId, answer, targets, null);
    }

    /** Answers the conference: the pundit question, the public targets (league, cups) and, at a conflict club,
     *  the puppet question. Once per season. */
    @Transactional
    public Conference hold(long teamId, ArticleAnswer answer, Map<Long, String> targets, PuppetAnswer puppet) {
        int season = squadMeetings.currentSeason();
        requirePending(teamId, season);
        long managerId = managerOf(teamId);
        Human manager = humanRepository.findById(managerId).orElse(null);
        PunditQuestion pundit = punditQuestion(teamId);
        if (answer != null && manager != null) {
            changeReputation(manager, answer.reputation);
            if (pundit != null && answer.relation != 0) {
                relationService.change(RelationParty.ANALYST, pundit.analystId(), RelationParty.MANAGER, managerId, answer.relation);
            }
        }
        List<PublicPromise> promises = new ArrayList<>();
        for (SquadMeetingService.Item item : squadMeetings.agenda(teamId).items()) {
            String target = targets.get(item.competitionId());
            if (target == null) continue;
            if (!item.ladder().contains(target)) {
                throw new IllegalArgumentException("Unknown target " + target + " for " + item.competitionName());
            }
            PublicPromise promise = new PublicPromise();
            promise.setManagerId(managerId);
            promise.setTeamId(teamId);
            promise.setSeason(season);
            promise.setCompetitionId(item.competitionId());
            promise.setCompetitionName(item.competitionName());
            promise.setTarget(target);
            promise.setStake(item.league() ? LEAGUE_STAKE : CUP_STAKE);
            promises.add(promise);
        }
        promiseRepository.saveAll(promises);
        if (puppet != null && conflictClub(teamId)) answerPuppetQuestion(teamId, managerId, season, puppet);
        record(teamId, managerId, season, MeetingRecord.HELD);
        return conference(teamId);
    }

    /** Refuses the conference: reputation -100, every pundit one step more critical of him. */
    @Transactional
    public Conference refuse(long teamId) {
        int season = squadMeetings.currentSeason();
        requirePending(teamId, season);
        long managerId = managerOf(teamId);
        humanRepository.findById(managerId).ifPresent(m -> changeReputation(m, REFUSAL_REPUTATION));
        analysts.ensureAnalysts();
        List<RelationService.Change> changes = new ArrayList<>();
        for (Analyst analyst : analysts.all()) {
            changes.add(new RelationService.Change(RelationParty.ANALYST, analyst.getId(), RelationParty.MANAGER,
                    managerId, -AnalystStance.STEP));
        }
        relationService.changeAll(changes);
        record(teamId, managerId, season, MeetingRecord.REFUSED);
        return conference(teamId);
    }

    private void answerPuppetQuestion(long teamId, long managerId, int season, PuppetAnswer puppet) {
        com.footballmanagergamesimulator.service.board.BoardTrustService trust =
                boardTrust == null ? null : boardTrust.getIfAvailable();
        ClubBoard board = boardRepository.findByTeamId(teamId).orElseThrow();
        if (trust != null && puppet.chairman != 0) trust.chairmanHeard(teamId, puppet.chairman);
        int director = puppet.director;
        boolean promisedHim = meetingRecordRepository.findByTeamIdAndSeasonAndType(teamId, season, MeetingRecord.BOARD)
                .map(r -> "ANSWER:WITH_YOU".equals(r.getOutcome())).orElse(false);
        if (promisedHim && puppet == PuppetAnswer.FOR_THE_CLUB) director += LIED_TO;
        if (director != 0 && managerId > 0)
            relationService.change(RelationParty.DIRECTOR, board.getDirectorId(), RelationParty.MANAGER, managerId, director);
    }

    private void requirePending(long teamId, int season) {
        if (meetingRecordRepository.findByTeamIdAndSeasonAndType(teamId, season, MeetingRecord.PRESS).isPresent()) {
            throw new IllegalStateException("The first press conference has already taken place (or been refused) this season.");
        }
    }

    private void changeReputation(Human manager, int delta) {
        if (delta == 0) return;
        manager.setManagerReputation(Math.max(0, Math.min(10_000, manager.getManagerReputation() + delta)));
        humanRepository.save(manager);
    }

    private long managerOf(long teamId) {
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).stream()
                .filter(m -> !m.isRetired()).map(Human::getId).min(Long::compare).orElse(0L);
    }

    private void record(long teamId, long managerId, int season, String outcome) {
        MeetingRecord record = new MeetingRecord();
        record.setTeamId(teamId);
        record.setManagerId(managerId);
        record.setSeason(season);
        record.setType(MeetingRecord.PRESS);
        record.setOutcome(outcome);
        meetingRecordRepository.save(record);
    }
}
