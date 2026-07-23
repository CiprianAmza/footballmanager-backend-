package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.RoundRepository;
import org.springframework.stereotype.Component;

@Component
public class EconomyClock {

    private final RoundRepository roundRepository;

    public EconomyClock(RoundRepository roundRepository) {
        this.roundRepository = roundRepository;
    }

    public GameDate current() {
        Round round = roundRepository.findById(1L).orElseGet(Round::new);
        return new GameDate((int) round.getSeason(), (int) round.getRound());
    }

    public record GameDate(int season, int day) { }
}
