package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.service.HumanService;
import com.footballmanagergamesimulator.service.PlayerSkillsService;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class YouthAcademyService {

    @Autowired
    YouthPlayerRepository youthPlayerRepository;
    @Autowired
    HumanRepository humanRepository;
    @Autowired
    TeamRepository teamRepository;
    @Autowired
    RoundRepository roundRepository;
    @Autowired
    ManagerInboxRepository managerInboxRepository;
    @Autowired
    CompositeNameGenerator compositeNameGenerator;
    @Autowired
    PlayerSkillsRepository playerSkillsRepository;
    @Autowired
    CompetitionService competitionService;
    @Autowired
    TeamPlayerHistoricalRelationRepository teamPlayerHistoricalRelationRepository;
    @Autowired
    @Lazy
    CompetitionController competitionController;
    @Autowired
    StaffService staffService;
    @Autowired
    GameLock gameLock;
    @Autowired
    ScorerLeaderboardSyncService scorerLeaderboardSyncService;

    private static final String[] POSITIONS = {"GK", "DC", "DL", "WBL", "DR", "WBR", "MC", "DM", "ML", "AML", "MR", "AMR", "AMC", "ST"};
    private static final int[] POSITION_WEIGHTS = {5, 14, 8, 5, 8, 5, 11, 7, 8, 6, 8, 6, 9, 14};

    public void generateYouthReport(long teamId, int season) {

        Random random = new Random();
        int count = random.nextInt(1, 4); // 1-3 new youth players
        List<YouthPlayer> newProspects = new ArrayList<>();

        // HOYD quality boosts youth player potential (1-20 scale)
        int hoydQuality = staffService.getHOYDQuality(teamId);

        for (int i = 0; i < count; i++) {
            YouthPlayer yp = createProspect(teamId, season, random, hoydQuality);
            youthPlayerRepository.save(yp);
            newProspects.add(yp);
        }

        // Send inbox message to manager
        StringBuilder content = new StringBuilder("Youth Academy Report - New Prospects Found:\n\n");
        for (YouthPlayer yp : newProspects) {
            content.append(String.format("- %s, Age %d, %s, Potential: %s, Current Ability: %d\n",
                    yp.getName(), yp.getAge(), yp.getPosition(), yp.getPotential(), yp.getCurrentAbility()));
        }

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(season);
        inbox.setTitle("Youth Academy Report");
        inbox.setContent(content.toString());
        inbox.setCategory("YOUTH_ACADEMY");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());

        managerInboxRepository.save(inbox);
    }

    /**
     * Creates an unsaved academy prospect using the same rules as the regular
     * youth report. Package visibility lets the season-end minimum-squad
     * service batch-persist emergency academy graduates without duplicating
     * the academy quality model.
     */
    YouthPlayer createProspect(long teamId, int season, Random random, int hoydQuality) {
        YouthPlayer prospect = new YouthPlayer();
        prospect.setTeamId(teamId);
        prospect.setName(compositeNameGenerator.generateName(1L));
        prospect.setAge(random.nextInt(15, 19));
        int basePotential = generateWeightedPotential(random);
        // HOYD quality bonus: up to +10 potential for top HOYD (quality 20)
        int hoydBonus = (int) (hoydQuality * 0.5);
        prospect.setPotentialAbility(Math.min(99, basePotential + hoydBonus));
        prospect.setCurrentAbility((int) (prospect.getPotentialAbility()
                * random.nextDouble(0.3, 0.6)));
        prospect.setPosition(generateWeightedPosition(random));
        prospect.setPotential(categorizePotential(prospect.getPotentialAbility()));
        prospect.setStatus("IN_ACADEMY");
        prospect.setSeasonJoined(season);
        prospect.setDaysInAcademy(0);
        return prospect;
    }

    public Human promoteToFirstTeam(long youthPlayerId, long teamId) {
        // Serialize against the calendar advance / season transition: both write
        // the same TEAM row (salary budget) and racing them tripped H2's
        // lock-timeout during the off-season transition.
        gameLock.lock();
        try {
            return promoteToFirstTeamLocked(youthPlayerId, teamId);
        } finally {
            gameLock.unlock();
        }
    }

    private Human promoteToFirstTeamLocked(long youthPlayerId, long teamId) {

        YouthPlayer yp = youthPlayerRepository.findById(youthPlayerId)
                .orElseThrow(() -> new RuntimeException("Youth player not found: " + youthPlayerId));

        Round round = roundRepository.findById(1L).orElse(new Round());
        int currentSeason = (int) round.getSeason();

        // Wage from the shared curve so a promoted youth matches everyone else's rating-based wage
        long youthWage = WageService.baseWage(yp.getCurrentAbility());
        youthWage = Math.max(youthWage, 500); // minimum wage for youth

        // Calculate release clause (5x annual wage equivalent)
        long releaseClause = youthWage * 52 * 5;

        // Find next available shirt number
        List<Human> teamPlayers = humanRepository.findAllByTeamId(teamId);
        Set<Integer> usedNumbers = new HashSet<>();
        for (Human h : teamPlayers) {
            if (!h.isRetired()) usedNumbers.add(h.getShirtNumber());
        }
        int shirtNumber = 1;
        for (int n = 30; n <= 99; n++) {
            if (!usedNumbers.contains(n)) { shirtNumber = n; break; }
        }

        Human human = new Human();
        human.setTypeId(1); // PLAYER_TYPE
        human.setTeamId(teamId);
        human.setPosition(yp.getPosition());
        human.setName(yp.getName());
        human.setAge(yp.getAge());
        human.setRating(yp.getCurrentAbility());
        human.setCurrentAbility(yp.getCurrentAbility());
        human.setPotentialAbility(yp.getPotentialAbility());
        human.setBestEverRating(yp.getCurrentAbility());
        human.setSeasonOfBestEverRating(currentSeason);
        human.setMorale(75);
        human.setFitness(70);
        human.setCurrentStatus("Junior");
        human.setRetired(false);
        human.setContractEndSeason(currentSeason + 5);
        human.setWage(youthWage);
        human.setSalary(youthWage);
        human.setReleaseClause(releaseClause);
        human.setTransferValue(com.footballmanagergamesimulator.service.TransferValueCalculator.calculate(
                yp.getAge(), yp.getPosition(), yp.getCurrentAbility()));
        human.setSeasonCreated(currentSeason);
        human.setShirtNumber(shirtNumber);

        // Generate physical profile
        HumanService.generatePhysicalProfile(human, new java.util.Random());

        human = humanRepository.save(human);

        // Generate player skills
        PlayerSkills playerSkills = new PlayerSkills();
        playerSkills.setPlayerId(human.getId());
        playerSkills.setPosition(yp.getPosition());
        competitionService.generateSkills(playerSkills, yp.getCurrentAbility());
        playerSkillsRepository.save(playerSkills);

        // Recompute rating from attributes
        double computedRating = PlayerSkillsService.computeOverallRating(playerSkills);
        human.setRating(computedRating);
        human.setCurrentAbility((int) computedRating);
        human.setBestEverRating(computedRating);
        humanRepository.save(human);

        // Create historical relation for player career history
        TeamPlayerHistoricalRelation relation = new TeamPlayerHistoricalRelation();
        relation.setPlayerId(human.getId());
        relation.setTeamId(teamId);
        relation.setSeasonNumber(currentSeason);
        relation.setRating(human.getRating());
        teamPlayerHistoricalRelationRepository.save(relation);

        // A promoted academy player can enter the next match immediately; make
        // sure the denormalized Golden Boot row exists before that simulation.
        scorerLeaderboardSyncService.trackNewPlayer(human);

        // Update team salary budget
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team != null) {
            team.setSalaryBudget(team.getSalaryBudget() + youthWage);
            teamRepository.save(team);
        }

        yp.setStatus("PROMOTED");
        yp.setPlayerId(human.getId());
        youthPlayerRepository.save(yp);

        return human;
    }

    public void developYouthPlayers(long teamId) {

        Random random = new Random();
        List<YouthPlayer> youthSquad = youthPlayerRepository.findAllByTeamIdAndStatus(teamId, "IN_ACADEMY");

        for (YouthPlayer yp : youthSquad) {
            int potentialGap = yp.getPotentialAbility() - yp.getCurrentAbility();
            double growthFactor = Math.min(1.0, potentialGap / 50.0);
            double growth = 0.1 + (random.nextDouble() * 0.4 * growthFactor);
            yp.setCurrentAbility(Math.min(yp.getCurrentAbility() + (int) Math.ceil(growth), yp.getPotentialAbility()));
            yp.setDaysInAcademy(yp.getDaysInAcademy() + 1);

            if (yp.getCurrentAbility() >= yp.getPotentialAbility() * 0.8) {
                ManagerInbox inbox = new ManagerInbox();
                inbox.setTeamId(teamId);
                inbox.setTitle("Youth Player Ready for Promotion");
                inbox.setContent(String.format("%s (%s, Age %d) has reached %d ability and may be ready for first team promotion.",
                        yp.getName(), yp.getPosition(), yp.getAge(), yp.getCurrentAbility()));
                inbox.setCategory("YOUTH_ACADEMY");
                inbox.setRead(false);
                inbox.setCreatedAt(System.currentTimeMillis());
                managerInboxRepository.save(inbox);
            }
        }

        youthPlayerRepository.saveAll(youthSquad);
    }

    public void releaseYouthPlayer(long youthPlayerId) {

        YouthPlayer yp = youthPlayerRepository.findById(youthPlayerId)
                .orElseThrow(() -> new RuntimeException("Youth player not found: " + youthPlayerId));
        yp.setStatus("RELEASED");
        youthPlayerRepository.save(yp);
    }

    public List<YouthPlayer> getYouthSquad(long teamId) {
        return youthPlayerRepository.findAllByTeamIdAndStatus(teamId, "IN_ACADEMY");
    }

    private int generateWeightedPotential(Random random) {
        // Higher potential = rarer. Use weighted distribution.
        double roll = random.nextDouble();
        if (roll < 0.40) {
            return random.nextInt(40, 55);  // 40% chance: low potential
        } else if (roll < 0.70) {
            return random.nextInt(55, 65);  // 30% chance: average potential
        } else if (roll < 0.90) {
            return random.nextInt(65, 75);  // 20% chance: good potential
        } else {
            return random.nextInt(75, 91);  // 10% chance: star potential
        }
    }

    private String categorizePotential(int potentialAbility) {
        if (potentialAbility >= 75) return "STAR";
        if (potentialAbility >= 65) return "GOOD";
        if (potentialAbility >= 55) return "AVERAGE";
        return "LOW";
    }

    private String generateWeightedPosition(Random random) {
        int totalWeight = 0;
        for (int w : POSITION_WEIGHTS) totalWeight += w;

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (int i = 0; i < POSITIONS.length; i++) {
            cumulative += POSITION_WEIGHTS[i];
            if (roll < cumulative) return POSITIONS[i];
        }
        return "MC";
    }
}
