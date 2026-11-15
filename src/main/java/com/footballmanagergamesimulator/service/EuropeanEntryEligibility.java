package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;

import java.util.Set;

/**
 * Domestic expansion must not silently redistribute the existing European places.
 * These nations join the domestic world now; their European membership is deferred
 * until the complete world and its continental access list are approved together.
 * Existing formats, rank allocations and historical entries remain untouched.
 */
public final class EuropeanEntryEligibility {
    private static final Set<Long> PENDING_NATIONS = Set.of(11L, 12L, 13L);

    private EuropeanEntryEligibility() {}

    public static boolean isEligibleTopFlight(Competition competition) {
        return competition.isTopFlight() && !PENDING_NATIONS.contains(competition.getNationId());
    }
}
