package com.footballmanagergamesimulator.testutil;

/**
 * A team as seen by the outcome-simulation tests: stable id, display name, and
 * a pre-computed power (sum of top-11 player ratings). Shared across the league,
 * cup, LoC, and Stars Cup {@code *OutcomeIT} suites so they all speak one type.
 */
public record TeamSetup(long id, String name, double power) {}
