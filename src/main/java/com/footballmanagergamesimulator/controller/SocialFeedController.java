package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.service.FanSocialFeedService;
import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.TeamAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/social-feed")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class SocialFeedController {

    private final ManagerInboxRepository inbox;
    private final TeamAccessGuard teamAccessGuard;
    private final CurrentUserService currentUserService;

    public SocialFeedController(ManagerInboxRepository inbox, TeamAccessGuard teamAccessGuard,
                                CurrentUserService currentUserService) {
        this.inbox = inbox;
        this.teamAccessGuard = teamAccessGuard;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/me")
    public ResponseEntity<List<SocialFeedPostView>> myFeed(HttpServletRequest request) {
        if (currentUserService.getUserOrNull(request) == null) return ResponseEntity.status(401).build();
        Long teamId = teamAccessGuard.resolveInboxTeamId(request, 0L);
        if (teamId == null || teamId <= 0) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(inbox.findAllByTeamIdAndCategoryOrderByIdDesc(teamId, FanSocialFeedService.CATEGORY)
                .stream().map(this::toView).toList());
    }

    private SocialFeedPostView toView(ManagerInbox message) {
        String[] parts = message.getContent() == null ? new String[0] : message.getContent().split("\\n", 3);
        String tone = parts.length > 0 ? parts[0] : "REACTION";
        String context = parts.length > 1 ? parts[1] : "Club discussion";
        String body = parts.length > 2 ? parts[2] : "";
        int intensity = switch (tone) {
            case "HARSH", "ANGRY" -> 3;
            case "CRITICAL", "FRUSTRATED", "WORRIED", "DEMANDING" -> 2;
            default -> 1;
        };
        long seed = Math.abs((message.getDeduplicationKey() == null ? "" : message.getDeduplicationKey()).hashCode());
        int upvotes = 12 + (int) Math.floorMod(seed * 37L + intensity * 97L, 1100L);
        int replies = 2 + (int) Math.floorMod(seed * 13L + intensity * 11L, 180L);
        return new SocialFeedPostView(message.getId(), message.getTitle(), tone, context, body,
                upvotes, replies, message.getSeasonNumber(), message.getRoundNumber(), message.getCreatedAt());
    }

    public record SocialFeedPostView(long id, String handle, String tone, String context, String body,
                                     int upvotes, int replies, int seasonNumber, int day, long createdAt) {}
}
