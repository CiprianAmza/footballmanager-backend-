package com.footballmanagergamesimulator.tacticarena;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.tacticarena.TacticArenaStats.MatchLine;
import com.footballmanagergamesimulator.tacticarena.TacticArenaStats.Results;
import com.footballmanagergamesimulator.tacticarena.TacticArenaStats.RunTotals;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** CLUB category, per-test engine, settings and per-run seeds: pure logic, no Spring context. */
class TacticArenaClubTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void engineFromRequestOrFromTheGame() {
        assertEquals("2d-human", TacticArenaService.engineFor(null, "all"));
        assertEquals("2d-human", TacticArenaService.engineFor(" ", "human"));
        assertEquals("classic", TacticArenaService.engineFor(null, "off"));
        assertEquals("classic", TacticArenaService.engineFor("classic", "all"));
        assertEquals("2d-human", TacticArenaService.engineFor("2d", "off"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> TacticArenaService.engineFor("2d-human", "off"));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    void repeatsDefaultAndRange() {
        assertEquals(3, TacticArenaService.repeatsFor(null));
        assertEquals(1, TacticArenaService.repeatsFor(1));
        assertEquals(10, TacticArenaService.repeatsFor(10));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> TacticArenaService.repeatsFor(0)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> TacticArenaService.repeatsFor(11)).getStatusCode().value());
    }

    @Test
    void settingsHaveAll16KeysWithDefaults() throws Exception {
        Map<String, Object> s = TacticArenaService.settings(
                mapper.readTree("{\"tactic\":\"4231\",\"pressing\":\"Aggressive\",\"width\":null,\"tempo\":\"\"}"), "442");
        assertEquals(16, s.size());
        assertEquals(List.of("tactic", "mentality", "inPossession", "passingType", "tempo", "timeWasting", "defensiveLine",
                "pressing", "width", "dribbling", "foulFrequency", "foulHardness", "tempoFragmentation", "widePlay",
                "transition", "recovery"), List.copyOf(s.keySet()));
        assertEquals("4231", s.get("tactic"));
        assertEquals("Aggressive", s.get("pressing"));
        assertEquals("Balanced", s.get("width"));
        assertEquals("Standard", s.get("tempo"));
        assertEquals("Sometimes", s.get("timeWasting"));
        assertEquals("Shoot", s.get("widePlay"));

        Map<String, Object> old = TacticArenaService.settings(null, "433");
        assertEquals(16, old.size());
        assertEquals("433", old.get("tactic"));
        assertEquals("Medium", old.get("foulHardness"));
        assertEquals("442", TacticArenaService.settings(null, null).get("tactic"));
    }

    @Test
    void repeatRunsAreLabelledByIndex() {
        TacticArenaRun run = new TacticArenaRun();
        run.setKind(TacticArenaEntities.KIND_REPEAT);
        run.setRunIndex(2);
        assertEquals("Season run 2", TacticArenaService.runLabel(run));
        assertNull(TacticArenaService.tier(run));
        run.setKind(TacticArenaEntities.KIND_CHOSEN);
        run.setRunIndex(0);
        assertEquals("Chosen club", TacticArenaService.runLabel(run));
    }

    @Test
    void onlyRepeatRunsGetTheirOwnSalt() {
        assertEquals(0, TacticArenaService.seedSalt(TacticArenaEntities.KIND_CHOSEN, 0));
        assertEquals(0, TacticArenaService.seedSalt(TacticArenaEntities.KIND_TOP, 1));
        assertEquals(0, TacticArenaService.seedSalt(TacticArenaEntities.KIND_DIAMOND, 3));
        Set<Long> salts = new HashSet<>();
        for (int i = 1; i <= TacticArenaService.MAX_REPEATS; i++) {
            long salt = TacticArenaService.seedSalt(TacticArenaEntities.KIND_REPEAT, i);
            assertNotEquals(0, salt);
            salts.add(salt);
        }
        assertEquals(TacticArenaService.MAX_REPEATS, salts.size());
    }

    @Test
    void saltLeavesGameSeedsAloneAtZeroAndChangesThemOtherwise() {
        long before = MatchPlanService.seedSalt();
        try {
            MatchPlanService.setSeedSalt(0);
            long plain = MatchPlanService.seedFor("CTIM:42", 16, 1, 3, 104, 87);
            assertEquals(42L, MatchPlanService.salted(42L));
            Set<Long> seeds = new HashSet<>();
            seeds.add(plain);
            for (int i = 1; i <= 3; i++) {
                MatchPlanService.setSeedSalt(TacticArenaService.seedSalt(TacticArenaEntities.KIND_REPEAT, i));
                long salted = MatchPlanService.seedFor("CTIM:42", 16, 1, 3, 104, 87);
                assertEquals(salted, MatchPlanService.seedFor("CTIM:42", 16, 1, 3, 104, 87), "stable within a run");
                seeds.add(salted);
            }
            assertEquals(4, seeds.size());
        } finally {
            MatchPlanService.setSeedSalt(before);
        }
    }

    @Test
    void repeatsAreAveragedWithTheClubsOwnLeagueMaximum() {
        // Three seasons of a 4-match league: 12, 6 and 3 points.
        RunTotals a = TacticArenaStats.totals(List.of(win(), win(), win(), win()));
        RunTotals b = TacticArenaStats.totals(List.of(win(), win(), loss(), loss()));
        RunTotals c = TacticArenaStats.totals(List.of(win(), loss(), loss(), loss()));
        Results r = TacticArenaStats.average(List.of(a, b, c));
        assertEquals(3, r.basedOn());
        assertEquals(12, r.overall().pld());
        assertEquals(7, r.overall().pts());
        assertEquals(12, r.overall().ptsMax());
        assertEquals(58.3, r.overall().ptsPct());
    }

    private static MatchLine win() {
        return new MatchLine(true, 2, 0, null, null);
    }

    private static MatchLine loss() {
        return new MatchLine(false, 0, 1, null, null);
    }
}
