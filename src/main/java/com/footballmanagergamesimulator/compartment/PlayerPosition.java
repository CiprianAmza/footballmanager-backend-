package com.footballmanagergamesimulator.compartment;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum PlayerPosition {
    GK("GK", true, false, false, false, Side.CENTRAL),
    DC("DC", false, true, false, false, Side.CENTRAL),
    DL("DL", false, false, false, false, Side.LEFT),
    DR("DR", false, false, false, false, Side.RIGHT),
    WBL("WBL", false, false, false, false, Side.LEFT),
    WBR("WBR", false, false, false, false, Side.RIGHT),
    DM("DM", false, false, true, false, Side.CENTRAL),
    MC("MC", false, false, false, false, Side.CENTRAL),
    ML("ML", false, false, false, false, Side.LEFT),
    MR("MR", false, false, false, false, Side.RIGHT),
    AMC("AMC", false, false, false, false, Side.CENTRAL),
    AML("AML", false, false, false, true, Side.LEFT),
    AMR("AMR", false, false, false, true, Side.RIGHT),
    ST("ST", false, false, false, false, Side.CENTRAL);

    private final String code;
    private final boolean goalkeeper;
    private final boolean centreBack;
    private final boolean defensiveMidfielder;
    private final boolean halfSpace;
    private final Side side;
    private final boolean wideEligible;

    PlayerPosition(String code, boolean goalkeeper, boolean centreBack, boolean defensiveMidfielder,
                   boolean halfSpace, Side side) {
        this.code = code;
        this.goalkeeper = goalkeeper;
        this.centreBack = centreBack;
        this.defensiveMidfielder = defensiveMidfielder;
        this.halfSpace = halfSpace;
        this.side = side;
        this.wideEligible = side != Side.CENTRAL;
    }

    public String code() {
        return code;
    }

    public static Optional<PlayerPosition> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(position -> position.code.equals(normalized)).findFirst();
    }

    public static PlayerPosition require(String raw) {
        return parse(raw).orElseThrow(() -> new IllegalArgumentException("unknown player position: " + raw));
    }

    public boolean isGoalkeeper() {
        return goalkeeper;
    }

    public boolean isCentreBack() {
        return centreBack;
    }

    public boolean isCenterBack() {
        return centreBack;
    }

    public boolean isDefensiveMidfielder() {
        return defensiveMidfielder;
    }

    public boolean isLeft() {
        return side == Side.LEFT;
    }

    public boolean isRight() {
        return side == Side.RIGHT;
    }

    public boolean isCentral() {
        return side == Side.CENTRAL;
    }

    public boolean isWideEligible() {
        return wideEligible;
    }

    public boolean isHalfSpaceEligible() {
        return halfSpace;
    }

    private enum Side {
        LEFT,
        RIGHT,
        CENTRAL
    }
}
