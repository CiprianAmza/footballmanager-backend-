package com.footballmanagergamesimulator.service.squad;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.FinanceService;
import com.footballmanagergamesimulator.service.RelationService;
import com.footballmanagergamesimulator.service.board.BoardTrustService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * An extra bonus for the next match (design DESIGN_START_CARIERA §6.4, decided 2026-11-13): 25% of the squad's
 * weekly wages, paid only on a win. The chairman grants it when he trusts the manager (50+) and the club is not in
 * debt; a second request in the same month annoys him (-2). Refused, the manager may pay it himself: the players
 * appreciate it (+3 each), the chairman does not mind, the press writes about it. Either way the squad's morale +5.
 */
@Service
public class MatchBonusService {

    public static final double WAGE_SHARE = 0.25;
    public static final int CHAIRMAN_GRANTS_FROM = 50;
    public static final int ASKS_TOO_OFTEN = -2;
    public static final double MORALE = 5;
    public static final int PLAYERS_APPRECIATE = 3;
    /** A month is counted in 30-day blocks of the season calendar. */
    public static final int MONTH_DAYS = 30;

    public enum Outcome { GRANTED, REFUSED, PAID_BY_YOU }

    public record Result(Outcome outcome, long amount, String message) {}

    private final MatchBonusRepository bonusRepository;
    private final HumanRepository humanRepository;
    private final TeamRepository teamRepository;
    private final GameCalendarRepository calendarRepository;
    private final ManagerInboxRepository inboxRepository;
    private final RelationService relationService;
    private final FinanceService financeService;
    private final BoardTrustService boardTrust;

    public MatchBonusService(MatchBonusRepository bonusRepository, HumanRepository humanRepository,
                             TeamRepository teamRepository, GameCalendarRepository calendarRepository,
                             ManagerInboxRepository inboxRepository, RelationService relationService,
                             FinanceService financeService, BoardTrustService boardTrust) {
        this.bonusRepository = bonusRepository;
        this.humanRepository = humanRepository;
        this.teamRepository = teamRepository;
        this.calendarRepository = calendarRepository;
        this.inboxRepository = inboxRepository;
        this.relationService = relationService;
        this.financeService = financeService;
        this.boardTrust = boardTrust;
    }

    /** 25% of the squad's weekly wages; {@code Human.wage} is monthly (as FinanceService pays it). */
    public long amount(long teamId) {
        long monthly = squad(teamId).stream().mapToLong(Human::getWage).sum();
        return Math.round(monthly * 12.0 / 52.0 * WAGE_SHARE);
    }

    /** Asks the chairman for a bonus on the next match. */
    @Transactional
    public Result request(long teamId) {
        requireNoOpenBonus(teamId);
        GameCalendar now = now();
        long manager = boardTrust.managerOf(teamId);
        boolean askedThisMonth = bonusRepository.findAllByTeamIdAndSeason(teamId, now.getSeason()).stream()
                .anyMatch(b -> !b.isOwnMoney() && sameMonth(b.getRequestDay(), now.getCurrentDay()));
        if (askedThisMonth) boardTrust.chairmanHeard(teamId, ASKS_TOO_OFTEN);
        long amount = amount(teamId);
        boolean solvent = teamRepository.findById(teamId).map(t -> t.getTotalFinances() >= 0).orElse(false);
        boolean grants = solvent && boardTrust.trust(teamId).chairmanInManager() >= CHAIRMAN_GRANTS_FROM;
        MatchBonus bonus = row(teamId, manager, now, amount);
        bonus.setRefused(!grants);
        bonus.setSettled(!grants);
        bonusRepository.save(bonus);
        if (!grants) return new Result(Outcome.REFUSED, amount,
                "The chairman will not pay an extra bonus. You could pay it yourself.");
        lift(teamId);
        return new Result(Outcome.GRANTED, amount, "The chairman agrees: a bonus for the players if they win the next match.");
    }

