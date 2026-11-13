package com.footballmanagergamesimulator.service.board;

import com.footballmanagergamesimulator.config.BoardTrustConfig;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.ManagerCareerService;
import com.footballmanagergamesimulator.service.RelationService;
import com.footballmanagergamesimulator.util.TypeNames;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The chairman's trust in the manager and in the general director, and the director's in the manager (design
 * DESIGN_START_CARIERA §5.0.1, decided 2026-11-13). Kept for human-managed clubs with an AI chairman. Results,
 * trophies and public promises move it; below the threshold the chairman sacks whoever he trusts less.
 */
@Service
public class BoardTrustService {

    private final RelationService relationService;
    private final ClubBoardRepository boardRepository;
    private final HumanRepository humanRepository;
    private final OwnershipRepository ownershipRepository;
    private final CompetitionHistoryRepository historyRepository;
    private final ManagerInboxRepository inboxRepository;
    private final BoardTrustConfig config;
    @Autowired @Lazy private ManagerCareerService managerCareerService;
    @Autowired @Lazy private UserContext userContext;
    @Autowired @Lazy private ClubBoardService clubBoardService;
    @Autowired private GameCalendarRepository calendarRepository;
    @Autowired(required = false) private LicenceService licenceService;
    @Autowired(required = false) private org.springframework.beans.factory.ObjectProvider<StaffLoyaltyService> staffLoyalty;

    public BoardTrustService(RelationService relationService, ClubBoardRepository boardRepository,
                             HumanRepository humanRepository, OwnershipRepository ownershipRepository,
                             CompetitionHistoryRepository historyRepository, ManagerInboxRepository inboxRepository,
                             BoardTrustConfig config) {
        this.relationService = relationService;
        this.boardRepository = boardRepository;
        this.humanRepository = humanRepository;
        this.ownershipRepository = ownershipRepository;
        this.historyRepository = historyRepository;
        this.inboxRepository = inboxRepository;
        this.config = config;
    }

    public record Trust(int chairmanInManager, Integer chairmanInDirector, Integer directorInManager) {}

    public Trust trust(long teamId) {
        ClubBoard board = boardRepository.findByTeamId(teamId).orElse(null);
        long manager = managerOf(teamId);
        if (board == null || manager == 0) return new Trust(RelationService.NEUTRAL, null, null);
        int inManager = relationService.relation(RelationParty.CHAIRMAN, board.getChairmanId(), RelationParty.MANAGER, manager);
        if (!board.hasDirector()) return new Trust(inManager, null, null);
        return new Trust(inManager,
                relationService.relation(RelationParty.CHAIRMAN, board.getChairmanId(), RelationParty.DIRECTOR, board.getDirectorId()),
                relationService.relation(RelationParty.DIRECTOR, board.getDirectorId(), RelationParty.MANAGER, manager));
    }

    /** A director takes office: the chairman's trust in him starts at 50 plus his seniority. */
    @Transactional
    public void directorAppointed(ClubBoard board, int season) {
        if (!board.hasDirector()) return;
        int seniority = Math.max(0, season - board.getDirectorSinceSeason());
        int start = Math.min(config.getDirectorTrustMax(), RelationService.NEUTRAL + seniority * config.getDirectorTrustPerSeason());
        setTo(RelationParty.CHAIRMAN, board.getChairmanId(), RelationParty.DIRECTOR, board.getDirectorId(), start);
    }

    /** A manager takes the club: the director's trust in him starts by the director's personality. */
    @Transactional
    public void managerAppointed(long managerId, long teamId) {
        ClubBoard board = boardRepository.findByTeamId(teamId).orElse(null);
        if (board == null) return;
        // Without the licence his league needs, the chairman starts with less patience (§3).
        boolean qualified = licenceService == null || licenceService.qualifies(managerId, teamId);
        setTo(RelationParty.CHAIRMAN, board.getChairmanId(), RelationParty.MANAGER, managerId,
                qualified ? RelationService.NEUTRAL : config.getUnlicensedStart());
        if (!board.hasDirector()) return;
        int start = switch (board.getDirectorPersonality()) {
            case SUPPORTIVE -> config.getDirectorSupportiveStart();
            case OWNER_LOYAL -> config.getDirectorOwnerLoyalStart();
            case AMBITIOUS -> config.getDirectorAmbitiousStart();
            default -> config.getDirectorNeutralStart();
        };
        setTo(RelationParty.DIRECTOR, board.getDirectorId(), RelationParty.MANAGER, managerId, start);
    }

    /** As below, in the current season. */
    @Transactional
    public void matchPlayed(long teamId, int goalsFor, int goalsAgainst) {
        matchPlayed(teamId, goalsFor, goalsAgainst,
                calendarRepository.findTopByOrderBySeasonDesc().map(GameCalendar::getSeason).orElse(1));
    }

