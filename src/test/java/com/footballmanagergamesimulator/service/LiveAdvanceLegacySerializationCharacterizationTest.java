package com.footballmanagergamesimulator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.footballmanagergamesimulator.frontend.LiveMatchData;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GATE 6 — compatibility (CHARACTERIZATION half).
 *
 * <p>Pins the CURRENT serialized shape of the {@code POST /match/live/{key}/advance}
 * response, field by field, so the Faza 2 delta contract is forced to be an opt-in,
 * separate representation instead of an in-place change. Concretely: if
 * {@code ambientSegments} (or any other new property) is ever added to
 * {@link LiveMatchData}, {@link #advanceResponse_exposesExactlyTheLegacyTopLevelFields()}
 * fails — which is the whole point of the gate.
 *
 * <p>Field-absence rules are pinned too: {@code canonicalAnimations},
 * {@code homeFormation}/{@code awayFormation} and {@code homeXg}/{@code awayXg} are
 * {@code NON_NULL} and must stay ABSENT from the flag-off payload.
 */
class LiveAdvanceLegacySerializationCharacterizationTest {

    /** Every property the legacy /advance + /state payload is allowed to carry today. */
    private static final Set<String> ALLOWED_TOP_LEVEL = Set.of(
            "goalAnimations", "canonicalAnimations",
            "homeTeamId", "awayTeamId", "homeTeamName", "awayTeamName",
            "homeFormation", "awayFormation", "competitionName", "competitionId", "round",
            "timeline",
            "homeScore", "awayScore", "homePossession", "awayPossession",
            "homeShots", "awayShots", "homeShotsOnTarget", "awayShotsOnTarget",
            "homeXg", "awayXg", "homeCorners", "awayCorners", "homeFouls", "awayFouls",
            "homeYellowCards", "awayYellowCards", "homeRedCards", "awayRedCards",
            "homeOffsides", "awayOffsides", "firstHalfStoppage", "secondHalfStoppage",
            "staminaSnapshots",
            "currentMinute", "finished", "awaitingCommit",
            "homeSubsRemaining", "awaySubsRemaining",
            "homePitch", "awayPitch", "homeBench", "awayBench");

    /** Properties every /advance response must always carry (no NON_NULL exemption). */
    private static final Set<String> ALWAYS_PRESENT = Set.of(
            "homeTeamId", "awayTeamId", "homeTeamName", "awayTeamName",
            "competitionName", "competitionId", "round", "timeline",
            "homeScore", "awayScore", "homePossession", "awayPossession",
            "homeShots", "awayShots", "homeShotsOnTarget", "awayShotsOnTarget",
            "homeCorners", "awayCorners", "homeFouls", "awayFouls",
            "homeYellowCards", "awayYellowCards", "homeRedCards", "awayRedCards",
            "homeOffsides", "awayOffsides", "firstHalfStoppage", "secondHalfStoppage",
            "currentMinute", "finished", "awaitingCommit",
            "homeSubsRemaining", "awaySubsRemaining",
            "homePitch", "awayPitch", "homeBench", "awayBench");

    private static final Set<String> TIMELINE_FIELDS = Set.of(
            "minute", "homeScore", "awayScore", "eventType", "commentary",
            "playerName", "playerId", "teamId", "teamName");

    private static final Set<String> PITCH_PLAYER_FIELDS = Set.of(
            "playerId", "name", "position", "stamina", "minutesPlayed",
            "onPitch", "yellowCardMinute", "redCardMinute");

    @Test
    void advanceResponse_exposesExactlyTheLegacyTopLevelFields() throws Exception {
        Faza2GateHarness h = new Faza2GateHarness();
        LiveMatchSession session = h.start();
        LiveMatchData data = session.advanceUntilAndSnapshot(40);

        Set<String> actual = fieldNames(h.mapper.valueToTree(data));
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(ALLOWED_TOP_LEVEL);
        assertTrue(unexpected.isEmpty(),
                "the legacy /advance serialization must not grow new properties while the delta "
                        + "contract is opt-in — new fields found: " + unexpected);
        Set<String> missing = new TreeSet<>(ALWAYS_PRESENT);
        missing.removeAll(actual);
        assertTrue(missing.isEmpty(), "legacy properties disappeared from /advance: " + missing);
    }

    @Test
    void nestedTimelineAndPitchShapes_areUnchanged() throws Exception {
        Faza2GateHarness h = new Faza2GateHarness();
        LiveMatchSession session = h.start();
        LiveMatchData data = session.advanceUntilAndSnapshot(40);
        JsonNode root = h.mapper.valueToTree(data);

        assertTrue(root.get("timeline").size() > 0, "the fixture produced timeline entries");
        for (JsonNode entry : root.get("timeline")) {
            assertEquals(TIMELINE_FIELDS, fieldNames(entry), "LiveMatchMinute shape is frozen");
        }
        for (String pitch : List.of("homePitch", "awayPitch", "homeBench", "awayBench")) {
            for (JsonNode player : root.get(pitch)) {
                assertEquals(PITCH_PLAYER_FIELDS, fieldNames(player),
                        "PlayerStaminaInfo shape is frozen (" + pitch + ")");
            }
        }
    }

    @Test
    void matchPlanFlagOff_behavesExactlyAsBefore_andOmitsCanonicalOnlyFields() throws Exception {
        Faza2GateHarness h = new Faza2GateHarness();
        h.engineConfig.getMatchPlan().setEnabled(false);

        LiveMatchSession session = h.start();
        assertFalse(session.isCanonicalPlanBound(), "flag off → no canonical plan is bound");
        LiveMatchData data = session.advanceUntilAndSnapshot(session.getTotalMinutes());

        assertNull(data.getCanonicalAnimations(), "no canonical animation boundary when flag off");
        String json = h.mapper.writeValueAsString(data);
        assertFalse(json.contains("canonicalAnimations"),
                "the NON_NULL property stays absent from the legacy payload");
        assertNull(h.checkpointJson(), "flag off → no canonical checkpoint is persisted");
        assertEquals(0, h.resolveCalls.size(), "flag off → no canonical slot resolution");
        assertEquals(Faza2GateHarness.TARGET_HOME, data.getHomeScore(),
                "the pinned scoreline still lands through the legacy path");
        assertEquals(Faza2GateHarness.TARGET_AWAY, data.getAwayScore());

        Set<String> actual = fieldNames(h.mapper.valueToTree(data));
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(ALLOWED_TOP_LEVEL);
        assertTrue(unexpected.isEmpty(), "flag-off payload grew new properties: " + unexpected);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) names.add(it.next());
        return names;
    }
}
