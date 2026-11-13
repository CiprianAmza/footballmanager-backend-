package com.footballmanagergamesimulator.service.board;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.RelationService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The general director stepping in (design DESIGN_START_CARIERA §5, decided 2026-11-13): each month he passes on
 * "the chairman would like X", which may really be his own agenda; obeying or ignoring it moves the trust of whoever
 * wanted it. The manager may ask the chairman directly once a season, and either side may ask the chairman to
 * dismiss the other.
 */
@Service
public class DirectorWishService {

    public static final int OBEYED = 3;
    public static final int IGNORED = -5;
    public static final int ASKED_CHAIRMAN = -5;
    public static final int DISMISSAL_MARGIN = 10;
    public static final int FAILED_DISMISSAL = -10;
    public static final int LOYAL = 70;
    /** An ambitious director who trusts the manager less than this asks the chairman to sack him. */
    public static final int DIRECTOR_PLOTS_BELOW = 20;
    public static final double SUPPORTIVE_WISH_CHANCE = 0.25;
    public static final double AMBITIOUS_OWN_AGENDA = 0.60;

    private final DirectorWishRepository wishRepository;
    private final ClubBoardRepository boardRepository;
    private final HumanRepository humanRepository;
    private final PersonalizedTacticRepository tacticRepository;
    private final StaffLoyaltyRepository loyaltyRepository;
    private final ManagerInboxRepository inboxRepository;
    private final GameCalendarRepository calendarRepository;
    private final RelationService relationService;
    private final BoardTrustService boardTrust;
    private final ObjectProvider<UserContext> userContext;
    private final Random rng;

    @Autowired
    public DirectorWishService(DirectorWishRepository wishRepository, ClubBoardRepository boardRepository,
                               HumanRepository humanRepository, PersonalizedTacticRepository tacticRepository,
                               StaffLoyaltyRepository loyaltyRepository, ManagerInboxRepository inboxRepository,
                               GameCalendarRepository calendarRepository, RelationService relationService,
                               BoardTrustService boardTrust, ObjectProvider<UserContext> userContext) {
        this(wishRepository, boardRepository, humanRepository, tacticRepository, loyaltyRepository, inboxRepository,
                calendarRepository, relationService, boardTrust, userContext, new Random());
    }

    DirectorWishService(DirectorWishRepository wishRepository, ClubBoardRepository boardRepository,
                        HumanRepository humanRepository, PersonalizedTacticRepository tacticRepository,
                        StaffLoyaltyRepository loyaltyRepository, ManagerInboxRepository inboxRepository,
                        GameCalendarRepository calendarRepository, RelationService relationService,
                        BoardTrustService boardTrust, ObjectProvider<UserContext> userContext, Random rng) {
        this.wishRepository = wishRepository;
        this.boardRepository = boardRepository;
        this.humanRepository = humanRepository;
        this.tacticRepository = tacticRepository;
        this.loyaltyRepository = loyaltyRepository;
        this.inboxRepository = inboxRepository;
        this.calendarRepository = calendarRepository;
        this.relationService = relationService;
        this.boardTrust = boardTrust;
        this.userContext = userContext;
        this.rng = rng;
    }

    /** Monthly: settle last month's wish, let a plotting director act, then pass on a new wish. */
    @Transactional
    public void monthPassed() {
        UserContext users = userContext.getIfAvailable();
        if (users == null) return;
        int season = season();
        for (long teamId : users.getAllHumanTeamIds()) {
            ClubBoard board = boardRepository.findByTeamId(teamId).orElse(null);
            if (board == null || !board.hasDirector()) continue;
            settle(teamId, season);
            directorPlots(board, season);
            if (boardTrust.managerOf(teamId) > 0) issue(board, season);
        }
    }

