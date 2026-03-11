package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.user.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/scouts")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class ScoutManagementController {

    @Autowired
    private ScoutRepository scoutRepository;

    @Autowired
    private ScoutAssignmentRepository scoutAssignmentRepository;

    @Autowired
    private HumanRepository humanRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private RoundRepository roundRepository;

    @Autowired
    private GameCalendarRepository gameCalendarRepository;

    @Autowired
    private ManagerInboxRepository managerInboxRepository;

    @Autowired
    private CompetitionRepository competitionRepository;

    @Autowired
    private UserContext userContext;

    private final Random random = new Random();

    private Round getRound() {
        return roundRepository.findById(1L).orElse(new Round());
    }

    private GameCalendar getCurrentCalendar() {
        Round round = getRound();
        int season = (int) round.getSeason();
        List<GameCalendar> calendars = gameCalendarRepository.findBySeason(season);
        return calendars.isEmpty() ? null : calendars.get(0);
    }

    // ==========================================
    // SCOUT GENERATION (called at game start / season start)
    // ==========================================

    /**
     * Generate free agent scouts if none exist.
     * Called from CompetitionController or on first access.
     */
    public void generateFreeAgentScouts() {
        List<Scout> existing = scoutRepository.findAllByTeamIdIsNull();
        if (!existing.isEmpty()) return; // already have free agents

        String[] firstNames = {"Marco", "Pierre", "Hans", "Carlos", "João", "Sven", "Luca",
                "Dimitri", "Yuri", "Ahmed", "Kenji", "Patrick", "Fabio", "Oscar", "Nikolai",
                "Rui", "Stefan", "Diego", "Hugo", "Erik"};
        String[] lastNames = {"Silva", "Müller", "Rossi", "García", "Petrov", "Andersson",
                "Dubois", "Nakamura", "Santos", "Weber", "Fernandez", "Kowalski",
                "Jansen", "O'Brien", "Larsson", "Moreira", "Bianchi", "Koval", "Tanaka", "Berg"};

        // Get all competition IDs for league specialization
        List<Competition> competitions = competitionRepository.findAll();
        List<Long> competitionIds = competitions.stream().map(Competition::getId).collect(Collectors.toList());

        Round round = getRound();
        int currentSeason = (int) round.getSeason();

        List<Scout> scouts = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Scout scout = new Scout();
            scout.setName(firstNames[random.nextInt(firstNames.length)] + " " +
                    lastNames[random.nextInt(lastNames.length)]);

            // Random abilities 5-18
            scout.setScoutingAbility(5 + random.nextInt(14));
            scout.setExperience(3 + random.nextInt(16));
            scout.setJudgingPotential(4 + random.nextInt(15));

            // Wage based on ability
            int avgAbility = (scout.getScoutingAbility() + scout.getExperience() + scout.getJudgingPotential()) / 3;
            long baseWage = (long) (avgAbility * avgAbility * 50); // 1250 - 16200
            scout.setWageDemand(baseWage);
            scout.setWage(0);

            scout.setContractEndSeason(0);
            scout.setTeamId(null);
            scout.setHired(false);

            // Each scout knows 1-3 leagues
            int numLeagues = 1 + random.nextInt(Math.min(3, competitionIds.size()));
            Set<Long> knownSet = new HashSet<>();
            while (knownSet.size() < numLeagues && knownSet.size() < competitionIds.size()) {
                knownSet.add(competitionIds.get(random.nextInt(competitionIds.size())));
            }
            scout.setKnownLeagues(knownSet.stream().map(String::valueOf).collect(Collectors.joining(",")));

            scouts.add(scout);
        }

        scoutRepository.saveAll(scouts);
    }

    // ==========================================
    // ENDPOINTS
    // ==========================================

    /**
     * Get scouts available for hire (free agents)
     */
    @GetMapping("/available")
    public List<Map<String, Object>> getAvailableScouts() {
        // Generate scouts if none exist
        generateFreeAgentScouts();

        List<Scout> freeAgents = scoutRepository.findAllByTeamIdIsNull();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Scout scout : freeAgents) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", scout.getId());
            info.put("name", scout.getName());
            info.put("scoutingAbility", scout.getScoutingAbility());
            info.put("experience", scout.getExperience());
            info.put("judgingPotential", scout.getJudgingPotential());
            info.put("wageDemand", scout.getWageDemand());
            info.put("knownLeagues", parseKnownLeagues(scout.getKnownLeagues()));
            result.add(info);
        }

        return result;
    }

    /**
     * Get team's hired scouts
     */
    @GetMapping("/team/{teamId}")
    public List<Map<String, Object>> getTeamScouts(@PathVariable long teamId) {
        List<Scout> scouts = scoutRepository.findAllByTeamId(teamId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Scout scout : scouts) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", scout.getId());
            info.put("name", scout.getName());
            info.put("scoutingAbility", scout.getScoutingAbility());
            info.put("experience", scout.getExperience());
            info.put("judgingPotential", scout.getJudgingPotential());
            info.put("wage", scout.getWage());
            info.put("contractEndSeason", scout.getContractEndSeason());
            info.put("knownLeagues", parseKnownLeagues(scout.getKnownLeagues()));

            // Check if currently on assignment
            List<ScoutAssignment> activeAssignments = scoutAssignmentRepository
                    .findAllByScoutIdAndStatus(scout.getId(), "in_progress");
            info.put("onAssignment", !activeAssignments.isEmpty());
            if (!activeAssignments.isEmpty()) {
                ScoutAssignment current = activeAssignments.get(0);
                info.put("assignmentPlayerName", current.getPlayerName());
                info.put("assignmentEndDay", current.getEndDay());
            }

            result.add(info);
        }

        return result;
    }

    /**
     * Hire a scout - negotiate salary
     */
    @PostMapping("/hire")
    public ResponseEntity<?> hireScout(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long scoutId = ((Number) body.get("scoutId")).longValue();
        long offeredWage = ((Number) body.get("offeredWage")).longValue();
        int contractYears = ((Number) body.get("contractYears")).intValue();

        Optional<Scout> scoutOpt = scoutRepository.findById(scoutId);
        if (scoutOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Scout not found"));
        }

        Scout scout = scoutOpt.get();
        if (scout.getTeamId() != null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Scout is already hired"));
        }

        long humanTeamId = userContext.getTeamId(request);
        Team team = teamRepository.findById(humanTeamId).orElse(null);
        if (team == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Team not found"));
        }

        Round round = getRound();
        int currentSeason = (int) round.getSeason();
        long wageDemand = scout.getWageDemand();

        // Negotiation logic
        boolean accepted;
        String message;

        if (offeredWage >= wageDemand) {
            // Full acceptance
            accepted = true;
            message = scout.getName() + " has accepted your offer of " + offeredWage + "/week.";
        } else if (offeredWage >= (long) (wageDemand * 0.85)) {
            // Negotiation zone - 60% chance of acceptance
            accepted = random.nextDouble() < 0.6;
            if (accepted) {
                message = scout.getName() + " has accepted your offer after some negotiation.";
            } else {
                message = scout.getName() + " wants at least " + wageDemand + "/week. Your offer of " +
                        offeredWage + " is too low.";
            }
        } else if (offeredWage >= (long) (wageDemand * 0.7)) {
            // Low offer - 25% chance
            accepted = random.nextDouble() < 0.25;
            if (accepted) {
                message = scout.getName() + " reluctantly accepted your low offer.";
            } else {
                message = scout.getName() + " has rejected your offer. They demand at least " + wageDemand + "/week.";
            }
        } else {
            // Way too low
            accepted = false;
            message = scout.getName() + " is insulted by your offer. They demand at least " + wageDemand + "/week.";
        }

        if (accepted) {
            scout.setTeamId(humanTeamId);
            scout.setWage(offeredWage);
            scout.setHired(true);
            scout.setContractEndSeason(currentSeason + contractYears);
            scoutRepository.save(scout);

            // Update salary budget
            team.setSalaryBudget(team.getSalaryBudget() + offeredWage);
            teamRepository.save(team);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", message,
                    "scout", Map.of(
                            "id", scout.getId(),
                            "name", scout.getName(),
                            "scoutingAbility", scout.getScoutingAbility(),
                            "experience", scout.getExperience(),
                            "judgingPotential", scout.getJudgingPotential(),
                            "wage", scout.getWage(),
                            "contractEndSeason", scout.getContractEndSeason()
                    )
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", message,
                    "wageDemand", wageDemand
            ));
        }
    }

    /**
     * Fire/release a scout
     */
    @PostMapping("/fire/{scoutId}")
    public ResponseEntity<?> fireScout(@PathVariable long scoutId, HttpServletRequest request) {
        Optional<Scout> scoutOpt = scoutRepository.findById(scoutId);
        if (scoutOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Scout not found");
        }

        Scout scout = scoutOpt.get();
        if (scout.getTeamId() == null || scout.getTeamId() != userContext.getTeamId(request)) {
            return ResponseEntity.badRequest().body("Not your scout");
        }

        // Cancel any active assignments
        List<ScoutAssignment> active = scoutAssignmentRepository.findAllByScoutIdAndStatus(scout.getId(), "in_progress");
        for (ScoutAssignment assignment : active) {
            assignment.setStatus("cancelled");
        }
        if (!active.isEmpty()) scoutAssignmentRepository.saveAll(active);

        // Update salary budget before releasing
        Team team = teamRepository.findById(scout.getTeamId()).orElse(null);
        if (team != null) {
            team.setSalaryBudget(team.getSalaryBudget() - scout.getWage());
            teamRepository.save(team);
        }

        // Release the scout back to free agency
        scout.setTeamId(null);
        scout.setWage(0);
        scout.setHired(false);
        scout.setContractEndSeason(0);
        scoutRepository.save(scout);

        return ResponseEntity.ok(Map.of("success", true, "message", scout.getName() + " has been released."));
    }

    /**
     * Negotiate contract renewal for a scout
     */
    @PostMapping("/renew")
    public ResponseEntity<?> renewContract(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long scoutId = ((Number) body.get("scoutId")).longValue();
        long newWage = ((Number) body.get("newWage")).longValue();
        int extraYears = ((Number) body.get("extraYears")).intValue();

        Optional<Scout> scoutOpt = scoutRepository.findById(scoutId);
        if (scoutOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Scout not found"));
        }

        Scout scout = scoutOpt.get();
        if (scout.getTeamId() == null || scout.getTeamId() != userContext.getTeamId(request)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Not your scout"));
        }

        Round round = getRound();
        int currentSeason = (int) round.getSeason();

        // Wage demand increases slightly each renewal
        long demandedWage = (long) (scout.getWageDemand() * 1.1);

        boolean accepted;
        String message;

        if (newWage >= demandedWage) {
            accepted = true;
            message = scout.getName() + " has renewed their contract until Season " +
                    (currentSeason + extraYears) + ".";
        } else if (newWage >= (long) (demandedWage * 0.85)) {
            accepted = random.nextDouble() < 0.5;
            message = accepted
                    ? scout.getName() + " has agreed to renew after negotiation."
                    : scout.getName() + " rejected your offer. They want at least " + demandedWage + "/week.";
        } else {
            accepted = false;
            message = scout.getName() + " rejected your offer. They demand at least " + demandedWage + "/week.";
        }

        if (accepted) {
            scout.setWage(newWage);
            scout.setWageDemand(demandedWage);
            scout.setContractEndSeason(currentSeason + extraYears);
            scoutRepository.save(scout);

            return ResponseEntity.ok(Map.of("success", true, "message", message));
        } else {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", message,
                    "wageDemand", demandedWage
            ));
        }
    }

    /**
     * Send a scout to watch a player.
     * Cost and duration depend on whether the player is in the same league.
     */
    @PostMapping("/assign")
    public ResponseEntity<?> assignScout(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long scoutId = ((Number) body.get("scoutId")).longValue();
        long playerId = ((Number) body.get("playerId")).longValue();

        Optional<Scout> scoutOpt = scoutRepository.findById(scoutId);
        if (scoutOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Scout not found"));
        }

        Scout scout = scoutOpt.get();
        long humanTeamId = userContext.getTeamId(request);
        if (scout.getTeamId() == null || scout.getTeamId() != humanTeamId) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Not your scout"));
        }

        // Check if scout is already on assignment
        List<ScoutAssignment> activeAssignments = scoutAssignmentRepository
                .findAllByScoutIdAndStatus(scout.getId(), "in_progress");
        if (!activeAssignments.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", scout.getName() + " is already on an assignment. Wait for it to complete."));
        }

        Optional<Human> playerOpt = humanRepository.findById(playerId);
        if (playerOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Player not found"));
        }

        Human player = playerOpt.get();
        if (player.getTeamId() == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Player has no team"));
        }

        // Check if already scouted (completed) this player
        List<ScoutAssignment> alreadyScouted = scoutAssignmentRepository
                .findAllByTeamIdAndPlayerIdAndStatus(humanTeamId, playerId, "completed");
        if (!alreadyScouted.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "You already have a completed scout report for " + player.getName()));
        }

        // Check if already scouting this player
        List<ScoutAssignment> alreadyInProgress = scoutAssignmentRepository
                .findAllByTeamIdAndPlayerIdAndStatus(humanTeamId, playerId, "in_progress");
        if (!alreadyInProgress.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "A scout is already watching " + player.getName()));
        }

        Team playerTeam = teamRepository.findById(player.getTeamId()).orElse(null);
        Team humanTeam = teamRepository.findById(humanTeamId).orElse(null);
        if (playerTeam == null || humanTeam == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Team not found"));
        }

        GameCalendar calendar = getCurrentCalendar();
        if (calendar == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "No active calendar"));
        }

        // Determine if same league
        boolean sameLeague = humanTeam.getCompetitionId() == playerTeam.getCompetitionId();

        // Check if scout knows the player's league
        boolean scoutKnowsLeague = false;
        if (scout.getKnownLeagues() != null && !scout.getKnownLeagues().isEmpty()) {
            for (String leagueId : scout.getKnownLeagues().split(",")) {
                if (Long.parseLong(leagueId.trim()) == playerTeam.getCompetitionId()) {
                    scoutKnowsLeague = true;
                    break;
                }
            }
        }

        // Calculate duration and cost
        int baseDays;
        long baseCost;

        if (sameLeague) {
            baseDays = 3 + random.nextInt(5); // 3-7 days
            baseCost = 5_000 + random.nextInt(10_000); // 5K-15K
        } else {
            baseDays = 10 + random.nextInt(11); // 10-20 days
            baseCost = 20_000 + random.nextInt(30_000); // 20K-50K
        }

        // Scout's experience reduces duration
        double experienceReduction = 1.0 - (scout.getExperience() - 5) * 0.03; // up to 45% reduction at exp 20
        experienceReduction = Math.max(0.55, Math.min(1.0, experienceReduction));
        int finalDays = Math.max(2, (int) (baseDays * experienceReduction));

        // Knowing the league reduces cost and duration
        if (scoutKnowsLeague && !sameLeague) {
            finalDays = Math.max(2, finalDays - 3);
            baseCost = (long) (baseCost * 0.7);
        }

        // Check if team can afford it
        if (baseCost > humanTeam.getTransferBudget()) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "Insufficient budget. Scouting mission costs " + baseCost +
                            " but you only have " + humanTeam.getTransferBudget()));
        }

        // Deduct cost
        humanTeam.setTransferBudget(humanTeam.getTransferBudget() - baseCost);
        teamRepository.save(humanTeam);

        // Create assignment
        int endDay = calendar.getCurrentDay() + finalDays;
        ScoutAssignment assignment = new ScoutAssignment();
        assignment.setScoutId(scout.getId());
        assignment.setScoutName(scout.getName());
        assignment.setPlayerId(playerId);
        assignment.setPlayerName(player.getName());
        assignment.setPlayerPosition(player.getPosition());
        assignment.setPlayerTeamId(playerTeam.getId());
        assignment.setPlayerTeamName(playerTeam.getName());
        assignment.setTeamId(humanTeamId);
        assignment.setStartDay(calendar.getCurrentDay());
        assignment.setEndDay(endDay);
        assignment.setSeason(calendar.getSeason());
        assignment.setCost(baseCost);
        assignment.setSameLeague(sameLeague);
        assignment.setStatus("in_progress");
        scoutAssignmentRepository.save(assignment);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", scout.getName() + " has been sent to scout " + player.getName() +
                        " (" + playerTeam.getName() + "). Expected to return in " + finalDays + " days.",
                "assignment", Map.of(
                        "id", assignment.getId(),
                        "scoutName", assignment.getScoutName(),
                        "playerName", assignment.getPlayerName(),
                        "endDay", assignment.getEndDay(),
                        "cost", assignment.getCost(),
                        "daysRemaining", finalDays,
                        "sameLeague", sameLeague
                )
        ));
    }

    /**
     * Get active (in-progress) scouting assignments for a team
     */
    @GetMapping("/assignments/{teamId}")
    public List<Map<String, Object>> getActiveAssignments(@PathVariable long teamId) {
        GameCalendar calendar = getCurrentCalendar();
        int currentDay = calendar != null ? calendar.getCurrentDay() : 0;

        List<ScoutAssignment> assignments = scoutAssignmentRepository
                .findAllByTeamIdAndStatus(teamId, "in_progress");

        List<Map<String, Object>> result = new ArrayList<>();
        for (ScoutAssignment a : assignments) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", a.getId());
            info.put("scoutName", a.getScoutName());
            info.put("playerName", a.getPlayerName());
            info.put("playerPosition", a.getPlayerPosition());
            info.put("playerTeamName", a.getPlayerTeamName());
            info.put("startDay", a.getStartDay());
            info.put("endDay", a.getEndDay());
            info.put("daysRemaining", Math.max(0, a.getEndDay() - currentDay));
            info.put("cost", a.getCost());
            info.put("sameLeague", a.isSameLeague());
            result.add(info);
        }

        return result;
    }

    /**
     * Get completed scouting reports for a team
     */
    @GetMapping("/completed/{teamId}")
    public List<Map<String, Object>> getCompletedReports(@PathVariable long teamId) {
        List<ScoutAssignment> completed = scoutAssignmentRepository
                .findAllByTeamIdAndStatus(teamId, "completed");

        List<Map<String, Object>> result = new ArrayList<>();
        for (ScoutAssignment a : completed) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", a.getId());
            info.put("scoutName", a.getScoutName());
            info.put("playerId", a.getPlayerId());
            info.put("playerName", a.getPlayerName());
            info.put("playerPosition", a.getPlayerPosition());
            info.put("playerTeamId", a.getPlayerTeamId());
            info.put("playerTeamName", a.getPlayerTeamName());
            info.put("revealedRating", a.getRevealedRating());
            info.put("revealedPotential", a.getRevealedPotential());
            info.put("revealedTransferValue", a.getRevealedTransferValue());
            info.put("cost", a.getCost());
            info.put("season", a.getSeason());
            result.add(info);
        }

        return result;
    }

    /**
     * Get expiring scout contracts for a team
     */
    @GetMapping("/expiring/{teamId}")
    public List<Map<String, Object>> getExpiringContracts(@PathVariable long teamId) {
        Round round = getRound();
        int currentSeason = (int) round.getSeason();

        List<Scout> expiring = scoutRepository
                .findAllByTeamIdAndContractEndSeasonLessThanEqual(teamId, currentSeason + 1);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Scout scout : expiring) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", scout.getId());
            info.put("name", scout.getName());
            info.put("scoutingAbility", scout.getScoutingAbility());
            info.put("experience", scout.getExperience());
            info.put("judgingPotential", scout.getJudgingPotential());
            info.put("wage", scout.getWage());
            info.put("wageDemand", (long) (scout.getWageDemand() * 1.1));
            info.put("contractEndSeason", scout.getContractEndSeason());
            result.add(info);
        }

        return result;
    }

    // ==========================================
    // DAILY PROCESSING (called from GameAdvanceService)
    // ==========================================

    /**
     * Process completed scouting assignments for the current day.
     * Called from GameAdvanceService during INJURY_UPDATE or similar daily event.
     */
    public void processCompletedAssignments(int season, int currentDay) {
        List<ScoutAssignment> completedToday = scoutAssignmentRepository
                .findAllBySeasonAndStatusAndEndDayLessThanEqual(season, "in_progress", currentDay);

        for (ScoutAssignment assignment : completedToday) {
            Optional<Human> playerOpt = humanRepository.findById(assignment.getPlayerId());
            if (playerOpt.isEmpty()) {
                assignment.setStatus("completed");
                continue;
            }

            Human player = playerOpt.get();
            Optional<Scout> scoutOpt = scoutRepository.findById(assignment.getScoutId());

            // Calculate accuracy based on scout ability
            int scoutingAbility = scoutOpt.map(Scout::getScoutingAbility).orElse(10);
            int judgingPotential = scoutOpt.map(Scout::getJudgingPotential).orElse(10);

            // Reveal rating with small noise based on scout ability
            double ratingNoise = (20 - scoutingAbility) * 0.5; // 0-7.5 noise
            double revealedRating = player.getRating() +
                    (ratingNoise > 0 ? (random.nextDouble() * 2 * ratingNoise - ratingNoise) : 0);
            revealedRating = Math.max(1, Math.round(revealedRating * 10) / 10.0);

            // Reveal potential with noise based on judging potential ability
            double potentialNoise = (20 - judgingPotential) * 1.5; // 0-24 noise
            double revealedPotential = player.getPotentialAbility() +
                    (potentialNoise > 0 ? (random.nextDouble() * 2 * potentialNoise - potentialNoise) : 0);
            revealedPotential = Math.max(1, Math.min(100, Math.round(revealedPotential)));

            // Calculate transfer value from revealed rating
            long transferValue = calculateTransferValue(player.getAge(), player.getPosition(), revealedRating);

            assignment.setRevealedRating(revealedRating);
            assignment.setRevealedPotential(revealedPotential);
            assignment.setRevealedTransferValue(transferValue);
            assignment.setStatus("completed");

            // Send inbox notification
            String scoutName = scoutOpt.map(Scout::getName).orElse("Your scout");
            ManagerInbox inbox = new ManagerInbox();
            inbox.setTeamId(assignment.getTeamId());
            inbox.setSeasonNumber(season);
            inbox.setRoundNumber(currentDay);
            inbox.setTitle("Scout Report: " + player.getName());
            inbox.setContent(scoutName + " has completed scouting " + player.getName() +
                    " (" + player.getPosition() + ", " + player.getAge() + " yrs) at " +
                    assignment.getPlayerTeamName() + ".\n" +
                    "Rating: " + revealedRating + " | Potential: " + (int) revealedPotential +
                    " | Value: " + formatCurrency(transferValue));
            inbox.setCategory("scouting");
            inbox.setRead(false);
            inbox.setCreatedAt(System.currentTimeMillis());
            managerInboxRepository.save(inbox);
        }

        if (!completedToday.isEmpty()) {
            scoutAssignmentRepository.saveAll(completedToday);
        }
    }

    /**
     * Process expired scout contracts at end of season.
     * Called from CompetitionController during season transition.
     */
    public void processExpiredContracts(int season) {
        List<Scout> allScouts = scoutRepository.findAll();
        for (Scout scout : allScouts) {
            if (scout.getTeamId() != null && scout.getContractEndSeason() <= season) {
                // Contract expired — release scout
                long teamId = scout.getTeamId();
                String name = scout.getName();

                // Update salary budget
                Team team = teamRepository.findById(teamId).orElse(null);
                if (team != null) {
                    team.setSalaryBudget(team.getSalaryBudget() - scout.getWage());
                    teamRepository.save(team);
                }

                scout.setTeamId(null);
                scout.setWage(0);
                scout.setHired(false);
                scout.setContractEndSeason(0);
                scoutRepository.save(scout);

                // Notify
                ManagerInbox inbox = new ManagerInbox();
                inbox.setTeamId(teamId);
                inbox.setSeasonNumber(season);
                inbox.setRoundNumber(0);
                inbox.setTitle("Scout Contract Expired");
                inbox.setContent(name + "'s contract has expired. They are now a free agent.");
                inbox.setCategory("contract");
                inbox.setRead(false);
                inbox.setCreatedAt(System.currentTimeMillis());
                managerInboxRepository.save(inbox);
            }
        }
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private List<Map<String, Object>> parseKnownLeagues(String knownLeagues) {
        if (knownLeagues == null || knownLeagues.isEmpty()) return Collections.emptyList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (String idStr : knownLeagues.split(",")) {
            try {
                long compId = Long.parseLong(idStr.trim());
                Optional<Competition> comp = competitionRepository.findById(compId);
                Map<String, Object> league = new LinkedHashMap<>();
                league.put("id", compId);
                league.put("name", comp.map(Competition::getName).orElse("Unknown League"));
                result.add(league);
            } catch (NumberFormatException e) {
                // skip invalid
            }
        }
        return result;
    }

    private long calculateTransferValue(long age, String position, double rating) {
        double baseValue = Math.pow(rating, 3) * 20;
        double ageMultiplier;
        if (age <= 21) ageMultiplier = 1.3;
        else if (age <= 23) ageMultiplier = 1.1;
        else if (age <= 25) ageMultiplier = 1.0;
        else if (age <= 27) ageMultiplier = 0.95;
        else if (age <= 29) ageMultiplier = 0.75;
        else if (age <= 31) ageMultiplier = 0.45;
        else if (age <= 33) ageMultiplier = 0.2;
        else ageMultiplier = 0.08;
        return Math.max(50_000L, (long) (baseValue * ageMultiplier));
    }

    private String formatCurrency(long amount) {
        if (amount >= 1_000_000) return String.format("€%.1fM", amount / 1_000_000.0);
        if (amount >= 1_000) return String.format("€%dK", amount / 1_000);
        return "€" + amount;
    }
}
