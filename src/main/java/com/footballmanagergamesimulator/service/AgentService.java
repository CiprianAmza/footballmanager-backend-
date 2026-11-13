package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Agent;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerPersonality;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.AgentRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/** Creates agents and decides which agent represents which player (design §3.2). */
@Service
public class AgentService {

    @Autowired AgentRepository agentRepository;
    @Autowired HumanRepository humanRepository;
    @Autowired NationService nationService;
    @Autowired CompositeNameGenerator nameGenerator;

    Random rng = new Random();

    /**
     * World start (and any save without agents): every active player gets an agent.
     * Per nation, agents with random portfolios of 3–50; players split between them at random.
     * @return number of agents created (0 if agents already exist)
     */
    @Transactional
    public int ensureAgents() {
        ensurePlayerPersonalities();
        if (agentRepository.count() > 0) return 0;
        Map<Long, NationService.NationInfo> nations = nationService.infoByTeamId();
        Map<Long, List<Human>> playersByNation = activePlayers().stream()
                .collect(Collectors.groupingBy(p -> nationOf(p, nations), java.util.TreeMap::new, Collectors.toList()));
        int created = 0;
        List<Human> changed = new ArrayList<>();
        for (Map.Entry<Long, List<Human>> nation : playersByNation.entrySet()) {
            for (List<Human> portfolio : AgentDistribution.portfolios(nation.getValue(), rng)) {
                Agent agent = agentRepository.save(newAgent(nation.getKey()));
                created++;
                for (Human player : portfolio) {
                    player.setAgentId(agent.getId());
                    changed.add(player);
                }
            }
        }
        humanRepository.saveAll(changed);
        return created;
    }

    /**
     * Season rollover, after ages went up: agentless players aged 18+ may take an agent
     * (18 → 1/3, 19 → 1/2, 20+ → certain), chosen among the agents of their club mates,
     * weighted by how many mates each represents. Without such mates: any agent of the nation.
     * @return number of players who took an agent
     */
    @Transactional
    public int assignAgentsToYoungPlayers() {
        ensurePlayerPersonalities();
        List<Human> players = activePlayers();
        Map<Long, Map<Long, Integer>> agentsByTeam = new HashMap<>();
        for (Human p : players) {
            if (p.getAgentId() > 0 && p.getTeamId() != null) {
                agentsByTeam.computeIfAbsent(p.getTeamId(), t -> new HashMap<>()).merge(p.getAgentId(), 1, Integer::sum);
            }
        }
        Map<Long, NationService.NationInfo> nations = null;
        List<Human> changed = new ArrayList<>();
        for (Human p : players) {
            if (p.getAgentId() > 0) continue;
            if (rng.nextDouble() >= AgentDistribution.takeAgentChance(p.getAge())) continue;
            long agentId = p.getTeamId() == null ? 0L
                    : AgentDistribution.pickFromClubMates(agentsByTeam.getOrDefault(p.getTeamId(), Map.of()), rng);
            if (agentId == 0L) {
                if (nations == null) nations = nationService.infoByTeamId();
                agentId = anyAgentOfNation(nationOf(p, nations));
            }
            p.setAgentId(agentId);
            changed.add(p);
        }
        humanRepository.saveAll(changed);
        return changed.size();
    }

    /** Every active player without a personality gets one at random (a third each). */
    @Transactional
    public int ensurePlayerPersonalities() {
        List<Human> missing = activePlayers().stream().filter(p -> p.getPlayerPersonality() == null).toList();
        PlayerPersonality[] all = PlayerPersonality.values();
        missing.forEach(p -> p.setPlayerPersonality(all[rng.nextInt(all.length)]));
        humanRepository.saveAll(missing);
        return missing.size();
    }

    private long anyAgentOfNation(long nationId) {
        List<Agent> agents = agentRepository.findAllByNationId(nationId);
        if (agents.isEmpty()) return agentRepository.save(newAgent(nationId)).getId();
        return agents.get(rng.nextInt(agents.size())).getId();
    }

    /** The player fired his agent: he takes another agent of his nation at random (a new one if there is none). */
    @Transactional
    public long replaceAgent(Human player) {
        long nationId = nationOf(player, nationService.infoByTeamId());
        List<Agent> others = agentRepository.findAllByNationId(nationId).stream()
                .filter(a -> a.getId() != player.getAgentId()).toList();
        long agentId = others.isEmpty() ? agentRepository.save(newAgent(nationId)).getId()
                : others.get(rng.nextInt(others.size())).getId();
        player.setAgentId(agentId);
        humanRepository.save(player);
        return agentId;
    }

    private Agent newAgent(long nationId) {
        Agent agent = new Agent();
        agent.setNationId(nationId);
        agent.setName(nameGenerator.generateNameForNation(nationId));
        agent.setPersonality(AgentDistribution.personality(rng));
        agent.setPatience(AgentDistribution.patience(rng));
        return agent;
    }

    private List<Human> activePlayers() {
        return humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE).stream()
                .filter(p -> !p.isRetired())
                .toList();
    }

    private static long nationOf(Human player, Map<Long, NationService.NationInfo> nations) {
        if (player.getTeamId() == null) return 0L;
        NationService.NationInfo info = nations.get(player.getTeamId());
        return info == null ? 0L : info.id();
    }
}
