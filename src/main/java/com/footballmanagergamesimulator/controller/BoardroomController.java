package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Asset;
import com.footballmanagergamesimulator.model.ClubShareholding;
import com.footballmanagergamesimulator.model.CoachPermissions;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.ClubShareholdingRepository;
import com.footballmanagergamesimulator.repository.CoachPermissionsRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.AssetService;
import com.footballmanagergamesimulator.service.CoachPermissionService;
import com.footballmanagergamesimulator.service.FinanceService;
import com.footballmanagergamesimulator.service.HumanService;
import com.footballmanagergamesimulator.service.OwnershipService;
import com.footballmanagergamesimulator.service.ShareMarketService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boardroom / "My Life" REST API (Faza 1-2): manager wealth, personal assets,
 * club shareholdings, and owner invest/withdraw to/from owned clubs.
 */
@RestController
@RequestMapping("/boardroom")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class BoardroomController {

    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private ClubShareholdingRepository shareholdingRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private AssetService assetService;
    @Autowired private ShareMarketService shareMarketService;
    @Autowired private OwnershipService ownershipService;
    @Autowired private FinanceService financeService;
    @Autowired private CoachPermissionService coachPermissionService;
    @Autowired private CoachPermissionsRepository coachPermissionsRepository;
    @Autowired private HumanService humanService;
    @Autowired private ManagerInboxRepository managerInboxRepository;

    // ==================== HUMANS / WEALTH ====================

    /**
     * List managers (type MANAGER) with wealth/reputation/owned clubs. The
     * {@code humansOnly} flag is a no-op placeholder for a future human flag;
     * managers are always returned (there is no human flag on Human yet).
     */
    @GetMapping("/humans")
    public ResponseEntity<?> listHumans(@RequestParam(required = false, defaultValue = "false") boolean humansOnly) {
        List<Human> managers = humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Human m : managers) {
            out.add(summary(m));
        }
        return ResponseEntity.ok(out);
    }

    /** Wealth + assets + shareholdings + owned clubs for a single human. */
    @GetMapping("/wealth/{humanId}")
    public ResponseEntity<?> wealth(@PathVariable long humanId) {
        Human human = humanRepository.findById(humanId).orElse(null);
        if (human == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Human not found"));
        }
        Map<String, Object> result = summary(human);
        result.put("careerEarnings", human.getCareerEarnings());

        List<Asset> assets = assetService.listAssets(humanId);
        result.put("assets", assets);

        List<Map<String, Object>> holdings = new ArrayList<>();
        for (ClubShareholding s : shareholdingRepository.findAllByHumanId(humanId)) {
            Team t = teamRepository.findById(s.getTeamId()).orElse(null);
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("teamId", s.getTeamId());
            h.put("teamName", t != null ? t.getName() : "Club " + s.getTeamId());
            h.put("percent", s.getPercent());
            h.put("pricePerPercent", shareMarketService.pricePerPercent(s.getTeamId()));
            h.put("isOwner", s.getPercent() > ownershipService.ownershipThreshold());
            holdings.add(h);
        }
        result.put("shareholdings", holdings);
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> summary(Human human) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("humanId", human.getId());
        m.put("name", human.getName());
        m.put("wealth", human.getWealth());
        m.put("managerReputation", human.getManagerReputation());
        m.put("teamId", human.getTeamId());

        List<Map<String, Object>> ownedClubs = new ArrayList<>();
        for (Long teamId : ownershipService.ownedClubIds(human.getId())) {
            Team t = teamRepository.findById(teamId).orElse(null);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("teamId", teamId);
            c.put("teamName", t != null ? t.getName() : "Club " + teamId);
            ownedClubs.add(c);
        }
        m.put("ownedClubs", ownedClubs);
        return m;
    }

    // ==================== PERSONAL ASSETS ====================

    @PostMapping("/assets/buy")
    public ResponseEntity<?> buyAsset(@RequestBody Map<String, Object> body) {
        try {
            long humanId = asLong(body.get("humanId"));
            Asset.AssetType type = Asset.AssetType.valueOf(String.valueOf(body.get("type")).toUpperCase());
            String name = body.get("name") != null ? String.valueOf(body.get("name")) : null;
            long value = body.get("value") != null ? asLong(body.get("value")) : 0;
            Asset asset = assetService.buyAsset(humanId, type, name, value);
            return ResponseEntity.ok(Map.of("success", true, "asset", asset));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/assets/sell")
    public ResponseEntity<?> sellAsset(@RequestBody Map<String, Object> body) {
        try {
            long humanId = asLong(body.get("humanId"));
            long assetId = asLong(body.get("assetId"));
            assetService.sellAsset(humanId, assetId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ==================== CLUB SHARES ====================

    @PostMapping("/shares/buy")
    public ResponseEntity<?> buyShares(@RequestBody Map<String, Object> body) {
        try {
            long humanId = asLong(body.get("humanId"));
            long teamId = asLong(body.get("teamId"));
            double percent = asDouble(body.get("percent"));
            ClubShareholding h = shareMarketService.buyShares(humanId, teamId, percent);
            return ResponseEntity.ok(Map.of("success", true, "percent", h.getPercent(),
                    "isOwner", ownershipService.isOwner(humanId, teamId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/shares/sell")
    public ResponseEntity<?> sellShares(@RequestBody Map<String, Object> body) {
        try {
            long humanId = asLong(body.get("humanId"));
            long teamId = asLong(body.get("teamId"));
            double percent = asDouble(body.get("percent"));
            ClubShareholding h = shareMarketService.sellShares(humanId, teamId, percent);
            return ResponseEntity.ok(Map.of("success", true, "percent", h.getPercent(),
                    "isOwner", ownershipService.isOwner(humanId, teamId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ==================== INVEST / WITHDRAW (owner only) ====================

    @PostMapping("/club/invest")
    public ResponseEntity<?> invest(@RequestBody Map<String, Object> body) {
        return moveMoney(body, true);
    }

    @PostMapping("/club/withdraw")
    public ResponseEntity<?> withdraw(@RequestBody Map<String, Object> body) {
        return moveMoney(body, false);
    }

    /**
     * Invest: owner moves money from personal wealth into the owned club's
     * finances (income via FinanceService). Withdraw: the reverse. Guarded by
     * {@link OwnershipService#isOwner}.
     */
    private ResponseEntity<?> moveMoney(Map<String, Object> body, boolean invest) {
        try {
            long humanId = asLong(body.get("humanId"));
            long teamId = asLong(body.get("teamId"));
            long amount = Math.abs(asLong(body.get("amount")));
            if (amount <= 0) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Amount must be positive"));
            }
            if (!ownershipService.isOwner(humanId, teamId)) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Not the owner of this club"));
            }
            Human human = humanRepository.findById(humanId).orElseThrow();
            Team team = teamRepository.findById(teamId).orElseThrow();

            Round round = roundRepository.findById(1L).orElse(new Round());
            int season = (int) round.getSeason();
            int day = (int) round.getRound();

            if (invest) {
                if (human.getWealth() < amount) {
                    return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Insufficient wealth"));
                }
                human.setWealth(human.getWealth() - amount);
                humanRepository.save(human);
                financeService.recordTransaction(teamId, season, day, "OWNER_INJECTION",
                        "Owner investment from " + human.getName(), amount);
            } else {
                if (team.getTotalFinances() < amount) {
                    return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Club has insufficient finances"));
                }
                financeService.recordExpense(teamId, season, day, "OWNER_INJECTION",
                        "Owner withdrawal by " + human.getName(), amount);
                human.setWealth(human.getWealth() + amount);
                humanRepository.save(human);
            }
            Team updated = teamRepository.findById(teamId).orElse(team);
            return ResponseEntity.ok(Map.of("success", true,
                    "wealth", human.getWealth(),
                    "clubFinances", updated.getTotalFinances()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ==================== COACH MARKET (owner hires/fires the coach) — Faza 3 ====================

    /** Owner hires a coach for an owned club, paying the coach's release clause from club finances. */
    @PostMapping("/coach/hire")
    public ResponseEntity<?> hireCoach(@RequestBody Map<String, Object> body) {
        try {
            long ownerHumanId = asLong(body.get("ownerHumanId"));
            long teamId = asLong(body.get("teamId"));
            long coachHumanId = asLong(body.get("coachHumanId"));
            if (!ownershipService.isOwner(ownerHumanId, teamId)) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "Not the owner of this club"));
            }
            Human coach = humanRepository.findById(coachHumanId).orElse(null);
            if (coach == null || coach.getTypeId() != TypeNames.MANAGER_TYPE) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Coach not found"));
            }
            Team team = teamRepository.findById(teamId).orElseThrow();
            Round round = roundRepository.findById(1L).orElse(new Round());
            int season = (int) round.getSeason();
            int day = (int) round.getRound();

            Long coachOldTeam = coach.getTeamId();
            boolean employedElsewhere = coachOldTeam != null && coachOldTeam > 0 && coachOldTeam != teamId;
            if (employedElsewhere) {
                long clause = Math.max(0, coach.getReleaseClause());
                if (clause > 0) {
                    if (team.getTotalFinances() < clause) {
                        return ResponseEntity.badRequest().body(Map.of("success", false,
                                "message", "Club has insufficient finances for the release clause (" + clause + ")"));
                    }
                    financeService.recordExpense(teamId, season, day, "COACH_RELEASE_CLAUSE",
                            "Release clause paid for " + coach.getName(), clause);
                }
            }

            // Displace the current manager(s) of the target team.
            for (Human m : humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE)) {
                if (m.getId() == coachHumanId) continue;
                m.setTeamId(0L);
                humanRepository.save(m);
            }
            coach.setTeamId(teamId);
            coach.setRetired(false);
            humanRepository.save(coach);
            if (employedElsewhere) {
                humanService.ensureTeamHasManager(coachOldTeam);
            }
            ownerInbox(teamId, season, day, "New Coach Appointed",
                    coach.getName() + " has been appointed as coach by the owner.");
            return ResponseEntity.ok(Map.of("success", true, "coachId", coachHumanId, "teamId", teamId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", String.valueOf(e.getMessage())));
        }
    }

    /** Owner fires the current coach of an owned club; an AI replacement is spawned. */
    @PostMapping("/coach/fire")
    public ResponseEntity<?> fireCoach(@RequestBody Map<String, Object> body) {
        try {
            long ownerHumanId = asLong(body.get("ownerHumanId"));
            long teamId = asLong(body.get("teamId"));
            if (!ownershipService.isOwner(ownerHumanId, teamId)) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "Not the owner of this club"));
            }
            for (Human m : humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE)) {
                m.setTeamId(0L);
                humanRepository.save(m);
            }
            humanService.ensureTeamHasManager(teamId);
            Round round = roundRepository.findById(1L).orElse(new Round());
            ownerInbox(teamId, (int) round.getSeason(), (int) round.getRound(), "Coach Dismissed",
                    "The owner has dismissed the coach. A new coach has taken charge.");
            return ResponseEntity.ok(Map.of("success", true, "teamId", teamId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", String.valueOf(e.getMessage())));
        }
    }

    // ==================== COACH PERMISSIONS + XI LOCKING (owner) — Faza 4/5 ====================

    @GetMapping("/permissions/{teamId}")
    public ResponseEntity<?> getPermissions(@PathVariable long teamId) {
        return ResponseEntity.ok(coachPermissionService.getOrDefault(teamId));
    }

    @PostMapping("/permissions/{teamId}")
    public ResponseEntity<?> setPermissions(@PathVariable long teamId, @RequestBody Map<String, Object> body) {
        try {
            long ownerHumanId = asLong(body.get("ownerHumanId"));
            if (!ownershipService.isOwner(ownerHumanId, teamId)) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "Not the owner of this club"));
            }
            CoachPermissions p = coachPermissionService.getOrDefault(teamId);
            p.setTeamId(teamId);
            if (body.containsKey("canBuyPlayers")) p.setCanBuyPlayers(asBool(body.get("canBuyPlayers")));
            if (body.containsKey("canSellPlayers")) p.setCanSellPlayers(asBool(body.get("canSellPlayers")));
            if (body.containsKey("canNegotiateContracts")) p.setCanNegotiateContracts(asBool(body.get("canNegotiateContracts")));
            if (body.containsKey("canPickXI")) p.setCanPickXI(asBool(body.get("canPickXI")));
            if (body.containsKey("canChangeFormationTactics")) p.setCanChangeFormationTactics(asBool(body.get("canChangeFormationTactics")));
            if (body.containsKey("canSetTraining")) p.setCanSetTraining(asBool(body.get("canSetTraining")));
            if (body.containsKey("canSetSetPieces")) p.setCanSetSetPieces(asBool(body.get("canSetSetPieces")));
            if (body.containsKey("transferBudgetCap")) p.setTransferBudgetCap(asLong(body.get("transferBudgetCap")));
            return ResponseEntity.ok(coachPermissionService.save(p));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/lockXI/{teamId}")
    public ResponseEntity<?> lockXI(@PathVariable long teamId, @RequestBody Map<String, Object> body) {
        try {
            long ownerHumanId = asLong(body.get("ownerHumanId"));
            if (!ownershipService.isOwner(ownerHumanId, teamId)) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "Not the owner of this club"));
            }
            List<CoachPermissionService.LockedSlot> locks = new ArrayList<>();
            Object slotsObj = body.get("slots");
            if (slotsObj instanceof List<?> slots) {
                for (Object s : slots) {
                    if (s instanceof Map<?, ?> m) {
                        int idx = (int) asLong(m.get("positionIndex"));
                        long pid = asLong(m.get("playerId"));
                        locks.add(new CoachPermissionService.LockedSlot(idx, pid));
                    }
                }
            }
            CoachPermissions p = coachPermissionService.getOrDefault(teamId);
            p.setTeamId(teamId);
            p.setLockedSlots(coachPermissionService.writeLockedSlots(locks));
            coachPermissionService.save(p);
            return ResponseEntity.ok(Map.of("success", true, "lockedCount", locks.size()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", String.valueOf(e.getMessage())));
        }
    }

    @DeleteMapping("/lockXI/{teamId}")
    public ResponseEntity<?> clearLockXI(@PathVariable long teamId, @RequestParam long ownerHumanId) {
        if (!ownershipService.isOwner(ownerHumanId, teamId)) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Not the owner of this club"));
        }
        CoachPermissions p = coachPermissionService.getOrDefault(teamId);
        p.setTeamId(teamId);
        p.setLockedSlots(null);
        coachPermissionService.save(p);
        return ResponseEntity.ok(Map.of("success", true));
    }

    private void ownerInbox(long teamId, int season, int day, String title, String content) {
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(day);
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory("OWNER_DECISION");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);
    }

    private static boolean asBool(Object o) {
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private static long asLong(Object o) {
        if (o == null) throw new IllegalArgumentException("Missing numeric field");
        return ((Number) o).longValue();
    }

    private static double asDouble(Object o) {
        if (o == null) throw new IllegalArgumentException("Missing numeric field");
        return ((Number) o).doubleValue();
    }
}
