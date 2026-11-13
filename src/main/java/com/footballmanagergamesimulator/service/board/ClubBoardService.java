package com.footballmanagergamesimulator.service.board;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.NationService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Every club's chairman and, at some clubs, a general director (design DESIGN_START_CARIERA §5, decided
 * 2026-11-12). Created once per world; the editor can add, remove or change a director.
 */
@Service
public class ClubBoardService {

    private final ClubBoardRepository boardRepository;
    private final TeamRepository teamRepository;
    private final HumanRepository humanRepository;
    private final OwnershipRepository ownershipRepository;
    private final NationService nationService;
    private final CompositeNameGenerator nameGenerator;
    private final GameCalendarRepository calendarRepository;
    private final Random rng = new Random();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<BoardTrustService> boardTrust;

    private void trustDirector(ClubBoard board, int season) {
        if (boardTrust != null) boardTrust.ifAvailable(t -> t.directorAppointed(board, season));
    }

    public ClubBoardService(ClubBoardRepository boardRepository, TeamRepository teamRepository,
                            HumanRepository humanRepository, OwnershipRepository ownershipRepository,
                            NationService nationService, CompositeNameGenerator nameGenerator,
                            GameCalendarRepository calendarRepository) {
        this.boardRepository = boardRepository;
        this.teamRepository = teamRepository;
        this.humanRepository = humanRepository;
        this.ownershipRepository = ownershipRepository;
        this.nationService = nationService;
        this.nameGenerator = nameGenerator;
        this.calendarRepository = calendarRepository;
    }

    /** A club's board as shown to everyone; the director's personality is shown only when it is public (conflict). */
    public record BoardView(long teamId, String chairmanName, boolean chairmanIsUser, boolean hasDirector,
                            String directorName, boolean conflict, String description) {}

    /** Creates the boards of every club without one. Returns how many were created. */
    @Transactional
    public int ensureBoards() {
        Set<Long> done = new HashSet<>();
        boardRepository.findAll().forEach(b -> done.add(b.getTeamId()));
        List<Team> clubs = teamRepository.findAll().stream()
                .sorted(Comparator.comparingInt(Team::getReputation).reversed().thenComparingLong(Team::getId))
                .toList();
        int season = currentSeason();
        int created = 0;
        for (int rank = 0; rank < clubs.size(); rank++) {
            Team club = clubs.get(rank);
            if (done.contains(club.getId())) continue;
            long nation = nationService.nationIdForTeam(club.getId());
            ClubBoard board = new ClubBoard();
            board.setTeamId(club.getId());
            board.setChairmanId(person(club.getId(), nation, TypeNames.CHAIRMAN_TYPE, 45, 75).getId());
            if (rng.nextDouble() < ClubBoards.directorChance(rank, clubs.size())) {
                board.setDirectorId(person(club.getId(), nation, TypeNames.DIRECTOR_TYPE, 38, 65).getId());
                board.setDirectorPersonality(ClubBoards.personality(rng.nextDouble()));
                board.setDirectorSinceSeason(Math.max(1, season - rng.nextInt(6)));
            }
            boardRepository.save(board);
            trustDirector(board, season);
            created++;
        }
        return created;
    }

    private Human person(long teamId, long nationId, long type, int minAge, int maxAge) {
        Human h = new Human();
        h.setName(nameGenerator.generateNameForNation(nationId));
        h.setTypeId(type);
        h.setTeamId(teamId);
        h.setAge(minAge + rng.nextInt(maxAge - minAge + 1));
        return humanRepository.save(h);
    }

    public Optional<ClubBoard> board(long teamId) {
        return boardRepository.findByTeamId(teamId);
    }

    public BoardView view(long teamId) {
        ClubBoard board = boardRepository.findByTeamId(teamId).orElse(null);
        if (board == null) return new BoardView(teamId, null, false, false, null, false, "");
        Optional<Human> owner = ownershipRepository.findAllByTeamId(teamId).stream().findFirst()
                .flatMap(o -> humanRepository.findById(o.getHumanId()));
        String chairman = owner.map(Human::getName)
                .orElseGet(() -> humanRepository.findById(board.getChairmanId()).map(Human::getName).orElse(null));
        String director = board.hasDirector()
                ? humanRepository.findById(board.getDirectorId()).map(Human::getName).orElse(null) : null;
        String description;
        if (board.isConflict()) {
            description = "Power struggle at the top: general director " + director
                    + " pursues his own agenda, often against the chairman's wishes.";
        } else if (board.hasDirector()) {
            description = "The chairman runs the club through general director " + director + ".";
        } else {
            description = "The chairman deals with the manager directly.";
        }
        return new BoardView(teamId, chairman, owner.isPresent(), board.hasDirector(), director, board.isConflict(), description);
    }

    /** Editor: give the club a director with this personality (a new one if it had none), or remove him (null). */
    @Transactional
    public BoardView edit(long teamId, DirectorPersonality personality) {
        ensureBoards();
        ClubBoard board = boardRepository.findByTeamId(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown club " + teamId));
        if (personality == null) {
            if (board.getDirectorId() > 0) {
                humanRepository.findById(board.getDirectorId()).ifPresent(d -> {
                    d.setTeamId(0L);
                    humanRepository.save(d);
                });
            }
            board.setDirectorId(0);
            board.setDirectorPersonality(null);
        } else {
            boolean appointed = board.getDirectorId() == 0;
            if (appointed) {
                board.setDirectorId(person(teamId, nationService.nationIdForTeam(teamId), TypeNames.DIRECTOR_TYPE, 38, 65).getId());
                board.setDirectorSinceSeason(currentSeason());
            }
            board.setDirectorPersonality(personality);
            boardRepository.save(board);
            if (appointed) trustDirector(board, currentSeason());
        }
        boardRepository.save(board);
        return view(teamId);
    }

    private int currentSeason() {
        return calendarRepository.findTopByOrderBySeasonDesc().map(GameCalendar::getSeason).orElse(1);
    }
}
