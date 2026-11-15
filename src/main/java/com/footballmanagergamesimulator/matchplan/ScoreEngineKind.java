package com.footballmanagergamesimulator.matchplan;

public enum ScoreEngineKind {
    ADMIN_OVERRIDE("admin-override-1"),
    COMPARTMENT_V1("compartment-score-1"),
    /** Regular-time score, goals and ratings played by the external 2D match engine. */
    MATCH2D_V1("match2d-1");

    private final String algorithmVersion;

    ScoreEngineKind(String algorithmVersion) {
        this.algorithmVersion = algorithmVersion;
    }

    public String algorithmVersion() {
        return algorithmVersion;
    }
}