    /** Was the open wish followed? Whoever really wanted it trusts the manager more (+3) or less (-5). */
    @Transactional
    public void settle(long teamId, int season) {
        ClubBoard board = boardRepository.findByTeamId(teamId).orElse(null);
        long manager = boardTrust.managerOf(teamId);
        for (DirectorWish wish : wishRepository.findAllByTeamIdAndSettledFalse(teamId)) {
            boolean obeyed = obeyed(wish);
            wish.setSettled(true);
            wish.setObeyed(obeyed);
            wishRepository.save(wish);
            if (board == null || manager == 0) continue;
            int delta = obeyed ? OBEYED : IGNORED;
            if (wish.isFromChairman()) boardTrust.changeTrustInManager(teamId, delta, season);
            else if (board.hasDirector())
                relationService.change(RelationParty.DIRECTOR, board.getDirectorId(), RelationParty.MANAGER, manager, delta);
        }
    }

    /** A new wish: a formation, a young player to start, or a sale. The ambitious director slips in his own. */
    @Transactional
    public Optional<DirectorWish> issue(ClubBoard board, int season) {
        if (!board.hasDirector()) return Optional.empty();
        if (board.getDirectorPersonality() == DirectorPersonality.SUPPORTIVE && rng.nextDouble() >= SUPPORTIVE_WISH_CHANCE)
            return Optional.empty();
        long teamId = board.getTeamId();
        List<Human> squad = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).stream()
                .filter(p -> !p.isRetired()).toList();
        DirectorWish wish = new DirectorWish();
        wish.setTeamId(teamId);
        wish.setSeason(season);
        wish.setFromChairman(board.getDirectorPersonality() != DirectorPersonality.AMBITIOUS
                || rng.nextDouble() >= AMBITIOUS_OWN_AGENDA);
        String kind = List.of(DirectorWish.FORMATION, DirectorWish.PLAY_YOUNG, DirectorWish.SELL).get(rng.nextInt(3));
        Optional<Human> young = squad.stream().filter(p -> p.getAge() <= 21)
                .min(Comparator.comparingInt(Human::getSeasonMatchesPlayed));
        List<Human> sellable = squad.stream().sorted(Comparator.comparingInt(Human::getCurrentAbility).reversed())
                .skip(5).filter(p -> p.getAge() >= 27).toList();
        if (kind.equals(DirectorWish.PLAY_YOUNG) && young.isPresent()) {
            Human p = young.get();
            wish.setKind(kind);
            wish.setPlayerId(p.getId());
            wish.setAppearancesAtStart(p.getSeasonMatchesPlayed());
            wish.setText("The chairman would like to see " + p.getName() + " get some games this month.");
        } else if (kind.equals(DirectorWish.SELL) && !sellable.isEmpty()) {
            Human p = sellable.get(rng.nextInt(sellable.size()));
            wish.setKind(kind);
            wish.setPlayerId(p.getId());
            wish.setText("The chairman would like " + p.getName() + " sold before the month is out.");
        } else {
            String current = tacticRepository.findPersonalizedTacticByTeamId(teamId).map(PersonalizedTactic::getTactic).orElse(null);
            List<String> options = new ArrayList<>(TacticService.existingFormations());
            options.remove(current);
            if (options.isEmpty()) return Optional.empty();
            wish.setKind(DirectorWish.FORMATION);
            wish.setFormation(options.get(rng.nextInt(options.size())));
            wish.setText("The chairman would like the team to play " + wish.getFormation() + ". He likes it.");
        }
        wishRepository.save(wish);
        notify(teamId, season, "A word from the director", wish.getText());
        return Optional.of(wish);
    }

    public record Answer(boolean ok, String message) {}

    /** Ask the chairman whose wish it is: once a season, and the director takes it badly (-5). */
    @Transactional
    public Answer askChairman(long teamId, long wishId) {
        DirectorWish wish = wishRepository.findById(wishId).orElse(null);
        if (wish == null || wish.getTeamId() != teamId) return new Answer(false, "No such request.");
        int season = season();
        if (wishRepository.findAllByTeamIdAndSeason(teamId, season).stream().anyMatch(DirectorWish::isRevealed))
            return new Answer(false, "You have already gone over the director's head this season.");
        wish.setRevealed(true);
        wishRepository.save(wish);
        ClubBoard board = boardRepository.findByTeamId(teamId).orElse(null);
        long manager = boardTrust.managerOf(teamId);
        if (board != null && board.hasDirector() && manager > 0)
            relationService.change(RelationParty.DIRECTOR, board.getDirectorId(), RelationParty.MANAGER, manager, ASKED_CHAIRMAN);
        return new Answer(true, wish.isFromChairman()
                ? "The chairman confirms it: that is what he wants."
                : "The chairman looks surprised. He never asked for that.");
    }

    /**
     * The manager asks the chairman to dismiss the director: granted when the chairman trusts him at least 10 more
     * and at least half the staff stand with him (70+); otherwise the chairman's trust in him drops by 10.
     */
    @Transactional
    public Answer requestDirectorDismissal(long teamId) {
        ClubBoard board = boardRepository.findByTeamId(teamId).orElse(null);
        if (board == null || !board.hasDirector()) return new Answer(false, "The club has no director.");
        BoardTrustService.Trust t = boardTrust.trust(teamId);
        List<StaffLoyalty> staff = loyaltyRepository.findAllByTeamId(teamId);
        long loyal = staff.stream().filter(s -> s.getLoyalty() > LOYAL).count();
        boolean backed = !staff.isEmpty() && loyal * 2 >= staff.size();
        int season = season();
        if (t.chairmanInDirector() != null && t.chairmanInManager() >= t.chairmanInDirector() + DISMISSAL_MARGIN && backed) {
            boardTrust.replaceDirector(teamId, season);
            return new Answer(true, "The chairman agrees. The director is gone.");
        }
        boardTrust.changeTrustInManager(teamId, FAILED_DISMISSAL, season);
        return new Answer(false, "The chairman refuses, and he did not like being asked.");
    }

    /** An ambitious director who has given up on the manager asks the chairman to sack him. */
    void directorPlots(ClubBoard board, int season) {
        if (!board.isConflict()) return;
        BoardTrustService.Trust t = boardTrust.trust(board.getTeamId());
        if (t.directorInManager() == null || t.directorInManager() >= DIRECTOR_PLOTS_BELOW) return;
        if (t.chairmanInDirector() < t.chairmanInManager() + DISMISSAL_MARGIN) return;
        notify(board.getTeamId(), season, "Whispers upstairs",
                "Word is the director has asked the chairman to replace you.");
        boardTrust.changeTrustInManager(board.getTeamId(), FAILED_DISMISSAL, season);
    }

    public List<DirectorWish> wishes(long teamId) {
        return wishRepository.findAllByTeamIdOrderByIdDesc(teamId).stream().limit(12).toList();
    }

    boolean obeyed(DirectorWish wish) {
        switch (wish.getKind()) {
            case DirectorWish.FORMATION:
                return tacticRepository.findPersonalizedTacticByTeamId(wish.getTeamId())
                        .map(t -> wish.getFormation().equals(t.getTactic())).orElse(false);
            case DirectorWish.SELL:
                return humanRepository.findById(wish.getPlayerId())
                        .map(p -> p.getTeamId() == null || p.getTeamId() != wish.getTeamId()).orElse(true);
            default:
                return humanRepository.findById(wish.getPlayerId()).map(p -> {
                    int now = p.getSeasonMatchesPlayed();
                    return now - wish.getAppearancesAtStart() >= 2 || (now < wish.getAppearancesAtStart() && now >= 2);
                }).orElse(false);
        }
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

    private int season() {
        return calendarRepository.findTopByOrderBySeasonDesc().map(GameCalendar::getSeason).orElse(1);
    }
}
