package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.config.ChairmanModeProperties;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.regent.market.core.MarketRiskClass;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

@Service
public class MarketBootstrapService {
    static final long CLUB_SUPPLY = 1_000_000L;
    static final int DEFAULT_DAILY_LIMIT_BPS = 700;
    static final int DEFAULT_WEEKLY_LIMIT_BPS = 5_000;

    private final MarketInstrumentRepository instrumentRepository;
    private final TeamRepository teamRepository;
    private final ChairmanModeProperties chairmanModeProperties;
    private final ClubValuationService clubValuationService;
    private final TransactionTemplate isolatedTransaction;

    public MarketBootstrapService(MarketInstrumentRepository instrumentRepository,
                                  TeamRepository teamRepository,
                                  ChairmanModeProperties chairmanModeProperties,
                                  ClubValuationService clubValuationService,
                                  PlatformTransactionManager transactionManager) {
        this.instrumentRepository = instrumentRepository;
        this.teamRepository = teamRepository;
        this.chairmanModeProperties = chairmanModeProperties;
        this.clubValuationService = clubValuationService;
        this.isolatedTransaction = new TransactionTemplate(transactionManager);
        this.isolatedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(30)
    public void initializeOnStartup() {
        if (chairmanModeProperties.isEnabled()) ensureAllInstruments();
    }

    /**
     * Keep the Java monitor until the transaction has committed. With a synchronized
     * {@code @Transactional} target method Spring commits after the monitor is released, which
     * allowed an HTTP request to race the ApplicationReady bootstrap on H2.
     */
    public synchronized void ensureAllInstruments() {
        isolatedTransaction.executeWithoutResult(status -> ensureAllInstrumentsInTransaction());
    }

    /**
     * Rebuilds missing instruments inside an already active caller transaction.
     * Save import must use this path: suspending its transaction and opening a
     * REQUIRES_NEW transaction would make H2 wait on rows locked by the import
     * itself.
     */
    public synchronized void ensureAllInstrumentsInCurrentTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("an active transaction is required for in-transaction market bootstrap");
        }
        ensureAllInstrumentsInTransaction();
    }

    private void ensureAllInstrumentsInTransaction() {
        ensureCompany("FMX", "Football Markets Exchange", 1_250, 772360782L, MarketRiskClass.SAFE_COMPANY);
        ensureCompany("SPORTTECH", "Sport Technology Group", 850, 1297702381L, MarketRiskClass.SAFE_COMPANY);
        ensureCompany("MEDIA11", "Eleven Sports Media", 640, 214013921L, MarketRiskClass.SPECULATIVE);
        for (Team team : teamRepository.findAll().stream().sorted(java.util.Comparator.comparingLong(Team::getId)).toList()) {
            if (instrumentRepository.findByTeamId(team.getId()).isPresent()) continue;
            MarketInstrument instrument = new MarketInstrument();
            instrument.setCode("CLUB-" + team.getId());
            instrument.setInstrumentType(MarketInstrumentType.CLUB);
            instrument.setTeamId(team.getId());
            instrument.setName(team.getName());
            instrument.setTotalSupply(CLUB_SUPPLY);
            instrument.setAvailableSupply(CLUB_SUPPLY);
            instrument.setCurrentPrice(clubValuationService.perSharePrice(
                    clubValuationService.value(team), CLUB_SUPPLY));
            instrument.setPriceSeed(stableSeed(instrument.getCode()));
            instrument.setPriceAlgorithmVersion(DeterministicMarketPriceService.MARKET_V1);
            instrument.setRiskClass(MarketRiskClass.CLUB_EQUITY);
            instrument.setRiskConfigVersion(DeterministicMarketPriceService.RISK_V1);
            instrument.setDailyLimitBps(DEFAULT_DAILY_LIMIT_BPS);
            instrument.setWeeklyLimitBps(DEFAULT_WEEKLY_LIMIT_BPS);
            instrument.setActive(true);
            instrumentRepository.save(instrument);
        }
    }

    private void ensureCompany(String code, String name, long price, long seed, MarketRiskClass riskClass) {
        if (instrumentRepository.findByCode(code).isPresent()) return;
        MarketInstrument instrument = new MarketInstrument();
        instrument.setCode(code);
        instrument.setInstrumentType(MarketInstrumentType.COMPANY);
        instrument.setName(name);
        instrument.setTotalSupply(CLUB_SUPPLY);
        instrument.setAvailableSupply(CLUB_SUPPLY);
        instrument.setCurrentPrice(price);
        instrument.setPriceSeed(seed);
        instrument.setPriceAlgorithmVersion(DeterministicMarketPriceService.MARKET_V1);
        instrument.setRiskClass(riskClass);
        instrument.setRiskConfigVersion(DeterministicMarketPriceService.RISK_V1);
        instrument.setDailyLimitBps(DEFAULT_DAILY_LIMIT_BPS);
        instrument.setWeeklyLimitBps(DEFAULT_WEEKLY_LIMIT_BPS);
        instrument.setActive(true);
        instrumentRepository.save(instrument);
    }

    static long clubInitialPrice(int reputation) {
        return Math.max(100L, Math.multiplyExact(Math.max(1, reputation), 25L));
    }

    static long stableSeed(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= Byte.toUnsignedLong(current);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