    /** A match of a human-managed club: the chairman's trust moves with the result, then he may act. */
    @Transactional
    public void matchPlayed(long teamId, int goalsFor, int goalsAgainst, int season) {
        if (goalsFor == goalsAgainst) return;
        changeTrustInManager(teamId, goalsFor > goalsAgainst ? config.getWin() : config.getLoss(), season);
    }

    /** A public promise kept or missed. */
    @Transactional
    public void promiseSettled(long teamId, boolean kept, int season) {
        changeTrustInManager(teamId, kept ? config.getPromiseKept() : config.getPromiseMissed(), season);
    }

    /** Season end: every trophy the club won this season lifts the chairman's trust in its manager. */
    @Transactional
    public void seasonEnded(int season) {
        for (long teamId : humanTeams()) {
            long trophies = historyRepository.findByTeamIdAndSeasonNumber(teamId, season).stream()
                    .filter(h -> h.getLastPosition() == 1).count();
            if (trophies > 0) changeTrustInManager(teamId, (int) trophies * config.getTrophy(), season);
        }
    }

    /** A public statement moved the chairman's trust (in the current season). */
    @Transactional
    public void chairmanHeard(long teamId, int delta) {
        changeTrustInManager(teamId, delta,
                calendarRepository.findTopByOrderBySeasonDesc().map(GameCalendar::getSeason).orElse(1));
    }

    void changeTrustInManager(long teamId, int delta, int season) {
        if (delta == 0 || !managed(teamId)) return;
        ClubBoard board = boardRepository.findByTeamId(teamId).orElse(null);
        long manager = managerOf(teamId);
        if (board == null || manager == 0 || userOwned(teamId)) return;
        int trust = relationService.change(RelationParty.CHAIRMAN, board.getChairmanId(), RelationParty.MANAGER, manager, delta);
        if (trust < config.getSackThreshold()) chairmanActs(board, manager, trust, season);
    }

    /** The AI chairman's patience is gone: he sacks whoever he trusts less, the director or the manager. */
    private void chairmanActs(ClubBoard board, long managerId, int trustInManager, int season) {
        if (board.hasDirector()) {
            int trustInDirector = relationService.relation(RelationParty.CHAIRMAN, board.getChairmanId(),
                    RelationParty.DIRECTOR, board.getDirectorId());
            if (trustInDirector < trustInManager) {
                String old = replaceDirector(board.getTeamId(), season);
                // The chairman gives his manager a fresh start after making the director pay.
                setTo(RelationParty.CHAIRMAN, board.getChairmanId(), RelationParty.MANAGER, managerId, config.getSackThreshold() + 10);
                notify(board.getTeamId(), season, "Director Dismissed",
                        old + " has been dismissed by the chairman. The board blames him, not you, for now.");
                return;
            }
        }
        managerCareerService.sackHumanManager(board.getTeamId(), season);
        notify(board.getTeamId(), season, "You Have Been Sacked!",
                "The chairman has lost all faith in you and has relieved you of your duties with immediate effect.");
    }

    /** The chairman dismisses the director and brings in a new one (any personality, no seniority); returns the old name. */
    @Transactional
    public String replaceDirector(long teamId, int season) {
        ClubBoard board = boardRepository.findByTeamId(teamId).orElseThrow();
        String old = humanRepository.findById(board.getDirectorId()).map(Human::getName).orElse("The director");
        clubBoardService.edit(teamId, null);
        clubBoardService.edit(teamId, ClubBoards.personality(Math.random()));
        directorAppointed(boardRepository.findByTeamId(teamId).orElseThrow(), season);
        StaffLoyaltyService loyalty = staffLoyalty == null ? null : staffLoyalty.getIfAvailable();
        if (loyalty != null) loyalty.directorReplaced(teamId);
        return old;
    }

    private void setTo(RelationParty subject, long subjectId, RelationParty target, long targetId, int value) {
        int current = relationService.relation(subject, subjectId, target, targetId);
        if (current != value) relationService.change(subject, subjectId, target, targetId, value - current);
    }

    private void notify(long teamId, int season, String title, String content) {
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(season);
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory("board");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        inboxRepository.save(inbox);
    }

    private boolean managed(long teamId) {
        return userContext == null || userContext.isHumanTeam(teamId);
    }

    private java.util.List<Long> humanTeams() {
        return userContext == null ? java.util.List.of() : userContext.getAllHumanTeamIds();
    }

    private boolean userOwned(long teamId) {
        return !ownershipRepository.findAllByTeamId(teamId).isEmpty();
    }

    public long managerOf(long teamId) {
        Optional<Human> manager = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).stream()
                .filter(m -> !m.isRetired()).min(java.util.Comparator.comparingLong(Human::getId));
        return manager.map(Human::getId).orElse(0L);
    }
}
