package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamPlayerHistoricalRelation;
import com.footballmanagergamesimulator.model.YouthPlayer;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamPlayerHistoricalRelationRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.YouthPlayerRepository;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guarantees that every club starts a new season with a usable permanent
 * first-team squad. Existing academy prospects are promoted first; when an
 * academy cannot cover the deficit, new prospects are generated through the
 * normal youth-academy quality model and immediately promoted.
 */
@Service
public class MinimumSquadService {

    public static final int MINIMUM_SQUAD_SIZE = 18;

    @Autowired private TeamRepository teamRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private YouthPlayerRepository youthPlayerRepository;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private TeamPlayerHistoricalRelationRepository historicalRelationRepository;
    @Autowired private ManagerInboxRepository managerInboxRepository;
    @Autowired private YouthAcademyService youthAcademyService;
    @Autowired private CompetitionService competitionService;
    @Autowired private StaffService staffService;
    @Autowired private UserContext userContext;

    @Transactional
    public CompletionSummary ensureMinimumSquads(int season) {
        List<Team> teams = teamRepository.findAll();
        Map<Long, List<Human>> activePlayersByTeam = humanRepository
                .findAllByTypeId(TypeNames.PLAYER_TYPE).stream()
                .filter(player -> !player.isRetired() && player.getTeamId() != null
                        && player.getTeamId() > 0)
                .collect(Collectors.groupingBy(Human::getTeamId));

        Map<Long, List<YouthPlayer>> academyByTeam = youthPlayerRepository.findAll().stream()
                .filter(prospect -> "IN_ACADEMY".equals(prospect.getStatus()))
                .collect(Collectors.groupingBy(YouthPlayer::getTeamId));
        academyByTeam.values().forEach(prospects -> prospects.sort(
                Comparator.comparingInt(YouthPlayer::getCurrentAbility).reversed()
                        .thenComparing(Comparator.comparingInt(
                                YouthPlayer::getPotentialAbility).reversed())
                        .thenComparingLong(YouthPlayer::getId)));

        Set<Long> humanTeamIds = new HashSet<>(userContext.getAllHumanTeamIds());
        Random random = new Random();
        List<Promotion> promotions = new ArrayList<>();
        Map<Long, List<String>> promotedNamesByTeam = new LinkedHashMap<>();
        int generatedProspects = 0;

        for (Team team : teams) {
            List<Human> currentPlayers = activePlayersByTeam
                    .getOrDefault(team.getId(), List.of());
            int deficit = MINIMUM_SQUAD_SIZE - currentPlayers.size();
            if (deficit <= 0) continue;

            Set<Integer> usedShirtNumbers = currentPlayers.stream()
                    .map(Human::getShirtNumber)
                    .filter(number -> number > 0)
                    .collect(Collectors.toSet());
            List<YouthPlayer> availableProspects = new ArrayList<>(
                    academyByTeam.getOrDefault(team.getId(), List.of()));
            int hoydQuality = staffService.getHOYDQuality(team.getId());

            for (int index = 0; index < deficit; index++) {
                YouthPlayer prospect;
                if (index < availableProspects.size()) {
                    prospect = availableProspects.get(index);
                } else {
                    prospect = youthAcademyService.createProspect(
                            team.getId(), season, random, hoydQuality);
                    generatedProspects++;
                }

                int shirtNumber = nextShirtNumber(usedShirtNumbers);
                usedShirtNumbers.add(shirtNumber);
                Human player = createGraduate(prospect, team.getId(), season,
                        shirtNumber, random);
                promotions.add(new Promotion(team, prospect, player));
                promotedNamesByTeam.computeIfAbsent(team.getId(), ignored -> new ArrayList<>())
                        .add(player.getName());
                team.setSalaryBudget(team.getSalaryBudget() + player.getWage());
            }
        }

        if (promotions.isEmpty()) {
            return new CompletionSummary(0, 0, 0, MINIMUM_SQUAD_SIZE);
        }

        List<Human> newPlayers = promotions.stream().map(Promotion::player)
                .collect(Collectors.toCollection(ArrayList::new));
        humanRepository.saveAll(newPlayers); // assigns ids before dependent rows

        List<PlayerSkills> skills = new ArrayList<>(promotions.size());
        List<TeamPlayerHistoricalRelation> relations = new ArrayList<>(promotions.size());
        for (Promotion promotion : promotions) {
            Human player = promotion.player();
            PlayerSkills playerSkills = new PlayerSkills();
            playerSkills.setPlayerId(player.getId());
            playerSkills.setPosition(player.getPosition());
            competitionService.generateSkills(playerSkills, player.getRating());

            double computedRating = PlayerSkillsService.computeOverallRating(playerSkills);
            player.setRating(computedRating);
            player.setCurrentAbility((int) computedRating);
            player.setBestEverRating(computedRating);
            player.setTransferValue(TransferValueCalculator.calculate(
                    player.getAge(), player.getPosition(), computedRating));

            YouthPlayer prospect = promotion.prospect();
            prospect.setStatus("PROMOTED");
            prospect.setPlayerId(player.getId());

            TeamPlayerHistoricalRelation relation = new TeamPlayerHistoricalRelation();
            relation.setPlayerId(player.getId());
            relation.setTeamId(promotion.team().getId());
            relation.setSeasonNumber(season);
            relation.setRating(computedRating);
            relations.add(relation);
            skills.add(playerSkills);
        }

        humanRepository.saveAll(newPlayers);
        playerSkillsRepository.saveAll(skills);
        historicalRelationRepository.saveAll(relations);
        youthPlayerRepository.saveAll(promotions.stream().map(Promotion::prospect).toList());
        teamRepository.saveAll(promotions.stream().map(Promotion::team).distinct().toList());

        List<ManagerInbox> notifications = new ArrayList<>();
        for (Map.Entry<Long, List<String>> entry : promotedNamesByTeam.entrySet()) {
            if (!humanTeamIds.contains(entry.getKey())) continue;
            ManagerInbox inbox = new ManagerInbox();
            inbox.setTeamId(entry.getKey());
            inbox.setSeasonNumber(season);
            inbox.setTitle("Academy players promoted to complete the squad");
            inbox.setContent("The first-team squad had fewer than " + MINIMUM_SQUAD_SIZE
                    + " players. The following academy players were promoted automatically: "
                    + String.join(", ", entry.getValue()) + ".");
            inbox.setCategory("YOUTH_ACADEMY");
            inbox.setRead(false);
            inbox.setCreatedAt(System.currentTimeMillis());
            notifications.add(inbox);
        }
        managerInboxRepository.saveAll(notifications);

        int affectedTeams = promotedNamesByTeam.size();
        System.out.println("=== Minimum squad service: promoted " + promotions.size()
                + " academy player(s) across " + affectedTeams + " team(s); generated "
                + generatedProspects + " new prospect(s) ===");
        return new CompletionSummary(promotions.size(), generatedProspects,
                affectedTeams, MINIMUM_SQUAD_SIZE);
    }

