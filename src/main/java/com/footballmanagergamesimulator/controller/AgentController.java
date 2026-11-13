package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Agent;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.AgentRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.AgentRelationService;
import com.footballmanagergamesimulator.service.NationService;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The agent page (design §3.2): who he is, how he sees me, and which players he represents. */
@RestController
@RequestMapping("/agents")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class AgentController {

    @Autowired AgentRepository agentRepository;
    @Autowired HumanRepository humanRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired NationService nationService;
    @Autowired AgentRelationService agentRelationService;
    @Autowired UserContext userContext;

    @GetMapping("/{agentId}")
    public ResponseEntity<?> agent(HttpServletRequest request, @PathVariable long agentId) {
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) return ResponseEntity.notFound().build();

        long managerId = myManagerId(request);
        Map<Long, String> teamNames = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Team::getName, (a, b) -> a));
        List<Map<String, Object>> players = humanRepository
                .findAllByAgentIdAndTypeIdAndRetiredFalse(agentId, TypeNames.PLAYER_TYPE).stream()
                .sorted(Comparator.comparingDouble(Human::getRating).reversed())
                .map(playerRow(teamNames))
                .toList();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", agent.getId());
        view.put("name", agent.getName());
        view.put("nationName", nationService.infoFor(agent.getNationId()).name());
        view.put("personality", agent.getPersonality() == null ? null : agent.getPersonality().name());
        view.put("patience", agent.getPatience());
        view.put("relation", managerId > 0 ? agentRelationService.relation(agentId, managerId) : null);
        view.put("refusesToNegotiate", managerId > 0 && agentRelationService.refusesToNegotiate(agentId, managerId));
        view.put("players", players);
        return ResponseEntity.ok(view);
    }

    private static Function<Human, Map<String, Object>> playerRow(Map<Long, String> teamNames) {
        return p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("name", p.getName());
            row.put("position", p.getPosition());
            row.put("age", p.getAge());
            row.put("rating", p.getRating());
            row.put("teamId", p.getTeamId());
            row.put("teamName", p.getTeamId() == null ? "Free agent" : teamNames.getOrDefault(p.getTeamId(), "Free agent"));
            return row;
        };
    }

    /** The current user's manager (a Human of manager type at his club), 0 without a club. */
    private long myManagerId(HttpServletRequest request) {
        Long teamId = userContext.getTeamIdOrNull(request);
        if (teamId == null) return 0;
        List<Human> managers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE);
        return managers.isEmpty() ? 0 : managers.get(0).getId();
    }
}
