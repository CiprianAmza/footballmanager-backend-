package com.footballmanagergamesimulator.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Controls how often training is allowed to mutate long-term player ability. */
@Service
public class TrainingCadenceService {

    @Value("${simulation.training.rating-update-interval-days:21}")
    private int ratingUpdateIntervalDays;

    public boolean isRatingUpdateDay(int day) {
        int interval = Math.max(1, ratingUpdateIntervalDays);
        int safeDay = Math.max(1, day);
        return safeDay == 1 || (safeDay - 1) % interval == 0;
    }

    public int intervalDays() {
        return Math.max(1, ratingUpdateIntervalDays);
    }
}
