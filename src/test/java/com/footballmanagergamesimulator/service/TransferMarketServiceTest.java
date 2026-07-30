package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.MatchingPass;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.TransferPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the eligibility rules + window flag of {@link TransferMarketService}.
 * Pure tests — no Spring context required. {@code generateAiOffersForHumanPlayers}
 * is exercised via the {@code @SpringBootTest} season-transition integration test.
 *
 * <p>Ratings are on the 1-300 scale. A club's "incumbent" is the effective rating
 * of its current starter at that position; 0 means it fields nobody there.
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
    //  Hard rejections — nothing gets past these
    // ============================================================

    @Test
    @DisplayName("rejected when the player is older than the plan allows")
    void tooOld() {
        assertFalse(svc.canBeTransfered(
                player(99L, 31L, 200.0, "ST"),
                plan(30, 150.0, 40.0, 60.0, 1L),
                slot("ST", 150.0)));
    }

    @Test
    @DisplayName("rejected when the position does not match the plan slot")
    void wrongPosition() {
        assertFalse(svc.canBeTransfered(
                player(99L, 24L, 200.0, "ST"),
                plan(30, 150.0, 40.0, 60.0, 1L),
                slot("DC", 150.0)));
    }

    @Test
    @DisplayName("rejected when the buyer already owns the player")
    void sameTeam() {
        assertFalse(svc.canBeTransfered(
                player(7L, 24L, 200.0, "ST"),
                plan(30, 150.0, 40.0, 60.0, 7L),
                slot("ST", 150.0)));
    }

    @Test
    @DisplayName("editor-protected one-club player is never eligible")
    void neverLeavePlayer() {
        PlayerTransferView player = new PlayerTransferView(
                1L, 99L, 250.0, "ST", "ST", 24L, true, true);
        assertFalse(svc.canBeTransfered(player, plan(30, 150.0, 40.0, 60.0, 7L), slot("ST", 150.0)));
    }

    // ============================================================
    //  Path 1 — the signing walks into the XI
    // ============================================================

    @Nested
    @DisplayName("starter path")
    class StarterPath {

        @Test
        @DisplayName("accepted when he beats the club's incumbent at that position")
        void beatsIncumbent() {
            assertTrue(svc.canBeTransfered(
                    player(99L, 24L, 160.0, "ST"),
                    plan(30, 150.0, 10.0, 60.0, 1L),
                    slot("ST", 150.0)));
        }

        @Test
        @DisplayName("a position with no starter is a hole and admits anybody")
        void holeAdmitsAnybody() {
            assertTrue(svc.canBeTransfered(
                    player(99L, 24L, 40.0, "GK"),
                    plan(30, 200.0, 0.0, 60.0, 1L), // stepUpGap 0 → only the starter path can fire
                    slot("GK", 0.0)));
        }

        @Test
        @DisplayName("a big club's fringe player drops to a smaller club where he starts")
        void bigClubFringeFlowsDown() {
            // Rating 250 — a reserve at an elite club, comfortably better than the
            // mid club's 215 starter. This is the flow the old reputation gate blocked:
            // it compared the selling CLUB's reputation, so anything leaving a strong
            // club was refused by every weaker one.
            assertTrue(svc.canBeTransfered(
                    player(99L, 27L, 250.0, "MC"),
                    plan(40, 215.0, 10.0, 60.0, 1L),
                    slot("MC", 215.0)));
        }
    }

    // ============================================================
    //  Path 2 — squad depth, a step up for the player
    // ============================================================

    @Nested
    @DisplayName("step-up path")
    class StepUpPath {

        @Test
        @DisplayName("accepted as depth when he backs up the incumbent AND the club is a step up")
        void acceptedAsDepth() {
            // Loses to the 200 incumbent but is within the club's 60-point backup
            // tolerance, and the club averages 200 against his 150 → a bench seat
            // here is still a move up for him.
            assertTrue(svc.canBeTransfered(
                    player(99L, 24L, 150.0, "ST"),
                    plan(30, 200.0, 40.0, 60.0, 1L),
                    slot("ST", 200.0)));
        }

        @Test
        @DisplayName("refused when he is far too weak to back the incumbent up")
        void refusedWhenNotACredibleBackup() {
            // The bug this pins: "the club is better than him" gets EASIER to satisfy
            // the worse the player is, so without the club's own floor a side
            // averaging 231 signed a 99-rated keeper behind a 215-rated one.
            assertFalse(svc.canBeTransfered(
                    player(99L, 28L, 99.7, "GK"),
                    plan(40, 231.9, 15.0, 20.0, 1L),
                    slot("GK", 215.7)));
        }

        @Test
        @DisplayName("a weak incumbent is still replaced by anyone better — the floor never blocks a hole")
        void floorDoesNotBlockHoleFilling() {
            // Centre-back rated 35 in a side averaging 218: a 100-rated replacement
            // goes through the STARTER path, so the backup floor is never consulted.
            assertTrue(svc.canBeTransfered(
                    player(99L, 26L, 100.0, "DC"),
                    plan(40, 218.9, 15.0, 20.0, 1L),
                    slot("DC", 35.0)));
        }

        @Test
        @DisplayName("refused when the club is not enough of a step up")
        void refusedWhenNotAStepUp() {
            // Beaten by the incumbent AND the club is only 10 better than he is,
            // so there is nothing in it for either side.
            assertFalse(svc.canBeTransfered(
                    player(99L, 24L, 190.0, "ST"),
                    plan(30, 200.0, 40.0, 60.0, 1L),
                    slot("ST", 200.0)));
        }

        @Test
        @DisplayName("a wider gap makes a club a more willing buyer")
        void widerGapBuysMore() {
            PlayerTransferView candidate = player(99L, 24L, 190.0, "ST");
            assertFalse(svc.canBeTransfered(candidate, plan(30, 200.0, 40.0, 60.0, 1L), slot("ST", 200.0)));
            assertTrue(svc.canBeTransfered(candidate, plan(30, 200.0, 5.0, 60.0, 1L), slot("ST", 200.0)));
        }
    }

    // ============================================================
    //  Clearance pass relaxes both paths
    // ============================================================

    @Test
    @DisplayName("clearance pass signs a player the primary pass refused")
    void clearanceRelaxesTheBar() {
        PlayerTransferView candidate = player(99L, 24L, 190.0, "ST");
        BuyPlanTransferView buyer = plan(30, 200.0, 5.0, 20.0, 1L);
        TransferPlayer wanted = slot("ST", 240.0);

        assertFalse(svc.canBeTransfered(candidate, buyer, wanted, MatchingPass.PRIMARY),
                "190 is 50 below the incumbent, outside this club's 20-point backup tolerance");
        assertTrue(svc.canBeTransfered(candidate, buyer, wanted, MatchingPass.CLEARANCE),
                "clearance widens the backup tolerance by 35, bringing 190 inside it");
    }

    @Test
    @DisplayName("clearance still respects the hard rejections")
    void clearanceDoesNotOverrideHardRules() {
        assertFalse(svc.canBeTransfered(
                player(99L, 31L, 250.0, "ST"),
                plan(30, 200.0, 40.0, 60.0, 1L),
                slot("ST", 150.0),
                MatchingPass.CLEARANCE));
    }

    // ============================================================
    //  Free agents
    // ============================================================

    @Test
    @DisplayName("a free agent is not treated as already belonging to the buyer")
    void freeAgentIsNotOwnedByTeamZero() {
        // Free agents carry teamId 0. A club whose own id happened to be 0 would
        // otherwise be unable to sign any of them.
        PlayerTransferView freeAgent = new PlayerTransferView(
                1L, 0L, 200.0, "ST", "ST", 24L, false, false);
        assertTrue(freeAgent.isFreeAgent());
        assertTrue(svc.canBeTransfered(freeAgent, plan(30, 150.0, 10.0, 60.0, 0L), slot("ST", 150.0)));
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private PlayerTransferView player(long teamId, long age, double rating, String pos) {
        return new PlayerTransferView(1L, teamId, rating, pos, pos, age, false, false);
    }

    private BuyPlanTransferView plan(int maxAge, double xiAverage, double stepUpGap,
                                    double depthTolerance, long teamId) {
        BuyPlanTransferView p = new BuyPlanTransferView();
        p.setMaxAge(maxAge);
        p.setXiAverage(xiAverage);
        p.setStepUpGap(stepUpGap);
        p.setDepthTolerance(depthTolerance);
        p.setTeamId(teamId);
        return p;
    }

    private TransferPlayer slot(String pos, double incumbentRating) {
        TransferPlayer t = new TransferPlayer();
        t.setPosition(pos);
        t.setIncumbentRating(incumbentRating);
        return t;
    }
}
