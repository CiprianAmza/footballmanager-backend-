package com.footballmanagergamesimulator.matchplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LineupAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TacticController tc = mock(TacticController.class);
    private final PlayerSkillsRepository skillsRepo = mock(PlayerSkillsRepository.class);
    private final PersonalizedTacticRepository ptRepo = mock(PersonalizedTacticRepository.class);
    private final HumanRepository humanRepo = mock(HumanRepository.class);
    private final Map<Long, Human> humans = new HashMap<>();

    private LineupAdapter adapter() {
        when(humanRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(humans.get((Long) inv.getArgument(0))));
        LineupAdapter adapter = new LineupAdapter();
        ReflectionTestUtils.setField(adapter, "tacticController", tc);
        ReflectionTestUtils.setField(adapter, "playerSkillsRepository", skillsRepo);
        ReflectionTestUtils.setField(adapter, "personalizedTacticRepository", ptRepo);
        ReflectionTestUtils.setField(adapter, "humanRepository", humanRepo);
        ReflectionTestUtils.setField(adapter, "objectMapper", mapper);
        ReflectionTestUtils.setField(adapter, "engineConfig", new MatchEngineConfig());
        ReflectionTestUtils.setField(adapter, "tacticService", new TacticService());
        return adapter;
    }

    private PlayerView pv(long id, String pos) {
        PlayerView v = new PlayerView();
        v.setId(id);
        v.setName("P" + id);
        v.setPosition(pos);
        v.setRating(15.0);
        v.setFitness(100.0);
        return v;
    }

    private Human human(long id, String pos, long teamId) {
        Human h = new Human();
        h.setId(id);
        h.setName("H" + id);
        h.setPosition(pos);
        h.setRating(15.0);
        h.setFitness(100.0);
        h.setTeamId(teamId);
        h.setTypeId(TypeNames.PLAYER_TYPE);
        humans.put(id, h);
        return h;
    }

    private FormationData fd(int slot, long id) {
        FormationData d = new FormationData();
        d.setPositionIndex(slot);
        d.setPlayerId(id);
        return d;
    }

    /** Best-eleven of 11 (ids base..base+10, GK first) + a bench. */
    private void stubBestEleven(long base, List<PlayerView> bench) {
        List<PlayerView> xi = new ArrayList<>();
        xi.add(pv(base, "GK"));
        for (long i = 1; i <= 10; i++) xi.add(pv(base + i, i <= 4 ? "DC" : i <= 8 ? "MC" : "ST"));
        when(tc.getBestEleven(anyString(), any())).thenReturn(xi);
        when(tc.getSubstitutions(anyString(), any())).thenReturn(bench);
    }

    private String savedFirst11(long[] starterIds, long[] benchIds) throws Exception {
        List<FormationData> list = new ArrayList<>();
        int slot = 0;
        for (long id : starterIds) { FormationData d = new FormationData(); d.setPositionIndex(slot++); d.setPlayerId(id); list.add(d); }
        int b = 30;
        for (long id : benchIds) { FormationData d = new FormationData(); d.setPositionIndex(b++); d.setPlayerId(id); list.add(d); }
        return mapper.writeValueAsString(list);
    }

    private long[] range(long from, int count) {
        long[] a = new long[count];
        for (int i = 0; i < count; i++) a[i] = from + i;
        return a;
    }

    // ---------------- AI_INSTANT ----------------

    @Test
    void aiInstant_mapsSkillsTakers_andSubsAreGoalkeeperSafe() {
        List<PlayerView> bench = List.of(pv(12, "ST"), pv(13, "MC"), pv(14, "GK")); // includes a keeper
        stubBestEleven(1, bench);
        List<PlayerSkills> allSkills = new ArrayList<>();
        for (long i = 1; i <= 14; i++) { PlayerSkills s = new PlayerSkills(); s.setPlayerId(i); s.setFinishing(12); s.setPassing(13); s.setVision(14); allSkills.add(s); }
        when(skillsRepo.findAllByPlayerIdIn(any())).thenReturn(allSkills);
        PersonalizedTactic pt = new PersonalizedTactic();
        pt.setPenaltyTakerId(9L);
        when(ptRepo.findPersonalizedTacticByTeamId(anyLong())).thenReturn(Optional.of(pt));

        LineupAdapter.Result result = adapter().build(10L, "4-4-2", 42L, LineupAdapter.Mode.AI_INSTANT);
        assertEquals(LineupAdapter.Source.AI_INSTANT, result.source());
        Lineup lineup = result.lineup();

        assertEquals(11, lineup.getStartingXI().size());
        Contributor penTaker = lineup.getStartingXI().stream()
                .filter(c -> c.playerId() == 9L).findFirst().orElseThrow();
        assertTrue(penTaker.designatedPenaltyTaker());
        assertEquals(12, penTaker.finishing());

        assertFalse(lineup.getSubs().isEmpty());
        for (Lineup.SubMove m : lineup.getSubs()) {
            assertNotEquals(1L, m.offPlayerId(), "GK must not be subbed off");
            assertNotEquals(14L, m.on().playerId(), "a GK must never be subbed on");
        }
    }

    @Test
    void aiInstant_isDeterministicForSameSeed() {
        stubBestEleven(1, List.of(pv(12, "ST"), pv(13, "MC"), pv(14, "DC")));
        when(ptRepo.findPersonalizedTacticByTeamId(anyLong())).thenReturn(Optional.empty());

        List<Lineup.SubMove> a = adapter().build(10L, "4-4-2", 7L, LineupAdapter.Mode.AI_INSTANT).lineup().getSubs();
        List<Lineup.SubMove> b = adapter().build(10L, "4-4-2", 7L, LineupAdapter.Mode.AI_INSTANT).lineup().getSubs();
        assertEquals(a.stream().map(m -> m.minute() + ":" + m.offPlayerId() + ":" + m.on().playerId()).toList(),
                b.stream().map(m -> m.minute() + ":" + m.offPlayerId() + ":" + m.on().playerId()).toList());
    }

    // ---------------- USER_SAVED ----------------

    @Test
    void userSaved_usesSavedXi_notBestEleven_andHasNoInventedSubs() throws Exception {
        // Best eleven would pick ids 50..60; the saved XI is 1..11 (deliberately different).
        stubBestEleven(50, List.of(pv(90, "ST")));
        for (long i = 1; i <= 11; i++) human(i, "MC", 10L);
        for (long i = 12; i <= 14; i++) human(i, "ST", 10L);
        PersonalizedTactic pt = new PersonalizedTactic();
        pt.setFirst11(savedFirst11(range(1, 11), range(12, 3)));
        when(ptRepo.findPersonalizedTacticByTeamId(anyLong())).thenReturn(Optional.of(pt));

        LineupAdapter.Result result = adapter().build(10L, "4-4-2", 42L, LineupAdapter.Mode.USER_SAVED);

        assertEquals(LineupAdapter.Source.USER_SAVED, result.source());
        List<Long> starterIds = result.lineup().getStartingXI().stream().map(Contributor::playerId).toList();
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L), starterIds);
        assertTrue(result.lineup().getSubs().isEmpty(), "USER_SAVED must invent no substitutions");
        verify(tc, never()).getBestEleven(anyString(), any());
    }

    @Test
    void userSaved_starOnBench_staysOnBench() throws Exception {
        stubBestEleven(50, List.of());
        for (long i = 1; i <= 11; i++) human(i, "MC", 10L);
        human(99, "ST", 10L); // the star, deliberately benched
        PersonalizedTactic pt = new PersonalizedTactic();
        pt.setFirst11(savedFirst11(range(1, 11), new long[]{99}));
        when(ptRepo.findPersonalizedTacticByTeamId(anyLong())).thenReturn(Optional.of(pt));

        Lineup lineup = adapter().build(10L, "4-4-2", 1L, LineupAdapter.Mode.USER_SAVED).lineup();

        assertTrue(lineup.getStartingXI().stream().noneMatch(c -> c.playerId() == 99L));
        assertTrue(lineup.getBench().stream().anyMatch(c -> c.playerId() == 99L));
    }

    @Test
    void userSaved_malformedJson_fallsBackSafely() {
        stubBestEleven(50, List.of(pv(90, "ST")));
        PersonalizedTactic pt = new PersonalizedTactic();
        pt.setFirst11("{ not valid json");
        when(ptRepo.findPersonalizedTacticByTeamId(anyLong())).thenReturn(Optional.of(pt));

        LineupAdapter.Result result = adapter().build(10L, "4-4-2", 1L, LineupAdapter.Mode.USER_SAVED);

        assertEquals(LineupAdapter.Source.AUTO_FALLBACK, result.source());
        assertEquals(11, result.lineup().getStartingXI().size());
        assertTrue(result.lineup().getSubs().isEmpty(), "fallback for a user team invents no subs");
    }

    @Test
    void userSaved_incompleteXi_fallsBack() throws Exception {
        stubBestEleven(50, List.of());
        for (long i = 1; i <= 10; i++) human(i, "MC", 10L);
        PersonalizedTactic pt = new PersonalizedTactic();
        pt.setFirst11(savedFirst11(range(1, 10), new long[]{})); // only 10 starters
        when(ptRepo.findPersonalizedTacticByTeamId(anyLong())).thenReturn(Optional.of(pt));

        assertEquals(LineupAdapter.Source.AUTO_FALLBACK,
                adapter().build(10L, "4-4-2", 1L, LineupAdapter.Mode.USER_SAVED).source());
    }

    @Test
    void userSaved_playerFromAnotherTeam_fallsBack() throws Exception {
        stubBestEleven(50, List.of());
        for (long i = 1; i <= 10; i++) human(i, "MC", 10L);
        human(11, "ST", 999L); // wrong team
        PersonalizedTactic pt = new PersonalizedTactic();
        pt.setFirst11(savedFirst11(range(1, 11), new long[]{}));
        when(ptRepo.findPersonalizedTacticByTeamId(anyLong())).thenReturn(Optional.of(pt));

        assertEquals(LineupAdapter.Source.AUTO_FALLBACK,
                adapter().build(10L, "4-4-2", 1L, LineupAdapter.Mode.USER_SAVED).source());
    }

    // ---------------- USER_SAVED: strict schema + atomic snapshot ----------------

    private void savePt(List<FormationData> list) throws Exception {
        PersonalizedTactic pt = new PersonalizedTactic();
        pt.setFirst11(mapper.writeValueAsString(list));
        when(ptRepo.findPersonalizedTacticByTeamId(anyLong())).thenReturn(Optional.of(pt));
    }

    /** Eleven valid starters of team 10 at pitch slots 0..10. */
    private List<FormationData> validEleven() {
        for (long i = 1; i <= 11; i++) human(i, "MC", 10L);
        List<FormationData> list = new ArrayList<>();
        for (int s = 0; s < 11; s++) list.add(fd(s, s + 1));
        return list;
    }

    @Test
    void userSaved_nullFormationElement_fallsBack() throws Exception {
        stubBestEleven(50, List.of());
        List<FormationData> list = validEleven();
        list.add(null); // a null slot must not throw from the comparator
        savePt(list);

        assertEquals(LineupAdapter.Source.AUTO_FALLBACK,
                adapter().build(10L, "4-4-2", 1L, LineupAdapter.Mode.USER_SAVED).source());
    }

    @Test
    void userSaved_duplicateSlotIndex_fallsBack() throws Exception {
        stubBestEleven(50, List.of());
        List<FormationData> list = validEleven();
        list.add(fd(5, 12)); // slot 5 already used
        human(12, "ST", 10L);
        savePt(list);

        assertEquals(LineupAdapter.Source.AUTO_FALLBACK,
                adapter().build(10L, "4-4-2", 1L, LineupAdapter.Mode.USER_SAVED).source());
    }

    @Test
    void userSaved_negativeSlot_fallsBack() throws Exception {
        stubBestEleven(50, List.of());
        List<FormationData> list = validEleven();
        list.add(fd(-1, 12)); // negative slot is not a valid starter
        human(12, "ST", 10L);
        savePt(list);

        assertEquals(LineupAdapter.Source.AUTO_FALLBACK,
                adapter().build(10L, "4-4-2", 1L, LineupAdapter.Mode.USER_SAVED).source());
    }

    @Test
    void userSaved_slotAbove36_fallsBack() throws Exception {
        stubBestEleven(50, List.of());
        List<FormationData> list = validEleven();
        list.add(fd(37, 12)); // above the bench range
        human(12, "ST", 10L);
        savePt(list);

        assertEquals(LineupAdapter.Source.AUTO_FALLBACK,
                adapter().build(10L, "4-4-2", 1L, LineupAdapter.Mode.USER_SAVED).source());
    }

    @Test
    void userSaved_wrongTeamBenchPlayer_fallsBackAtomically() throws Exception {
        stubBestEleven(50, List.of());
        List<FormationData> list = validEleven();
        list.add(fd(30, 20)); // bench entry from another team
        human(20, "ST", 999L);
        savePt(list);

        assertEquals(LineupAdapter.Source.AUTO_FALLBACK,
                adapter().build(10L, "4-4-2", 1L, LineupAdapter.Mode.USER_SAVED).source());
    }

    @Test
    void userSaved_duplicateBenchPlayer_fallsBackAtomically() throws Exception {
        stubBestEleven(50, List.of());
        List<FormationData> list = validEleven();
        list.add(fd(30, 1)); // bench id duplicates a starter id
        savePt(list);

        assertEquals(LineupAdapter.Source.AUTO_FALLBACK,
                adapter().build(10L, "4-4-2", 1L, LineupAdapter.Mode.USER_SAVED).source());
    }

    @Test
    void userSaved_managerOrStaffId_fallsBack() throws Exception {
        stubBestEleven(50, List.of());
        List<FormationData> list = validEleven();
        humans.get(11L).setTypeId(TypeNames.MANAGER_TYPE); // slot-10 entry is not a player
        savePt(list);

        assertEquals(LineupAdapter.Source.AUTO_FALLBACK,
                adapter().build(10L, "4-4-2", 1L, LineupAdapter.Mode.USER_SAVED).source());
    }

    @Test
    void userSaved_retiredPlayer_fallsBack() throws Exception {
        stubBestEleven(50, List.of());
        List<FormationData> list = validEleven();
        humans.get(11L).setRetired(true);
        savePt(list);

        assertEquals(LineupAdapter.Source.AUTO_FALLBACK,
                adapter().build(10L, "4-4-2", 1L, LineupAdapter.Mode.USER_SAVED).source());
    }

    @Test
    void userSaved_naturalStInMidfieldSlot_usesFieldedPosition() throws Exception {
        stubBestEleven(50, List.of());
        // Ten fillers + one natural ST fielded in a central-midfield grid slot (11 -> "MC").
        for (long i = 1; i <= 10; i++) human(i, "MC", 10L);
        human(11, "ST", 10L); // natural striker
        List<FormationData> list = new ArrayList<>();
        int[] slots = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11};
        for (int i = 0; i < 11; i++) list.add(fd(slots[i], i + 1));
        savePt(list);

        LineupAdapter.Result result = adapter().build(10L, "4-4-2", 1L, LineupAdapter.Mode.USER_SAVED);

        assertEquals(LineupAdapter.Source.USER_SAVED, result.source());
        Contributor fielded = result.lineup().getStartingXI().stream()
                .filter(c -> c.playerId() == 11L).findFirst().orElseThrow();
        assertEquals("MC", fielded.position(),
                "a natural ST fielded in a midfield slot must be weighted as a midfielder");
    }
}
