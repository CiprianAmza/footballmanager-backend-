package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;

/**
 * Records when a player last put his name on a contract — a renewal, a transfer, a free-agent or youth
 * contract, a pre-contract — all count (decided 2026-11-12). A player who signed less than
 * {@link #SETTLE_DAYS} days ago does not want to move.
 */
@Component
public class ContractSignature {

    /** About six months of game days. */
    public static final int SETTLE_DAYS = 182;
    private static final int DAYS_PER_SEASON = 365;

    @Autowired GameCalendarRepository gameCalendarRepository;

    /** Stamps today's date as the player's last signature (the caller saves the player). */
    public void mark(Human player) {
        GameCalendar calendar = current();
        player.setContractSignedSeason(calendar == null ? 1 : calendar.getSeason());
        player.setContractSignedDay(calendar == null ? 1 : calendar.getCurrentDay());
    }

    /** True when the player signed less than six months ago (never signed in this world = false). */
    public boolean signedRecently(Human player) {
        GameCalendar calendar = current();
        if (calendar == null) return false;
        return signedRecently(player.getContractSignedSeason(), player.getContractSignedDay(),
                calendar.getSeason(), calendar.getCurrentDay());
    }

    public static boolean signedRecently(int signedSeason, int signedDay, int season, int day) {
        if (signedSeason <= 0) return false;
        long since = (long) (season - signedSeason) * DAYS_PER_SEASON + (day - signedDay);
        return since >= 0 && since < SETTLE_DAYS;
    }

    private GameCalendar current() {
        return gameCalendarRepository.findAll().stream()
                .max(Comparator.comparingInt(GameCalendar::getSeason)).orElse(null);
    }
}
