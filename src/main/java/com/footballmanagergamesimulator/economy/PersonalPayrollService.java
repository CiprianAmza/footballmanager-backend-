package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.service.FinanceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class PersonalPayrollService {

    private final FinanceService financeService;
    private final PersonalCompensationService compensationService;
    private final PersonalLedgerEntryRepository ledgerRepository;
    private final PersonalAccountRepository accountRepository;

    public PersonalPayrollService(FinanceService financeService,
                                  PersonalCompensationService compensationService,
                                  PersonalLedgerEntryRepository ledgerRepository,
                                  PersonalAccountRepository accountRepository) {
        this.financeService = financeService;
        this.compensationService = compensationService;
        this.ledgerRepository = ledgerRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public long processTeam(Team team, int season, int day) {
        FinanceService.MonthlyPayrollResult payroll =
                financeService.processTeamMonthlyPayroll(team, season, day);
        if (!payroll.replayed()) {
            for (Map.Entry<Long, Long> payment : payroll.personalCredits().entrySet()) {
                String key = "PAYROLL:" + season + ":" + day + ":" + team.getId()
                        + ":HUMAN:" + payment.getKey();
                compensationService.creditCareerIncome(payment.getKey(), LedgerEntryType.SALARY,
                        payment.getValue(), season, day, team.getId(), key, key, "Monthly salary");
            }
        }
        return payroll.totalWages();
    }

    @Transactional
    public PersonalAccountingService.PostingResult payCareerBonus(
            Team team, long humanId, long amount, int season, int day, String eventKey, String description) {
        if (eventKey == null || eventKey.isBlank() || eventKey.length() > 100) {
            throw new IllegalArgumentException("eventKey must contain 1 to 100 characters");
        }
        String correlation = "BONUS:" + team.getId() + ":" + eventKey;
        boolean replayed = financeService.processCareerBonusDebit(team, amount, season, day, correlation);
        if (replayed) validateBonusReplay(correlation, team.getId(), humanId, amount, season, day, description);
        return compensationService.creditCareerIncome(humanId, LedgerEntryType.BONUS,
                amount, season, day, team.getId(), correlation, correlation, description);
    }

    private void validateBonusReplay(String correlation, long teamId, long humanId, long amount,
                                     int season, int day, String description) {
        var entries = ledgerRepository.findAllByCorrelationId(correlation);
        if (entries.size() != 1) {
            throw new EconomyConflictException("IDEMPOTENCY_KEY_REUSED",
                    "Bonus key does not identify one completed personal credit");
        }
        PersonalLedgerEntry entry = entries.get(0);
        PersonalAccount account = accountRepository.findById(entry.getAccountId())
                .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND",
                        "Bonus recipient account is missing"));
        if (entry.getEntryType() != LedgerEntryType.BONUS
                || entry.getSignedAmount() != amount
                || entry.getCareerEarningsDelta() != amount
                || entry.getSeasonNumber() != season
                || entry.getGameDay() != day
                || !java.util.Objects.equals(entry.getCounterpartTeamId(), teamId)
                || !java.util.Objects.equals(entry.getDescription(), description)
                || !java.util.Objects.equals(account.getOwnerHumanId(), humanId)) {
            throw new EconomyConflictException("IDEMPOTENCY_KEY_REUSED",
                    "Bonus key was already used for a different payment");
        }
    }
}
