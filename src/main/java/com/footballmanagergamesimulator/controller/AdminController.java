package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.CompetitionService;
import com.footballmanagergamesimulator.service.EuropeanFixturePreparationService;
import com.footballmanagergamesimulator.service.EuropeanCompetitionService;
import com.footballmanagergamesimulator.service.FinanceService;
import com.footballmanagergamesimulator.service.HumanService;
import com.footballmanagergamesimulator.service.PlayerSkillsService;
import com.footballmanagergamesimulator.service.AwardService;
import com.footballmanagergamesimulator.service.ManualCompetitionDrawService;
import com.footballmanagergamesimulator.service.AdminTransferService;
import com.footballmanagergamesimulator.service.AdminClubFundingService;
import com.footballmanagergamesimulator.service.ScorerLeaderboardSyncService;
import com.footballmanagergamesimulator.service.MatchSimulationOrchestrator;
import com.footballmanagergamesimulator.util.TypeNames;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}",
             allowedHeaders = "*")
public class AdminController {

    // ===== Admin auth (dev-only credentials) =====
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin";
    private static final String ADMIN_TOKEN = "admin-token-2026";
    private static final String TOKEN_HEADER = "X-Admin-Token";

    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private PlayerSkillsRepository playerSkillsRepository;
    @Autowired
    private RoundRepository roundRepository;
    @Autowired
    private CompetitionController competitionController;
    @Autowired
    private CompetitionService competitionService;
    @Autowired
    private FinanceService financeService;
    @Autowired
    private com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator compositeNameGenerator;
    @Autowired
    private TeamPlayerHistoricalRelationRepository teamPlayerHistoricalRelationRepository;
    @Autowired
    private PredeterminedScoreRepository predeterminedScoreRepository;
    @Autowired
    private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired
    private CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired
    private CompetitionRepository competitionRepository;
    @Autowired
    private com.footballmanagergamesimulator.service.JobOfferService jobOfferService;
    @Autowired
    private com.footballmanagergamesimulator.user.UserRepository userRepository;
    @Autowired
    private com.footballmanagergamesimulator.service.BestTacticService bestTacticService;
    @Autowired
    private EuropeanFixturePreparationService europeanFixturePreparationService;
    @Autowired
    private EuropeanCompetitionService europeanCompetitionService;
    @Autowired
    private AwardService awardService;
    @Autowired
    private ManualCompetitionDrawService manualCompetitionDrawService;
    @Autowired
    private AdminTransferService adminTransferService;
    @Autowired
    private AdminClubFundingService adminClubFundingService;
    @Autowired
    private ScorerLeaderboardSyncService scorerLeaderboardSyncService;
    @Autowired
    private com.footballmanagergamesimulator.service.TransferOfferLifecycleService transferOfferLifecycleService;
    @Autowired
    private MatchSimulationOrchestrator matchSimulationOrchestrator;

    private final Random random = new Random();

    // ===== Admin-controlled global flags =====
    // Toggle: when true, the next advance() round will spawn a job offer for every
    // logged-in human user. Reset after firing. Lets the admin reproduce / demo the
    // job-offer flow on demand instead of waiting for the periodic generator.
    private boolean forceJobOfferOnNextAdvance = false;
    private boolean jobOffersEnabled = true; // master kill-switch for automatic offers

    // ===== Auth helpers =====