    /** After the chairman's refusal: the manager pays the bonus himself (if he wins). */
    @Transactional
    public Result payYourself(long teamId) {
        requireNoOpenBonus(teamId);
        GameCalendar now = now();
        long managerId = boardTrust.managerOf(teamId);
        Human manager = humanRepository.findById(managerId).orElseThrow(() -> new IllegalStateException("No manager."));
        long amount = amount(teamId);
        if (manager.getWealth() < amount) throw new IllegalStateException("You cannot afford it.");
        MatchBonus bonus = row(teamId, managerId, now, amount);
        bonus.setOwnMoney(true);
        bonusRepository.save(bonus);
        lift(teamId);
        List<RelationService.Change> changes = new ArrayList<>();
        for (Human player : squad(teamId))
            changes.add(new RelationService.Change(RelationParty.PLAYER, player.getId(), RelationParty.MANAGER, managerId, PLAYERS_APPRECIATE));
        relationService.changeAll(changes);
        String club = teamRepository.findById(teamId).map(Team::getName).orElse("the club");
        ManagerInbox article = new ManagerInbox();
        article.setTeamId(teamId);
        article.setSeasonNumber(now.getSeason());
        article.setTitle(manager.getName() + " digs into his own pocket");
        article.setContent("The " + club + " manager has promised his players a bonus out of his own money if they win"
                + " the next match, after the board declined to pay one.");
        article.setCategory("press");
        article.setRead(false);
        article.setCreatedAt(System.currentTimeMillis());
        inboxRepository.save(article);
        return new Result(Outcome.PAID_BY_YOU, amount, "You promised the players a bonus from your own money.");
    }

    /** The next match is played: on a win the bonus is paid (by the club or by the manager). */
    @Transactional
    public void matchPlayed(long teamId, int goalsFor, int goalsAgainst) {
        for (MatchBonus bonus : bonusRepository.findAllByTeamIdAndSettledFalse(teamId)) {
            boolean won = goalsFor > goalsAgainst;
            bonus.setWon(won);
            bonus.setSettled(true);
            if (won) {
                if (bonus.isOwnMoney()) {
                    humanRepository.findById(bonus.getManagerId()).ifPresent(m -> {
                        m.setWealth(Math.max(0, m.getWealth() - bonus.getAmount()));
                        humanRepository.save(m);
                    });
                } else {
                    financeService.recordExpense(teamId, bonus.getSeason(), now().getCurrentDay(), "SQUAD_BONUS",
                            "Match bonus", bonus.getAmount());
                }
            }
            bonusRepository.save(bonus);
        }
    }

    /** What the next-match card shows: the amount, a bonus already promised, or a refusal this month. */
    public record Status(long amount, String promisedBy, boolean refusedThisMonth) {}

    public Status status(long teamId) {
        GameCalendar now = now();
        List<MatchBonus> rows = bonusRepository.findAllByTeamIdAndSeason(teamId, now.getSeason());
        String promisedBy = rows.stream().filter(b -> !b.isSettled()).findFirst()
                .map(b -> b.isOwnMoney() ? "YOU" : "CHAIRMAN").orElse(null);
        boolean refused = rows.stream().max(java.util.Comparator.comparingLong(MatchBonus::getId))
                .map(b -> b.isRefused() && sameMonth(b.getRequestDay(), now.getCurrentDay())).orElse(false);
        return new Status(amount(teamId), promisedBy, refused);
    }

    public boolean hasOpenBonus(long teamId) {
        return !bonusRepository.findAllByTeamIdAndSettledFalse(teamId).isEmpty();
    }

    static boolean sameMonth(int dayA, int dayB) {
        return (Math.max(1, dayA) - 1) / MONTH_DAYS == (Math.max(1, dayB) - 1) / MONTH_DAYS;
    }

    private void requireNoOpenBonus(long teamId) {
        if (hasOpenBonus(teamId)) throw new IllegalStateException("A bonus is already promised for the next match.");
    }

    private void lift(long teamId) {
        List<Human> squad = squad(teamId);
        for (Human p : squad) p.setMorale(Math.max(0, Math.min(100, p.getMorale() + MORALE)));
        humanRepository.saveAll(squad);
    }

    private List<Human> squad(long teamId) {
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).stream()
                .filter(p -> !p.isRetired()).toList();
    }

    private MatchBonus row(long teamId, long managerId, GameCalendar now, long amount) {
        MatchBonus bonus = new MatchBonus();
        bonus.setTeamId(teamId);
        bonus.setManagerId(managerId);
        bonus.setSeason(now.getSeason());
        bonus.setRequestDay(now.getCurrentDay());
        bonus.setAmount(amount);
        return bonus;
    }

    private GameCalendar now() {
        return calendarRepository.findTopByOrderBySeasonDesc().orElseGet(() -> {
            GameCalendar c = new GameCalendar();
            c.setSeason(1);
            c.setCurrentDay(1);
            return c;
        });
    }
}
