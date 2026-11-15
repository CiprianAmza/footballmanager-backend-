package com.footballmanagergamesimulator.match2d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Match2dLineupServiceTest {

    private static final long TEAM = 5L;
    /** The 4-4-2 grid slots: two strikers, a midfield four, a back four, the keeper. */
    private static final int[] SLOTS_442 = {1, 3, 10, 11, 13, 14, 20, 21, 23, 24, 27};

    private final ObjectMapper json = new ObjectMapper();
    private TeamRepository teams;
    private HumanRepository humans;
    private PlayerSkillsRepository skills;
    private PersonalizedTacticRepository tactics;
    private TacticController tacticController;
    private Match2dLineupService service;

    @BeforeEach
    void setUp() {
        teams = mock(TeamRepository.class);
        humans = mock(HumanRepository.class);
        skills = mock(PlayerSkillsRepository.class);
        tactics = mock(PersonalizedTacticRepository.class);
        tacticController = mock(TacticController.class);
        service = new Match2dLineupService(teams, humans, skills, tactics, new TacticService(),
                tacticController, json);

        Team team = new Team();
        team.setId(TEAM);
        team.setName("Athletic Sohatu");
        team.setColor1("yellow");
        team.setColor2("green");
        when(teams.findById(TEAM)).thenReturn(Optional.of(team));
    }

    @Test
    void savedElevenGoesOutInGridOrderWithSlotPositionsAndEveryAttribute() throws Exception {
        List<FormationData> saved = eleven(SLOTS_442, 100);
        saved.get(2).setSpecialRole("SHOOTER");                 // slot 10 = ML
        saved.add(slot(30, 199));                               // a substitute, must be ignored
        PersonalizedTactic tactic = tactic("442", json.writeValueAsString(saved));
        when(tactics.findPersonalizedTacticByTeamId(TEAM)).thenReturn(Optional.of(tactic));
        List<Human> squad = players(saved, TEAM);
        squad.get(0).setStayForward(true);                      // the striker in slot 1
        when(humans.findAllById(any())).thenReturn(squad);
        when(skills.findAllByPlayerIdIn(any())).thenReturn(skillsFor(saved));

        Match2dTeam out = service.build(TEAM);

        assertThat(out.name()).isEqualTo("Athletic Sohatu");
        assertThat(out.formation()).isEqualTo("442");
        assertThat(out.color()).isEqualTo("yellow");
        assertThat(out.gkColor()).isEqualTo("green");
        assertThat(out.players()).extracting(Match2dTeam.Player::position).containsExactly(
                "ST", "ST", "ML", "MC", "MC", "MR", "DL", "DC", "DC", "DR", "GK");
        assertThat(out.players()).extracting(Match2dTeam.Player::id).doesNotContain(199L);

        Match2dTeam.Player shooter = out.players().get(2);
        assertThat(shooter.specialRole()).isEqualTo("SHOOTER");
        assertThat(shooter.naturalPosition()).isEqualTo("MC");
        assertThat(shooter.ratings()).hasSize(43)
                .containsEntry("longShots", 20).containsEntry("ballRecovery", 7)
                .containsEntry("pace", 11).containsEntry("throwing", 3);

        Match2dTeam.Player striker = out.players().get(0);
        assertThat(striker.stayForward()).isTrue();
        assertThat(striker.shadow()).isTrue();
        assertThat(out.players().get(1).stayForward()).isFalse();
        verify(tacticController, never()).askAssistant(anyLong(), anyString());
    }

    @Test
    void incompleteSavedElevenFallsBackToTheAssistantSelection() throws Exception {
        List<FormationData> ten = eleven(SLOTS_442, 100).subList(0, 10);     // no goalkeeper saved
        when(tactics.findPersonalizedTacticByTeamId(TEAM))
                .thenReturn(Optional.of(tactic("442", json.writeValueAsString(ten))));
        List<FormationData> picked = eleven(SLOTS_442, 300);
        when(tacticController.askAssistant(TEAM, "442")).thenReturn(picked);
        when(humans.findAllById(any())).thenReturn(players(picked, TEAM));
        when(skills.findAllByPlayerIdIn(any())).thenReturn(skillsFor(picked));

        Match2dTeam out = service.build(TEAM);

        assertThat(out.players()).hasSize(11);
        assertThat(out.players()).extracting(Match2dTeam.Player::id).allMatch(id -> id >= 300);
        assertThat(out.players().get(10).position()).isEqualTo("GK");
    }

    @Test
    void savedElevenWithAPlayerOfAnotherClubIsNotTrusted() throws Exception {
        List<FormationData> saved = eleven(SLOTS_442, 100);
        when(tactics.findPersonalizedTacticByTeamId(TEAM))
                .thenReturn(Optional.of(tactic("442", json.writeValueAsString(saved))));
        List<Human> withStranger = players(saved, TEAM);
        withStranger.get(4).setTeamId(99L);
        List<FormationData> picked = eleven(SLOTS_442, 300);
        when(tacticController.askAssistant(TEAM, "442")).thenReturn(picked);
        when(humans.findAllById(any())).thenReturn(withStranger, players(picked, TEAM));
        when(skills.findAllByPlayerIdIn(any())).thenReturn(skillsFor(picked));

        Match2dTeam out = service.build(TEAM);

        assertThat(out.players()).extracting(Match2dTeam.Player::id).allMatch(id -> id >= 300);
    }

    @Test
    void everyTacticalChoiceOfTheClubIsExported() throws Exception {
        List<FormationData> saved = eleven(SLOTS_442, 100);
        PersonalizedTactic tactic = tactic("442", json.writeValueAsString(saved));
        tactic.setMentality("Defensive");
        tactic.setTempo("Much Lower");
        tactic.setPassingType("Long");
        tactic.setDefensiveLine("Deep");
        tactic.setPressing("Aggressive");
        tactic.setWidth("Narrow");
        tactic.setRecovery("Instantly");
        tactic.setInPossession("Keep Ball");
        tactic.setTimeWasting("Sometimes");
        tactic.setDribbling("More");
        tactic.setFoulFrequency("Often");
        tactic.setFoulHardness("Hard");
        tactic.setTempoFragmentation("Fragment");
        tactic.setWidePlay("Cross");
        tactic.setTransition("Fast Counter");
        tactic.setFreeKickTakerId(104L);
        when(tactics.findPersonalizedTacticByTeamId(TEAM)).thenReturn(Optional.of(tactic));
        when(humans.findAllById(any())).thenReturn(players(saved, TEAM));
        when(skills.findAllByPlayerIdIn(any())).thenReturn(skillsFor(saved));

        Match2dTeam out = service.build(TEAM);

        assertThat(out.tactic())
                .containsEntry("formation", "442")
                .containsEntry("mentality", "Defensive")
                .containsEntry("tempo", "Much Lower")
                .containsEntry("passingType", "Long")
                .containsEntry("defensiveLine", "Deep")
                .containsEntry("pressing", "Aggressive")
                .containsEntry("width", "Narrow")
                .containsEntry("recovery", "Instantly")
                .containsEntry("inPossession", "Keep Ball")
                .containsEntry("timeWasting", "Sometimes")
                .containsEntry("dribbling", "More")
                .containsEntry("foulFrequency", "Often")
                .containsEntry("foulHardness", "Hard")
                .containsEntry("tempoFragmentation", "Fragment")
                .containsEntry("widePlay", "Cross")
                .containsEntry("transition", "Fast Counter")
                .containsEntry("freeKickTakerId", 104L)
                .doesNotContainKey("penaltyTakerId");
    }

    @Test
    void clubWithoutATacticUsesItsManagersFormation() {
        when(tactics.findPersonalizedTacticByTeamId(TEAM)).thenReturn(Optional.empty());
        Human manager = new Human();
        manager.setId(900L);
        manager.setTeamId(TEAM);
        manager.setTypeId(TypeNames.MANAGER_TYPE);
        manager.setTacticStyle("4231");
        when(humans.findAllByTeamIdAndTypeId(TEAM, TypeNames.MANAGER_TYPE)).thenReturn(List.of(manager));
        int[] slots4231 = {2, 5, 7, 9, 16, 18, 20, 21, 23, 24, 27};
        List<FormationData> picked = eleven(slots4231, 300);
        when(tacticController.askAssistant(TEAM, "4231")).thenReturn(picked);
        when(humans.findAllById(any())).thenReturn(players(picked, TEAM));
        when(skills.findAllByPlayerIdIn(any())).thenReturn(skillsFor(picked));

        Match2dTeam out = service.build(TEAM);

        assertThat(out.formation()).isEqualTo("4231");
        assertThat(out.tactic()).containsOnlyKeys("formation");
        assertThat(out.players()).extracting(Match2dTeam.Player::position).containsExactly(
                "ST", "AML", "AMC", "AMR", "DM", "DM", "DL", "DC", "DC", "DR", "GK");
    }

    @Test
    void unknownTeamIsNotFound() {
        when(teams.findById(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.build(77L))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure ->
                        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void clubThatCannotFieldElevenIsRejectedWithItsName() {
        when(tactics.findPersonalizedTacticByTeamId(TEAM)).thenReturn(Optional.empty());
        when(humans.findAllByTeamIdAndTypeId(TEAM, TypeNames.MANAGER_TYPE)).thenReturn(List.of());
        when(tacticController.askAssistant(TEAM, "442")).thenReturn(eleven(SLOTS_442, 300).subList(0, 8));

        assertThatThrownBy(() -> service.build(TEAM))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(failure.getReason()).contains("Athletic Sohatu");
                });
    }

    // ---- fixtures ----

    private static FormationData slot(int index, long playerId) {
        FormationData data = new FormationData();
        data.setPositionIndex(index);
        data.setPlayerId(playerId);
        return data;
    }

    /** One player per slot, in slot order, with ids starting at {@code firstId}. */
    private static List<FormationData> eleven(int[] slots, long firstId) {
        List<FormationData> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) out.add(slot(slots[i], firstId + i));
        return out;
    }

    private static PersonalizedTactic tactic(String formation, String first11) {
        PersonalizedTactic tactic = new PersonalizedTactic();
        tactic.setTeamId(TEAM);
        tactic.setTactic(formation);
        tactic.setFirst11(first11);
        return tactic;
    }

    private static List<Human> players(List<FormationData> slots, long teamId) {
        List<Human> out = new ArrayList<>();
        for (FormationData slot : slots) {
            Human human = new Human();
            human.setId(slot.getPlayerId());
            human.setTeamId(teamId);
            human.setTypeId(TypeNames.PLAYER_TYPE);
            human.setName("Player " + slot.getPlayerId());
            human.setPosition("MC");
            human.setShirtNumber((int) (slot.getPlayerId() % 99) + 1);
            human.setRating(150);
            out.add(human);
        }
        return out;
    }

    private static List<PlayerSkills> skillsFor(List<FormationData> slots) {
        List<PlayerSkills> out = new ArrayList<>();
        for (FormationData slot : slots) {
            PlayerSkills s = new PlayerSkills();
            s.setPlayerId(slot.getPlayerId());
            s.setLongShots(20);
            s.setBallRecovery(7);
            s.setPace(11);
            s.setThrowing(3);
            out.add(s);
        }
        return out;
    }
}
