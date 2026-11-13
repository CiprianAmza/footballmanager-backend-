package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.Analyst;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchReactionArticleTest {

    @Test
    @DisplayName("""
            GIVEN the scores 3-0, 2-1, 2-2, 1-1, 0-0, 1-2 and 1-4 from the club's side
            WHEN the result is read
            THEN they are a big win, a win, a high draw, a draw, a draw, a loss and a big loss; the outcome ignores
             the margin (WIN, DRAW, LOSS) and the spoken score puts the higher number first ("4-1")
            """)
    void scoreSetsTheResult() {
        assertEquals(MatchReactionArticle.Result.BIG_WIN, MatchReactionArticle.result(3, 0));
        assertEquals(MatchReactionArticle.Result.WIN, MatchReactionArticle.result(2, 1));
        assertEquals(MatchReactionArticle.Result.HIGH_DRAW, MatchReactionArticle.result(2, 2));
        assertEquals(MatchReactionArticle.Result.DRAW, MatchReactionArticle.result(1, 1));
        assertEquals(MatchReactionArticle.Result.DRAW, MatchReactionArticle.result(0, 0));
        assertEquals(MatchReactionArticle.Result.LOSS, MatchReactionArticle.result(1, 2));
        assertEquals(MatchReactionArticle.Result.BIG_LOSS, MatchReactionArticle.result(1, 4));
        assertEquals("WIN", MatchReactionArticle.Result.BIG_WIN.outcome());
        assertEquals("DRAW", MatchReactionArticle.Result.HIGH_DRAW.outcome());
        assertEquals("LOSS", MatchReactionArticle.Result.BIG_LOSS.outcome());
        assertEquals("4-1", MatchReactionArticle.score(1, 4));
        assertEquals("2-2", MatchReactionArticle.score(2, 2));
    }

    @Test
    @DisplayName("""
            GIVEN a balanced reporter of average stubbornness
            WHEN he writes about a manager he is neutral to (50), one he has fallen out with (30), at the club he
             supports and at a rival of it
            THEN his tone is balanced, critic, supportive and critic; the closing paragraph after a 0-3 defeat by a
             critic comes from LOSS_CRITIC
            """)
    void toneFollowsRelationAndSympathy() {
        Analyst reporter = new Analyst();
        reporter.setHarshness(50);

        assertEquals(Analyst.Temperament.BALANCED,
                MatchReactionArticle.tone(reporter, 50, AppointmentArticle.Affinity.NONE));
        assertEquals(Analyst.Temperament.CRITIC,
                MatchReactionArticle.tone(reporter, 30, AppointmentArticle.Affinity.NONE));
        assertEquals(Analyst.Temperament.SUPPORTIVE,
                MatchReactionArticle.tone(reporter, 50, AppointmentArticle.Affinity.FAVORITE));
        assertEquals(Analyst.Temperament.CRITIC,
                MatchReactionArticle.tone(reporter, 50, AppointmentArticle.Affinity.RIVAL));
        assertEquals("LOSS_CRITIC", MatchReactionArticle.talkingPointBucket(
                MatchReactionArticle.Result.BIG_LOSS, Analyst.Temperament.CRITIC));
    }

    @Test
    @DisplayName("""
            GIVEN match numbers (possession, shots, on target, opponent shots, opponent on target)
             of 40/8/5/12/3, 55/9/4/11/4, 50/10/4/10/4 and 49/10/4/10/4
            WHEN we ask who had the better of them
            THEN the club did with more on target; level on target, the opponent with more shots; all level, the
             club with half of the ball and the opponent with less; a win while second best reads WIN_SECOND_BEST
            """)
    void numbersDecideWhoWasOnTop() {
        assertTrue(MatchReactionArticle.onTop(new MatchReactionArticle.Numbers(40, 8, 5, 12, 3)));
        assertFalse(MatchReactionArticle.onTop(new MatchReactionArticle.Numbers(55, 9, 4, 11, 4)));
        assertTrue(MatchReactionArticle.onTop(new MatchReactionArticle.Numbers(50, 10, 4, 10, 4)));
        assertFalse(MatchReactionArticle.onTop(new MatchReactionArticle.Numbers(49, 10, 4, 10, 4)));
        assertEquals("WIN_SECOND_BEST", MatchReactionArticle.statsBucket(
                MatchReactionArticle.Result.WIN, new MatchReactionArticle.Numbers(55, 9, 4, 11, 4)));
        assertEquals("LOSS_ON_TOP", MatchReactionArticle.statsBucket(
                MatchReactionArticle.Result.BIG_LOSS, new MatchReactionArticle.Numbers(40, 8, 5, 12, 3)));
    }

    @Test
    @DisplayName("""
            GIVEN reporters of verbosity 1, 2 and 3
            WHEN the article is laid out, without and with the match numbers
            THEN it has the verdict; the verdict and the talking point; two verdicts and the talking point; the
             numbers, when quoted, come before the talking point; a kicker goes in front of the headline
            """)
    void verbosityAndNumbersLayOutTheArticle() {
        assertEquals(List.of("VERDICT"), MatchReactionArticle.sections(1, false));
        assertEquals(List.of("VERDICT", "TALKING_POINT"), MatchReactionArticle.sections(2, false));
        assertEquals(List.of("VERDICT", "VERDICT", "TALKING_POINT"), MatchReactionArticle.sections(3, false));
        assertEquals(List.of("VERDICT", "STATS"), MatchReactionArticle.sections(1, true));
        assertEquals(List.of("VERDICT", "VERDICT", "STATS", "TALKING_POINT"), MatchReactionArticle.sections(3, true));
        assertEquals("MELTDOWN: Orbit FC torn apart", MatchReactionArticle.headline("MELTDOWN", "Orbit FC torn apart"));
        assertEquals("Orbit FC torn apart", MatchReactionArticle.headline(null, "Orbit FC torn apart"));
    }
}
