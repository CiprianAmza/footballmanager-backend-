package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;

@Service
public class MarketTradingService {
    private final PersonalAccountRepository accountRepository;
    private final PersonalAccountingService accountingService;
    private final MarketInstrumentRepository instrumentRepository;
    private final PortfolioPositionRepository positionRepository;
    private final MarketTradeRepository tradeRepository;
    private final GameCalendarRepository calendarRepository;

    public MarketTradingService(PersonalAccountRepository accountRepository,
                                PersonalAccountingService accountingService,
                                MarketInstrumentRepository instrumentRepository,
                                PortfolioPositionRepository positionRepository,
                                MarketTradeRepository tradeRepository,
                                GameCalendarRepository calendarRepository) {
        this.accountRepository = accountRepository;
        this.accountingService = accountingService;
        this.instrumentRepository = instrumentRepository;
        this.positionRepository = positionRepository;
        this.tradeRepository = tradeRepository;
        this.calendarRepository = calendarRepository;
    }

    @Transactional
    public TradeResult trade(PersonProfile profile, long instrumentId, MarketTradeSide side,
                             long quantity, String idempotencyKey) {
        if (side == null) throw new IllegalArgumentException("side is required");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        validateKey(idempotencyKey);

        PersonalAccount account = accountRepository.findByProfileIdForUpdate(profile.getId())
                .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND", "Personal account is missing"));
        MarketTrade replay = tradeRepository.findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey)
                .orElse(null);
        if (replay != null) {
            assertSameRequest(replay, instrumentId, side, quantity);
            return new TradeResult(replay, true);
        }

        MarketInstrument instrument = instrumentRepository.findByIdForUpdate(instrumentId)
                .orElseThrow(() -> new EconomyConflictException("INSTRUMENT_NOT_FOUND", "Market instrument was not found"));
        if (!instrument.isActive()) {
            throw new EconomyConflictException("INSTRUMENT_INACTIVE", "Market instrument is not tradable");
        }
        PortfolioPosition position = positionRepository.findForUpdate(account.getId(), instrumentId).orElse(null);
        if (position == null) {
            position = new PortfolioPosition();
            position.setAccountId(account.getId());
            position.setProfileId(account.getProfileId());
            position.setInstrumentId(instrumentId);
            position.setQuantity(0);
            position.setTotalCostBasis(0);
        }

        long gross = multiplyExact(instrument.getCurrentPrice(), quantity);
        long disposedBasis = 0;
        long realized = 0;
        long quantityAfter;
        long basisAfter;
        String ledgerKey = "MARKET:" + idempotencyKey;
        String correlation = "MARKET-TRADE:" + idempotencyKey;
        GameCalendar calendar = calendarRepository.findTopByOrderBySeasonDesc().orElse(null);
        int season = calendar == null ? 0 : calendar.getSeason();
        int day = calendar == null ? 0 : Math.max(0, calendar.getCurrentDay());

        if (side == MarketTradeSide.BUY) {
            if (instrument.getAvailableSupply() < quantity) {
                throw new EconomyConflictException("INSUFFICIENT_SUPPLY", "Requested shares exceed available supply");
            }
            quantityAfter = addExact(position.getQuantity(), quantity);
            basisAfter = addExact(position.getTotalCostBasis(), gross);
            accountingService.postLocked(account, LedgerEntryType.INVESTMENT_BUY, -gross, 0, season, day,
                    correlation, ledgerKey, instrument.getTeamId(), null,
                    "Bought " + quantity + " shares of " + instrument.getCode());
            instrument.setAvailableSupply(instrument.getAvailableSupply() - quantity);
        } else {
            if (position.getQuantity() < quantity) {
                throw new EconomyConflictException("INSUFFICIENT_HOLDINGS", "Requested shares exceed portfolio holdings");
            }
            disposedBasis = allocatedBasis(position.getTotalCostBasis(), quantity, position.getQuantity());
            quantityAfter = position.getQuantity() - quantity;
            basisAfter = position.getTotalCostBasis() - disposedBasis;
            realized = subtractExact(gross, disposedBasis);
            accountingService.postLocked(account, LedgerEntryType.INVESTMENT_SELL, gross, 0, season, day,
                    correlation, ledgerKey, instrument.getTeamId(), null,
                    "Sold " + quantity + " shares of " + instrument.getCode());
            account.setRealizedInvestmentGain(addExact(account.getRealizedInvestmentGain(), realized));
            accountRepository.save(account);
            instrument.setAvailableSupply(addExact(instrument.getAvailableSupply(), quantity));
            if (instrument.getAvailableSupply() > instrument.getTotalSupply()) {
                throw new EconomyConflictException("SUPPLY_CONSERVATION_FAILED", "Share supply exceeds instrument cap");
            }
        }

        position.setQuantity(quantityAfter);
        position.setTotalCostBasis(basisAfter);
        positionRepository.save(position);
        instrumentRepository.save(instrument);

        MarketTrade executed = new MarketTrade();
        executed.setAccountId(account.getId());
        executed.setProfileId(account.getProfileId());
        executed.setInstrumentId(instrumentId);
        executed.setSide(side);
        executed.setQuantity(quantity);
        executed.setUnitPrice(instrument.getCurrentPrice());
        executed.setGrossAmount(gross);
        executed.setCostBasisAmount(side == MarketTradeSide.BUY ? gross : disposedBasis);
        executed.setRealizedGain(realized);
        executed.setSeasonNumber(season);
        executed.setGameDay(day);
        executed.setIdempotencyKey(idempotencyKey);
        executed.setCorrelationId(correlation);
        executed.setCashBalanceAfter(account.getCashBalance());
        executed.setQuantityAfter(quantityAfter);
        executed.setCostBasisAfter(basisAfter);
        return new TradeResult(tradeRepository.save(executed), false);
    }

    private static long allocatedBasis(long totalBasis, long soldQuantity, long totalQuantity) {
        if (soldQuantity == totalQuantity) return totalBasis;
        return BigInteger.valueOf(totalBasis).multiply(BigInteger.valueOf(soldQuantity))
                .divide(BigInteger.valueOf(totalQuantity)).longValueExact();
    }

    private static long multiplyExact(long left, long right) {
        try { return Math.multiplyExact(left, right); }
        catch (ArithmeticException exception) {
            throw new EconomyConflictException("MONEY_OVERFLOW", "Trade value exceeds supported range");
        }
    }

    private static long addExact(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException exception) {
            throw new EconomyConflictException("MONEY_OVERFLOW", "Trade value exceeds supported range");
        }
    }

    private static long subtractExact(long left, long right) {
        try { return Math.subtractExact(left, right); }
        catch (ArithmeticException exception) {
            throw new EconomyConflictException("MONEY_OVERFLOW", "Trade value exceeds supported range");
        }
    }

    private static void validateKey(String value) {
        if (value == null || value.isBlank() || value.length() > 140) {
            throw new IllegalArgumentException("idempotencyKey must contain 1 to 140 characters");
        }
    }

    private static void assertSameRequest(MarketTrade replay, long instrumentId,
                                          MarketTradeSide side, long quantity) {
        if (replay.getInstrumentId() != instrumentId || replay.getSide() != side
                || replay.getQuantity() != quantity) {
            throw new EconomyConflictException("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency key was already used for a different trade");
        }
    }

    public record TradeResult(MarketTrade trade, boolean replayed) { }
}
