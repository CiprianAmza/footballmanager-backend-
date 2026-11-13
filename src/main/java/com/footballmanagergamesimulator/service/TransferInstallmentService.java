package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TransferInstallment;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferInstallmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Transfer fees paid in instalments (design §3.5): one per season, at its start. */
@Service
public class TransferInstallmentService {

    @Autowired TransferInstallmentRepository installmentRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired FinanceService financeService;

    /** Books {@code count} equal instalments, due at the start of the next seasons. */
    @Transactional
    public List<TransferInstallment> schedule(long playerId, String playerName, long payerTeamId, long payeeTeamId,
                                              int count, long amount, int season) {
        List<TransferInstallment> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            TransferInstallment row = new TransferInstallment();
            row.setPlayerId(playerId);
            row.setPlayerName(playerName);
            row.setPayerTeamId(payerTeamId);
            row.setPayeeTeamId(payeeTeamId);
            row.setAmount(amount);
            row.setDueSeason(season + i);
            rows.add(row);
        }
        return installmentRepository.saveAll(rows);
    }

    /** Season start: every instalment due is paid, with the same money movements as a transfer fee. */
    @Transactional
    public int payDue(int season) {
        List<TransferInstallment> due = installmentRepository.findAllByPaidFalseAndDueSeasonLessThanEqual(season);
        for (TransferInstallment row : due) {
            financeService.recordExpense(row.getPayerTeamId(), season, 1, "TRANSFER_INSTALMENT",
                    "Instalment for " + row.getPlayerName(), row.getAmount());
            teamRepository.findById(row.getPayerTeamId()).ifPresent(payer -> {
                payer.setTransferBudget(payer.getTransferBudget() - row.getAmount());
                teamRepository.save(payer);
            });
            financeService.recordTransaction(row.getPayeeTeamId(), season, 1, "TRANSFER_INSTALMENT",
                    "Instalment for " + row.getPlayerName(), row.getAmount());
            Team payee = teamRepository.findById(row.getPayeeTeamId()).orElse(null);
            if (payee != null) {
                payee.setTransferBudget(payee.getTransferBudget() + row.getAmount());
                teamRepository.save(payee);
            }
            row.setPaid(true);
        }
        installmentRepository.saveAll(due);
        return due.size();
    }
}
