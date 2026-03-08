package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service responsible for end-of-season transitions and new-season preparation.
 * Extracted from CompetitionController to provide a clean interface for season lifecycle logic.
 *
 * The methods here are initially stubs that define the correct interface. They will be
 * incrementally populated as logic is migrated from CompetitionController.
 */
@Service
public class SeasonTransitionService {

    public static final long HUMAN_TEAM_ID = 1L;

    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private CompetitionRepository competitionRepository;
    @Autowired
    private SeasonObjectiveRepository seasonObjectiveRepository;
    @Autowired
    private ManagerHistoryRepository managerHistoryRepository;
    @Autowired
    private ManagerInboxRepository managerInboxRepository;
    @Autowired
    private TransferRepository transferRepository;
    @Autowired
    private GameCalendarRepository gameCalendarRepository;
    @Autowired
    private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired
    private CompetitionHistoryRepository competitionHistoryRepository;
    @Autowired
    private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired
    private InjuryRepository injuryRepository;
    @Autowired
    private ScorerRepository scorerRepository;
    @Autowired
    private MatchEventRepository matchEventRepository;
    @Autowired
    private LoanRepository loanRepository;

    // ==================== END OF SEASON ====================

    /**
     * Process all end-of-season activities. Called when the season reaches its final round.
     *
     * @param season the season number that just ended
     * @return map containing processing results (e.g., whether the human manager was fired)
     */
    public Map<String, Object> processEndOfSeason(int season) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. Evaluate season objectives for all teams
        evaluateSeasonObjectives(season);

        // 2. Record manager history entries for the season
        recordManagerHistory(season);

        // 3. Check if the human manager should be fired based on objectives
        boolean humanFired = checkManagerFiring(season);

        // 4. Fire underperforming AI managers
        fireAIManagers(season);

        // 5. Handle contract expiries (players and staff whose contracts expire)
        handleContractExpiries(season + 1);

        // 6. Process player retirements
        processRetirements(season);

        // 7. Generate youth academy players for each team
        generateYouthPlayers(season + 1);

        // 8. Apply sponsorship revenue
        applySponsorshipRevenue(season);

