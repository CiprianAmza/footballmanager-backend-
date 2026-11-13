package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.NationService;
import com.footballmanagergamesimulator.service.PlayerCardService;
import com.footballmanagergamesimulator.service.PlayerMarketAvailabilityService;
import com.footballmanagergamesimulator.service.PlayerPreviewService;
import com.footballmanagergamesimulator.service.PlayerSkillsService;
import com.footballmanagergamesimulator.service.PositionAttributeImportance;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScoutingPlayersEndpointTest {

    private PositionAttributeImportance previousTable;

    @BeforeEach
    void installEngineWeights() throws Exception {
        previousTable = PositionAttributeImportance.current();
        MutablePropertySources properties = new MutablePropertySources();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("weights", new ClassPathResource("compartment-scoring-weights-v1.yml"))) {
            properties.addLast(source);
        }
        PositionAttributeImportance.install(new Binder(ConfigurationPropertySources.from(properties))
                .bind("match.engine.compartment", Bindable.of(CompartmentEngineConfig.class))
                .orElseThrow(IllegalStateException::new));
    }

    @AfterEach
    void restore() {
        PositionAttributeImportance.restore(previousTable);
    }

    @Test
    @DisplayName("""
            GIVEN two players at clubs with stored skills
            WHEN the scouting pool is requested
            THEN each row carries club, nation and special rating (0 without skills), resolved by bulk lookups without per-player queries
            """)
    void scoutingPoolUsesBulkLookupsAndExposesSpecialRating() throws Exception {
        HumanRepository humanRepository = mock(HumanRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        PlayerSkillsRepository playerSkillsRepository = mock(PlayerSkillsRepository.class);
        NationService nationService = mock(NationService.class);
        HumanController controller = new HumanController(humanRepository, teamRepository,
                playerSkillsRepository, nationService, mock(PlayerCardService.class));
        controller.marketAvailabilityService = mock(PlayerMarketAvailabilityService.class);
        controller.playerPreviewService = mock(PlayerPreviewService.class);
        controller.plannedTransferService = mock(com.footballmanagergamesimulator.service.PlannedTransferService.class);
        when(controller.plannedTransferService.wantedBy()).thenReturn(Map.of(8L, List.of(1L)));

        Team team = new Team();
        team.setId(1L);
        team.setName("Tik Tok");
        PlayerSkills skills = new PlayerSkills();
        skills.setPlayerId(7L);
        skills.setFinishing(18);
        skills.setPace(15);

        when(humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE))
                .thenReturn(List.of(player(7L, "Kvekrpur"), player(8L, "Kabutov")));
        when(teamRepository.findAll()).thenReturn(List.of(team));
        when(nationService.infoByTeamId()).thenReturn(Map.of(1L, new NationService.NationInfo(3L, "Khess", "kh")));
        when(playerSkillsRepository.findAllByPlayerIdIn(any())).thenReturn(List.of(skills));
        when(controller.marketAvailabilityService.currentSeasonStates()).thenReturn(Map.of());
        when(controller.marketAvailabilityService.stateFor(anyLong(), any()))
                .thenReturn(PlayerMarketAvailabilityService.MarketState.available());
        when(controller.playerPreviewService.previews(any(), anyInt())).thenReturn(Map.of());

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/humans/scoutingPlayers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].teamName").value("Tik Tok"))
                .andExpect(jsonPath("$[0].nationName").value("Khess"))
                .andExpect(jsonPath("$[0].specialRating").value(PlayerSkillsService.computeSpecialRating(skills)))
                .andExpect(jsonPath("$[0].skillNames").isEmpty())
                .andExpect(jsonPath("$[1].specialRating").value(0.0))
                .andExpect(jsonPath("$[0].wanted").value(false))
                .andExpect(jsonPath("$[1].wanted").value(true))
                .andExpect(jsonPath("$[1].wantedBy[0]").value("Tik Tok"));

        verify(teamRepository, never()).findById(anyLong());
        verify(nationService, never()).infoForTeam(anyLong());
        verify(playerSkillsRepository, never()).findPlayerSkillsByPlayerId(anyLong());
    }

    private Human player(long id, String name) {
        Human player = new Human();
        player.setId(id);
        player.setName(name);
        player.setTeamId(1L);
        player.setTypeId(TypeNames.PLAYER_TYPE);
        player.setPosition("ST");
        return player;
    }
}
