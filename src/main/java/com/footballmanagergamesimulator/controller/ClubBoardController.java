package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.DirectorPersonality;
import com.footballmanagergamesimulator.service.board.ClubBoardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** A club's chairman and general director (design DESIGN_START_CARIERA §5). */
@RestController
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class ClubBoardController {

    private final ClubBoardService boards;
    private final com.footballmanagergamesimulator.service.board.BoardTrustService trust;
    private final com.footballmanagergamesimulator.service.board.LicenceService licences;

    private final com.footballmanagergamesimulator.service.board.DirectorWishService wishes;
    private final com.footballmanagergamesimulator.user.UserContext userContext;

    public ClubBoardController(ClubBoardService boards,
                               com.footballmanagergamesimulator.service.board.BoardTrustService trust,
                               com.footballmanagergamesimulator.service.board.LicenceService licences,
                               com.footballmanagergamesimulator.service.board.DirectorWishService wishes,
                               com.footballmanagergamesimulator.user.UserContext userContext) {
        this.boards = boards;
        this.trust = trust;
        this.licences = licences;
        this.wishes = wishes;
        this.userContext = userContext;
    }

    /** The director's recent requests to my club (whose wish each was stays hidden unless asked). */
    @GetMapping("/club-board/wishes")
    public java.util.List<Map<String, Object>> wishes(jakarta.servlet.http.HttpServletRequest request) {
        return wishes.wishes(userContext.getTeamId(request)).stream().map(w -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", w.getId());
            m.put("season", w.getSeason());
            m.put("text", w.getText());
            m.put("settled", w.isSettled());
            m.put("obeyed", w.isObeyed());
            m.put("revealed", w.isRevealed());
            if (w.isRevealed()) m.put("fromChairman", w.isFromChairman());
            return m;
        }).toList();
    }

    /** Go over the director's head: ask the chairman whose wish it is (once a season). */
    @PostMapping("/club-board/wishes/{wishId}/ask-chairman")
    public com.footballmanagergamesimulator.service.board.DirectorWishService.Answer askChairman(jakarta.servlet.http.HttpServletRequest request, @PathVariable long wishId) {
        return wishes.askChairman(userContext.getTeamId(request), wishId);
    }

    /** Ask the chairman to dismiss the director. */
    @PostMapping("/club-board/dismiss-director")
    public com.footballmanagergamesimulator.service.board.DirectorWishService.Answer dismissDirector(jakarta.servlet.http.HttpServletRequest request) {
        return wishes.requestDirectorDismissal(userContext.getTeamId(request));
    }

    @GetMapping("/club-board/{teamId}")
    public ClubBoardService.BoardView board(@PathVariable long teamId) {
        return boards.view(teamId);
    }

    /** The chairman's trust in the club's manager, 0-100 (the director's feelings stay hidden). */
    @GetMapping("/club-board/{teamId}/trust")
    public Map<String, Object> trust(@PathVariable long teamId) {
        return Map.of("chairmanTrust", trust.trust(teamId).chairmanInManager());
    }

    /** How the club's manager is registered: the licence the league needs, his, his role, the front coach. */
    @GetMapping("/club-board/{teamId}/registration")
    public ResponseEntity<?> registration(@PathVariable long teamId) {
        long manager = trust.managerOf(teamId);
        if (manager == 0) return ResponseEntity.ok(Map.of());
        return ResponseEntity.ok(licences.registration(manager, teamId));
    }

    /** Editor (admin only, by the /admin/** rule). Body: {"personality": "AMBITIOUS"} or {"personality": null}. */
    @PutMapping("/admin/club-board/{teamId}")
    public ResponseEntity<?> edit(@PathVariable long teamId, @RequestBody Map<String, Object> body) {
        Object value = body.get("personality");
        try {
            DirectorPersonality personality = value == null ? null : DirectorPersonality.valueOf(String.valueOf(value));
            return ResponseEntity.ok(boards.edit(teamId, personality));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage()));
        }
    }
}
