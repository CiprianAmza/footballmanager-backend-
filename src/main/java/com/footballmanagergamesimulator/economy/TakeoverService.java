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

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TakeoverService {
    private final PersonalAccountRepository accountRepository;
    private final PersonalAccountingService accountingService;
    private final MarketInstrumentRepository instrumentRepository;
    private final PortfolioPositionRepository positionRepository;
    private final ClubCapTableStateRepository capStateRepository;
    private final ClubCapTableService capTableService;
    private final ClubValuationService valuationService;
    private final TakeoverQuoteRepository quoteRepository;
    private final TakeoverExecutionRepository executionRepository;
    private final TeamRepository teamRepository;
    private final FinancialRecordRepository financialRecordRepository;
    private final GameCalendarRepository calendarRepository;
    private final RegentEconomyProperties properties;
    private final Phase3TransactionProbe probe;

    public TakeoverService(PersonalAccountRepository accountRepository,
                           PersonalAccountingService accountingService,
                           MarketInstrumentRepository instrumentRepository,
                           PortfolioPositionRepository positionRepository,
                           ClubCapTableStateRepository capStateRepository,
                           ClubCapTableService capTableService,
                           ClubValuationService valuationService,
                           TakeoverQuoteRepository quoteRepository,
                           TakeoverExecutionRepository executionRepository,
                           TeamRepository teamRepository,
                           FinancialRecordRepository financialRecordRepository,
                           GameCalendarRepository calendarRepository,
                           RegentEconomyProperties properties,
                           Phase3TransactionProbe probe) {
        this.accountRepository = accountRepository;
        this.accountingService = accountingService;
        this.instrumentRepository = instrumentRepository;
        this.positionRepository = positionRepository;
        this.capStateRepository = capStateRepository;
        this.capTableService = capTableService;
        this.valuationService = valuationService;
        this.quoteRepository = quoteRepository;
        this.executionRepository = executionRepository;
        this.teamRepository = teamRepository;
        this.financialRecordRepository = financialRecordRepository;
        this.calendarRepository = calendarRepository;
        this.properties = properties;
        this.probe = probe;
    }

    @Transactional
    public QuoteResult quote(PersonProfile profile, long teamId, String idempotencyKey) {
        requireChairman(profile);
        validateKey(idempotencyKey);
        PersonalAccount buyer = accountRepository.findByProfileIdForUpdate(profile.getId())
                .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND", "Personal account is missing"));
        TakeoverQuote replay = quoteRepository
                .findByBuyerAccountIdAndIdempotencyKey(buyer.getId(), idempotencyKey).orElse(null);
        if (replay != null) {
            if (replay.getTeamId() != teamId) throw reused();
            return new QuoteResult(replay, true);
        }

        ClubCapTableService.CapTable capTable = capTableService.ensureMigrated(teamId);
        instrumentRepository.flush();
        MarketInstrument instrument = instrumentRepository.findById(capTable.instrumentId()).orElseThrow();
        rejectProtectedMinorities(capTable, buyer.getId());
        long buyerQuantity = capTable.holdings().stream()
                .filter(value -> value.accountId() == buyer.getId()).mapToLong(ClubCapTableService.Holding::quantity)
                .sum();
        long shares = instrument.getTotalSupply() - buyerQuantity;
        if (shares <= 0) {
            throw new EconomyConflictException("ALREADY_FULL_OWNER", "Chairman already owns all issued shares");
        }
        ClubValuationService.Valuation valuation = valuationService.value(teamId);
        int premium = properties.getClub().getTakeoverPremiumBps();
        int expiryDays = properties.getClub().getTakeoverQuoteExpiryDays();
        if (premium < 0 || premium > 100_000 || expiryDays < 0) {
            throw new IllegalStateException("Takeover premium configuration is invalid");
        }
        long baseUnit = valuationService.perSharePrice(valuation, instrument.getTotalSupply());
        long unitPrice = applyPremium(baseUnit, premium);
        long total = multiply(unitPrice, shares);
        GameDate date = date();

        TakeoverQuote quote = new TakeoverQuote();
        quote.setQuoteKey(UUID.randomUUID().toString());
        quote.setBuyerAccountId(buyer.getId());
        quote.setBuyerProfileId(profile.getId());
        quote.setInstrumentId(instrument.getId());
        quote.setTeamId(teamId);
        quote.setValuationFormulaVersion(valuation.formulaVersion());
        quote.setValuationStateVersion(valuation.stateVersion());
        quote.setInstrumentVersion(instrument.getVersion());
        quote.setIssuedShares(instrument.getTotalSupply());
        quote.setSharesToAcquire(shares);
        quote.setUnitPrice(unitPrice);
        quote.setPremiumBps(premium);
        quote.setTotalConsideration(total);
        quote.setQuotedSeason(date.season());
        quote.setQuotedDay(date.day());
        quote.setExpiresAbsoluteDay(add(date.absoluteDay(), expiryDays));
        quote.setIdempotencyKey(idempotencyKey);
        quote.setStatus(TakeoverQuoteStatus.OPEN);
        return new QuoteResult(quoteRepository.save(quote), false);
    }

    @Transactional
    public ExecutionResult execute(PersonProfile profile, long expectedTeamId,
                                   String quoteKey, String idempotencyKey) {
        requireChairman(profile);
        validateKey(idempotencyKey);
        if (quoteKey == null || quoteKey.isBlank() || quoteKey.length() > 36) {
            throw new IllegalArgumentException("quoteId is required");
        }
        PersonalAccount buyer = accountRepository.findByProfileIdForUpdate(profile.getId())
                .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND", "Personal account is missing"));
        TakeoverExecution replay = executionRepository
                .findByBuyerAccountIdAndIdempotencyKey(buyer.getId(), idempotencyKey).orElse(null);
        if (replay != null) {
            TakeoverQuote replayQuote = quoteRepository.findById(replay.getQuoteId()).orElseThrow();
            if (replay.getTeamId() != expectedTeamId || replayQuote.getTeamId() != expectedTeamId
                    || !replayQuote.getQuoteKey().equals(quoteKey)) throw reused();
            return new ExecutionResult(replay, true);
        }

        TakeoverQuote quote = quoteRepository.findByQuoteKeyForUpdate(quoteKey)
                .orElseThrow(() -> new EconomyConflictException("TAKEOVER_QUOTE_NOT_FOUND", "Takeover quote was not found"));
        if (quote.getBuyerAccountId() != buyer.getId() || quote.getBuyerProfileId() != profile.getId()) {
            throw new EconomyConflictException("TAKEOVER_QUOTE_FORBIDDEN", "Takeover quote belongs to another principal");
        }
        if (quote.getTeamId() != expectedTeamId) {
            throw new EconomyConflictException("TAKEOVER_QUOTE_TEAM_MISMATCH", "Quote belongs to another club");
        }
        if (quote.getStatus() != TakeoverQuoteStatus.OPEN) {
            throw new EconomyConflictException("TAKEOVER_QUOTE_USED", "Takeover quote was already executed");
        }
        MarketInstrument instrument = instrumentRepository.findByIdForUpdate(quote.getInstrumentId())
                .orElseThrow(() -> new EconomyConflictException("CLUB_INSTRUMENT_NOT_FOUND", "Club instrument is missing"));
        Team team = teamRepository.findByIdForUpdate(quote.getTeamId())
                .orElseThrow(() -> new EconomyConflictException("CLUB_NOT_FOUND", "Club was not found"));
        ClubCapTableState state = capStateRepository.findByInstrumentIdForUpdate(instrument.getId())
                .orElseThrow(() -> new EconomyConflictException("CAP_TABLE_NOT_MIGRATED", "Club cap table is not migrated"));
        GameDate date = date();
        if (date.absoluteDay() > quote.getExpiresAbsoluteDay()) {
            throw new EconomyConflictException("TAKEOVER_QUOTE_EXPIRED", "Takeover quote has expired");
        }
        ClubValuationService.Valuation currentValuation = valuationService.value(team);
        if (!currentValuation.formulaVersion().equals(quote.getValuationFormulaVersion())
                || !currentValuation.stateVersion().equals(quote.getValuationStateVersion())
                || instrument.getVersion() != quote.getInstrumentVersion()
                || instrument.getTotalSupply() != quote.getIssuedShares()) {
            throw new EconomyConflictException("TAKEOVER_QUOTE_STALE", "Club valuation or cap table changed after quote");
        }

        List<PortfolioPosition> positions = positionRepository
                .findPositiveByInstrumentIdForUpdate(instrument.getId());
        Map<Long, PersonalAccount> accounts = lockAccounts(positions, buyer.getId());
        rejectProtectedMinorities(positions, accounts, buyer.getId());
        PortfolioPosition buyerPosition = positions.stream()
                .filter(value -> value.getAccountId() == buyer.getId()).findFirst().orElse(null);
        long buyerQuantity = buyerPosition == null ? 0 : buyerPosition.getQuantity();
        long shares = instrument.getTotalSupply() - buyerQuantity;
        if (shares != quote.getSharesToAcquire()
                || multiply(quote.getUnitPrice(), shares) != quote.getTotalConsideration()) {
            throw new EconomyConflictException("TAKEOVER_QUOTE_STALE", "Club cap table changed after quote");
        }

        String correlation = "TAKEOVER:" + UUID.randomUUID();
        accountingService.postLocked(buyer, LedgerEntryType.CLUB_ACQUISITION,
                Math.negateExact(quote.getTotalConsideration()), 0, date.season(), date.day(),
                correlation, "TAKEOVER-BUY:" + idempotencyKey, team.getId(), null,
                "Acquired full control of " + team.getName());
        probe.checkpoint("TAKEOVER_AFTER_BUYER_DEBIT");

        long sellerProceeds = 0;
        for (PortfolioPosition position : positions) {
            if (position.getAccountId() == buyer.getId()) continue;
            PersonalAccount seller = accounts.get(position.getAccountId());
            if (seller == null) throw new EconomyConflictException("CAP_TABLE_IDENTITY_MISSING", "Shareholder account is missing");
            long proceeds = multiply(position.getQuantity(), quote.getUnitPrice());
            sellerProceeds = add(sellerProceeds, proceeds);
            accountingService.postLocked(seller, LedgerEntryType.CLUB_SHARE_SALE, proceeds, 0,
                    date.season(), date.day(), correlation,
                    "TAKEOVER-SELL:" + idempotencyKey + ":" + seller.getId(), team.getId(), null,
                    "Sold club shares during takeover of " + team.getName());
            seller.setRealizedInvestmentGain(add(seller.getRealizedInvestmentGain(),
                    subtract(proceeds, position.getTotalCostBasis())));
            accountRepository.save(seller);
            position.setQuantity(0);
            position.setTotalCostBasis(0);
            positionRepository.save(position);
        }
        probe.checkpoint("TAKEOVER_AFTER_SELLER_CREDITS");

        long freeFloatProceeds = multiply(instrument.getAvailableSupply(), quote.getUnitPrice());
        if (add(sellerProceeds, freeFloatProceeds) != quote.getTotalConsideration()) {
            throw new EconomyConflictException("SUPPLY_CONSERVATION_FAILED", "Takeover consideration does not reconcile");
        }
        if (buyerPosition == null) {
            buyerPosition = new PortfolioPosition();
            buyerPosition.setAccountId(buyer.getId());
            buyerPosition.setProfileId(profile.getId());
            buyerPosition.setInstrumentId(instrument.getId());
            buyerPosition.setQuantity(0);
            buyerPosition.setTotalCostBasis(0);
        }
        buyerPosition.setQuantity(instrument.getTotalSupply());
        buyerPosition.setTotalCostBasis(add(buyerPosition.getTotalCostBasis(), quote.getTotalConsideration()));
        positionRepository.save(buyerPosition);
        instrument.setAvailableSupply(0);
        instrumentRepository.save(instrument);
        probe.checkpoint("TAKEOVER_AFTER_SHARE_TRANSFER");

        capTableService.reconcileLocked(instrument, state);
        if (state.getControllingAccountId() == null || buyer.getId() != state.getControllingAccountId()) {
            throw new EconomyConflictException("CONTROL_RECONCILIATION_FAILED", "Takeover did not derive buyer control");
        }
        probe.checkpoint("TAKEOVER_AFTER_CONTROL");

        if (freeFloatProceeds > 0) {
            team.setTotalFinances(add(team.getTotalFinances(), freeFloatProceeds));
            teamRepository.save(team);
            FinancialRecord record = new FinancialRecord();
            record.setTeamId(team.getId());
            record.setSeasonNumber(date.season());
            record.setDay(date.day());
            record.setCategory("TAKEOVER_SHARE_ISSUE");
            record.setDescription("Takeover free-float proceeds [" + correlation + "]");
            record.setAmount(freeFloatProceeds);
            financialRecordRepository.save(record);
        }
        probe.checkpoint("TAKEOVER_AFTER_CLUB_LEDGER");

        quote.setStatus(TakeoverQuoteStatus.EXECUTED);
        quoteRepository.save(quote);
        TakeoverExecution execution = new TakeoverExecution();
        execution.setExecutionKey(UUID.randomUUID().toString());
        execution.setQuoteId(quote.getId());
        execution.setBuyerAccountId(buyer.getId());
        execution.setBuyerProfileId(profile.getId());
        execution.setInstrumentId(instrument.getId());
        execution.setTeamId(team.getId());
        execution.setSharesAcquired(shares);
        execution.setUnitPrice(quote.getUnitPrice());
        execution.setTotalConsideration(quote.getTotalConsideration());
        execution.setSeasonNumber(date.season());
        execution.setGameDay(date.day());
        execution.setIdempotencyKey(idempotencyKey);
        execution.setCorrelationId(correlation);
        execution.setCashBalanceAfter(buyer.getCashBalance());
        execution.setQuantityAfter(buyerPosition.getQuantity());
        TakeoverExecution saved = executionRepository.save(execution);
        probe.checkpoint("TAKEOVER_AFTER_EXECUTION_RECORD");
        return new ExecutionResult(saved, false);
    }

    private Map<Long, PersonalAccount> lockAccounts(List<PortfolioPosition> positions, long buyerId) {
        Map<Long, PersonalAccount> result = new HashMap<>();
        result.put(buyerId, accountRepository.findByIdForUpdate(buyerId).orElseThrow());
        positions.stream().map(PortfolioPosition::getAccountId).distinct().sorted().forEach(accountId -> {
            if (accountId != buyerId) result.put(accountId, accountRepository.findByIdForUpdate(accountId)
                    .orElseThrow(() -> new EconomyConflictException("CAP_TABLE_IDENTITY_MISSING", "Shareholder account is missing")));
        });
        return result;
    }

    private static void rejectProtectedMinorities(ClubCapTableService.CapTable table, long buyerAccountId) {
        if (table.holdings().stream().anyMatch(value -> value.accountId() != buyerAccountId && value.protectedUser())) {
            throw new EconomyConflictException("PROTECTED_MINORITY", "Full takeover cannot confiscate user minority shares");
        }
    }

    private static void rejectProtectedMinorities(List<PortfolioPosition> positions,
                                                   Map<Long, PersonalAccount> accounts,
                                                   long buyerAccountId) {
        if (positions.stream().anyMatch(value -> value.getAccountId() != buyerAccountId
                && accounts.get(value.getAccountId()) != null
                && accounts.get(value.getAccountId()).getOwnerUserId() != null)) {
            throw new EconomyConflictException("PROTECTED_MINORITY", "Full takeover cannot confiscate user minority shares");
        }
    }

    private static void requireChairman(PersonProfile profile) {
        if (profile.getCareerType() != CareerType.CHAIRMAN) {
            throw new EconomyConflictException("CHAIRMAN_REQUIRED", "A chairman career is required");
        }
    }

    private GameDate date() {
        GameCalendar value = calendarRepository.findTopByOrderBySeasonDesc().orElse(null);
        int season = value == null ? 0 : Math.max(0, value.getSeason());
        int day = value == null ? 0 : Math.max(0, value.getCurrentDay());
        return new GameDate(season, day, add(multiply(season, 366L), day));
    }

    private static long applyPremium(long value, int bps) {
        try {
            return BigInteger.valueOf(value).multiply(BigInteger.valueOf(10_000L + bps))
                    .add(BigInteger.valueOf(9_999L)).divide(BigInteger.valueOf(10_000L)).longValueExact();
        } catch (ArithmeticException exception) {
            throw overflow();
        }
    }

    private static void validateKey(String value) {
        if (value == null || value.isBlank() || value.length() > 140) {
            throw new IllegalArgumentException("idempotencyKey must contain 1 to 140 characters");
        }
    }

    private static EconomyConflictException reused() {
        return new EconomyConflictException("IDEMPOTENCY_KEY_REUSED",
                "Idempotency key was already used for a different takeover operation");
    }

    private static long multiply(long left, long right) {
        try { return Math.multiplyExact(left, right); }
        catch (ArithmeticException exception) { throw overflow(); }
    }

    private static long add(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException exception) { throw overflow(); }
    }

    private static long subtract(long left, long right) {
        try { return Math.subtractExact(left, right); }
        catch (ArithmeticException exception) { throw overflow(); }
    }

    private static EconomyConflictException overflow() {
        return new EconomyConflictException("MONEY_OVERFLOW", "Takeover value exceeds supported range");
    }

    private record GameDate(int season, int day, long absoluteDay) { }
    public record QuoteResult(TakeoverQuote quote, boolean replayed) { }
    public record ExecutionResult(TakeoverExecution execution, boolean replayed) { }
}