    private Human createGraduate(YouthPlayer prospect, long teamId, int season,
                                 int shirtNumber, Random random) {
        long wage = Math.max(WageService.baseWage(prospect.getCurrentAbility()), 500);
        Human player = new Human();
        player.setTypeId(TypeNames.PLAYER_TYPE);
        player.setTeamId(teamId);
        player.setPosition(prospect.getPosition());
        player.setName(prospect.getName());
        player.setAge(prospect.getAge());
        player.setRating(prospect.getCurrentAbility());
        player.setCurrentAbility(prospect.getCurrentAbility());
        player.setPotentialAbility(prospect.getPotentialAbility());
        player.setBestEverRating(prospect.getCurrentAbility());
        player.setSeasonOfBestEverRating(season);
        player.setMorale(75);
        player.setFitness(100);
        player.setCurrentStatus("Junior");
        player.setRetired(false);
        player.setContractEndSeason(season + 5);
        player.setWage(wage);
        player.setSalary(wage);
        player.setReleaseClause(wage * 52 * 5);
        player.setTransferValue(TransferValueCalculator.calculate(
                prospect.getAge(), prospect.getPosition(), prospect.getCurrentAbility()));
        player.setSeasonCreated(season);
        player.setShirtNumber(shirtNumber);
        HumanService.generatePhysicalProfile(player, random);
        return player;
    }

    private int nextShirtNumber(Set<Integer> usedNumbers) {
        for (int number = 30; number <= 99; number++) {
            if (!usedNumbers.contains(number)) return number;
        }
        for (int number = 1; number <= 29; number++) {
            if (!usedNumbers.contains(number)) return number;
        }
        return 0;
    }

    private record Promotion(Team team, YouthPlayer prospect, Human player) {}

    public record CompletionSummary(int promotedPlayers, int generatedProspects,
                                    int affectedTeams, int minimumSquadSize) {}
}
