package com.footballmanagergamesimulator.animation;

import com.footballmanagergamesimulator.animation.pattern.PatternLibrary;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

/** WBL/WBR have distinct lateral anchors, the wing-back group, and wide eligibility. */
class AnimationWingBackTest {
    private static final long HOME = 10;
    private static final long AWAY = 20;
    private final AnimationDirector director = new AnimationDirector();
    private final AnimationInvariantValidator validator = new AnimationInvariantValidator(AnimationPhysicsProfile.defaults());

    private static PlayerSnapshot player(long id, long team, String pos) {
        return new PlayerSnapshot(id, team, "P" + id, (int) (id % 99), pos, "ROLE_" + pos, 62);
    }

    /** A team with both wing-backs present, plus a WBL assister. Scorer is 1011 (ST). */
    private List<PlayerSnapshot> homeWithWingBacks() {
        String[] pos = {"GK", "DC", "DC", "WBL", "WBR", "DM", "MC", "MC", "AMC", "AML", "ST"};
        List<PlayerSnapshot> home = new ArrayList<>();
        for (int i = 0; i < pos.length; i++) home.add(player(HOME * 100 + i + 1, HOME, pos[i]));
        return home;
    }

    private List<PlayerSnapshot> standardAway() {
        String[] pos = {"GK", "DL", "DC", "DC", "DR", "DM", "MC", "ML", "MR", "AMC", "ST"};
        List<PlayerSnapshot> away = new ArrayList<>();
        for (int i = 0; i < pos.length; i++) away.add(player(AWAY * 100 + i + 1, AWAY, pos[i]));
        return away;
    }

    private static int indexOf(List<PlayerSnapshot> players, long id) {
        for (int i = 0; i < players.size(); i++) if (players.get(i).playerId() == id) return i;
        return -1;
    }

    private MatchMomentSpec spec(Long assist) {
        List<PlayerSnapshot> players = new ArrayList<>(homeWithWingBacks());
        players.addAll(standardAway());
        return new MatchMomentSpec("CTIM:WB", 0, 5L, AnimationDirector.CURRENT_GENERATOR_VERSION,
                30, 2, MatchPeriod.FIRST_HALF, HOME, AWAY, HOME, AnimationPhase.OPEN_PLAY,
                AnimationOutcome.GOAL, HOME * 100 + 11, assist, players, null);
    }

    @Test void wingBacksDoNotOccupyTheSameAnchor() {
        AnimationReplay replay = director.direct(spec(null)).replay();
        int wbl = indexOf(replay.players(), HOME * 100 + 4);
        int wbr = indexOf(replay.players(), HOME * 100 + 5);
        PitchPoint wblAt = replay.frames().get(0).positions().get(wbl);
        PitchPoint wbrAt = replay.frames().get(0).positions().get(wbr);
        assertTrue(wblAt.distanceTo(wbrAt) > 40, "wing-backs overlapped at " + wblAt + " / " + wbrAt);
        // They sit on opposite flanks.
        assertTrue(Math.abs(wblAt.y() - wbrAt.y()) > 40);
    }

    @Test void noTwoTeammatesShareAStartingCoordinate() {
        AnimationReplay replay = director.direct(spec(HOME * 100 + 4)).replay();
        List<PitchPoint> home = new ArrayList<>();
        for (int i = 0; i < replay.players().size(); i++)
            if (replay.players().get(i).teamId() == HOME) home.add(replay.frames().get(0).positions().get(i));
        for (int i = 0; i < home.size(); i++)
            for (int j = i + 1; j < home.size(); j++)
                assertTrue(home.get(i).distanceTo(home.get(j)) > 0.5,
                        "overlap " + home.get(i) + " / " + home.get(j));
    }

    @Test void wingBackDeliversTheAssistWhenItIsTheCanonicalAssister() {
        // A left wing-back as the canonical assister always plays the final pass into the scorer,
        // whether a wide pattern or the safe fallback is chosen.
        for (int slot = 0; slot < 6; slot++) {
            List<PlayerSnapshot> players = new ArrayList<>(homeWithWingBacks());
            players.addAll(standardAway());
            MatchMomentSpec spec = new MatchMomentSpec("CTIM:WB" + slot, slot, 5L,
                    AnimationDirector.CURRENT_GENERATOR_VERSION, 30, 2, MatchPeriod.FIRST_HALF, HOME, AWAY, HOME,
                    AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, HOME * 100 + 11, HOME * 100 + 4, players, null);
            AnimationReplay replay = director.direct(spec).replay();
            assertTrue(validator.validate(replay, spec).isEmpty());
            AnimationEvent lastPass = null;
            for (AnimationEvent e : replay.events()) if ("PASS".equals(e.type())) lastPass = e;
            assertNotNull(lastPass, "assisted goal must contain the assist pass");
            assertEquals(HOME * 100 + 4, lastPass.fromPlayerId(), "WBL should deliver the assist");
            assertEquals(HOME * 100 + 11, lastPass.toPlayerId());
        }
    }

    @Test void directorAnimatesWingBackRostersForEveryOutcome() {
        for (AnimationOutcome outcome : AnimationOutcome.values()) for (Long assist : new Long[]{HOME * 100 + 4, null}) {
            List<PlayerSnapshot> players = new ArrayList<>(homeWithWingBacks());
            players.addAll(standardAway());
            MatchMomentSpec spec = new MatchMomentSpec("CTIM:WB2", 0, 5L,
                    AnimationDirector.CURRENT_GENERATOR_VERSION, 30, 2, MatchPeriod.FIRST_HALF, HOME, AWAY, HOME,
                    AnimationPhase.OPEN_PLAY, outcome, HOME * 100 + 11, assist, players, null);
            assertTrue(validator.validate(director.direct(spec).replay(), spec).isEmpty());
        }
    }
}
