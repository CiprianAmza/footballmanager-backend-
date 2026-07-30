package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TransferOffer;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferOfferRepository;
import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.MatchingPass;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.SquadDepthChart;
import com.footballmanagergamesimulator.transfermarket.TransferPlayer;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Owns transfer-market workflow: the global transfer-window flag, the
 * eligibility rule for AI-vs-AI transfers ({@link #canBeTransfered}), and
 * the AI bidding pass against human-owned players
 * ({@link #generateAiOffersForHumanPlayers}).
 *
 * <p>Self-contained: no {@code @Lazy CompetitionController} back-ref. The
 * controller keeps thin delegate methods for callers that still talk to it
 * (REST endpoint, two other controllers); internal services should inject
 * this service directly.
 */
@Service
public class TransferMarketService {

    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TransferOfferRepository transferOfferRepository;
    @Autowired private ManagerInboxRepository managerInboxRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private UserContext userContext;
    @Autowired private CoachPermissionService coachPermissionService;

    /** Diagnostic only: which path the most recent successful match used. Single
     *  threaded within a window; read immediately after canBeTransfered returns true. */
    private boolean lastMatchWasStarter = false;

    public boolean lastMatchWasStarter() {
        return lastMatchWasStarter;
    }

    /** Global flag. Flipped by {@link com.footballmanagergamesimulator.service.SeasonTransitionService}
     *  (open at end-of-season) and {@link com.footballmanagergamesimulator.service.GameAdvanceService}
     *  (open/close on calendar transitions). */
    private boolean transferWindowOpen = false;

    public boolean isOpen() {
        return transferWindowOpen;
    }

    public void setOpen(boolean open) {
        this.transferWindowOpen = open;
    }

    /** Pure eligibility check used by the AI transfer-matching pass. */
    public boolean canBeTransfered(PlayerTransferView playerTransferView,
                                   BuyPlanTransferView clubPlan,
                                   TransferPlayer desiredPlayer) {
        return canBeTransfered(playerTransferView, clubPlan, desiredPlayer, MatchingPass.PRIMARY);
    }

    /**
     * Whether this club may sign this player, on the terms of the given pass.
     *
     * <p>Two ways in, and they are the same question asked from both sides:
     *
     * <ol>
     *   <li><b>Starter.</b> He beats the club's incumbent at that position, so he
     *       walks into the XI. The club wants him because he strengthens it; he
     *       accepts because he plays. A position with no starter has an incumbent
     *       of 0, so a hole admits anybody — which is what makes it urgent.</li>
     *   <li><b>Step-up.</b> He does not beat the incumbent, but he is a credible
     *       backup for him AND the club is enough better than he is that a bench
     *       seat there is still a move up. This is the squad-depth market, and how
     *       much of it a club does is the main thing that distinguishes the
     *       strategies.
     *
     *       <p>Both halves are load-bearing. "The club is better than him" alone has
     *       no lower bound — it gets <i>easier</i> to satisfy the worse the player
     *       is, so a side averaging 231 would sign a 99-rated keeper behind a
     *       215-rated one. The club's own floor is what stops that, and it is
     *       measured against the incumbent, not the squad average, so it never
     *       interferes with filling a genuine hole (that goes through the starter
     *       path).</li>
     * </ol>
     *
     * <p>Reputation plays no part. It was static seed data that judged the selling
     * <i>club</i> rather than the player, which blocked exactly the dominant flow —
     * a big club's fringe player dropping to a smaller one where he starts.
     *
     * <p>Positions are compared on the permissively collapsed base position, so an
     * AMC competes for an MC slot at full value. Familiarity has already discounted
     * the <i>incumbent</i> in {@link SquadDepthChart}; it never penalises the
     * candidate, because it must be able to lower a bar but never raise one.
     */
    public boolean canBeTransfered(PlayerTransferView playerTransferView,
                                   BuyPlanTransferView clubPlan,
                                   TransferPlayer desiredPlayer,
                                   MatchingPass pass) {
        if (playerTransferView.isWillNeverLeave())
            return false; // editor-protected one-club player
        if (playerTransferView.getAge() > clubPlan.getMaxAge())
            return false; // club does not want to buy player, too old
        if (!playerTransferView.getPosition().equals(desiredPlayer.getPosition()))
            return false; // not desired position
        if (!playerTransferView.isFreeAgent() && playerTransferView.getTeamId() == clubPlan.getTeamId())
            return false; // club already owns player

        double rating = playerTransferView.getRating();
        // The starter test is NOT relaxed by the clearance pass. Relaxing it let a club
        // agree a "starter" 35 points below the man he was supposed to displace, pay a
        // starter's fee, and then watch the engine leave him out. Clearance may loosen
        // what counts as squad depth; it may not redefine who is better than whom.
        if (rating > desiredPlayer.getIncumbentRating()) {
            lastMatchWasStarter = true;
            return true; // walks into the XI
        }
        lastMatchWasStarter = false;

        double relaxation = pass.ratingRelaxation();

        boolean credibleBackup =
                rating > desiredPlayer.getIncumbentRating() - clubPlan.getDepthTolerance() - relaxation;
        boolean worthTheMoveForHim =
                clubPlan.getXiAverage() > rating + clubPlan.getStepUpGap() - relaxation;
        return credibleBackup && worthTheMoveForHim;
    }

    /** AI team submits one offer per position slot for the best matching
     *  player on each human-owned squad. Persists a TransferOffer and an
     *  inbox notification for the human manager. */
    public synchronized void generateAiOffersForHumanPlayers(Team aiTeam, BuyPlanTransferView buyPlanTransferView) {
        if (buyPlanTransferView == null) return;
        // An owner who has barred buying binds the AI coach too — no AI offers for this club.
        if (!coachPermissionService.canBuyPlayers(aiTeam.getId())) return;

        Round round = roundRepository.findById(1L).orElseThrow();
        int season = (int) round.getSeason();
        int roundNumber = (int) round.getRound();

        for (long humanTeamId : userContext.getAllHumanTeamIds()) {
            List<Human> humanTeamPlayers = humanRepository.findAllByTeamId(humanTeamId);
            Team humanTeam = teamRepository.findById(humanTeamId).orElse(null);
            if (humanTeam == null) continue;

            for (TransferPlayer clubPlan : buyPlanTransferView.getPositions()) {
                for (Human player : humanTeamPlayers) {
                    if (player.isRetired()) continue;
                    if (player.getPosition() == null || player.getTypeId() != TypeNames.PLAYER_TYPE) continue;
                    // Same rule the AI-vs-AI market uses, so an AI club cannot bid for a
                    // human's player on terms it would never accept from another AI club.
                    // (This path used to compare raw positions against the plan's collapsed
                    // base position, so an AMC could never match an MC slot at all.)
                    PlayerTransferView candidate = new PlayerTransferView(
                            player.getId(), humanTeamId, player.getRating(),
                            TacticService.getBasePosition(player.getPosition()),
                            player.getPosition(), player.getAge(),
                            player.isWillNeverLeave(), false);
                    if (!canBeTransfered(candidate, buyPlanTransferView, clubPlan)) continue;

                    // Different clubs may compete for one player, but the same club cannot
                    // create duplicate active offers when parallel competitions are processed.
                    if (transferOfferRepository
                            .existsByPlayerIdAndFromTeamIdAndSeasonNumberAndStatusIn(
                                    player.getId(), aiTeam.getId(), season,
                                    List.of("pending", "negotiating", "counter", "accepted"))) {
                        continue;
                    }

                    long transferValue = TransferValueCalculator.calculate(
                            player.getAge(), player.getPosition(), player.getRating());
                    if (transferValue > aiTeam.getTransferBudget()) continue;

                    TransferOffer offer = new TransferOffer();
                    offer.setPlayerId(player.getId());
                    offer.setPlayerName(player.getName());
                    offer.setFromTeamId(aiTeam.getId());
                    offer.setFromTeamName(aiTeam.getName());
                    offer.setToTeamId(humanTeamId);
                    offer.setToTeamName(humanTeam.getName());
                    offer.setOfferAmount(transferValue);
                    offer.setAskingPrice(transferValue);
                    offer.setStatus("pending");
                    offer.setSeasonNumber(season);
                    offer.setDirection("incoming");
                    offer.setCreatedAt(System.currentTimeMillis());
                    transferOfferRepository.save(offer);

                    ManagerInbox inbox = new ManagerInbox();
                    inbox.setTeamId(humanTeamId);
                    inbox.setSeasonNumber(season);
                    inbox.setRoundNumber(roundNumber);
                    inbox.setTitle("Transfer Offer Received");
                    inbox.setContent(aiTeam.getName() + " have made an offer of " + transferValue +
                            " for your player " + player.getName() + " (" + player.getPosition() +
                            ", Rating: " + player.getRating() + "). Review the offer in the transfer section.");
                    inbox.setCategory("transfer");
                    inbox.setRead(false);
                    inbox.setCreatedAt(System.currentTimeMillis());
                    managerInboxRepository.save(inbox);

                    break; // Only one offer per position per AI team
                }
            }
        }
    }
}
