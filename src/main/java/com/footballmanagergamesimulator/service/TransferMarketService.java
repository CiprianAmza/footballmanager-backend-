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
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
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
        if (playerTransferView.isWillNeverLeave())
            return false; // editor-protected one-club player
        if (playerTransferView.getAge() > clubPlan.getMaxAge())
            return false; // club does not want to buy player, too old
        if (playerTransferView.getDesiredReputation() - 1000 > clubPlan.getTeamReputation())
            return false; // club can't buy player, reputation too low
        if (!playerTransferView.getPosition().equals(desiredPlayer.getPosition()))
            return false; // not desired position
        // 30 = scaled-up 10 for the 1-300 rating range (~3% of full scale tolerance).
        if (playerTransferView.getRating() < desiredPlayer.getMinRating() - 30)
            return false; // player rating too low
        if (playerTransferView.getTeamId() == clubPlan.getTeamId())
            return false; // club already owns player
        return true;
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
                    if (player.isWillNeverLeave()) continue;
                    if (player.getPosition() == null || player.getTypeId() != TypeNames.PLAYER_TYPE) continue;
                    if (!player.getPosition().equals(clubPlan.getPosition())) continue;
                    if (player.getAge() > buyPlanTransferView.getMaxAge()) continue;
                    // 30 = scaled-up 10 for the 1-300 rating range.
                    if (player.getRating() < clubPlan.getMinRating() - 30) continue;

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