        result.put("humanFired", humanFired);
        result.put("season", season);
        return result;
    }

    // ==================== NEW SEASON PREPARATION ====================

    /**
     * Prepare the new season by handling promotions/relegations, generating fixtures,
     * creating objectives, and resetting stats.
     *
     * @param newSeason the new season number
     */
    public void prepareNewSeason(int newSeason) {
        // 1. Process promotions and relegations
        processPromotionsAndRelegations(newSeason);

        // 2. Generate new season objectives for all teams
        generateSeasonObjectives(newSeason);

        // 3. Reset competition team entries for the new season
        resetCompetitionEntries(newSeason);

        // 4. Clean up old season data that is no longer needed
        cleanupOldSeasonData(newSeason);
    }

    // ==================== SEASON OBJECTIVES ====================

    /**
     * Evaluate all season objectives at end of season.
     * Compares actual results against targets and marks objectives as "achieved" or "failed".
     */
    private void evaluateSeasonObjectives(int season) {
        List<SeasonObjective> objectives = seasonObjectiveRepository.findAllByTeamId(HUMAN_TEAM_ID).stream()
                .filter(obj -> obj.getSeasonNumber() == season && "active".equals(obj.getStatus()))
                .collect(Collectors.toList());

        for (SeasonObjective obj : objectives) {
            if ("league_position".equals(obj.getObjectiveType())) {
                // For league position objectives, lower is better (1st = best)
                // actualValue is filled during season from standings
                if (obj.getActualValue() > 0 && obj.getActualValue() <= obj.getTargetValue()) {
                    obj.setStatus("achieved");
                } else if (obj.getActualValue() > 0) {
                    obj.setStatus("failed");
                }
            } else if ("cup_round".equals(obj.getObjectiveType())) {
                // For cup objectives, higher round = better
                if (obj.getActualValue() >= obj.getTargetValue()) {
                    obj.setStatus("achieved");
                } else {
                    obj.setStatus("failed");
                }
            } else if ("european_qualification".equals(obj.getObjectiveType())) {
                // Binary: qualified or not
                if (obj.getActualValue() >= obj.getTargetValue()) {
                    obj.setStatus("achieved");
                } else {
                    obj.setStatus("failed");
                }
            }
        }

        if (!objectives.isEmpty()) {
            seasonObjectiveRepository.saveAll(objectives);
        }

        // Send inbox summary to the human manager
        long achieved = objectives.stream().filter(o -> "achieved".equals(o.getStatus())).count();
        long failed = objectives.stream().filter(o -> "failed".equals(o.getStatus())).count();

        if (!objectives.isEmpty()) {
            ManagerInbox inbox = new ManagerInbox();
            inbox.setTeamId(HUMAN_TEAM_ID);
            inbox.setSeasonNumber(season);
            inbox.setRoundNumber(0);
            inbox.setTitle("Season " + season + " Objectives Review");
            inbox.setContent("Season objectives review:\n"
                    + "Achieved: " + achieved + "\n"
                    + "Failed: " + failed + "\n"
                    + "The board will take these results into consideration.");
            inbox.setCategory("board");
            inbox.setRead(false);
            inbox.setCreatedAt(System.currentTimeMillis());
            managerInboxRepository.save(inbox);
        }
    }

    // ==================== MANAGER HISTORY ====================

    /**
     * Record a ManagerHistory entry for each manager summarizing their season performance.
     */
    private void recordManagerHistory(int season) {
        List<Human> managers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE);

        for (Human manager : managers) {
            if (manager.getTeamId() == null || manager.getTeamId() == 0) continue;

            long teamId = manager.getTeamId();
            String teamName = teamRepository.findById(teamId).map(Team::getName).orElse("Unknown");

            // Aggregate stats from all competitions for this team
            List<TeamCompetitionDetail> details = teamCompetitionDetailRepository.findAll().stream()
                    .filter(d -> d.getTeamId() == teamId)
                    .collect(Collectors.toList());

            int totalGames = details.stream().mapToInt(TeamCompetitionDetail::getGames).sum();
            int totalWins = details.stream().mapToInt(TeamCompetitionDetail::getWins).sum();
            int totalDraws = details.stream().mapToInt(TeamCompetitionDetail::getDraws).sum();
            int totalLosses = details.stream().mapToInt(TeamCompetitionDetail::getLoses).sum();
            int totalGoalsFor = details.stream().mapToInt(TeamCompetitionDetail::getGoalsFor).sum();
            int totalGoalsAgainst = details.stream().mapToInt(TeamCompetitionDetail::getGoalsAgainst).sum();

            ManagerHistory history = new ManagerHistory();
            history.setManagerId(manager.getId());
            history.setManagerName(manager.getName());
            history.setTeamId(teamId);
            history.setTeamName(teamName);
            history.setSeasonNumber(season);
            history.setGamesPlayed(totalGames);
            history.setWins(totalWins);
            history.setDraws(totalDraws);
            history.setLosses(totalLosses);
            history.setGoalsFor(totalGoalsFor);
            history.setGoalsAgainst(totalGoalsAgainst);

            managerHistoryRepository.save(history);
        }
    }

    // ==================== MANAGER FIRING ====================

    /**
     * Check if the human manager should be fired based on failed objectives.
     * Uses a weighted scoring system where critical objectives count more.
     *
     * @return true if the human manager is fired
     */
    private boolean checkManagerFiring(int season) {
        List<SeasonObjective> humanObjectives = seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(HUMAN_TEAM_ID, season);
        if (humanObjectives.isEmpty()) return false;

        int firingScore = 0;
        for (SeasonObjective obj : humanObjectives) {
            if (!"failed".equals(obj.getStatus())) continue;

            int weight = "critical".equals(obj.getImportance()) ? 3
                    : "high".equals(obj.getImportance()) ? 2 : 1;

            if ("league_position".equals(obj.getObjectiveType())) {
                int miss = obj.getActualValue() - obj.getTargetValue();
                firingScore += weight * Math.min(miss, 5);
            } else {
                int miss = obj.getTargetValue() - obj.getActualValue();
                firingScore += weight * Math.min(miss, 3);
            }
        }

        if (firingScore >= 12) {
            // Fire the human manager
            ManagerInbox inbox = new ManagerInbox();
            inbox.setTeamId(HUMAN_TEAM_ID);
            inbox.setSeasonNumber(season);
            inbox.setRoundNumber(0);
            inbox.setTitle("You Have Been Sacked!");
            inbox.setContent("The board has lost all confidence in your ability to manage this club. "
                    + "You have been relieved of your duties with immediate effect. "
                    + "Check the available jobs to find a new position.");
            inbox.setCategory("board");
            inbox.setRead(false);
            inbox.setCreatedAt(System.currentTimeMillis());
            managerInboxRepository.save(inbox);

            // Reduce manager reputation
            Human humanManager = humanRepository.findAllByTeamIdAndTypeId(HUMAN_TEAM_ID, TypeNames.MANAGER_TYPE)
                    .stream().findFirst().orElse(null);
            if (humanManager != null) {
                humanManager.setManagerReputation(Math.max(0, humanManager.getManagerReputation() - 100));
                humanRepository.save(humanManager);
            }

            return true;
        } else if (firingScore >= 6) {
            // Warning from the board
            ManagerInbox inbox = new ManagerInbox();
            inbox.setTeamId(HUMAN_TEAM_ID);
            inbox.setSeasonNumber(season);
            inbox.setRoundNumber(0);
            inbox.setTitle("Board Warning");
            inbox.setContent("The board is disappointed with this season's results. "
                    + "Significant improvement is expected next season or your position will be reconsidered.");
            inbox.setCategory("board");
            inbox.setRead(false);
            inbox.setCreatedAt(System.currentTimeMillis());
            managerInboxRepository.save(inbox);
        }

        return false;
    }

    /**
     * Fire AI managers who have severely underperformed.
     * AI managers are evaluated more leniently but can be fired for very poor results.
     */
    private void fireAIManagers(int season) {
        List<Human> aiManagers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE).stream()
                .filter(m -> m.getTeamId() != null && m.getTeamId() != HUMAN_TEAM_ID)
                .collect(Collectors.toList());

        for (Human manager : aiManagers) {
            List<SeasonObjective> objectives = seasonObjectiveRepository
                    .findAllByTeamIdAndSeasonNumber(manager.getTeamId(), season);

            long failedCritical = objectives.stream()
                    .filter(o -> "failed".equals(o.getStatus()) && "critical".equals(o.getImportance()))
                    .count();

            // AI managers only get fired for failing critical objectives badly
            if (failedCritical >= 2) {
                manager.setManagerReputation(Math.max(0, manager.getManagerReputation() - 50));
                humanRepository.save(manager);
                // Note: Full firing logic (reassigning team, generating replacement) is in CompetitionController
            }
        }
    }

    // ==================== CONTRACT EXPIRIES ====================

    /**
     * Handle contract expiries for the upcoming season.
     * Players and staff whose contracts expire become free agents.
     */
    private void handleContractExpiries(int newSeason) {
        // Find players whose contracts expire at end of current season
        List<Team> allTeams = teamRepository.findAll();

        for (Team team : allTeams) {
            List<Human> expiringPlayers = humanRepository
                    .findAllByTeamIdAndTypeIdAndContractEndSeasonLessThanEqual(
                            team.getId(), TypeNames.PLAYER_TYPE, newSeason - 1);

            for (Human player : expiringPlayers) {
                if (player.isRetired()) continue;

                // For human team, send notification about expiring contracts
                if (team.getId() == HUMAN_TEAM_ID) {
                    ManagerInbox inbox = new ManagerInbox();
                    inbox.setTeamId(HUMAN_TEAM_ID);
                    inbox.setSeasonNumber(newSeason - 1);
                    inbox.setRoundNumber(0);
                    inbox.setTitle("Contract Expired: " + player.getName());
                    inbox.setContent(player.getName() + " has left the club as their contract has expired.");
                    inbox.setCategory("transfer");
                    inbox.setRead(false);
                    inbox.setCreatedAt(System.currentTimeMillis());
                    managerInboxRepository.save(inbox);
                }

                // Release the player (set teamId to 0 = free agent)
                player.setTeamId(0L);
                humanRepository.save(player);
            }
        }
    }

    // ==================== PLAYER RETIREMENT ====================

    /**
     * Process end-of-season retirements. Players over a certain age with declining ability
     * may choose to retire.
     */
    private void processRetirements(int season) {
        List<Human> allPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);
        Random random = new Random();

        for (Human player : allPlayers) {
            if (player.isRetired()) continue;

            int age = player.getAge();
            double retirementChance;

            if (age < 33) continue; // Too young to retire
            else if (age <= 34) retirementChance = 0.10;
            else if (age <= 36) retirementChance = 0.30;
            else if (age <= 38) retirementChance = 0.60;
            else retirementChance = 0.90;

            // Lower rated players retire sooner
            if (player.getRating() < 60) retirementChance += 0.15;

            if (random.nextDouble() < retirementChance) {
                player.setRetired(true);
                player.setCurrentStatus("Retired");
                humanRepository.save(player);

                // Notify human team if one of their players retired
                if (player.getTeamId() != null && player.getTeamId() == HUMAN_TEAM_ID) {
                    ManagerInbox inbox = new ManagerInbox();
                    inbox.setTeamId(HUMAN_TEAM_ID);
                    inbox.setSeasonNumber(season);
                    inbox.setRoundNumber(0);
                    inbox.setTitle("Player Retired: " + player.getName());
                    inbox.setContent(player.getName() + " (age " + age + ") has announced their retirement from professional football.");
                    inbox.setCategory("player_news");
                    inbox.setRead(false);
                    inbox.setCreatedAt(System.currentTimeMillis());
                    managerInboxRepository.save(inbox);
                }
            }
        }
    }

    // ==================== YOUTH GENERATION ====================

    /**
     * Generate youth academy players for each team for the new season.
     * Stub: will be populated when the youth generation logic is migrated from CompetitionController.
     */
    private void generateYouthPlayers(int newSeason) {
        // Stub: youth player generation logic will be migrated from CompetitionController.
        // Each team receives 1-3 new youth players per season from their academy.
    }

    // ==================== SPONSORSHIP ====================

    /**
     * Apply sponsorship revenue to teams at end of season.
     * Stub: will be populated when sponsorship logic is migrated.
     */
    private void applySponsorshipRevenue(int season) {
        // Stub: sponsorship revenue application logic will be migrated from CompetitionController.
    }

    // ==================== PROMOTIONS / RELEGATIONS ====================

    /**
     * Process promotions and relegations between leagues based on final standings.
     * Stub: will be populated with full promotion/relegation logic.
     */
    private void processPromotionsAndRelegations(int newSeason) {
        // Stub: promotion/relegation logic will be migrated from CompetitionController.
        // Involves swapping bottom N teams from league 1 with top N from league 2 (typeId 3).
    }

    /**
     * Generate season objectives for all teams in the new season.
     * Stub: objectives generation logic will be migrated.
     */
    private void generateSeasonObjectives(int newSeason) {
        // Stub: season objectives generation will be migrated from CompetitionController.
    }

    /**
     * Reset competition team entries and standings for the new season.
     */
    private void resetCompetitionEntries(int newSeason) {
        // Stub: competition entry reset logic will be migrated from CompetitionController.
    }

    /**
     * Clean up data from previous seasons that is no longer needed for active gameplay.
     */
    private void cleanupOldSeasonData(int newSeason) {
        // Clear injuries from old season
        List<Injury> oldInjuries = injuryRepository.findAll().stream()
                .filter(i -> i.getSeasonNumber() < newSeason - 1)
                .collect(Collectors.toList());
        if (!oldInjuries.isEmpty()) {
            injuryRepository.deleteAll(oldInjuries);
        }

        // Clear match events from seasons older than 2 seasons ago
        matchEventRepository.deleteAllBySeasonNumber(newSeason - 3);
    }

    // ==================== LOAN RETURNS ====================

    /**
     * Return all loaned players to their parent clubs at end of season.
     * Stub: loan return logic will be migrated.
     */
    public void processLoanReturns(int season) {
        // Stub: loan return logic will be migrated from CompetitionController.
        // All loans ending this season should return players to their parent clubs.
    }
}
