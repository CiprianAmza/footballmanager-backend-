package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LineupAdapterTest {

    private PlayerView pv(long id, String pos) {
        PlayerView v = new PlayerView();
        v.setId(id);
        v.setName("P" + id);
        v.setPosition(pos);
        v.setRating(15.0);
        v.setFitness(100.0);
        return v;
    }

    private PlayerSkills skills(long id, int finishing, int passing, int vision) {
        PlayerSkills s = new PlayerSkills();
        s.setPlayerId(id);
        s.setFinishing(finishing);
        s.setPassing(passing);
        s.setVision(vision);
        return s;
    }

    private LineupAdapter newAdapter(TacticController tc, PlayerSkillsRepository skillsRepo,
                                     PersonalizedTacticRepository ptRepo) {
        LineupAdapter adapter = new LineupAdapter();
        ReflectionTestUtils.setField(adapter, "tacticController", tc);
        ReflectionTestUtils.setField(adapter, "playerSkillsRepository", skillsRepo);
        ReflectionTestUtils.setField(adapter, "personalizedTacticRepository", ptRepo);
        ReflectionTestUtils.setField(adapter, "engineConfig", new MatchEngineConfig());
        return adapter;
    }

    @Test
    void mapsSkillsTakersAndSimulatesSubs() {
        TacticController tc = mock(TacticController.class);
        PlayerSkillsRepository skillsRepo = mock(PlayerSkillsRepository.class);
        PersonalizedTacticRepository ptRepo = mock(PersonalizedTacticRepository.class);

        List<PlayerView> xi = new ArrayList<>();
        xi.add(pv(1, "GK"));
        for (long i = 2; i <= 11; i++) xi.add(pv(i, i <= 5 ? "DC" : i <= 8 ? "MC" : "ST"));
        List<PlayerView> bench = List.of(pv(12, "ST"), pv(13, "MC"), pv(14, "DC"));

        when(tc.getBestEleven(anyString(), any())).thenReturn(xi);
        when(tc.getSubstitutions(anyString(), any())).thenReturn(bench);

        List<PlayerSkills> allSkills = new ArrayList<>();
        for (long i = 1; i <= 14; i++) allSkills.add(skills(i, 12, 13, 14));
        when(skillsRepo.findAllByPlayerIdIn(any())).thenReturn(allSkills);

        PersonalizedTactic pt = new PersonalizedTactic();
        pt.setPenaltyTakerId(9L);
        when(ptRepo.findPersonalizedTacticByTeamId(anyLong())).thenReturn(Optional.of(pt));

        LineupAdapter adapter = newAdapter(tc, skillsRepo, ptRepo);
        Lineup lineup = adapter.build(10L, "4-4-2", 42L);

        assertEquals(11, lineup.getStartingXI().size());

        Contributor penTaker = lineup.getStartingXI().stream()
                .filter(c -> c.playerId() == 9L).findFirst().orElseThrow();
        assertTrue(penTaker.designatedPenaltyTaker());
        assertEquals(12, penTaker.finishing());
        assertEquals(14, penTaker.vision());

        // 3 subs (subsPerTeam default) — bench has exactly 3.
        assertEquals(3, lineup.getSubs().size());
        for (Lineup.SubMove m : lineup.getSubs()) {
            // subbed-off must be an outfield starter (never the GK id 1)
            assertNotEquals(1L, m.offPlayerId());
            assertTrue(m.on().playerId() >= 12L);
        }
    }

    @Test
    void subbedOnPlayer_isOnPitchOnlyAfterSubMinute() {
        TacticController tc = mock(TacticController.class);
        PlayerSkillsRepository skillsRepo = mock(PlayerSkillsRepository.class);
        PersonalizedTacticRepository ptRepo = mock(PersonalizedTacticRepository.class);

        List<PlayerView> xi = new ArrayList<>();
        xi.add(pv(1, "GK"));
        for (long i = 2; i <= 11; i++) xi.add(pv(i, "MC"));
        when(tc.getBestEleven(anyString(), any())).thenReturn(xi);
        when(tc.getSubstitutions(anyString(), any())).thenReturn(List.of(pv(12, "ST")));
        when(skillsRepo.findAllByPlayerIdIn(any())).thenReturn(List.of());
        when(ptRepo.findPersonalizedTacticByTeamId(anyLong())).thenReturn(Optional.empty());

        LineupAdapter adapter = newAdapter(tc, skillsRepo, ptRepo);
        Lineup lineup = adapter.build(10L, "4-4-2", 1L);
        assertEquals(1, lineup.getSubs().size());
        int subMinute = lineup.getSubs().get(0).minute();

        boolean before = lineup.onPitchAt(subMinute - 1).stream().anyMatch(c -> c.playerId() == 12L);
        boolean after = lineup.onPitchAt(subMinute).stream().anyMatch(c -> c.playerId() == 12L);
        assertFalse(before, "sub on pitch before his minute");
        assertTrue(after, "sub not on pitch at his minute");
    }
}
