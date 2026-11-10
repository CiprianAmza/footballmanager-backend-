package com.footballmanagergamesimulator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.frontend.LiveMatchData.LiveMatchMinute;
import com.footballmanagergamesimulator.frontend.MatchPhaseData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhaseTemplateServiceTest {
    @TempDir Path temp;

    @Test
    void savesAndReplaysThreePassesAndAShotWithoutChangingTheMatchOutcome() throws Exception {
        Path file = temp.resolve("templates.json");
        PhaseTemplateService templates = new PhaseTemplateService(new ObjectMapper(), file.toString());
        templates.load();
        PhaseTemplateService.Template saved = templates.save(new PhaseTemplateService.Draft(
                "Right wing break", List.of(4, 6, 8, 9), List.of(
                        new PhaseTemplateService.Point(25, 86),
                        new PhaseTemplateService.Point(47, 60),
                        new PhaseTemplateService.Point(67, 82),
                        new PhaseTemplateService.Point(81, 51))));

        List<MatchPhaseEngine.PhasePlayer> reorderedAttack = players(100);
        List<MatchPhaseEngine.PhasePlayer> reorderedDefence = players(200);
        Collections.reverse(reorderedAttack);
        Collections.reverse(reorderedDefence);
        MatchPhaseData preview = templates.preview(saved, reorderedAttack, reorderedDefence, "shot_saved", 42);
        assertEquals("shot_saved", preview.getEndEvent());
        assertEquals(List.of("PASS", "PASS", "PASS", "SHOT", "SAVE"),
                preview.getActions().stream().map(action -> action.getType()).toList());
        assertEquals(105, preview.getActions().get(0).getPlayerId());
        assertEquals(107, preview.getActions().get(0).getTargetPlayerId());
        assertEquals(110, preview.getActions().get(3).getPlayerId());
        assertEquals(201, preview.getActions().get(4).getPlayerId());
        assertEquals(preview.getActions().get(1).getX(),
                templates.preview(saved, players(100), players(200), "shot_saved", 43)
                        .getActions().get(1).getX());
        assertEquals(81.0, preview.getActions().get(3).getX());

        PhaseTemplateService restored = new PhaseTemplateService(new ObjectMapper(), file.toString());
        restored.load();
        assertEquals(saved, restored.all().get(0));
        restored.delete(saved.id());
        assertTrue(restored.all().isEmpty());
    }

    @Test
    void liveReplayKeepsTheRealScorerAndRemapsEarlierPasses() throws Exception {
        PhaseTemplateService templates = new PhaseTemplateService(new ObjectMapper(),
                temp.resolve("live.json").toString());
        templates.load();
        templates.save(new PhaseTemplateService.Draft("Break", List.of(4, 6, 8, 9), List.of(
                new PhaseTemplateService.Point(25, 86), new PhaseTemplateService.Point(47, 60),
                new PhaseTemplateService.Point(67, 82), new PhaseTemplateService.Point(81, 51))));
        LiveMatchMinute shot = new LiveMatchMinute();
        shot.setEventType("goal"); shot.setTeamId(1); shot.setPlayerId(110);
        MatchPhaseData accepted = null;
        for (int seed = 0; seed < 100 && accepted == null; seed++) {
            accepted = templates.live(new MatchPhaseEngine.MinuteContext(30, true, true, 1, 2,
                    players(100), players(200), List.of(shot), false,
                    null, null, null, seed));
        }
        assertNotNull(accepted);
        assertEquals("goal", accepted.getEndEvent());
        assertEquals(110, accepted.getActions().get(3).getPlayerId());
        assertEquals("GOAL", accepted.getActions().get(4).getType());

        shot.setPlayerId(105); // a player in a saved passing role may be the real scorer
        MatchPhaseData remapped = null;
        for (int seed = 0; seed < 100 && remapped == null; seed++) {
            remapped = templates.live(new MatchPhaseEngine.MinuteContext(30, true, true, 1, 2,
                    players(100), players(200), List.of(shot), false,
                    null, null, null, seed));
        }
        assertNotNull(remapped);
        assertEquals(105, remapped.getActions().get(3).getPlayerId());
        assertEquals(4, remapped.getActions().stream().limit(4)
                .map(action -> action.getPlayerId()).distinct().count());
    }

    @Test
    void supportsExtraPassesAndAReturnToAnEarlierPlayer() throws Exception {
        PhaseTemplateService templates = new PhaseTemplateService(new ObjectMapper(),
                temp.resolve("return-pass.json").toString());
        templates.load();
        PhaseTemplateService.Template saved = templates.save(new PhaseTemplateService.Draft(
                "Recycle and finish", List.of(4, 6, 8, 6, 8, 9), List.of(
                        new PhaseTemplateService.Point(25, 80),
                        new PhaseTemplateService.Point(44, 60),
                        new PhaseTemplateService.Point(60, 80),
                        new PhaseTemplateService.Point(49, 62),
                        new PhaseTemplateService.Point(70, 77),
                        new PhaseTemplateService.Point(82, 50))));

        MatchPhaseData preview = templates.preview(saved, players(100), players(200), "goal", 7);
        assertEquals(List.of("PASS", "PASS", "PASS", "PASS", "PASS", "SHOT", "GOAL"),
                preview.getActions().stream().map(action -> action.getType()).toList());
        assertEquals(preview.getActions().get(1).getPlayerId(), preview.getActions().get(3).getPlayerId());
        assertEquals(preview.getActions().get(2).getPlayerId(), preview.getActions().get(4).getPlayerId());
        assertTrue(preview.getActions().get(2).getEndX() < preview.getActions().get(2).getX());

        LiveMatchMinute goal = new LiveMatchMinute();
        goal.setEventType("goal"); goal.setTeamId(1); goal.setPlayerId(107);
        MatchPhaseData live = null;
        for (int seed = 0; seed < 100 && live == null; seed++) {
            live = templates.live(new MatchPhaseEngine.MinuteContext(30, true, true, 1, 2,
                    players(100), players(200), List.of(goal), false,
                    null, null, null, seed));
        }
        assertNotNull(live);
        assertEquals(107, live.getActions().get(5).getPlayerId());
    }

    @Test
    void acceptsShortAndLongPassesWithinThePitch() throws Exception {
        PhaseTemplateService templates = new PhaseTemplateService(new ObjectMapper(),
                temp.resolve("pass-distance.json").toString());
        templates.load();
        PhaseTemplateService.Template saved = templates.save(new PhaseTemplateService.Draft(
                "Switch and layoff", List.of(4, 6, 9), List.of(
                        new PhaseTemplateService.Point(3, 5),
                        new PhaseTemplateService.Point(94, 95),
                        new PhaseTemplateService.Point(93, 94))));
        MatchPhaseData preview = templates.preview(saved, players(100), players(200), "goal", 4);
        assertEquals(List.of("PASS", "PASS", "SHOT", "GOAL"),
                preview.getActions().stream().map(action -> action.getType()).toList());
    }

    @Test
    void dribbleAndRunKeepTheSamePlayerBeforeTheShot() throws Exception {
        PhaseTemplateService templates = new PhaseTemplateService(new ObjectMapper(),
                temp.resolve("carry.json").toString());
        templates.load();
        PhaseTemplateService.Template saved = templates.save(new PhaseTemplateService.Draft(
                "Carry and finish", List.of(6, 6, 6, 9), List.of(
                        new PhaseTemplateService.Point(45, 20),
                        new PhaseTemplateService.Point(53, 25),
                        new PhaseTemplateService.Point(70, 32),
                        new PhaseTemplateService.Point(84, 40)),
                List.of("DRIBBLE", "SPRINT_DRIBBLE", "PASS")));
        MatchPhaseData preview = templates.preview(saved, players(100), players(200), "goal", 2);
        assertEquals(List.of("DRIBBLE", "SPRINT_DRIBBLE", "PASS", "SHOT", "GOAL"),
                preview.getActions().stream().map(action -> action.getType()).toList());
        assertEquals(preview.getActions().get(0).getPlayerId(), preview.getActions().get(1).getPlayerId());
        assertEquals(preview.getActions().get(1).getPlayerId(), preview.getActions().get(2).getPlayerId());
        assertEquals(0, preview.getActions().get(0).getTargetPlayerId());
        assertEquals(84.0, preview.getActions().get(3).getX());
    }

    @Test
    void migratesOldVerticalCoordinatesOnceWithoutChangingTheDrawnRoute() throws Exception {
        Path file = temp.resolve("legacy.json");
        Files.writeString(file, """
                [{"id":"old","name":"Wing","slots":[4,9],
                  "points":[{"x":25,"y":86},{"x":84,"y":50}]}]
                """);
        PhaseTemplateService first = new PhaseTemplateService(new ObjectMapper(), file.toString());
        first.load();
        assertEquals(14.0, first.all().get(0).points().get(0).y());
        assertEquals(List.of("PASS"), first.all().get(0).moves());
        assertEquals("TOP_DOWN", first.all().get(0).yAxis());
        PhaseTemplateService second = new PhaseTemplateService(new ObjectMapper(), file.toString());
        second.load();
        assertEquals(14.0, second.all().get(0).points().get(0).y());
    }

    private static List<MatchPhaseEngine.PhasePlayer> players(int base) {
        List<MatchPhaseEngine.PhasePlayer> result = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            result.add(new MatchPhaseEngine.PhasePlayer(base + i + 1, "Player " + i,
                    PhaseLabGenerator.ROLES[i], 12, 12, 12, 12, 12, 12, 12));
        }
        return result;
    }
}
