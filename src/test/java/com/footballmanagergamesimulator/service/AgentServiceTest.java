package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Agent;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.AgentRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import(AgentService.class)
class AgentServiceTest {

    @Autowired private AgentService agents;
    @Autowired private AgentRepository agentRepository;
    @Autowired private HumanRepository humans;
    @MockBean private NationService nationService;
    @MockBean private CompositeNameGenerator nameGenerator;

    @BeforeEach
    void setUp() {
        agents.rng = new Random(11);
        when(nationService.infoByTeamId()).thenReturn(Map.of(
                1L, new NationService.NationInfo(1, "Gallactick", "ga"),
                2L, new NationService.NationInfo(2, "Dong", "do")));
        when(nameGenerator.generateNameForNation(anyLong())).thenReturn("Agent Name");
    }

    private Human player(long team, int age) {
        Human p = new Human();
        p.setTypeId(TypeNames.PLAYER_TYPE);
        p.setTeamId(team);
        p.setAge(age);
        p.setName("P");
        return humans.save(p);
    }

    @Test
    @DisplayName("""
            GIVEN 120 players in nation 1 and 30 in nation 2, nobody with an agent, no agents in the world
            WHEN agents are ensured
            THEN every player has an agent of his own nation, and a second call creates nothing
            """)
    void worldStartGivesEveryPlayerAnAgentOfHisNation() {
        for (int i = 0; i < 120; i++) player(1L, 25);
        for (int i = 0; i < 30; i++) player(2L, 25);

        int created = agents.ensureAgents();

        Map<Long, Long> nationOfAgent = agentRepository.findAll().stream()
                .collect(Collectors.toMap(Agent::getId, Agent::getNationId));
        assertEquals(created, nationOfAgent.size());
        for (Human p : humans.findAll()) {
            assertTrue(p.getAgentId() > 0);
            assertEquals(p.getTeamId(), nationOfAgent.get(p.getAgentId()));
        }
        assertTrue(agentRepository.findAll().stream().allMatch(a -> a.getPatience() >= 3 && a.getPatience() <= 10
                && a.getPersonality() != null));
        assertEquals(0, agents.ensureAgents());
    }

    @Test
    @DisplayName("""
            GIVEN a club whose mates are all represented by agents of the world start,
             a 20-year-old and a 17-year-old there without an agent
            WHEN the season rollover assigns agents to young players
            THEN the 20-year-old takes one of his mates' agents and the 17-year-old stays without
            """)
    void youngPlayerTakesAMatesAgent() {
        for (int i = 0; i < 10; i++) player(1L, 25);
        agents.ensureAgents();
        Set<Long> matesAgents = humans.findAll().stream().map(Human::getAgentId).collect(Collectors.toSet());
        Human twenty = player(1L, 20);
        Human seventeen = player(1L, 17);

        int assigned = agents.assignAgentsToYoungPlayers();

        assertEquals(1, assigned);
        assertTrue(matesAgents.contains(humans.findById(twenty.getId()).orElseThrow().getAgentId()));
        assertEquals(0L, humans.findById(seventeen.getId()).orElseThrow().getAgentId());
    }
}
