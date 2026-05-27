package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.CompetitionService;
import com.footballmanagergamesimulator.service.FinanceService;
import com.footballmanagergamesimulator.service.HumanService;
import com.footballmanagergamesimulator.service.PlayerSkillsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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

        // Rating is required
        Number ratingNum = (Number) body.get("rating");
        if (ratingNum == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "rating is required"));
        }
        double rating = ratingNum.doubleValue();

        // Team (optional, 0 or absent = free agent)
        Long teamId = null;
        if (body.containsKey("teamId")) {
            long tid = ((Number) body.get("teamId")).longValue();
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
            wage = (long) (rating * 50);
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

        player.setTransferValue(competitionController.calculateTransferValue(age, position, rating));
        player.setContractEndSeason(teamId == null ? 0 : currentSeason + contractLength);
        player.setWage(wage);
        long transferVal = competitionController.calculateTransferValue(age, position, rating);
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
        playerSkillsRepository.save(playerSkills);

        // Recompute rating from attributes
        double computedRating = PlayerSkillsService.computeOverallRating(playerSkills);
        player.setRating(computedRating);
        player.setCurrentAbility((int) computedRating);
        player.setBestEverRating(computedRating);
        humanRepository.save(player);

        // Create historical relation for player career history
        if (teamId != null) {
            TeamPlayerHistoricalRelation relation = new TeamPlayerHistoricalRelation();
            relation.setPlayerId(player.getId());
            relation.setTeamId(teamId);
            relation.setSeasonNumber(currentSeason);
            relation.setRating(player.getRating());
            teamPlayerHistoricalRelationRepository.save(relation);
        }

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
