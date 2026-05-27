package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.TransferPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the eligibility rules + window flag of {@link TransferMarketService}.
 * Pure tests — no Spring context required. {@code generateAiOffersForHumanPlayers}
 * is exercised via the {@code @SpringBootTest} season-transition integration test.
 */
class TransferMarketServiceTest {

    private final TransferMarketService svc = new TransferMarketService();

    // ============================================================
    //  Transfer window flag
    // ============================================================

    @Test
    @DisplayName("window flag: defaults to closed, mutable via setOpen")
    void windowFlag_roundTrips() {
        assertFalse(svc.isOpen(), "new service should default to closed window");
        svc.setOpen(true);
        assertTrue(svc.isOpen());
        svc.setOpen(false);
        assertFalse(svc.isOpen());
    }

    // ============================================================
    //  canBeTransfered — each branch returns false; happy path returns true
    // ============================================================

    @Test
    @DisplayName("canBeTransfered: happy path — all checks pass")
    void canBeTransfered_happyPath() {
        // player: age 24, rating 70, "ST", desiredRep 3000, in team 99
        // plan:   maxAge 30, teamRep 2500 (3000-1000=2000 <= 2500 ✓)
        // target: position "ST", minRating 60 (70 >= 60-10 ✓)
        assertTrue(svc.canBeTransfered(
                playerView(99L, 3000L, 24L, 70.0, "ST"),
                plan(30, 2500L, 1L),
                target("ST", 60.0)
        ));
    }

    @Test
    @DisplayName("canBeTransfered: rejected when player too old")
    void canBeTransfered_tooOld() {
        assertFalse(svc.canBeTransfered(
                playerView(99L, 3000L, 31L, 70.0, "ST"),
                plan(30, 5000L, 1L),
                target("ST", 60.0)
        ));
    }

    @Test
    @DisplayName("canBeTransfered: rejected when buyer reputation too low (player desired - 1000 > team rep)")
    void canBeTransfered_repTooLow() {
        // desiredRep 4000 - 1000 = 3000 > teamRep 2500 → reject
        assertFalse(svc.canBeTransfered(
                playerView(99L, 4000L, 24L, 70.0, "ST"),
                plan(30, 2500L, 1L),
                target("ST", 60.0)
        ));
    }

    @Test
    @DisplayName("canBeTransfered: rejected when position mismatches plan slot")
    void canBeTransfered_wrongPosition() {
        assertFalse(svc.canBeTransfered(
                playerView(99L, 3000L, 24L, 70.0, "ST"),
                plan(30, 5000L, 1L),
                target("DC", 60.0)  // plan slot wants DC, player is ST
        ));
    }

    @Test
    @DisplayName("canBeTransfered: rejected when rating too far below minRating (>10 below)")
    void canBeTransfered_ratingTooLow() {
        // player rating 49, minRating 60 → 60 - 10 = 50; 49 < 50 → reject
        assertFalse(svc.canBeTransfered(
                playerView(99L, 3000L, 24L, 49.0, "ST"),
                plan(30, 5000L, 1L),
                target("ST", 60.0)
        ));
    }

    @Test
    @DisplayName("canBeTransfered: rating exactly at minRating-10 boundary is allowed")
    void canBeTransfered_ratingAtBoundary() {
        // minRating 60, threshold = 50; player 50.0 → 50.0 >= 50.0 ✓
        assertTrue(svc.canBeTransfered(
                playerView(99L, 3000L, 24L, 50.0, "ST"),
                plan(30, 5000L, 1L),
                target("ST", 60.0)
        ));
    }

    @Test
    @DisplayName("canBeTransfered: rejected when player already on the buyer's team")
    void canBeTransfered_sameTeam() {
        assertFalse(svc.canBeTransfered(
                playerView(7L, 3000L, 24L, 70.0, "ST"),
                plan(30, 5000L, 7L),  // teamId matches
                target("ST", 60.0)
        ));
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private PlayerTransferView playerView(long teamId, long desiredRep, long age, double rating, String pos) {
        return new PlayerTransferView(1L, teamId, desiredRep, rating, pos, age);
    }

    private BuyPlanTransferView plan(int maxAge, long teamRep, long teamId) {
        BuyPlanTransferView p = new BuyPlanTransferView();
        p.setMaxAge(maxAge);
        p.setTeamReputation(teamRep);
        p.setTeamId(teamId);
        return p;
    }

    private TransferPlayer target(String pos, double minRating) {
        TransferPlayer t = new TransferPlayer();
        t.setPosition(pos);
        t.setMinRating(minRating);
        return t;
    }
}
