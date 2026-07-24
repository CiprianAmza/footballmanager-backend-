package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.regent.market.core.MarketRiskClass;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-phase4b-pricing",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=update",
        "simulation.matchday.parallel.enabled=false",
        "regent.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RegentPhase4BMarketPricingIT {
    @Autowired private DeterministicMarketPriceService priceService;
    @Autowired private MarketInstrumentRepository instrumentRepository;
    @Autowired private MarketPriceSnapshotRepository snapshotRepository;
    @Autowired private ClubValuationService valuationService;
    @Autowired private PortfolioPositionRepository positionRepository;

    @Test
    void riskClassesArePersistentAndDailyPricingIsBoundedBiasedAndRetryDeterministic() {
        MarketInstrument safe = instrumentRepository.findByCode("FMX").orElseThrow();
        MarketInstrument speculative = instrumentRepository.findByCode("MEDIA11").orElseThrow();
        instrumentRepository.findAll().forEach(value -> value.setActive(
                value.getId() == safe.getId() || value.getId() == speculative.getId()));
        instrumentRepository.flush();
        assertThat(safe.getRiskClass()).isEqualTo(MarketRiskClass.SAFE_COMPANY);
        assertThat(speculative.getRiskClass()).isEqualTo(MarketRiskClass.SPECULATIVE);
        assertThat(safe.getRiskConfigVersion()).isEqualTo(DeterministicMarketPriceService.RISK_V1);

        priceService.processDay(1, 366);
        List<MarketPriceSnapshot> safeDays = history(safe.getId());
        List<MarketPriceSnapshot> speculativeDays = history(speculative.getId());
        assertThat(safeDays).hasSize(366);
        assertThat(speculativeDays).hasSize(366);
        assertThat(safeDays).allSatisfy(value -> assertRealizedBound(value, 100));
        assertThat(speculativeDays).allSatisfy(value -> assertRealizedBound(value, 5_000));
        long positive = safeDays.stream().filter(value -> value.getClosePrice() > value.getPreviousClose()).count();
        long negative = safeDays.stream().filter(value -> value.getClosePrice() < value.getPreviousClose()).count();
        assertThat(positive).isGreaterThan(negative);
        List<Long> fingerprint = safeDays.stream().map(MarketPriceSnapshot::getDeterministicHash).toList();

        priceService.processDay(1, 366);
        assertThat(history(safe.getId())).extracting(MarketPriceSnapshot::getDeterministicHash)
                .containsExactlyElementsOf(fingerprint);
        assertThat(DeterministicMarketPriceService.applyBps(10_000, -5_000)).isEqualTo(5_000);
        assertThat(DeterministicMarketPriceService.applyBps(10_000, 5_000)).isEqualTo(15_000);
    }

    @Test
    void clubEquityTracksCanonicalValuationWithinNoiseAndKeepsFiniteSupply() {
        MarketInstrument club = instrumentRepository.findAll().stream()
                .filter(value -> value.getRiskClass() == MarketRiskClass.CLUB_EQUITY).findFirst().orElseThrow();
        long clubId = club.getId();
        instrumentRepository.findAll().forEach(value -> value.setActive(value.getId() == clubId));
        instrumentRepository.flush();
        priceService.processDay(2, 40);
        club = instrumentRepository.findById(club.getId()).orElseThrow();
        long reference = valuationService.perSharePrice(valuationService.value(club.getTeamId()), club.getTotalSupply());
        long tolerance = Math.max(1L, BigInteger.valueOf(reference).multiply(BigInteger.valueOf(3L))
                .divide(BigInteger.valueOf(100L)).longValueExact());
        assertThat(club.getCurrentPrice()).isBetween(Math.max(1L, reference - tolerance - 1L), reference + tolerance + 1L);
        assertThat(club.getAvailableSupply() + positionRepository.sumQuantityByInstrumentId(club.getId()))
                .isEqualTo(club.getTotalSupply());
        assertThat(club.getTotalSupply()).isPositive();
    }

    @Test
    void annualCatchUpFlushPolicyKeepsPendingSnapshotsBounded() {
        int pending = 0;
        int maximumPending = 0;
        int flushes = 0;
        for (int day = 1; day <= 366; day++) {
            pending++;
            maximumPending = Math.max(maximumPending, pending);
            if (DeterministicMarketPriceService.shouldFlushAndClear(pending)) {
                pending = 0;
                flushes++;
            }
        }
        assertThat(maximumPending).isEqualTo(DeterministicMarketPriceService.SNAPSHOT_BATCH_SIZE);
        assertThat(flushes).isEqualTo(366 / DeterministicMarketPriceService.SNAPSHOT_BATCH_SIZE);
        assertThat(pending).isLessThan(DeterministicMarketPriceService.SNAPSHOT_BATCH_SIZE);
    }

    private List<MarketPriceSnapshot> history(long instrumentId) {
        return snapshotRepository.findAllByInstrumentIdOrderBySeasonNumberDescGameDayDesc(
                instrumentId, Pageable.unpaged());
    }

    private static void assertRealizedBound(MarketPriceSnapshot value, int maximumBps) {
        BigInteger move = BigInteger.valueOf(value.getClosePrice()).subtract(BigInteger.valueOf(value.getPreviousClose()))
                .abs().multiply(BigInteger.valueOf(10_000L));
        BigInteger bound = BigInteger.valueOf(value.getPreviousClose()).multiply(BigInteger.valueOf(maximumBps))
                .add(BigInteger.valueOf(10_000L));
        assertThat(move).isLessThanOrEqualTo(bound);
        assertThat(value.getClosePrice()).isPositive();
    }
}
