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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MarketBootstrapService {
    static final long CLUB_SUPPLY = 1_000_000L;
    static final int DEFAULT_DAILY_LIMIT_BPS = 700;
    static final int DEFAULT_WEEKLY_LIMIT_BPS = 5_000;

    private final MarketInstrumentRepository instrumentRepository;
    private final TeamRepository teamRepository;
    private final ChairmanModeProperties chairmanModeProperties;
    private final ClubValuationService clubValuationService;
    private final MarketMutationLock marketMutationLock;
    private final TransactionTemplate isolatedTransaction;

    public MarketBootstrapService(MarketInstrumentRepository instrumentRepository,
                                  TeamRepository teamRepository,
                                  ChairmanModeProperties chairmanModeProperties,
                                  ClubValuationService clubValuationService,
                                  MarketMutationLock marketMutationLock,
                                  PlatformTransactionManager transactionManager) {
        this.instrumentRepository = instrumentRepository;
        this.teamRepository = teamRepository;
        this.chairmanModeProperties = chairmanModeProperties;
        this.clubValuationService = clubValuationService;
        this.marketMutationLock = marketMutationLock;
        this.isolatedTransaction = new TransactionTemplate(transactionManager);
        this.isolatedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(30)
    public void initializeOnStartup() {
        if (chairmanModeProperties.isEnabled()) ensureAllInstruments();
    }

    /** Keep the shared market lane until the isolated bootstrap transaction commits. */
    public void ensureAllInstruments() {
        marketMutationLock.lock();
        try {
            isolatedTransaction.executeWithoutResult(status -> ensureAllInstrumentsInTransaction());
        } finally {
            marketMutationLock.unlock();
        }
    }

    /**
     * Rebuilds missing instruments inside an already active caller transaction.
     * Save import must use this path: suspending its transaction and opening a
     * REQUIRES_NEW transaction would make H2 wait on rows locked by the import
     * itself.
     */
    public void ensureAllInstrumentsInCurrentTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("an active transaction is required for in-transaction market bootstrap");
        }
        marketMutationLock.lock();
        try {
            ensureAllInstrumentsInTransaction();
        } finally {
            marketMutationLock.unlock();
        }
    }

    private void ensureAllInstrumentsInTransaction() {
        List<MarketInstrument> existing = instrumentRepository.findAll();
        Set<String> existingCodes = new HashSet<>();
        Set<Long> listedTeamIds = new HashSet<>();
        for (MarketInstrument instrument : existing) {
            existingCodes.add(instrument.getCode());
            if (instrument.getTeamId() != null) listedTeamIds.add(instrument.getTeamId());
        }
        List<MarketInstrument> pending = new ArrayList<>();
        ensureCompany(existingCodes, pending, "FMX", "Football Markets Exchange", 1_250,
                772360782L, MarketRiskClass.SAFE_COMPANY);
        ensureCompany(existingCodes, pending, "SPORTTECH", "Sport Technology Group", 850,
                1297702381L, MarketRiskClass.SAFE_COMPANY);
        ensureCompany(existingCodes, pending, "MEDIA11", "Eleven Sports Media", 640,
                214013921L, MarketRiskClass.SPECULATIVE);

        List<Team> missingTeams = teamRepository.findAll().stream()
                .filter(team -> !listedTeamIds.contains(team.getId()))
                .sorted(java.util.Comparator.comparingLong(Team::getId))
                .toList();
        Map<Long, ClubValuationService.Valuation> valuations =
                missingTeams.isEmpty() ? Map.of() : clubValuationService.valueBatch(missingTeams);
        for (Team team : missingTeams) {
            MarketInstrument instrument = new MarketInstrument();
            instrument.setCode("CLUB-" + team.getId());
            instrument.setInstrumentType(MarketInstrumentType.CLUB);
            instrument.setTeamId(team.getId());
            instrument.setName(team.getName());
            instrument.setTotalSupply(CLUB_SUPPLY);
            instrument.setAvailableSupply(CLUB_SUPPLY);
            instrument.setCurrentPrice(clubValuationService.perSharePrice(
                    valuations.get(team.getId()), CLUB_SUPPLY));
            instrument.setPriceSeed(stableSeed(instrument.getCode()));
            instrument.setPriceAlgorithmVersion(DeterministicMarketPriceService.MARKET_V1);
            instrument.setRiskClass(MarketRiskClass.CLUB_EQUITY);
            instrument.setRiskConfigVersion(DeterministicMarketPriceService.RISK_V1);
            instrument.setDailyLimitBps(DEFAULT_DAILY_LIMIT_BPS);
            instrument.setWeeklyLimitBps(DEFAULT_WEEKLY_LIMIT_BPS);
            instrument.setActive(true);
            pending.add(instrument);
        }
        if (!pending.isEmpty()) instrumentRepository.saveAll(pending);
    }

    private void ensureCompany(Set<String> existingCodes, List<MarketInstrument> pending,
                               String code, String name, long price, long seed,
                               MarketRiskClass riskClass) {
        if (!existingCodes.add(code)) return;
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
        pending.add(instrument);
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
