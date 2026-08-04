package com.footballmanagergamesimulator.frontend;

import java.util.List;

/** Lightweight, navigation-oriented results for the global command search. */
public record GlobalSearchResponse(
        List<Item> players,
        List<Item> clubs,
        List<Item> competitions
) {
    public record Item(long id, String name, String meta) {}

    public static GlobalSearchResponse empty() {
        return new GlobalSearchResponse(List.of(), List.of(), List.of());
    }
}
