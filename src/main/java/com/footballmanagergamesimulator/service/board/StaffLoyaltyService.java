package com.footballmanagergamesimulator.service.board;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.RelationService;
import com.footballmanagergamesimulator.service.StaffService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Staff loyalty split between the human manager and the general director (design DESIGN_START_CARIERA §5.2,
 * decided 2026-11-13). Hidden: the manager only hears his assistant's read of each colleague. An ambitious
 * director works on the staff every month and can insist on keeping his own men.
 */
@Service
public class StaffLoyaltyService {

    public static final int BROUGHT = 100;
    public static final int FOUND_START = 50;
    public static final int PER_SEASON_AT_CLUB = 5;
    public static final int FOUND_MIN = 15;
    public static final int MAX_TENURE = 8;
    public static final int RESULT = 1;
    public static final int PRAISE = 5;
    public static final int DIRECTOR_MONTHLY = -2;
    /** Below this the man is the director's, and an ambitious director will fight to keep him. */
    public static final int DIRECTORS_MAN = 30;
    /** What the director thinks of the manager after the chairman overrules him. */
    public static final int OVERRULED_DIRECTOR = -10;

    private final StaffLoyaltyRepository loyaltyRepository;
    private final ClubBoardRepository boardRepository;
    private final HumanRepository humanRepository;
    private final GameCalendarRepository calendarRepository;
    private final StaffService staffService;
    private final RelationService relationService;
    private final ObjectProvider<BoardTrustService> boardTrust;
    private final Random rng = new Random();

    public StaffLoyaltyService(StaffLoyaltyRepository loyaltyRepository, ClubBoardRepository boardRepository,
                               HumanRepository humanRepository, GameCalendarRepository calendarRepository,
                               StaffService staffService, RelationService relationService,
                               ObjectProvider<BoardTrustService> boardTrust) {
        this.loyaltyRepository = loyaltyRepository;
        this.boardRepository = boardRepository;
        this.humanRepository = humanRepository;
        this.calendarRepository = calendarRepository;
        this.staffService = staffService;
        this.relationService = relationService;
        this.boardTrust = boardTrust;
    }

    /** Starting loyalty: his own hires and clubs without a director are fully his; the longer at the club, the more the director's. */
    public static int starting(boolean broughtByManager, boolean clubHasDirector, int tenureSeasons) {
        if (broughtByManager || !clubHasDirector) return BROUGHT;
        return Math.max(FOUND_MIN, FOUND_START - PER_SEASON_AT_CLUB * tenureSeasons);
    }

    /** The assistant's read of a colleague (the only hint the manager gets). */
    public static String read(int loyalty) {
        if (loyalty >= 70) return "Firmly in your corner.";
        if (loyalty >= 45) return "Hard to read. Does his job and keeps his opinions to himself.";
        if (loyalty >= 25) return "Spends a lot of time in the director's office.";
        return "I would not tell him anything you don't want the director to hear.";
    }

    /** A manager takes the club: everyone he finds starts by his years at the club (drawn 0-8). */
    @Transactional
    public void managerAppointed(long teamId) {
        loyaltyRepository.deleteAllByTeamId(teamId);
        loyaltyRepository.flush();
        boolean director = hasDirector(teamId);
        for (Human staff : staffService.getTeamStaff(teamId)) {
            int tenure = rng.nextInt(MAX_TENURE + 1);
            save(staff.getId(), teamId, starting(false, director, tenure), tenure, false);
        }
    }

    /** The manager hires someone himself: fully his man. */
    @Transactional
    public void hired(long staffId, long teamId) {
        loyaltyRepository.findByStaffId(staffId).ifPresent(row -> {
            loyaltyRepository.delete(row);
            loyaltyRepository.flush();
        });
        save(staffId, teamId, BROUGHT, 0, true);
    }

    @Transactional
    public void left(long staffId) {
        loyaltyRepository.deleteByStaffId(staffId);
    }

    /** Good results win the staff over, bad ones cost him. */
    @Transactional
    public void matchPlayed(long teamId, int goalsFor, int goalsAgainst) {
        if (goalsFor == goalsAgainst) return;
        int delta = goalsFor > goalsAgainst ? RESULT : -RESULT;
        for (StaffLoyalty row : loyaltyRepository.findAllByTeamId(teamId)) move(row, delta);
    }

    public record Praise(boolean counted, String message) {}

    /** Praising a member of staff in public: counts once a season. */
    @Transactional
    public Praise praise(long teamId, long staffId) {
        StaffLoyalty row = loyaltyRepository.findByStaffId(staffId).orElse(null);
        String name = humanRepository.findById(staffId).map(Human::getName).orElse("He");
        if (row == null || row.getTeamId() != teamId) return new Praise(false, "He is not on your staff.");
        int season = season();
        if (row.getLastPraisedSeason() == season)
            return new Praise(false, "You already praised " + name + " in public this season.");
        row.setLastPraisedSeason(season);
        move(row, PRAISE);
        return new Praise(true, "You praised " + name + " in front of the press.");
    }

