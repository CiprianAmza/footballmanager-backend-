package com.footballmanagergamesimulator.economy;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;

@Service
public class DeterministicMarketPriceService {
    public static final String MARKET_V1 = "market-v1";
    private static final BigInteger BPS = BigInteger.valueOf(10_000L);

    private final MarketBootstrapService bootstrapService;
    private final MarketInstrumentRepository instrumentRepository;
    private final MarketPriceSnapshotRepository snapshotRepository;
    private final RegentEconomyProperties properties;

    public DeterministicMarketPriceService(MarketBootstrapService bootstrapService,
                                           MarketInstrumentRepository instrumentRepository,
                                           MarketPriceSnapshotRepository snapshotRepository,
                                           RegentEconomyProperties properties) {
        this.bootstrapService = bootstrapService;
        this.instrumentRepository = instrumentRepository;
        this.snapshotRepository = snapshotRepository;
        this.properties = properties;
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
            int requestedBps = deterministicBps(instrument, deterministicHash);
            long proposed = applyBps(previous, requestedBps);
            long weeklyMinimum = boundedPrice(weeklyAnchor, -instrument.getWeeklyLimitBps());
            long weeklyMaximum = boundedPrice(weeklyAnchor, instrument.getWeeklyLimitBps());
            long close = Math.max(1L, Math.min(weeklyMaximum, Math.max(weeklyMinimum, proposed)));

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

    static long applyBps(long price, int changeBps) {
        if (price <= 0) throw new EconomyConflictException("INVALID_MARKET_PRICE", "Market price must be positive");
        BigInteger delta = BigInteger.valueOf(price).multiply(BigInteger.valueOf(changeBps)).divide(BPS);
        return exactPositive(BigInteger.valueOf(price).add(delta));
    }

    static long boundedPrice(long anchor, int changeBps) {
        return applyBps(anchor, changeBps);
    }

    private static int actualBps(long previous, long close) {
        BigInteger value = BigInteger.valueOf(close - previous).multiply(BPS)
                .divide(BigInteger.valueOf(previous));
        return value.intValueExact();
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
