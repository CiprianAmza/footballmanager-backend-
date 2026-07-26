package com.footballmanagergamesimulator.repository;

/**
 * Public Spring Data projection for one team's aggregate active-player value.
 *
 * <p>This intentionally lives in its own class file. Keeping it nested inside
 * {@link HumanRepository} made incremental Maven/Failsafe runs vulnerable to
 * loading the repository signature before the nested {@code $...class} file
 * had been refreshed.
 */
public interface TeamValueTotal {
    Long getTeamId();
    Long getTotalValue();
}
