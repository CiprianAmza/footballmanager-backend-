package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.FinancialRecord;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.repository.FinancialRecordRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ClubTreasuryService {
    private final PersonalAccountRepository accountRepository;
    private final PersonalAccountingService accountingService;
    private final MarketInstrumentRepository instrumentRepository;
    private final ClubCapTableStateRepository capStateRepository;
    private final ClubCapTableService capTableService;
    private final TeamRepository teamRepository;
    private final FinancialRecordRepository financialRecordRepository;
    private final ClubCashTransferRepository transferRepository;
    private final ClubFinancialPolicyService policyService;
    private final GameCalendarRepository calendarRepository;
    private final Phase3TransactionProbe probe;

    public ClubTreasuryService(PersonalAccountRepository accountRepository,
                               PersonalAccountingService accountingService,
                               MarketInstrumentRepository instrumentRepository,
                               ClubCapTableStateRepository capStateRepository,
                               ClubCapTableService capTableService,
                               TeamRepository teamRepository,
                               FinancialRecordRepository financialRecordRepository,
                               ClubCashTransferRepository transferRepository,
                               ClubFinancialPolicyService policyService,
                               GameCalendarRepository calendarRepository,
                               Phase3TransactionProbe probe) {
        this.accountRepository = accountRepository;
        this.accountingService = accountingService;
        this.instrumentRepository = instrumentRepository;
        this.capStateRepository = capStateRepository;
        this.capTableService = capTableService;
        this.teamRepository = teamRepository;
        this.financialRecordRepository = financialRecordRepository;
        this.transferRepository = transferRepository;
        this.policyService = policyService;
        this.calendarRepository = calendarRepository;
        this.probe = probe;
    }

    @Transactional
    public TransferResult transfer(PersonProfile profile, long teamId,
                                   ClubCashTransferDirection direction, long amount,
                                   String idempotencyKey) {
        if (profile.getCareerType() != CareerType.CHAIRMAN) {
            throw new EconomyConflictException("CHAIRMAN_REQUIRED", "A chairman career is required");
        }
        if (direction == null) throw new IllegalArgumentException("direction is required");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        validateKey(idempotencyKey);
        PersonalAccount account = accountRepository.findByProfileIdForUpdate(profile.getId())
                .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND", "Personal account is missing"));
        ClubCashTransfer replay = transferRepository
                .findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey).orElse(null);
        if (replay != null) {
            if (replay.getTeamId() != teamId || replay.getDirection() != direction || replay.getAmount() != amount) {
                throw new EconomyConflictException("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key was already used for a different treasury transfer");
            }
            return new TransferResult(replay, true);
        }

        ClubCapTableService.CapTable capTable = capTableService.ensureMigrated(teamId);
        instrumentRepository.findByIdForUpdate(capTable.instrumentId()).orElseThrow();
        ClubCapTableState state = capStateRepository.findByInstrumentIdForUpdate(capTable.instrumentId())
                .orElseThrow(() -> new EconomyConflictException("CAP_TABLE_NOT_MIGRATED", "Club cap table is not migrated"));
        if (state.getControllingAccountId() == null || state.getControllingAccountId() != account.getId()) {
            throw new EconomyConflictException("CLUB_CONTROL_REQUIRED", "Authenticated chairman does not control this club");
        }
        Team team = teamRepository.findByIdForUpdate(teamId)
                .orElseThrow(() -> new EconomyConflictException("CLUB_NOT_FOUND", "Club was not found"));
        ClubFinancialPolicyService.Policy policy = policyService.policy(team);
        if (direction == ClubCashTransferDirection.WITHDRAWAL) {
            if (policy.withdrawalRestricted()) {
                throw new EconomyConflictException("WITHDRAWAL_RESTRICTED",
                        "Debt or an active financial restriction blocks withdrawal");
            }
            if (amount > policy.distributableCash()) {
                throw new EconomyConflictException("INSUFFICIENT_DISTRIBUTABLE_CASH",
                        "Withdrawal exceeds distributable club cash");
            }
        }

        GameDate date = date();
        String correlation = "CLUB-CASH:" + UUID.randomUUID();
        long signedPersonal = direction == ClubCashTransferDirection.INJECTION
                ? Math.negateExact(amount) : amount;
        long earnings = direction == ClubCashTransferDirection.WITHDRAWAL ? amount : 0;
        accountingService.postLocked(account,
                direction == ClubCashTransferDirection.INJECTION
                        ? LedgerEntryType.CLUB_INJECTION : LedgerEntryType.CLUB_WITHDRAWAL,
                signedPersonal, earnings, date.season(), date.day(), correlation,
                "CLUB-CASH:" + idempotencyKey, teamId, null,
                (direction == ClubCashTransferDirection.INJECTION ? "Injected cash into " : "Withdrew cash from ")
                        + team.getName());
        probe.checkpoint("TREASURY_AFTER_PERSONAL_LEDGER");

        long signedClub = direction == ClubCashTransferDirection.INJECTION ? amount : Math.negateExact(amount);
        team.setTotalFinances(add(team.getTotalFinances(), signedClub));
        if (team.getTotalFinances() < 0) {
            throw new EconomyConflictException("NEGATIVE_CLUB_CASH", "Club treasury cannot become negative");
        }
        teamRepository.save(team);
        probe.checkpoint("TREASURY_AFTER_CLUB_BALANCE");

        FinancialRecord financial = new FinancialRecord();
        financial.setTeamId(teamId);
        financial.setSeasonNumber(date.season());
        financial.setDay(date.day());
        financial.setCategory(direction == ClubCashTransferDirection.INJECTION
                ? "OWNER_INJECTION" : "OWNER_WITHDRAWAL");
        financial.setDescription(direction + " [" + correlation + "]");
        financial.setAmount(signedClub);
        financialRecordRepository.save(financial);
        probe.checkpoint("TREASURY_AFTER_CLUB_LEDGER");

        ClubCashTransfer transfer = new ClubCashTransfer();
        transfer.setTransferKey(UUID.randomUUID().toString());
        transfer.setAccountId(account.getId());
        transfer.setProfileId(profile.getId());
        transfer.setTeamId(teamId);
        transfer.setDirection(direction);
        transfer.setAmount(amount);
        transfer.setSeasonNumber(date.season());
        transfer.setGameDay(date.day());
        transfer.setIdempotencyKey(idempotencyKey);
        transfer.setCorrelationId(correlation);
        transfer.setPersonalBalanceAfter(account.getCashBalance());
        transfer.setClubBalanceAfter(team.getTotalFinances());
        transfer.setDistributableBefore(policy.distributableCash());
        ClubCashTransfer saved = transferRepository.save(transfer);
        probe.checkpoint("TREASURY_AFTER_TRANSFER_RECORD");
        return new TransferResult(saved, false);
    }

    private GameDate date() {
        GameCalendar value = calendarRepository.findTopByOrderBySeasonDesc().orElse(null);
        return value == null ? new GameDate(0, 0)
                : new GameDate(Math.max(0, value.getSeason()), Math.max(0, value.getCurrentDay()));
    }

    private static void validateKey(String value) {
        if (value == null || value.isBlank() || value.length() > 140) {
            throw new IllegalArgumentException("idempotencyKey must contain 1 to 140 characters");
        }
    }

    private static long add(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException exception) {
            throw new EconomyConflictException("MONEY_OVERFLOW", "Treasury transfer exceeds supported range");
        }
    }

    private record GameDate(int season, int day) { }
    public record TransferResult(ClubCashTransfer transfer, boolean replayed) { }
}
