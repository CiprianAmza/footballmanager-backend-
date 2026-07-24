package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.controller.GameController;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.service.CalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-phase2-save-load;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=update",
        "simulation.matchday.parallel.enabled=false",
        "regent.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegentPhase2MarketSaveLoadIT {
    @Autowired private GameController gameController;
    @Autowired private RoundRepository roundRepository;
    @Autowired private CalendarService calendarService;
    @Autowired private DeterministicMarketPriceService priceService;
    @Autowired private MarketInstrumentRepository instrumentRepository;
    @Autowired private MarketPriceSnapshotRepository snapshotRepository;

    @Test
    void saveLoadThenContinueReproducesTheExactFuturePricePath() {
        Round round = roundRepository.findById(1L).orElseThrow();
        calendarService.getOrCreateCalendar((int) round.getSeason());
        MarketInstrument instrument = instrumentRepository.findByCode("FMX").orElseThrow();

        priceService.processDay(91, 10);
        Map<String, Object> saveAtDayTen = gameController.exportGame();
        assertThat(saveAtDayTen.get("saveVersion")).isEqualTo(10);

        priceService.processDay(91, 20);
        List<PriceFingerprint> expected = fingerprints(instrument.getId(), 91, 11, 20);

        Map<String, Object> result = gameController.importGame(saveAtDayTen);
        assertThat(result).containsEntry("success", true);
        instrument = instrumentRepository.findByCode("FMX").orElseThrow();
        priceService.processDay(91, 20);

        assertThat(fingerprints(instrument.getId(), 91, 11, 20))
                .containsExactlyElementsOf(expected);
    }

    private List<PriceFingerprint> fingerprints(long instrumentId, int season, int fromDay, int toDay) {
        List<MarketPriceSnapshot> rows = new ArrayList<>(snapshotRepository
                .findAllByInstrumentIdOrderBySeasonNumberDescGameDayDesc(instrumentId, Pageable.unpaged()));
        return rows.stream()
                .filter(value -> value.getSeasonNumber() == season
                        && value.getGameDay() >= fromDay && value.getGameDay() <= toDay)
                .sorted(Comparator.comparingInt(MarketPriceSnapshot::getGameDay))
                .map(value -> new PriceFingerprint(value.getGameDay(), value.getPreviousClose(),
                        value.getClosePrice(), value.getWeeklyAnchorPrice(), value.getDailyChangeBps(),
                        value.getAlgorithmVersion(), value.getDeterministicHash()))
                .toList();
    }

    private record PriceFingerprint(int day, long previousClose, long closePrice,
                                    long weeklyAnchorPrice, int dailyChangeBps,
                                    String algorithmVersion, long deterministicHash) { }
}
