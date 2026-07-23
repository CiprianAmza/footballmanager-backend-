package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class MarketBootstrapService {
    static final long CLUB_SUPPLY = 1_000_000L;
    static final int DEFAULT_DAILY_LIMIT_BPS = 700;
    static final int DEFAULT_WEEKLY_LIMIT_BPS = 5_000;

    private final MarketInstrumentRepository instrumentRepository;
    private final TeamRepository teamRepository;
    private final RegentEconomyProperties properties;

    public MarketBootstrapService(MarketInstrumentRepository instrumentRepository,
                                  TeamRepository teamRepository,
                                  RegentEconomyProperties properties) {
        this.instrumentRepository = instrumentRepository;
        this.teamRepository = teamRepository;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(30)
    @Transactional
    public void initializeOnStartup() {
        if (properties.isEnabled()) ensureAllInstruments();
    }

    @Transactional
    public void ensureAllInstruments() {
        ensureCompany("FMX", "Football Markets Exchange", 1_250, 772360782L);
        ensureCompany("SPORTTECH", "Sport Technology Group", 850, 1297702381L);
        ensureCompany("MEDIA11", "Eleven Sports Media", 640, 214013921L);
        for (Team team : teamRepository.findAll().stream().sorted(java.util.Comparator.comparingLong(Team::getId)).toList()) {
            if (instrumentRepository.findByTeamId(team.getId()).isPresent()) continue;
            MarketInstrument instrument = new MarketInstrument();
            instrument.setCode("CLUB-" + team.getId());
            instrument.setInstrumentType(MarketInstrumentType.CLUB);
            instrument.setTeamId(team.getId());
            instrument.setName(team.getName());
            instrument.setTotalSupply(CLUB_SUPPLY);
            instrument.setAvailableSupply(CLUB_SUPPLY);
            instrument.setCurrentPrice(clubInitialPrice(team.getReputation()));
            instrument.setPriceSeed(stableSeed(instrument.getCode()));
            instrument.setPriceAlgorithmVersion(DeterministicMarketPriceService.MARKET_V1);
            instrument.setDailyLimitBps(DEFAULT_DAILY_LIMIT_BPS);
            instrument.setWeeklyLimitBps(DEFAULT_WEEKLY_LIMIT_BPS);
            instrument.setActive(true);
            instrumentRepository.save(instrument);
        }
    }

    private void ensureCompany(String code, String name, long price, long seed) {
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