    private boolean isAdmin(HttpServletRequest req) {
        return ADMIN_TOKEN.equals(req.getHeader(TOKEN_HEADER));
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "Admin token missing or invalid"));
    }

    private String matchKey(long compId, int round, long t1, long t2) {
        return compId + "-" + round + "-" + t1 + "-" + t2;
    }

    /** POST /admin/login {username, password} -> {success, token} */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String user = body.get("username");
        String pass = body.get("password");
        if (ADMIN_USER.equals(user) && ADMIN_PASS.equals(pass)) {
            return ResponseEntity.ok(Map.of("success", true, "token", ADMIN_TOKEN));
        }
        return ResponseEntity.status(401).body(Map.of("success", false, "message", "Invalid credentials"));
    }

    private int getCurrentSeason() {
        return (int) roundRepository.findById(1L).orElse(new Round()).getSeason();
    }

    /** Repair a not-yet-drawn Stars Cup field created by the legacy cup-points rule. */
    @PostMapping("/competitions/repair-stars-cup/{season}")
    public ResponseEntity<?> repairStarsCupQualification(
            @PathVariable long season,
            HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        try {
            return ResponseEntity.ok(europeanCompetitionService
                    .repairStarsCupDomesticQualifiers(season));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        }
    }

    /** Controlled sources that can be used for an administrative club cash injection. */
    @GetMapping("/finances/funding-options")
    public ResponseEntity<?> getFundingOptions(HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        return ResponseEntity.ok(adminClubFundingService.options());
    }

    /** Controlled reasons available when the admin removes money from a club. */
    @GetMapping("/finances/withdrawal-options")
    public ResponseEntity<?> getWithdrawalOptions(HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        return ResponseEntity.ok(adminClubFundingService.withdrawalOptions());
    }

    /** Credit a club and persist the operation in its normal financial ledger. */
    @PostMapping("/finances/funding")
    public ResponseEntity<?> addClubFunding(
            @RequestBody AdminClubFundingService.FundingCommand command,
            HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        try {
            return ResponseEntity.ok(adminClubFundingService.addFunding(command));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        }
    }

    /** Debit a club and persist the operation as an expense in its financial ledger. */
    @PostMapping("/finances/withdrawal")
    public ResponseEntity<?> removeClubFunding(
            @RequestBody AdminClubFundingService.FundingCommand command,
            HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        try {
            return ResponseEntity.ok(adminClubFundingService.removeFunding(command));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        }
    }

    /** Current Admin transfer queue and completed audit rows. */
    @GetMapping("/transfers")
    public ResponseEntity<?> getAdminTransfers(HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        return ResponseEntity.ok(adminTransferService.state());
    }

    /** Club squad or free-agent candidates for the Admin transfer editor. */
    @GetMapping("/transfers/players")
    public ResponseEntity<?> getAdminTransferPlayers(
            @RequestParam(required = false) Long sourceTeamId,
            @RequestParam(defaultValue = "false") boolean freeAgents,
            @RequestParam(required = false) String query,
            HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        try {
            return ResponseEntity.ok(adminTransferService.playerOptions(sourceTeamId, freeAgents, query));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        }
    }

    /** Execute now or durably schedule a permanent transfer, free-agent signing or loan. */
    @PostMapping("/transfers")
    public ResponseEntity<?> createAdminTransfer(
            @RequestBody AdminTransferService.MovementCommand command,
            HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        try {
            return ResponseEntity.ok(adminTransferService.create(command));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(409).body(Map.of("error", exception.getMessage()));
        }
    }

    /** Cancel a movement that has not reached its execution season yet. */
    @DeleteMapping("/transfers/{movementId}")
    public ResponseEntity<?> cancelAdminTransfer(
            @PathVariable long movementId,
            HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        try {
            return ResponseEntity.ok(adminTransferService.cancel(movementId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(409).body(Map.of("error", exception.getMessage()));
        }
    }

    /**
     * Editor-only one-club-player flag. Enabling it also clears any transfer
     * intent, pre-contract, active offer and scheduled Admin movement.
     */
    @PatchMapping("/players/{playerId}/will-never-leave")
    @Transactional
    public ResponseEntity<?> updateWillNeverLeave(
            @PathVariable long playerId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        Object value = body.get("willNeverLeave");
        if (!(value instanceof Boolean enabled)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "willNeverLeave must be true or false"));
        }
        Human player = humanRepository.findById(playerId).orElse(null);
        if (player == null || player.getTypeId() != 1L) {
            return ResponseEntity.badRequest().body(Map.of("error", "Player not found"));
        }

        player.setWillNeverLeave(enabled);
        int removedOffers = 0;
        int cancelledMovements = 0;
        if (enabled) {
            player.setWantsTransfer(false);
            player.setPreContractTeamId(0);
            removedOffers = transferOfferLifecycleService.removeActiveOffersForPlayer(playerId);
            cancelledMovements = adminTransferService.cancelPendingForPlayer(playerId);
        }
        humanRepository.save(player);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("playerId", player.getId());
        response.put("playerName", player.getName());
        response.put("willNeverLeave", player.isWillNeverLeave());
        response.put("removedOffers", removedOffers);
        response.put("cancelledAdminMovements", cancelledMovements);
        response.put("message", enabled
                ? player.getName() + " will stay at the current club and retire when the contract ends."
                : player.getName() + " is eligible for transfers again.");
        return ResponseEntity.ok(response);
    }

    /**
     * Federation Editor control for the manager's squad-adaptive AI trait.
     * The simulator cache is invalidated so the next fixture uses the new
     * policy even when it is changed in the middle of a season.
     */
    @PatchMapping("/managers/{managerId}/always-best-tactic")
    @Transactional
    public ResponseEntity<?> updateAlwaysBestTactic(
            @PathVariable long managerId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        Object value = body.get("alwaysUseBestPossibleTactic");
        if (!(value instanceof Boolean enabled)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "alwaysUseBestPossibleTactic must be true or false"));
        }

        Human manager = humanRepository.findById(managerId).orElse(null);
        if (manager == null || manager.getTypeId() != TypeNames.MANAGER_TYPE) {
            return ResponseEntity.badRequest().body(Map.of("error", "Manager not found"));
        }

        manager.setAlwaysUseBestPossibleTactic(enabled);
        humanRepository.save(manager);
        if (manager.getTeamId() != null && manager.getTeamId() > 0) {
            matchSimulationOrchestrator.invalidateManagerTacticPolicy(manager.getTeamId());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("managerId", manager.getId());
        response.put("managerName", manager.getName());
        response.put("alwaysUseBestPossibleTactic", manager.isAlwaysUseBestPossibleTactic());
        response.put("message", enabled
                ? manager.getName() + " will always use the best possible tactic."
                : manager.getName() + " will use tactical preferences and coaching judgement.");
        return ResponseEntity.ok(response);
    }

    /** Draw controls for the next unplayed domestic-cup round and every pending European draw. */
    @GetMapping("/draws")
    public ResponseEntity<?> getManualDraws(HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        return ResponseEntity.ok(manualCompetitionDrawService.drawStates(getCurrentSeason()));
    }

    /** Completes a pending draw with the exact pairings/groups selected by the admin. */
    @PostMapping("/draws/complete")
    public ResponseEntity<?> completeManualDraw(
            @RequestBody ManualCompetitionDrawService.DrawCommand command,
            HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        try {
            return ResponseEntity.ok(manualCompetitionDrawService.complete(command));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(409).body(Map.of("error", exception.getMessage()));
        }
    }

    /**
     * Extend every active player contract for one team or for the whole game.
     * Existing time is preserved: a Season 6 deal extended by 3 ends in Season 9.
     * Expired deals restart from the current season.
     */
    @PostMapping("/contracts/extend")
    @Transactional
    public ResponseEntity<?> extendPlayerContracts(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();

        Object seasonsValue = body.get("seasons");
        if (!(seasonsValue instanceof Number seasonsNumber)) {
            return ResponseEntity.badRequest().body(Map.of("error", "seasons is required"));
        }
        int seasons = seasonsNumber.intValue();
        if (seasons < 1 || seasons > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "seasons must be between 1 and 100"));
        }

        boolean allTeams = Boolean.TRUE.equals(body.get("allTeams"));
        Long teamId = body.get("teamId") instanceof Number teamNumber
                ? teamNumber.longValue() : null;
        if (!allTeams && teamId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "teamId is required when allTeams is false"));
        }

        String scope;
        List<Human> candidates;
        if (allTeams) {
            scope = "All teams";
            candidates = humanRepository.findAllByTypeId(1L);
        } else {
            Team team = teamRepository.findById(teamId).orElse(null);
            if (team == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Team not found: " + teamId));
            }
            scope = team.getName();
            candidates = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L);
        }

        int currentSeason = getCurrentSeason();
        List<Human> players = candidates.stream()
                .filter(player -> !player.isRetired())
                .filter(player -> player.getTeamId() != null && player.getTeamId() > 0)
                .toList();
        Set<Long> affectedTeams = new HashSet<>();
        int preContractsCleared = 0;
        int earliestNewExpiry = Integer.MAX_VALUE;
        int latestNewExpiry = 0;
        for (Human player : players) {
            int newExpiry = Math.max(currentSeason, player.getContractEndSeason()) + seasons;
            player.setContractEndSeason(newExpiry);
            if (player.getPreContractTeamId() > 0) {
                player.setPreContractTeamId(0);
                preContractsCleared++;
            }
            affectedTeams.add(player.getTeamId());
            earliestNewExpiry = Math.min(earliestNewExpiry, newExpiry);
            latestNewExpiry = Math.max(latestNewExpiry, newExpiry);
        }
        humanRepository.saveAll(players);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("scope", scope);
        result.put("seasonsAdded", seasons);
        result.put("contractsExtended", players.size());
        result.put("teamsAffected", affectedTeams.size());
        result.put("preContractsCleared", preContractsCleared);
        result.put("earliestNewExpiry", players.isEmpty() ? null : earliestNewExpiry);
        result.put("latestNewExpiry", players.isEmpty() ? null : latestNewExpiry);
        return ResponseEntity.ok(result);
    }

    /** Statistical Ballon d'Or ranking and any armed admin winner selection. */
    @GetMapping("/awards/ballon-dor")
    public ResponseEntity<?> getBallonDorAdminState(
            @RequestParam(required = false) Integer season, HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        int selectedSeason = season == null ? getCurrentSeason() : season;
        if (selectedSeason < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "season must be positive"));
        }
        return ResponseEntity.ok(awardService.getBallonDorAdminState(selectedSeason));
    }

    /** Arms or replaces the manual winner for an unfinalised season. */
    @PostMapping("/awards/ballon-dor/override")
    public ResponseEntity<?> setBallonDorOverride(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        Object winnerValue = body.get("winnerId");
        if (!(winnerValue instanceof Number winnerNumber)) {
            return ResponseEntity.badRequest().body(Map.of("error", "winnerId is required"));
        }
        int season = body.get("season") instanceof Number seasonNumber
                ? seasonNumber.intValue() : getCurrentSeason();
        try {
            return ResponseEntity.ok(awardService.setBallonDorOverride(
                    season, winnerNumber.longValue()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        }
    }

    /** Returns winner selection to the statistical recommendation. */
    @DeleteMapping("/awards/ballon-dor/override")
    public ResponseEntity<?> clearBallonDorOverride(
            @RequestParam(required = false) Integer season, HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        int selectedSeason = season == null ? getCurrentSeason() : season;
        try {
            return ResponseEntity.ok(awardService.clearBallonDorOverride(selectedSeason));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        }
    }

    /**
     * Generate a player for a team (or free agent if teamId is 0 or not provided).
     *
     * Body params:
     *   teamId (optional, 0 = free agent)
     *   position (required: GK, DL, DR, DC, ML, MR, MC, AMC, ST)
     *   rating (required)
     *   wage (optional, auto-calculated if not provided)
     *   contractLength (optional, default 3 seasons)
     *   name (optional, auto-generated if not provided)
     *   age (optional, default random 18-30)
     */
    @PostMapping("/generatePlayer")
    public ResponseEntity<Map<String, Object>> generatePlayer(@RequestBody Map<String, Object> body) {
        // Position is required
        String position = (String) body.get("position");
        if (position == null || position.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "position is required"));
        }

        // Rating is required. Accept the old frontend field name as a temporary
        // compatibility fallback for clients that still send `overall`.
        Object ratingValue = body.containsKey("rating") ? body.get("rating") : body.get("overall");
        if (!(ratingValue instanceof Number ratingNum)) {
            return ResponseEntity.badRequest().body(Map.of("error", "rating is required"));
        }
        double rating = ratingNum.doubleValue();
        if (!Double.isFinite(rating) || rating < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "rating must be at least 1"));
        }

        // Team (optional, 0 or absent = free agent)
        Long teamId = null;
        Object teamIdValue = body.get("teamId");
        if (teamIdValue != null) {
            if (!(teamIdValue instanceof Number teamIdNum)) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamId must be a number"));
            }
            long tid = teamIdNum.longValue();
            if (tid > 0) {
                Team team = teamRepository.findById(tid).orElse(null);
                if (team == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Team not found: " + tid));
                }
                teamId = tid;
            }
        }

        int currentSeason = getCurrentSeason();

        // Optional fields
        int contractLength = body.containsKey("contractLength") ? ((Number) body.get("contractLength")).intValue() : 3;
        int age = body.containsKey("age") ? ((Number) body.get("age")).intValue() : (18 + random.nextInt(13));

        long wage;
        if (body.containsKey("wage")) {
            wage = ((Number) body.get("wage")).longValue();
        } else {
            wage = com.footballmanagergamesimulator.service.WageService.baseWage(rating);
        }

        String name;
        if (body.containsKey("name") && body.get("name") != null && !((String) body.get("name")).isBlank()) {
            name = (String) body.get("name");
        } else {
            long compId = 1L;
            if (teamId != null) {
                Team team = teamRepository.findById(teamId).orElse(null);
                if (team != null) compId = team.getCompetitionId();
            }
            name = compositeNameGenerator.generateName(compId);
        }

        Human player = new Human();
        player.setTeamId(teamId);
        player.setName(name);
        player.setTypeId(1L); // PLAYER_TYPE
        player.setPosition(position);
        player.setAge(age);
        player.setSeasonCreated(currentSeason);
        player.setCurrentStatus(teamId == null ? "Free Agent" : "Senior");
        player.setMorale(70);
        player.setFitness(100);
        player.setRating(rating);
        player.setCurrentAbility((int) rating);
        player.setPotentialAbility((int) rating + random.nextInt(10, 40));
        player.setBestEverRating(rating);
        player.setSeasonOfBestEverRating(currentSeason);

        // Generate physical profile
        HumanService.generatePhysicalProfile(player, random);

        player.setTransferValue(com.footballmanagergamesimulator.service.TransferValueCalculator.calculate(age, position, rating));
        player.setContractEndSeason(teamId == null ? 0 : currentSeason + contractLength);
        player.setWage(wage);
        long transferVal = com.footballmanagergamesimulator.service.TransferValueCalculator.calculate(age, position, rating);
        player.setReleaseClause(random.nextInt(10) < 3 ? 0 : transferVal * 2);

        // Pick a shirt number that doesn't clash with the team's current squad.
        if (teamId != null) {
            List<Human> existing = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L).stream()
                    .filter(h -> !h.isRetired()).collect(java.util.stream.Collectors.toList());
            existing.add(player);
            HumanService.assignShirtNumbers(existing);
        }
        player = humanRepository.save(player);

        // Generate skills
        PlayerSkills playerSkills = new PlayerSkills();
        playerSkills.setPlayerId(player.getId());
        playerSkills.setPosition(position);
        competitionService.generateSkills(playerSkills, rating);
        PlayerSkillsService.calibrateOverallRating(playerSkills, rating);
        playerSkillsRepository.save(playerSkills);

        // The admin-provided rating is authoritative. Skills are calibrated above
        // so later attribute-based recalculations do not immediately lower it.

        // Create historical relation for player career history
        if (teamId != null) {
            TeamPlayerHistoricalRelation relation = new TeamPlayerHistoricalRelation();
            relation.setPlayerId(player.getId());
            relation.setTeamId(teamId);
            relation.setSeasonNumber(currentSeason);
            relation.setRating(player.getRating());
            teamPlayerHistoricalRelationRepository.save(relation);
        }
        scorerLeaderboardSyncService.trackNewPlayer(player);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("playerId", player.getId());
        result.put("name", player.getName());
        result.put("position", player.getPosition());
        result.put("rating", player.getRating());
        result.put("age", player.getAge());
        result.put("wage", player.getWage());
        result.put("teamId", player.getTeamId());
        result.put("teamName", teamId != null ? teamRepository.findNameById(teamId) : "Free Agent");
        result.put("contractEndSeason", player.getContractEndSeason());
        result.put("transferValue", player.getTransferValue());

        return ResponseEntity.ok(result);
    }

    /**
     * Backfill realistic shirt numbers for every player who currently has 0.
     * One-shot operation for existing savegames generated before the
     * shirt-number assignment was wired into roster creation.
     */
    @PostMapping("/backfillShirtNumbers")
    public ResponseEntity<Map<String, Object>> backfillShirtNumbers() {
        List<Team> teams = teamRepository.findAll();
        int teamsTouched = 0;
        int playersAssigned = 0;
        for (Team team : teams) {
            List<Human> squad = humanRepository.findAllByTeamIdAndTypeId(team.getId(), 1L).stream()
                    .filter(h -> !h.isRetired()).collect(Collectors.toList());
            long missingBefore = squad.stream().filter(h -> h.getShirtNumber() <= 0).count();
            if (missingBefore == 0) continue;
            HumanService.assignShirtNumbers(squad);
            humanRepository.saveAll(squad);
            teamsTouched++;
            playersAssigned += (int) missingBefore;
        }
        return ResponseEntity.ok(Map.of(
                "teamsTouched", teamsTouched,
                "playersAssigned", playersAssigned));
    }

    /**
     * Inject money into a team's finances.
     *
     * Body params:
     *   teamId (required)
     *   amount (required)
     *   reason (optional, default "Admin injection")
     */
    @PostMapping("/injectMoney")
    public ResponseEntity<Map<String, Object>> injectMoney(@RequestBody Map<String, Object> body) {
        if (!body.containsKey("teamId")) {
            return ResponseEntity.badRequest().body(Map.of("error", "teamId is required"));
        }
        if (!body.containsKey("amount")) {
            return ResponseEntity.badRequest().body(Map.of("error", "amount is required"));
        }

        long teamId = ((Number) body.get("teamId")).longValue();
        long amount = ((Number) body.get("amount")).longValue();
        String reason = body.containsKey("reason") ? (String) body.get("reason") : "Admin injection";

        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Team not found: " + teamId));
        }

        int currentSeason = getCurrentSeason();

        financeService.recordTransaction(teamId, currentSeason, 0, "OWNER_INJECTION", reason, amount);

        // Re-read team after finance update
        team = teamRepository.findById(teamId).orElse(team);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("teamId", teamId);
        result.put("teamName", team.getName());
        result.put("injected", amount);
        result.put("newTotalFinances", team.getTotalFinances());
        result.put("newTransferBudget", team.getTransferBudget());

        return ResponseEntity.ok(result);
    }

    // ===================== PREDETERMINED SCORES =====================

    /**
     * GET /admin/upcomingMatches
     * Returns every not-yet-played match in the current season with team/competition
     * names, calendar day, and the predetermined score already set on it (if any).
     */
    @GetMapping("/upcomingMatches")
    public ResponseEntity<?> getUpcomingMatches(HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();

        int season = getCurrentSeason();
        String seasonStr = String.valueOf(season);

        // 1) Matches scheduled this season
        List<CompetitionTeamInfoMatch> allMatches = competitionTeamInfoMatchRepository.findAll()
                .stream()
                .filter(m -> seasonStr.equals(m.getSeasonNumber()))
                .toList();

        // 2) Keys of matches already played (have a Detail row)
        Set<String> playedKeys = competitionTeamInfoDetailRepository.findAll()
                .stream()
                .filter(d -> d.getSeasonNumber() == season)
                .map(d -> matchKey(d.getCompetitionId(), (int) d.getRoundId(), d.getTeam1Id(), d.getTeam2Id()))
                .collect(Collectors.toSet());

        // 3) Active predetermined scores indexed by match key
        Map<String, PredeterminedScore> presetByKey = predeterminedScoreRepository.findAllByConsumedFalse()
                .stream()
                .filter(p -> p.getSeasonNumber() == season)
                .collect(Collectors.toMap(
                        p -> matchKey(p.getCompetitionId(), p.getRoundNumber(), p.getTeam1Id(), p.getTeam2Id()),
                        p -> p,
                        (a, b) -> a));

        // 4) Cache competition + team names
        Map<Long, String> compNames = competitionRepository.findAll().stream()
                .collect(Collectors.toMap(Competition::getId, Competition::getName));
        Map<Long, String> teamNames = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));

        List<Map<String, Object>> result = new ArrayList<>();
        for (CompetitionTeamInfoMatch m : allMatches) {
            String key = matchKey(m.getCompetitionId(), (int) m.getRound(), m.getTeam1Id(), m.getTeam2Id());
            if (playedKeys.contains(key)) continue;
            // Skip cup-bracket placeholder matches whose teams aren't decided yet
            // (winner-of-X slots) — admin can only set a score when BOTH sides are known.
            if (m.getTeam1Id() <= 0 || m.getTeam2Id() <= 0) continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("competitionId", m.getCompetitionId());
            entry.put("competitionName", compNames.getOrDefault(m.getCompetitionId(), "?"));
            entry.put("seasonNumber", season);
            entry.put("roundNumber", m.getRound());
            entry.put("day", m.getDay());
            entry.put("team1Id", m.getTeam1Id());
            entry.put("team1Name", teamNames.getOrDefault(m.getTeam1Id(), "?"));
            entry.put("team2Id", m.getTeam2Id());
            entry.put("team2Name", teamNames.getOrDefault(m.getTeam2Id(), "?"));

            PredeterminedScore preset = presetByKey.get(key);
            if (preset != null) {
                entry.put("predeterminedId", preset.getId());
                entry.put("predeterminedTeam1Score", preset.getTeam1Score());
                entry.put("predeterminedTeam2Score", preset.getTeam2Score());
            }
            result.add(entry);
        }

        // Sort by calendar day so soonest matches show first
        result.sort(Comparator.comparingInt(e -> {
            Object d = e.get("day");
            return d instanceof Number ? ((Number) d).intValue() : Integer.MAX_VALUE;
        }));

        return ResponseEntity.ok(result);
    }

    /**
     * POST /admin/setScore
     * Body: { competitionId, seasonNumber, roundNumber, team1Id, team2Id, team1Score, team2Score }
     * Upserts the predetermined score for that exact match. The simulator will pick it up
     * on the next simulateRound for that competition/round.
     */
    @PostMapping("/setScore")
    public ResponseEntity<?> setScore(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();

        try {
            long competitionId = ((Number) body.get("competitionId")).longValue();
            int seasonNumber = ((Number) body.get("seasonNumber")).intValue();
            int roundNumber = ((Number) body.get("roundNumber")).intValue();
            long team1Id = ((Number) body.get("team1Id")).longValue();
            long team2Id = ((Number) body.get("team2Id")).longValue();
            int team1Score = ((Number) body.get("team1Score")).intValue();
            int team2Score = ((Number) body.get("team2Score")).intValue();

            if (team1Score < 0 || team2Score < 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Scores must be non-negative"));
            }

            Optional<PredeterminedScore> existing = predeterminedScoreRepository
                    .findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2IdAndConsumedFalse(
                            competitionId, seasonNumber, roundNumber, team1Id, team2Id);

            PredeterminedScore p = existing.orElseGet(PredeterminedScore::new);
            p.setCompetitionId(competitionId);
            p.setSeasonNumber(seasonNumber);
            p.setRoundNumber(roundNumber);
            p.setTeam1Id(team1Id);
            p.setTeam2Id(team2Id);
            p.setTeam1Score(team1Score);
            p.setTeam2Score(team2Score);
            p.setConsumed(false);
            predeterminedScoreRepository.save(p);

            return ResponseEntity.ok(Map.of("success", true, "id", p.getId()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid body: " + e.getMessage()));
        }
    }

    /** GET /admin/predeterminedScores -> list of un-consumed entries. */
    @GetMapping("/predeterminedScores")
    public ResponseEntity<?> listPredetermined(HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        return ResponseEntity.ok(predeterminedScoreRepository.findAllByConsumedFalse());
    }

    /** DELETE /admin/predeterminedScore/{id} -> remove a predetermined score. */
    @DeleteMapping("/predeterminedScore/{id}")
    public ResponseEntity<?> deletePredetermined(@PathVariable long id, HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        predeterminedScoreRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ============================================================
    //                     JOB OFFER CONTROLS
    // ============================================================

    /** Returns the current job-offer admin flags. */
    @GetMapping("/jobOffers/state")
    public ResponseEntity<?> jobOfferState(HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        return ResponseEntity.ok(Map.of(
                "jobOffersEnabled", jobOffersEnabled,
                "forceJobOfferOnNextAdvance", forceJobOfferOnNextAdvance
        ));
    }

    /** Master toggle: when off, automatic offers won't be generated. */
    @PostMapping("/jobOffers/setEnabled")
    public ResponseEntity<?> setJobOffersEnabled(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        jobOffersEnabled = Boolean.TRUE.equals(body.get("enabled"));
        return ResponseEntity.ok(Map.of("jobOffersEnabled", jobOffersEnabled));
    }

    /** Arm a one-shot: the next advance() will spawn an offer for every active user. */
    @PostMapping("/jobOffers/forceNext")
    public ResponseEntity<?> forceNextOffer(HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        forceJobOfferOnNextAdvance = true;
        return ResponseEntity.ok(Map.of("forceJobOfferOnNextAdvance", true));
    }

    /** Manually generate an offer from a specific team to a specific user. */
    @PostMapping("/jobOffers/generateNow")
    public ResponseEntity<?> generateNow(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        try {
            int userId = ((Number) body.get("userId")).intValue();
            long teamId = ((Number) body.get("teamId")).longValue();
            var offer = jobOfferService.generateOffer(userId, teamId);
            if (offer == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Offer could not be created (duplicate or invalid)."));
            }
            return ResponseEntity.ok(offer);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid body: " + e.getMessage()));
        }
    }

    /** Lightweight list of all logged-in users so the admin UI can pick targets. */
    @GetMapping("/users")
    public ResponseEntity<?> listUsers(HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var u : userRepository.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", u.getId());
            row.put("username", u.getUsername());
            row.put("teamId", u.getTeamId());
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * GET /admin/bestTactic/{teamId}
     * Read-only advisory: searches every formation × the 900 tactic-setting combinations for the
     * team using its CURRENT live squad (morale/fitness/injuries/ratings/attributes from the DB) and
     * returns the recommended formation + settings plus a ranked top list. Does not mutate state.
     */
    @GetMapping("/bestTactic/{teamId}")
    public ResponseEntity<?> bestTactic(@PathVariable long teamId, HttpServletRequest req) {
        if (!isAdmin(req)) return unauthorized();
        if (teamRepository.findById(teamId).isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Team not found: " + teamId));
        }
        return ResponseEntity.ok(bestTacticService.findBestTactic(teamId));
    }

    // ===== Hooks used by GameAdvanceService =====
    /** Called by GameAdvanceService to know if it must generate offers this tick. */
    public boolean consumeForceOfferFlag() {
        boolean v = forceJobOfferOnNextAdvance;
        forceJobOfferOnNextAdvance = false;
        return v;
    }

    public boolean areJobOffersEnabled() {
        return jobOffersEnabled;
    }
}
