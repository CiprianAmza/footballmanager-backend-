package com.footballmanagergamesimulator.compartment.adapter;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;

import java.util.Map;
import java.util.Set;

public record PositionRoleKey(PlayerPosition position, PlayerRole role) {
    private static final Set<PlayerRole> GK_ROLES = Set.of(PlayerRole.GOALKEEPER, PlayerRole.SWEEPER_KEEPER);
    private static final Set<PlayerRole> DC_ROLES = Set.of(PlayerRole.CENTRAL_DEFENDER,
            PlayerRole.BALL_PLAYING_DEFENDER, PlayerRole.NO_NONSENSE_DEFENDER);
    private static final Set<PlayerRole> FB_ROLES = Set.of(PlayerRole.FULL_BACK,
            PlayerRole.WING_BACK, PlayerRole.INVERTED_WING_BACK);
    private static final Set<PlayerRole> MC_ROLES = Set.of(PlayerRole.CENTRAL_MIDFIELDER,
            PlayerRole.DEEP_LYING_PLAYMAKER, PlayerRole.BALL_WINNING_MIDFIELDER,
            PlayerRole.BOX_TO_BOX_MIDFIELDER, PlayerRole.ADVANCED_PLAYMAKER, PlayerRole.MEZZALA);
    private static final Set<PlayerRole> MC_ROLES_WITH_SHADOW = Set.of(PlayerRole.CENTRAL_MIDFIELDER,
            PlayerRole.DEEP_LYING_PLAYMAKER, PlayerRole.BALL_WINNING_MIDFIELDER,
            PlayerRole.BOX_TO_BOX_MIDFIELDER, PlayerRole.ADVANCED_PLAYMAKER, PlayerRole.MEZZALA,
            PlayerRole.SHADOW_STRIKER);
    private static final Set<PlayerRole> WIDE_ROLES = Set.of(PlayerRole.WINGER,
            PlayerRole.INSIDE_FORWARD, PlayerRole.WIDE_MIDFIELDER, PlayerRole.INVERTED_WINGER);
    private static final Set<PlayerRole> ST_ROLES = Set.of(PlayerRole.ADVANCED_FORWARD,
            PlayerRole.POACHER, PlayerRole.TARGET_MAN, PlayerRole.DEEP_LYING_FORWARD,
            PlayerRole.PRESSING_FORWARD, PlayerRole.COMPLETE_FORWARD);

    private static final Map<PlayerPosition, Set<PlayerRole>> ROLES_BY_POSITION = Map.ofEntries(
            Map.entry(PlayerPosition.GK, GK_ROLES),
            Map.entry(PlayerPosition.DC, DC_ROLES),
            Map.entry(PlayerPosition.DL, FB_ROLES),
            Map.entry(PlayerPosition.DR, FB_ROLES),
            Map.entry(PlayerPosition.WBL, FB_ROLES),
            Map.entry(PlayerPosition.WBR, FB_ROLES),
            Map.entry(PlayerPosition.DM, MC_ROLES),
            Map.entry(PlayerPosition.MC, MC_ROLES_WITH_SHADOW),
            Map.entry(PlayerPosition.AMC, MC_ROLES_WITH_SHADOW),
            Map.entry(PlayerPosition.ML, WIDE_ROLES),
            Map.entry(PlayerPosition.MR, WIDE_ROLES),
            Map.entry(PlayerPosition.AML, WIDE_ROLES),
            Map.entry(PlayerPosition.AMR, WIDE_ROLES),
            Map.entry(PlayerPosition.ST, ST_ROLES)
    );

    public PositionRoleKey {
        if (position == null) {
            throw new IllegalArgumentException("position is required");
        }
        if (role == null) {
            throw new IllegalArgumentException("role is required");
        }
        if (!isAvailable(position, role)) {
            throw new IllegalArgumentException("role " + role.name() + " is not available for " + position.code());
        }
    }

    public static PositionRoleKey ofCodes(String positionCode, String roleCode) {
        return new PositionRoleKey(PlayerPosition.require(positionCode), roleFromCode(roleCode));
    }

    public static boolean isAvailable(PlayerPosition position, PlayerRole role) {
        return ROLES_BY_POSITION.getOrDefault(position, Set.of()).contains(role);
    }

    public static PlayerRole roleFromCode(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            throw new IllegalArgumentException("role code is required");
        }
        try {
            return PlayerRole.valueOf(roleCode.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown player role: " + roleCode, exception);
        }
    }
}
