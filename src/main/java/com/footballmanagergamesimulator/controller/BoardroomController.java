package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Asset;
import com.footballmanagergamesimulator.model.ClubShareholding;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.ClubShareholdingRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.AssetService;
import com.footballmanagergamesimulator.service.FinanceService;
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

    private static long asLong(Object o) {
        if (o == null) throw new IllegalArgumentException("Missing numeric field");
        return ((Number) o).longValue();
    }

    private static double asDouble(Object o) {
        if (o == null) throw new IllegalArgumentException("Missing numeric field");
        return ((Number) o).doubleValue();
    }
}
