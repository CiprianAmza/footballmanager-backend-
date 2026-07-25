package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.config.ChairmanModeProperties;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.TeamRepository;
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
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeterministicMarketPriceService {
    public static final String MARKET_V1 = "market-v1";
    public static final String RISK_V1 = "risk-v1";
    static final int SNAPSHOT_BATCH_SIZE = 256;
    private static final String INSERT_SNAPSHOT_SQL = """
            INSERT INTO MARKET_PRICE_SNAPSHOT
                (ALGORITHM_VERSION, CLOSE_PRICE, DAILY_CHANGE_BPS, DETERMINISTIC_HASH,
                 GAME_DAY, INSTRUMENT_ID, PREVIOUS_CLOSE, SEASON_NUMBER, WEEKLY_ANCHOR_PRICE)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final BigInteger BPS = BigInteger.valueOf(10_000L);
    private static final SafeCompanyProfile SAFE_V1 = new SafeCompanyProfile(
            new BigDecimal("0.01"), new BigDecimal("0.60"), RISK_V1);
    private static final SpeculativeProfile SPECULATIVE_V1 = new SpeculativeProfile(BigDecimal.ONE, RISK_V1);
    private static final ClubEquityProfile CLUB_V1 = new ClubEquityProfile(
            new BigDecimal("0.03"), BigDecimal.ONE, RISK_V1);
    private static final SafeCompanyReturnModel SAFE_MODEL = new SafeCompanyReturnModel();
    private static final SpeculativeQuoteModel SPECULATIVE_MODEL = new SpeculativeQuoteModel();
    private static final ClubEquityQuoteModel CLUB_MODEL = new ClubEquityQuoteModel();

    private final MarketBootstrapService bootstrapService;
    private final MarketInstrumentRepository instrumentRepository;
    private final MarketPriceSnapshotRepository snapshotRepository;
    private final ChairmanModeProperties chairmanModeProperties;
    private final ClubValuationService clubValuationService;
    private final TraderAdviserService traderAdviserService;
    private final TeamRepository teamRepository;
    private final MarketMutationLock marketMutationLock;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate marketTransaction;

    public DeterministicMarketPriceService(MarketBootstrapService bootstrapService,
                                           MarketInstrumentRepository instrumentRepository,
                                           MarketPriceSnapshotRepository snapshotRepository,
                                           ChairmanModeProperties chairmanModeProperties,
                                           ClubValuationService clubValuationService,
                                           TraderAdviserService traderAdviserService,
                                           TeamRepository teamRepository,
                                           MarketMutationLock marketMutationLock,
                                           PlatformTransactionManager transactionManager,
                                           JdbcTemplate jdbcTemplate,
                                           EntityManager entityManager) {
        this.bootstrapService = bootstrapService;
        this.instrumentRepository = instrumentRepository;
        this.snapshotRepository = snapshotRepository;
        this.chairmanModeProperties = chairmanModeProperties;
        this.clubValuationService = clubValuationService;
        this.traderAdviserService = traderAdviserService;
        this.teamRepository = teamRepository;
        this.marketMutationLock = marketMutationLock;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.marketTransaction = new TransactionTemplate(transactionManager);
    }

    public void processDay(int season, int day) {
        if (!chairmanModeProperties.isEnabled()) return;
        if (season < 1 || day < 1 || day > 366) {
            throw new IllegalArgumentException("Market date is outside supported bounds");
        }
        marketMutationLock.lock();
        try {
            marketTransaction.executeWithoutResult(status -> processMarketDay(season, day));
        } finally {
            marketMutationLock.unlock();
        }
        traderAdviserService.processDailyPayroll(season, day);
    }

    private void processMarketDay(int season, int targetDay) {
        List<MarketInstrument> listed = instrumentRepository.findAllByActiveTrueOrderByCodeAsc();
        if (listed.isEmpty()) {
            // Cold databases are normally bootstrapped at ApplicationReady. Keep one
            // cheap recovery path without repeating the O(clubs) bootstrap every day.
            bootstrapService.ensureAllInstrumentsInCurrentTransaction();
            instrumentRepository.flush();
            entityManager.clear();
            listed = instrumentRepository.findAllByActiveTrueOrderByCodeAsc();
        }
        if (listed.isEmpty()) return;

        Map<Long, ClubValuationService.Valuation> clubValuations = clubValuations(listed);
        // valueBatch deliberately loads all squads and club assets in bounded queries.
        // They are immutable inputs for this pricing pass; discard them before any
        // snapshot flush so Hibernate does not dirty-check thousands of entities.
        entityManager.clear();
        List<MarketInstrument> instruments = instrumentRepository.findAllActiveForUpdate();

        List<Long> instrumentIds = instruments.stream().map(MarketInstrument::getId).toList();
        Map<Long, MarketPriceSnapshot> latestByInstrument = new HashMap<>();
        snapshotRepository.findLatestForInstruments(instrumentIds)
                .forEach(snapshot -> latestByInstrument.put(snapshot.getInstrumentId(), snapshot));
        List<MarketPriceSnapshot> snapshotBatch = new ArrayList<>(SNAPSHOT_BATCH_SIZE);
        for (MarketInstrument instrument : instruments) {
            processInstrument(instrument, latestByInstrument.get(instrument.getId()),
                    clubValuations, season, targetDay, snapshotBatch);
        }
        flushSnapshotBatch(snapshotBatch);
        instrumentRepository.saveAll(instruments);
        entityManager.flush();
    }

    private Map<Long, ClubValuationService.Valuation> clubValuations(
            Collection<MarketInstrument> instruments) {
        List<Long> teamIds = instruments.stream()
                .filter(instrument -> instrument.getInstrumentType() == MarketInstrumentType.CLUB)
                .map(MarketInstrument::getTeamId)
                .distinct()
                .toList();
        if (teamIds.isEmpty()) return Map.of();
        List<Team> teams = teamRepository.findAllById(teamIds);
        if (teams.size() != teamIds.size()) {
            throw new EconomyConflictException("CLUB_NOT_FOUND", "A listed club was not found");
        }
        return clubValuationService.valueBatch(teams);
    }

    private void processInstrument(MarketInstrument instrument, MarketPriceSnapshot latest,
                                   Map<Long, ClubValuationService.Valuation> clubValuations,
                                   int season, int targetDay,
                                   List<MarketPriceSnapshot> snapshotBatch) {
        if (latest != null && (latest.getSeasonNumber() > season
                || (latest.getSeasonNumber() == season && latest.getGameDay() >= targetDay))) {
            return;
        }
        int firstDay = latest != null && latest.getSeasonNumber() == season ? latest.getGameDay() + 1 : 1;
        long previous = instrument.getCurrentPrice();
        long weeklyAnchor = latest != null && latest.getSeasonNumber() == season
                ? latest.getWeeklyAnchorPrice() : previous;
        for (int day = firstDay; day <= targetDay; day++) {
            if ((day - 1) % 7 == 0) weeklyAnchor = previous;
            long deterministicHash = deterministicHash(instrument, season, day);
            long close = riskClose(instrument, previous, season, day, clubValuations);

            MarketPriceSnapshot snapshot = new MarketPriceSnapshot();
            snapshot.setInstrumentId(instrument.getId());
            snapshot.setSeasonNumber(season);
            snapshot.setGameDay(day);
            snapshot.setPreviousClose(previous);
            snapshot.setClosePrice(close);
            snapshot.setWeeklyAnchorPrice(weeklyAnchor);
            snapshot.setDailyChangeBps(actualBps(previous, close));
            snapshot.setAlgorithmVersion(instrument.getPriceAlgorithmVersion());
            snapshot.setDeterministicHash(deterministicHash);
            snapshotBatch.add(snapshot);
            previous = close;
            if (shouldFlushAndClear(snapshotBatch.size())) flushSnapshotBatch(snapshotBatch);
        }
        instrument.setCurrentPrice(previous);
    }

    private void flushSnapshotBatch(List<MarketPriceSnapshot> snapshots) {
        if (snapshots.isEmpty()) return;
        jdbcTemplate.batchUpdate(INSERT_SNAPSHOT_SQL, snapshots, snapshots.size(), (statement, snapshot) -> {
            statement.setString(1, snapshot.getAlgorithmVersion());
            statement.setLong(2, snapshot.getClosePrice());
            statement.setInt(3, snapshot.getDailyChangeBps());
            statement.setLong(4, snapshot.getDeterministicHash());
            statement.setInt(5, snapshot.getGameDay());
            statement.setLong(6, snapshot.getInstrumentId());
            statement.setLong(7, snapshot.getPreviousClose());
            statement.setInt(8, snapshot.getSeasonNumber());
            statement.setLong(9, snapshot.getWeeklyAnchorPrice());
        });
        snapshots.clear();
    }

    static boolean shouldFlushAndClear(int pendingSnapshots) {
        return pendingSnapshots >= SNAPSHOT_BATCH_SIZE;
    }

    private long riskClose(MarketInstrument instrument, long previous, int season, int day,
                           Map<Long, ClubValuationService.Valuation> clubValuations) {
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
            case SAFE_COMPANY -> applyReturn(previous, SAFE_MODEL.quote(key, SAFE_V1));
            case SPECULATIVE -> {
                SpeculativeQuote quote = SPECULATIVE_MODEL.quote(
                        key, BigDecimal.valueOf(previous), SPECULATIVE_V1);
                yield applyReturn(previous, quote.dailyReturn());
            }
            case CLUB_EQUITY -> clubClose(instrument, key, clubValuations.get(instrument.getTeamId()));
        };
    }

    private long clubClose(MarketInstrument instrument, MarketQuoteKey key,
                           ClubValuationService.Valuation valuation) {
        if (instrument.getTeamId() == null || instrument.getTotalSupply() <= 0) {
            throw new EconomyConflictException("INVALID_CLUB_EQUITY", "Club equity requires a club and finite supply");
        }
        if (valuation == null) {
            throw new EconomyConflictException("CLUB_NOT_FOUND", "Listed club valuation is missing");
        }
        ClubEquityQuote quote = CLUB_MODEL.quote(
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
