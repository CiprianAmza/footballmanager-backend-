package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Stadium;
import com.footballmanagergamesimulator.model.Team;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates a stable visual identity for every ground from immutable club data.
 * No random generator is read at request time, so designs survive Save/Load and
 * old careers receive the same design without a schema migration.
 */
@Service
public class StadiumDesignService {

    private static final Map<String, String> CSS_COLOURS = Map.ofEntries(
            Map.entry("black", "#171a21"), Map.entry("white", "#f4f5f7"),
            Map.entry("grey", "#687386"), Map.entry("gray", "#687386"),
            Map.entry("red", "#d64045"), Map.entry("blue", "#2867b2"),
            Map.entry("green", "#238b57"), Map.entry("yellow", "#f3c623"),
            Map.entry("orange", "#ed7d31"), Map.entry("pink", "#e967a1"),
            Map.entry("purple", "#7551a8"), Map.entry("lila", "#9b76c5"),
            Map.entry("cyan", "#20b8c8"), Map.entry("gold", "#d9a928"),
            Map.entry("brown", "#795548"), Map.entry("navy", "#172b55"));

    public StadiumDesign design(Team team, Stadium stadium) {
        int effectiveCapacity = Math.max(1, stadium.getEffectiveCapacity());
        int reputation = Math.max(0, team.getReputation());
        int seed = stableSeed(team);
        int variant = Math.floorMod(seed, 12);

        String scale = effectiveCapacity >= 75_000 ? "MEGA"
                : effectiveCapacity >= 50_000 ? "ELITE"
                : effectiveCapacity >= 30_000 ? "NATIONAL"
                : effectiveCapacity >= 16_000 ? "REGIONAL" : "LOCAL";
        int tiers = effectiveCapacity >= 72_000 ? 3 : effectiveCapacity >= 36_000 ? 2 : 1;
        String shape = shape(scale, variant);
        String roof = reputation >= 9_000 ? "FULL"
                : reputation >= 7_200 ? "THREE_QUARTER"
                : reputation >= 5_200 ? (variant % 3 == 0 ? "TWO_SIDES" : "PARTIAL")
                : variant % 2 == 0 ? "MAIN_STAND" : "NONE";
        String corners = effectiveCapacity >= 52_000 ? "CLOSED"
                : effectiveCapacity >= 28_000 ? (variant % 2 == 0 ? "MIXED" : "CLOSED")
                : variant % 3 == 0 ? "MIXED" : "OPEN";
        String facade = reputation >= 8_800 ? List.of("GLASS", "STEEL", "ICONIC").get(variant % 3)
                : List.of("BRICK", "CONCRETE", "STEEL", "HISTORIC").get(variant % 4);
        String pitchPattern = List.of("STRIPES", "CHECKER", "DIAGONAL", "RINGS").get(variant % 4);
        String floodlights = roof.equals("FULL") ? "ROOF_INTEGRATED"
                : variant % 2 == 0 ? "FOUR_TOWERS" : "CORNER_MASTS";
        String openEnd = shape.equals("HORSESHOE")
                ? List.of("NORTH", "SOUTH", "EAST", "WEST").get(variant % 4) : "NONE";

        double irregularity = reputation >= 8_000 ? .04 : reputation >= 5_500 ? .10 : .18;
        double north = standScale(seed, 3, irregularity);
        double south = standScale(seed, 7, irregularity);
        double west = standScale(seed, 11, irregularity);
        double east = standScale(seed, 17, irregularity);
        if (shape.equals("HORSESHOE")) {
            if (openEnd.equals("NORTH")) north = .18;
            if (openEnd.equals("SOUTH")) south = .18;
            if (openEnd.equals("WEST")) west = .18;
            if (openEnd.equals("EAST")) east = .18;
        }

        int prestige = Math.min(100, Math.max(1,
                reputation / 110 + stadium.getVipBoxesLevel() * 2 + stadium.getExpansionLevel()));
        return new StadiumDesign(seed, shape, scale, tiers, roof, corners, facade,
                pitchPattern, floodlights, openEnd, colour(team.getColor1(), "#2867b2"),
                colour(team.getColor2(), "#f4f5f7"), colour(team.getBorder(), "#d9a928"),
                north, south, west, east, prestige, architectureLabel(shape, tiers, roof));
    }

    /** Reputation is the dominant capacity input; rounding creates realistic published figures. */
    public static int initialCapacityForReputation(int reputation) {
        int raw = 10_000 + Math.max(0, reputation) * 8;
        int bounded = Math.max(8_000, Math.min(100_000, raw));
        return Math.round(bounded / 500f) * 500;
    }

    private int stableSeed(Team team) {
        int hash = Long.hashCode(team.getId());
        hash = 31 * hash + safe(team.getName()).toLowerCase(Locale.ROOT).hashCode();
        hash = 31 * hash + safe(team.getColor1()).toLowerCase(Locale.ROOT).hashCode();
        hash = 31 * hash + safe(team.getColor2()).toLowerCase(Locale.ROOT).hashCode();
        return hash == Integer.MIN_VALUE ? 0 : Math.abs(hash);
    }

    private String shape(String scale, int variant) {
        if (scale.equals("MEGA")) return variant % 3 == 0 ? "OVAL" : "CONTINUOUS_BOWL";
        if (scale.equals("ELITE")) return List.of("CONTINUOUS_BOWL", "RECTANGULAR", "OVAL").get(variant % 3);
        if (scale.equals("NATIONAL")) return List.of("RECTANGULAR", "COMPACT_BOWL", "FOUR_STANDS").get(variant % 3);
        return List.of("FOUR_STANDS", "HORSESHOE", "RECTANGULAR", "ASYMMETRIC").get(variant % 4);
    }

    private double standScale(int seed, int shift, double irregularity) {
        int bucket = Math.floorMod(seed / shift, 9) - 4;
        return Math.round((1 + bucket * irregularity / 4) * 100.0) / 100.0;
    }

    private String colour(String authored, String fallback) {
        if (authored == null || authored.isBlank()) return fallback;
        String value = authored.trim().toLowerCase(Locale.ROOT);
        if (value.matches("#[0-9a-f]{3}([0-9a-f]{3})?")) return value;
        return CSS_COLOURS.getOrDefault(value, fallback);
    }

    private String architectureLabel(String shape, int tiers, String roof) {
        String friendlyShape = shape.toLowerCase(Locale.ROOT).replace('_', ' ');
        String friendlyRoof = roof.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(friendlyShape.charAt(0)) + friendlyShape.substring(1)
                + " · " + tiers + (tiers == 1 ? " tier" : " tiers") + " · " + friendlyRoof + " roof";
    }

    private String safe(String value) { return value == null ? "" : value; }

    public record StadiumDesign(int seed, String shape, String scale, int tiers, String roof,
                                String corners, String facade, String pitchPattern,
                                String floodlights, String openEnd, String primaryColour,
                                String secondaryColour, String accentColour,
                                double northStandScale, double southStandScale,
                                double westStandScale, double eastStandScale,
                                int prestige, String architectureLabel) {}
}
