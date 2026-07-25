package com.footballmanagergamesimulator.service;

import java.util.Set;

public record AdvanceScope(Set<Integer> roomUserIds, Set<Long> roomTeamIds) {
    public AdvanceScope {
        roomUserIds = Set.copyOf(roomUserIds);
        roomTeamIds = Set.copyOf(roomTeamIds);
    }
}