    /** Every month an ambitious director works on the staff against the manager. */
    @Transactional
    public void monthPassed() {
        for (ClubBoard board : boardRepository.findAll()) {
            if (!board.isConflict()) continue;
            for (StaffLoyalty row : loyaltyRepository.findAllByTeamId(board.getTeamId())) move(row, DIRECTOR_MONTHLY);
        }
    }

    /** A new director has no men yet: everyone the manager did not bring starts again from 50. */
    @Transactional
    public void directorReplaced(long teamId) {
        for (StaffLoyalty row : loyaltyRepository.findAllByTeamId(teamId)) {
            if (row.isBroughtByManager()) continue;
            row.setLoyalty(FOUND_START);
            loyaltyRepository.save(row);
        }
    }

    /** The assistant's remarks on each colleague; a disloyal assistant covers for the director's men. */
    public Map<Long, String> assistantReads(long teamId) {
        Map<Long, Integer> loyalty = new HashMap<>();
        for (StaffLoyalty row : loyaltyRepository.findAllByTeamId(teamId)) loyalty.put(row.getStaffId(), row.getLoyalty());
        Human assistant = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.ASSISTANT_MANAGER_TYPE).stream()
                .min(Comparator.comparingLong(Human::getId)).orElse(null);
        Map<Long, String> reads = new LinkedHashMap<>();
        if (assistant == null) return reads;
        boolean covers = loyalty.getOrDefault(assistant.getId(), BROUGHT) < 45;
        for (Human staff : staffService.getTeamStaff(teamId)) {
            if (staff.getId() == assistant.getId() || !loyalty.containsKey(staff.getId())) continue;
            int value = loyalty.get(staff.getId());
            reads.put(staff.getId(), read(covers ? Math.max(45, value) : value));
        }
        return reads;
    }

    public record Dismissal(boolean done, boolean directorInsists, String message) {}

    /** Firing someone: an ambitious director insists on keeping his own men. */
    @Transactional
    public Dismissal fire(long teamId, long staffId) {
        ClubBoard board = boardRepository.findByTeamId(teamId).orElse(null);
        StaffLoyalty row = loyaltyRepository.findByStaffId(staffId).orElse(null);
        if (board != null && board.isConflict() && row != null && row.getTeamId() == teamId
                && row.getLoyalty() < DIRECTORS_MAN) {
            String name = humanRepository.findById(staffId).map(Human::getName).orElse("He");
            return new Dismissal(false, true, "The director insists that " + name
                    + " stays. You can accept it or take it to the chairman.");
        }
        return release(teamId, staffId);
    }

    /** The manager takes the dismissal to the chairman, who sides with whoever he trusts more. */
    @Transactional
    public Dismissal appeal(long teamId, long staffId) {
        ClubBoard board = boardRepository.findByTeamId(teamId).orElse(null);
        BoardTrustService trust = boardTrust.getIfAvailable();
        if (board == null || !board.hasDirector() || trust == null) return release(teamId, staffId);
        BoardTrustService.Trust t = trust.trust(teamId);
        String name = humanRepository.findById(staffId).map(Human::getName).orElse("He");
        if (t.chairmanInDirector() != null && t.chairmanInDirector() > t.chairmanInManager())
            return new Dismissal(false, false, "The chairman backs the director: " + name + " stays.");
        long manager = trust.managerOf(teamId);
        if (manager > 0)
            relationService.change(RelationParty.DIRECTOR, board.getDirectorId(), RelationParty.MANAGER, manager, OVERRULED_DIRECTOR);
        Dismissal done = release(teamId, staffId);
        return new Dismissal(done.done(), false, "The chairman backs you. " + done.message()
                + " The director will not forget it.");
    }

    public OptionalInt loyalty(long staffId) {
        return loyaltyRepository.findByStaffId(staffId).map(r -> OptionalInt.of(r.getLoyalty())).orElse(OptionalInt.empty());
    }

    private Dismissal release(long teamId, long staffId) {
        Map<String, Object> result = staffService.fireCoach(staffId, teamId);
        boolean done = Boolean.TRUE.equals(result.get("success"));
        if (done) left(staffId);
        return new Dismissal(done, false, String.valueOf(result.get("message")));
    }

    private void move(StaffLoyalty row, int delta) {
        row.setLoyalty(Math.max(0, Math.min(100, row.getLoyalty() + delta)));
        loyaltyRepository.save(row);
    }

    private void save(long staffId, long teamId, int loyalty, int tenure, boolean brought) {
        StaffLoyalty row = new StaffLoyalty();
        row.setStaffId(staffId);
        row.setTeamId(teamId);
        row.setLoyalty(loyalty);
        row.setTenureSeasons(tenure);
        row.setBroughtByManager(brought);
        loyaltyRepository.save(row);
    }

    private boolean hasDirector(long teamId) {
        return boardRepository.findByTeamId(teamId).map(ClubBoard::hasDirector).orElse(false);
    }

    private int season() {
        return calendarRepository.findTopByOrderBySeasonDesc().map(GameCalendar::getSeason).orElse(1);
    }
}
