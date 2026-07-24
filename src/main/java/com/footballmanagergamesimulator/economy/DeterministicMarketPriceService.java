package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.regent.market.core.ClubEquityProfile;
import com.footballmanagergamesimulator.regent.market.core.ClubEquityQuote;
import com.footballmanagergamesimulator.regent.market.core.ClubEquityQuoteModel;
import com.footballmanagergamesimulator.regent.market.core.DailyReturn;
import com.footballmanagergamesimulator.regent.market.core.MarketQuoteKey;
import com.footballmanagergamesimulator.regent.market.core.SafeCompanyProfile;
import com.footballmanagergamesimulator.regent.market.core.SafeCompanyReturnModel;
import com.footballmanagergamesimulator.regent.market.core.SpeculativeProfile;
import com.footballmanagergamesimulator.regent.market.core.SpeculativeQuote;
import com.footballmanagergamesimulator.regent.market.core.SpeculativeQuoteModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class DeterministicMarketPriceService {
    public static final String MARKET_V1 = "market-v1";
    public static final String RISK_V1 = "risk-v1";
    private static final BigInteger BPS = BigInteger.valueOf(10_000L);
    private static final SafeCompanyProfile SAFE_V1 = new SafeCompanyProfile(
            new BigDecimal("0.01"), new BigDecimal("0.60"), RISK_V1);
    private static final SpeculativeProfile SPECULATIVE_V1 = new SpeculativeProfile(BigDecimal.ONE, RISK_V1);
    private static final ClubEquityProfile CLUB_V1 = new ClubEquityProfile(
            new BigDecimal("0.03"), BigDecimal.ONE, RISK_V1);

    private final MarketBootstrapService bootstrapService;
    private final MarketInstrumentRepository instrumentRepository;
    private final MarketPriceSnapshotRepository snapshotRepository;
    private final RegentEconomyProperties properties;
    private final ClubValuationService clubValuationService;
    private final TraderAdviserService traderAdviserService;

    public DeterministicMarketPriceService(MarketBootstrapService bootstrapService,
                                           MarketInstrumentRepository instrumentRepository,
                                           MarketPriceSnapshotRepository snapshotRepository,
                                           RegentEconomyProperties properties,
                                           ClubValuationService clubValuationService,
                                           TraderAdviserService traderAdviserService) {
        this.bootstrapService = bootstrapService;
        this.instrumentRepository = instrumentRepository;
        this.snapshotRepository = snapshotRepository;
        this.properties = properties;
        this.clubValuationService = clubValuationService;
        this.traderAdviserService = traderAdviserService;
    }

    @Transactional
    public void processDay(int season, int day) {
        if (!properties.isEnabled()) return;
        if (season < 1 || day < 1 || day > 366) {
            throw new IllegalArgumentException("Market date is outside supported bounds");
        }
        bootstrapService.ensureAllInstruments();
        for (MarketInstrument listed : instrumentRepository.findAllByActiveTrueOrderByCodeAsc()) {
            processInstrument(listed.getId(), season, day);
        }
        traderAdviserService.processDailyPayroll(season, day);
    }

    private void processInstrument(long instrumentId, int season, int targetDay) {
        MarketInstrument instrument = instrumentRepository.findByIdForUpdate(instrumentId)
                .orElseThrow(() -> new EconomyConflictException("INSTRUMENT_NOT_FOUND", "Market instrument is missing"));
        if (!instrument.isActive()) return;
        MarketPriceSnapshot latest = snapshotRepository
                .findTopByInstrumentIdOrderBySeasonNumberDescGameDayDesc(instrumentId).orElse(null);
        if (latest != null && (latest.getSeasonNumber() > season
                || (latest.getSeasonNumber() == season && latest.getGameDay() >= targetDay))) {
            return;
        }
        int firstDay = latest != null && latest.getSeasonNumber() == season ? latest.getGameDay() + 1 : 1;
        long previous = instrument.getCurrentPrice();
        for (int day = firstDay; day <= targetDay; day++) {
            MarketPriceSnapshot existing = snapshotRepository
                    .findByInstrumentIdAndSeasonNumberAndGameDay(instrumentId, season, day).orElse(null);
            if (existing != null) {
                previous = existing.getClosePrice();
                continue;
            }
            long weeklyAnchor = ((day - 1) % 7 == 0 || latest == null || latest.getSeasonNumber() != season)
                    ? previous : latest.getWeeklyAnchorPrice();
            long deterministicHash = deterministicHash(instrument, season, day);
            long close = riskClose(instrument, previous, season, day);

            MarketPriceSnapshot snapshot = new MarketPriceSnapshot();
            snapshot.setInstrumentId(instrumentId);
            snapshot.setSeasonNumber(season);
            snapshot.setGameDay(day);
            snapshot.setPreviousClose(previous);
            snapshot.setClosePrice(close);
            snapshot.setWeeklyAnchorPrice(weeklyAnchor);
            snapshot.setDailyChangeBps(actualBps(previous, close));
            snapshot.setAlgorithmVersion(instrument.getPriceAlgorithmVersion());
            snapshot.setDeterministicHash(deterministicHash);
            latest = snapshotRepository.save(snapshot);
            previous = close;
        }
        instrument.setCurrentPrice(previous);
        instrumentRepository.save(instrument);
    }

    private long riskClose(MarketInstrument instrument, long previous, int season, int day) {
        requireSupportedRiskVersion(instrument.getRiskConfigVersion());
        long absoluteDay;
        try {
            absoluteDay = Math.addExact(Math.multiplyExact((long) season - 1L, 366L), day);
        } catch (ArithmeticException exception) {
            throw new EconomyConflictException("MARKET_DATE_OVERFLOW", "Market date exceeds supported range");
        }
        MarketQuoteKey key = new MarketQuoteKey(instrument.getPriceSeed(), instrument.getCode(), absoluteDay,
                "11:" + instrument.getRiskConfigVersion());
        return switch (instrument.getRiskClass()) {
            case SAFE_COMPANY -> applyReturn(previous, new SafeCompanyReturnModel().quote(key, SAFE_V1));
            case SPECULATIVE -> {
                SpeculativeQuote quote = new SpeculativeQuoteModel().quote(
                        key, BigDecimal.valueOf(previous), SPECULATIVE_V1);
                yield applyReturn(previous, quote.dailyReturn());
            }
            case CLUB_EQUITY -> clubClose(instrument, key);
        };
    }

    private long clubClose(MarketInstrument instrument, MarketQuoteKey key) {
        if (instrument.getTeamId() == null || instrument.getTotalSupply() <= 0) {
            throw new EconomyConflictException("INVALID_CLUB_EQUITY", "Club equity requires a club and finite supply");
        }
        ClubValuationService.Valuation valuation = clubValuationService.value(instrument.getTeamId());
        ClubEquityQuote quote = new ClubEquityQuoteModel().quote(
                key, BigDecimal.valueOf(valuation.totalValue()), instrument.getTotalSupply(), CLUB_V1);
        try {
            return Math.max(1L, quote.quotedPrice().setScale(0, RoundingMode.HALF_UP).longValueExact());
        } catch (ArithmeticException exception) {
            throw new EconomyConflictException("MONEY_OVERFLOW", "Market price exceeds supported range");
        }
    }

    private static long applyReturn(long opening, DailyReturn dailyReturn) {
        int changeBps = dailyReturn.value().movePointRight(4).setScale(0, RoundingMode.HALF_UP).intValueExact();
        return applyBps(opening, changeBps);
    }

    static int deterministicBps(MarketInstrument instrument, int season, int day) {
        return deterministicBps(instrument, deterministicHash(instrument, season, day));
    }

    private static int deterministicBps(MarketInstrument instrument, long deterministicHash) {
        requireSupportedVersion(instrument.getPriceAlgorithmVersion());
        int limit = instrument.getDailyLimitBps();
        return (int) Math.floorMod(deterministicHash, (long) limit * 2L + 1L) - limit;
    }

    private static long deterministicHash(MarketInstrument instrument, int season, int day) {
        requireSupportedVersion(instrument.getPriceAlgorithmVersion());
        return mix64(instrument.getPriceSeed()
                ^ (0x9e3779b97f4a7c15L * season)
                ^ (0xbf58476d1ce4e5b9L * day));
    }

    private static void requireSupportedVersion(String version) {
        if (!MARKET_V1.equals(version)) {
            throw new EconomyConflictException("UNSUPPORTED_MARKET_ALGORITHM",
                    "Market price algorithm is not supported: " + version);
        }
    }

    private static void requireSupportedRiskVersion(String version) {
        if (!RISK_V1.equals(version)) {
            throw new EconomyConflictException("UNSUPPORTED_RISK_CONFIG",
                    "Market risk configuration is not supported: " + version);
        }
    }

    static long applyBps(long price, int changeBps) {
        if (price <= 0) throw new EconomyConflictException("INVALID_MARKET_PRICE", "Market price must be positive");
        BigInteger delta = BigInteger.valueOf(price).multiply(BigInteger.valueOf(changeBps)).divide(BPS);
        return exactPositive(BigInteger.valueOf(price).add(delta));
    }

    static long boundedPrice(long anchor, int changeBps) {
        return applyBps(anchor, changeBps);
    }

    private static int actualBps(long previous, long close) {
        BigInteger value = BigInteger.valueOf(close).subtract(BigInteger.valueOf(previous)).multiply(BPS)
                .divide(BigInteger.valueOf(previous));
        try {
            return value.intValueExact();
        } catch (ArithmeticException exception) {
            throw new EconomyConflictException("MARKET_RETURN_OVERFLOW", "Daily market return exceeds supported range");
        }
    }

    private static long exactPositive(BigInteger value) {
        try {
            return Math.max(1L, value.longValueExact());
        } catch (ArithmeticException exception) {
            throw new EconomyConflictException("MONEY_OVERFLOW", "Market price exceeds supported range");
        }
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
