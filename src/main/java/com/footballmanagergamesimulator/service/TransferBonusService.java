package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TransferBonus;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferBonusRepository;
import com.footballmanagergamesimulator.util.TransferWindow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Performance bonuses of transfers (design §3.5): the selling club's estimate when it weighs an offer, booking
 * them when the transfer completes, and paying them at the end of a season in which they are earned.
 */
@Service
public class TransferBonusService {

    /** League competitions (type 1). */
    static final long LEAGUE_TYPE = 1;

    @Autowired TransferBonusRepository bonusRepository;
    @Autowired ScorerRepository scorerRepository;
    @Autowired CompetitionHistoryRepository competitionHistoryRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired FinanceService financeService;

    /** The selling club's chances, over the bonus's seasons, that each bonus is paid. */
    public record Chances(double goals, double title) {
    }

    public Chances chances(long playerId, int goalsTarget, long buyerTeamId, int season) {
        int goals = Math.max(goals(playerId, season, null), goals(playerId, season - 1, null));
        double goalsChance = TransferBonusOdds.overContract(TransferBonusOdds.goalsPerSeason(goals, goalsTarget));
        double titleChance = TransferBonusOdds.overContract(TransferBonusOdds.titlePerSeason(lastLeaguePosition(buyerTeamId)));
        return new Chances(goalsChance, titleChance);
    }

    /**
     * First season a bonus counts: a summer move before the season ends (June–July) counts from the next season,
     * any other (August–September, January) from the current one.
     */
    public static int firstSeason(int absoluteDay) {
        int season = PlannedTransferService.seasonOf(absoluteDay);
        return PlannedTransferService.dayOf(absoluteDay) >= TransferWindow.SUMMER_OPEN_DAY ? season + 1 : season;
    }

    /** Books the bonuses of a completed transfer (amount 0 = not offered). */
    @Transactional
    public List<TransferBonus> schedule(long playerId, String playerName, long payerTeamId, long payeeTeamId,
                                        int goalsTarget, long goalsAmount, long titleAmount, int absoluteDay) {
        int from = firstSeason(absoluteDay);
        List<TransferBonus> rows = new ArrayList<>();
        if (goalsAmount > 0 && goalsTarget > 0) {
            rows.add(bonus(playerId, playerName, payerTeamId, payeeTeamId, TransferBonus.GOALS, goalsTarget, goalsAmount, from));
        }
        if (titleAmount > 0) {
            rows.add(bonus(playerId, playerName, payerTeamId, payeeTeamId, TransferBonus.TITLE, 0, titleAmount, from));
        }
        return bonusRepository.saveAll(rows);
    }

    /** End of a season: every bonus earned in it is paid, once. Returns those paid. */
    @Transactional
    public List<TransferBonus> settle(int season) {
        List<TransferBonus> paid = new ArrayList<>();
        for (TransferBonus bonus : bonusRepository
                .findAllByPaidFalseAndFromSeasonLessThanEqualAndUntilSeasonGreaterThanEqual(season, season)) {
            boolean earned = TransferBonus.GOALS.equals(bonus.getType())
                    ? goals(bonus.getPlayerId(), season, bonus.getPayerTeamId()) >= bonus.getTarget()
                    : wonLeague(bonus.getPayerTeamId(), season);
            if (!earned) continue;
            pay(bonus, season);
            bonus.setPaid(true);
            paid.add(bonus);
        }
        bonusRepository.saveAll(paid);
        return paid;
    }

    private void pay(TransferBonus bonus, int season) {
        String what = TransferBonus.GOALS.equals(bonus.getType())
                ? bonus.getTarget() + "-goal bonus for " + bonus.getPlayerName()
                : "Title bonus for " + bonus.getPlayerName();
        financeService.recordExpense(bonus.getPayerTeamId(), season, 0, "TRANSFER_BONUS", what, bonus.getAmount());
        teamRepository.findById(bonus.getPayerTeamId()).ifPresent(payer -> {
            payer.setTransferBudget(payer.getTransferBudget() - bonus.getAmount());
            teamRepository.save(payer);
        });
        financeService.recordTransaction(bonus.getPayeeTeamId(), season, 0, "TRANSFER_BONUS", what, bonus.getAmount());
        Team payee = teamRepository.findById(bonus.getPayeeTeamId()).orElse(null);
        if (payee != null) {
            payee.setTransferBudget(payee.getTransferBudget() + bonus.getAmount());
            teamRepository.save(payee);
        }
    }

    private static TransferBonus bonus(long playerId, String playerName, long payer, long payee, String type,
                                       int target, long amount, int from) {
        TransferBonus row = new TransferBonus();
        row.setPlayerId(playerId);
        row.setPlayerName(playerName);
        row.setPayerTeamId(payer);
        row.setPayeeTeamId(payee);
        row.setType(type);
        row.setTarget(target);
        row.setAmount(amount);
        row.setFromSeason(from);
        row.setUntilSeason(from + TransferBonusOdds.SEASONS - 1);
        return row;
    }

    /** The player's goals in a season, in all competitions; only for {@code teamId} when given. */
    int goals(long playerId, int season, Long teamId) {
        return scorerRepository.findByPlayerIdAndSeasonNumber(playerId, season).stream()
                .filter(row -> teamId == null || row.getTeamId() == teamId)
                .mapToInt(Scorer::getGoals).sum();
    }

    private boolean wonLeague(long teamId, int season) {
        return competitionHistoryRepository.findByTeamIdAndSeasonNumber(teamId, season).stream()
                .anyMatch(h -> h.getCompetitionTypeId() == LEAGUE_TYPE && h.getLastPosition() == 1);
    }

    /** The club's finish in its league in the latest season on record; 0 when none. */
    private long lastLeaguePosition(long teamId) {
        return competitionHistoryRepository.findByTeamId(teamId).stream()
                .filter(h -> h.getCompetitionTypeId() == LEAGUE_TYPE)
                .max(Comparator.comparingLong(CompetitionHistory::getSeasonNumber))
                .map(CompetitionHistory::getLastPosition).orElse(0L);
    }
}
