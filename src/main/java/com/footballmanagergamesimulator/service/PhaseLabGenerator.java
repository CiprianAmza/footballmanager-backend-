package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.LiveMatchData.LiveMatchMinute;
import com.footballmanagergamesimulator.frontend.MatchPhaseData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic phase generation for the Phase Lab: synthetic elevens + a
 * synthetic terminal event → one presentation phase. Same tuple → the exact
 * same phase, which is what lets ratings be regenerated into feature vectors
 * after a restart.
 */
@Service
public class PhaseLabGenerator {

    /** Real in-game position codes — MUST match the frontend formation
     *  vocabulary (normalisePosition), or players get parked in midfield. */
    public static final String[] ROLES = {
            "GK", "DL", "DC", "DC", "DR", "ML", "MC", "MC", "MR", "ST", "ST" };

    private static final String[] SURNAMES = {
            "Ionescu", "Popa", "Dumitru", "Stancu", "Marin", "Radu", "Petrescu",
            "Diaconu", "Vlad", "Enache", "Tudor", "Barbu", "Nistor", "Sava",
            "Costea", "Iancu", "Preda", "Matei", "Olaru", "Voinea", "Lupu", "Neagu" };

    @Autowired
    MatchPhaseEngine phaseEngine;

    public MatchPhaseData buildPhase(String strategy, String scenario, String outcome, long seed) {
        Random rosterRng = new Random(seed ^ 0x5DEECE66DL);
        List<MatchPhaseEngine.PhasePlayer> team1 = syntheticTeam(100, rosterRng);
        List<MatchPhaseEngine.PhasePlayer> team2 = syntheticTeam(200, rosterRng);
        List<LiveMatchMinute> events = new ArrayList<>();
        if (!"possession".equals(outcome)) {
            LiveMatchMinute event = new LiveMatchMinute();
            event.setMinute(30);
            event.setEventType(outcome);
            event.setTeamId(1);
            events.add(event);
        }
        MatchPhaseEngine.MinuteContext ctx = new MatchPhaseEngine.MinuteContext(
                30, true, true, 1, 2, team1, team2, events, false,
                null, null, null, seed);
        return phaseEngine.buildScenarioPhase(ctx, strategy, scenario);
    }

    /** Two believable elevens: every attribute rolls 8-17, with the roles the
     *  scenarios lean on (winger crossing, striker heading/pace, playmaker
     *  vision) guaranteed a high floor. */
    private List<MatchPhaseEngine.PhasePlayer> syntheticTeam(long baseId, Random rng) {
        List<MatchPhaseEngine.PhasePlayer> team = new ArrayList<>();
        for (int i = 0; i < ROLES.length; i++) {
            String role = ROLES[i];
            String name = SURNAMES[rng.nextInt(SURNAMES.length)] + " "
                    + (char) ('A' + rng.nextInt(26)) + ".";
            int passing = roll(rng), vision = roll(rng), pace = roll(rng);
            int dribbling = roll(rng), flair = roll(rng), crossing = roll(rng), heading = roll(rng);
            if (role.startsWith("M") && (role.endsWith("L") || role.endsWith("R"))) {
                crossing = Math.max(crossing, 13);
                dribbling = Math.max(dribbling, 13);
                flair = Math.max(flair, 12);
                pace = Math.max(pace, 13);
            }
            if (role.startsWith("ST")) {
                heading = Math.max(heading, 13);
                pace = Math.max(pace, 13);
            }
            if ("MC".equals(role)) { passing = Math.max(passing, 12); vision = Math.max(vision, 12); }
            team.add(new MatchPhaseEngine.PhasePlayer(baseId + i + 1, name, role,
                    passing, vision, pace, dribbling, flair, crossing, heading));
        }
        return team;
    }

    private static int roll(Random rng) {
        return 8 + rng.nextInt(10);
    }
}
