package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.matchplan.Contributor;
import com.footballmanagergamesimulator.matchplan.Lineup;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.MatchEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatchInjuryContextTest {

    @Test
    void injuredPlayerWhoTookPartGetsAnEventAtHisLastMinuteOnThePitch() {
        // Home: 11 starters (1-11), 11 off for 12 at 60', 13 stays on the bench.
        Lineup home = lineup(1, List.of(new Lineup.SubMove(0, 60, 11, contributor(12))), contributor(12), contributor(13));
        Lineup away = lineup(21, List.of());
        MatchInjuryContext match = MatchInjuryContext.of(3, 16, 20, 86, 12, home, away, 90);

        MatchEvent finisher = match.eventFor(86, player(1, "Iripus"), injury("Hamstring Strain", "Minor"));
        assertThat(finisher.getEventType()).isEqualTo("injury");
        assertThat(finisher.getMinute()).isEqualTo(90);
        assertThat(finisher.getAddedTime()).isNull();
        assertThat(finisher.getPlayerId()).isEqualTo(1);
        assertThat(finisher.getPlayerName()).isEqualTo("Iripus");
        assertThat(finisher.getTeamId()).isEqualTo(86);
        assertThat(finisher.getDetails()).isEqualTo("Hamstring Strain (Minor)");
        assertThat(List.of(finisher.getCompetitionId(), (long) finisher.getSeasonNumber(),
                (long) finisher.getRoundNumber(), finisher.getTeamId1(), finisher.getTeamId2()))
                .containsExactly(3L, 16L, 20L, 86L, 12L);
        assertThat(finisher.getFixtureKey()).isNull();

        assertThat(match.eventFor(86, player(11, "Off"), injury("Ankle Sprain", "Serious")).getMinute()).isEqualTo(60);
        assertThat(match.eventFor(86, player(12, "On"), injury("Ankle Sprain", null)).getMinute()).isEqualTo(90);
        assertThat(match.eventFor(86, player(12, "On"), injury("Ankle Sprain", null)).getDetails()).isEqualTo("Ankle Sprain");
        assertThat(match.eventFor(12, player(25, "Away"), injury("Concussion", "Moderate")).getMinute()).isEqualTo(90);
    }

    @Test
    void unusedSubstitutesAndTheRestOfTheSquadGetNoEvent() {
        Lineup home = lineup(1, List.of(), contributor(12));
        MatchInjuryContext match = MatchInjuryContext.of(3, 16, 20, 86, 12, home, null, 90);

        assertThat(match.eventFor(86, player(12, "Bench"), injury("Knee Ligament", "Minor"))).isNull();
        assertThat(match.eventFor(86, player(40, "Reserve"), injury("Knee Ligament", "Minor"))).isNull();
        assertThat(match.eventFor(86, null, injury("Knee Ligament", "Minor"))).isNull();
        assertThat(match.eventFor(86, player(1, "Starter"), null)).isNull();
    }

    @Test
    void afterExtraTimeAFinisherIsPlacedAtTheLastMinute() {
        MatchInjuryContext match = MatchInjuryContext.of(3, 16, 20, 86, 12, lineup(1, List.of()), null, 120);
        assertThat(match.eventFor(86, player(5, "Finisher"), injury("Muscle Fatigue", "Minor")).getMinute()).isEqualTo(120);
    }

    private static Lineup lineup(long firstId, List<Lineup.SubMove> subs, Contributor... bench) {
        List<Contributor> xi = new ArrayList<>();
        for (long id = firstId; id < firstId + 11; id++) xi.add(contributor(id));
        return new Lineup(xi, List.of(bench), subs);
    }

    private static Contributor contributor(long id) {
        return new Contributor(id, "P" + id, "MC", 15.0, 15, 15, 15, 100.0, false, false);
    }

    private static Human player(long id, String name) {
        Human human = new Human();
        human.setId(id);
        human.setName(name);
        return human;
    }

    private static Injury injury(String type, String severity) {
        Injury injury = new Injury();
        injury.setInjuryType(type);
        injury.setSeverity(severity);
        return injury;
    }
}
