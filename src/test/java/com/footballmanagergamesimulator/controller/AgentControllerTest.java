package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Agent;
import com.footballmanagergamesimulator.model.AgentPersonality;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.AgentRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.AgentRelationService;
import com.footballmanagergamesimulator.service.NationService;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentControllerTest {

    @Test
    @DisplayName("""
            GIVEN a Turnover agent of nation 3 with patience 6, representing a 210 at Tik Tok and a 250 without a club,
             whose relation with my manager is 35
            WHEN I open his page
            THEN it shows his name, nation, personality, patience and relation with me,
             and his players best first, a player without a club shown as a free agent
            """)
    void agentPageShowsProfileRelationAndPlayers() throws Exception {
        AgentController controller = new AgentController();
        controller.agentRepository = mock(AgentRepository.class);
        controller.humanRepository = mock(HumanRepository.class);
        controller.teamRepository = mock(TeamRepository.class);
        controller.nationService = mock(NationService.class);
        controller.agentRelationService = mock(AgentRelationService.class);
        controller.userContext = mock(UserContext.class);

        Agent agent = new Agent();
        agent.setId(4L);
        agent.setName("Vrel Osk");
        agent.setNationId(3L);
        agent.setPersonality(AgentPersonality.TURNOVER);
        agent.setPatience(6);
        Team club = new Team();
        club.setId(1L);
        club.setName("Tik Tok");
        Human manager = new Human();
        manager.setId(900L);

        when(controller.agentRepository.findById(4L)).thenReturn(Optional.of(agent));
        when(controller.teamRepository.findAll()).thenReturn(List.of(club));
        when(controller.nationService.infoFor(3L)).thenReturn(new NationService.NationInfo(3L, "Khess", "kh"));
        when(controller.userContext.getTeamIdOrNull(any())).thenReturn(1L);
        when(controller.humanRepository.findAllByTeamIdAndTypeId(1L, TypeNames.MANAGER_TYPE)).thenReturn(List.of(manager));
        when(controller.agentRelationService.relation(4L, 900L)).thenReturn(35);
        when(controller.humanRepository.findAllByAgentIdAndTypeIdAndRetiredFalse(4L, TypeNames.PLAYER_TYPE))
                .thenReturn(List.of(player(7L, "Kvekrpur", 210, 1L), player(8L, "Kabutov", 250, null)));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/agents/4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Vrel Osk"))
                .andExpect(jsonPath("$.nationName").value("Khess"))
                .andExpect(jsonPath("$.personality").value("TURNOVER"))
                .andExpect(jsonPath("$.patience").value(6))
                .andExpect(jsonPath("$.relation").value(35))
                .andExpect(jsonPath("$.players[0].name").value("Kabutov"))
                .andExpect(jsonPath("$.players[0].teamName").value("Free agent"))
                .andExpect(jsonPath("$.players[1].teamName").value("Tik Tok"));
    }

    private static Human player(long id, String name, double rating, Long teamId) {
        Human h = new Human();
        h.setId(id);
        h.setName(name);
        h.setRating(rating);
        h.setTeamId(teamId);
        h.setTypeId(TypeNames.PLAYER_TYPE);
        return h;
    }
}
