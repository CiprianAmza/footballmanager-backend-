package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Verifies the team-talk morale effect is now deterministic: the same talk for the same match
 * reproduces the same per-player reaction (seeded from the talk context), so it can't be re-rolled
 * by re-simulating — consistent with the seeded match engine.
 */
class TeamTalkServiceDeterminismTest {

    @InjectMocks
    private TeamTalkService service;

    @Mock
    private HumanRepository humanRepository;
    @Mock
    private ManagerInboxRepository managerInboxRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private Human player(long id, double morale) {
        Human h = new Human();
        h.setId(id);
        h.setTypeId(TypeNames.PLAYER_TYPE);
        h.setMorale(morale);
        return h;
    }

    @Test
    void sameTalkSameMatch_reproducesIdenticalMoraleChanges() {
        long teamId = 7L;
        // Fresh player objects each call (start from the same morale) so we isolate RNG determinism.
        when(humanRepository.findAllByTeamIdAndTypeId(eq(teamId), eq(TypeNames.PLAYER_TYPE)))
                .thenReturn(List.of(player(101, 70), player(102, 55), player(103, 88)))
                .thenReturn(List.of(player(101, 70), player(102, 55), player(103, 88)));

        var first = service.giveTeamTalk(teamId, "PRE_MATCH", "show_passion", null, 3, 5L);
        service.resetForNewMatch(teamId); // clear the per-match latch so we can deliver again
        var second = service.giveTeamTalk(teamId, "PRE_MATCH", "show_passion", null, 3, 5L);

        assertThat(first.get("success")).isEqualTo(true);
        assertThat(second.get("playerReactions")).isEqualTo(first.get("playerReactions"));
        assertThat(second.get("averageMoraleChange")).isEqualTo(first.get("averageMoraleChange"));
    }

    @Test
    void reactionsVaryAcrossRounds() {
        long teamId = 7L;
        when(humanRepository.findAllByTeamIdAndTypeId(anyLong(), eq(TypeNames.PLAYER_TYPE)))
                .thenAnswer(inv -> List.of(player(101, 70), player(102, 55), player(103, 88)));

        Object baseline = service.giveTeamTalk(teamId, "PRE_MATCH", "show_passion", null, 3, 1L).get("playerReactions");

        // The seed includes the round, so reactions should not be frozen identical every match.
        boolean sawDifferent = false;
        for (long round = 2; round <= 12 && !sawDifferent; round++) {
            service.resetForNewMatch(teamId);
            Object reactions = service.giveTeamTalk(teamId, "PRE_MATCH", "show_passion", null, 3, round).get("playerReactions");
            if (!reactions.equals(baseline)) sawDifferent = true;
        }
        assertThat(sawDifferent)
                .as("team-talk reactions should differ across at least one of rounds 2..12")
                .isTrue();
    }
}
