package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.PlayerCardView;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.NationService;
import com.footballmanagergamesimulator.service.PlayerCardService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HumanControllerTest {

    @Test
    void getCardReturnsPlayerCardView() throws Exception {
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        PlayerSkillsRepository playerSkillsRepository = mock(PlayerSkillsRepository.class);
        NationService nationService = mock(NationService.class);
        PlayerCardService playerCardService = mock(PlayerCardService.class);

        PlayerCardView cardView = new PlayerCardView();
        cardView.setPlayerId(7L);
        cardView.setName("Ciprian Test");
        cardView.setPosition("ST");
        cardView.setOverall(88);
        cardView.setPac(91);
        cardView.setSho(85);
        cardView.setPas(79);
        cardView.setDri(84);
        cardView.setDef(40);
        cardView.setPhy(77);
        cardView.setAge(23);
        cardView.setNationId(24L);

        when(playerCardService.getPlayerCard(7L)).thenReturn(Optional.of(cardView));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new HumanController(humanRepository, teamRepository, playerSkillsRepository, nationService, playerCardService))
                .build();

        mockMvc.perform(get("/humans/7/card"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value(7))
                .andExpect(jsonPath("$.name").value("Ciprian Test"))
                .andExpect(jsonPath("$.position").value("ST"))
                .andExpect(jsonPath("$.overall").value(88))
                .andExpect(jsonPath("$.pac").value(91))
                .andExpect(jsonPath("$.sho").value(85))
                .andExpect(jsonPath("$.pas").value(79))
                .andExpect(jsonPath("$.dri").value(84))
                .andExpect(jsonPath("$.def").value(40))
                .andExpect(jsonPath("$.phy").value(77))
                .andExpect(jsonPath("$.age").value(23))
                .andExpect(jsonPath("$.nationId").value(24));
    }

    @Test
    void getByIdExposesPersistedStayForwardFlagOnlyWhenTrue() throws Exception {
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        PlayerSkillsRepository playerSkillsRepository = mock(PlayerSkillsRepository.class);
        NationService nationService = mock(NationService.class);
        PlayerCardService playerCardService = mock(PlayerCardService.class);

        Team team = new Team();
        team.setId(1L);
        team.setName("Tik Tok");
        Human kvekrpur = player(7L, "Kvekrpur", true);

        when(humanRepository.findById(7L)).thenReturn(Optional.of(kvekrpur));
        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(playerSkillsRepository.findPlayerSkillsByPlayerId(7L)).thenReturn(Optional.empty());
        when(nationService.infoForTeam(1L)).thenReturn(new NationService.NationInfo(2L, "Khess", "KH"));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new HumanController(humanRepository, teamRepository, playerSkillsRepository, nationService, playerCardService))
                .build();

        mockMvc.perform(get("/humans/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kvekrpur"))
                .andExpect(jsonPath("$.stayForward").value(true));
    }

    @Test
    void getByIdDefaultsStayForwardFalseForPlayersWithoutThePersistedTrait() throws Exception {
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        PlayerSkillsRepository playerSkillsRepository = mock(PlayerSkillsRepository.class);
        NationService nationService = mock(NationService.class);
        PlayerCardService playerCardService = mock(PlayerCardService.class);

        Team team = new Team();
        team.setId(1L);
        team.setName("Tik Tok");
        Human kabutov = player(8L, "Kabutov", false);

        when(humanRepository.findById(8L)).thenReturn(Optional.of(kabutov));
        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(playerSkillsRepository.findPlayerSkillsByPlayerId(8L)).thenReturn(Optional.empty());
        when(nationService.infoForTeam(1L)).thenReturn(new NationService.NationInfo(2L, "Khess", "KH"));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new HumanController(humanRepository, teamRepository, playerSkillsRepository, nationService, playerCardService))
                .build();

        mockMvc.perform(get("/humans/8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kabutov"))
                .andExpect(jsonPath("$.stayForward").value(false));
    }

    private Human player(long id, String name, boolean stayForward) {
        Human player = new Human();
        player.setId(id);
        player.setName(name);
        player.setTeamId(1L);
        player.setTypeId(1L);
        player.setPosition("ST");
        player.setStayForward(stayForward);
        return player;
    }
}
