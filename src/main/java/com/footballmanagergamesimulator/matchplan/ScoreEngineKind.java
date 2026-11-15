package com.footballmanagergamesimulator.matchplan;

public enum ScoreEngineKind {
    ADMIN_OVERRIDE("admin-override-1"),
    COMPARTMENT_V1("compartment-score-1"),
    /**
     * A fixture drawn by the external 2D match engine. The score is still the classic
     * engine's (Compartment V1): the 2D engine plays a match that reproduces it, and its goals
     * (scorers, assists, minutes), ratings and statistics are the ones kept.
     */
    MATCH2D_V1("match2d-1");

    private final String algorithmVersion;

    ScoreEngineKind(String algorithmVersion) {
        this.algorithmVersion = algorithmVersion;
    }

    public String algorithmVersion() {
        return algorithmVersion;
    }
}
