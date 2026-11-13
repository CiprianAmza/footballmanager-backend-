package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.AgentNegotiation.Status;
import com.footballmanagergamesimulator.service.AgentNegotiation.Step;
import com.footballmanagergamesimulator.service.AgentNegotiation.Terms;
import com.footballmanagergamesimulator.service.ContractPoints.Offer;
import com.footballmanagergamesimulator.service.ContractPoints.SquadMember;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persists round-based negotiations ({@link AgentNegotiation}) and applies their outcome: the contract when
 * agreed, the relation change with the agent in every case. While one is open the day cannot advance.
 */
@Service
public class ContractNegotiationService {

    public static final Set<Status> OPEN_STATUSES = EnumSet.of(Status.OPEN, Status.FINAL_OFFER);
    public static final int MAX_YEARS = 5;

    @Autowired ContractNegotiationRepository negotiationRepository;
    @Autowired HumanRepository humanRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired RoundRepository roundRepository;
    @Autowired AgentRepository agentRepository;
    @Autowired AgentRelationService agentRelationService;
    @Autowired TransferOfferRepository transferOfferRepository;
    @Autowired ManagerInboxRepository inboxRepository;
    @Autowired PlayerInterestService playerInterestService;
    @Autowired ContractSignature contractSignature;
    @Autowired RelationService relationService;
    @Autowired AgentService agentService;
    @Autowired PlannedTransferService plannedTransferService;
    @Autowired FinanceService financeService;
    // A provider: the market depends (through the transfer controller) on this service.
    @Autowired org.springframework.beans.factory.ObjectProvider<TransferMarketService> transferMarket;
    java.util.Random rng = new java.util.Random();
    // Lazy: the transfer market (TransferOfferController) depends on ContractController, which depends on this service.
    @Autowired @org.springframework.context.annotation.Lazy AgreedTransferHandler transferHandler;

    /** Thrown for any request the negotiation rules do not allow; the message is a stable code. */
    public static class NegotiationException extends RuntimeException {
        public NegotiationException(String code) {
            super(code);
        }
    }

    /**
     * Whether talks with an agent block the day (fee talks with a selling club do not). Talks nobody can finish
     * any more are closed first, so they cannot block the day for ever: the manager who opened them has left the
     * club, the player has left it (renewal) or the fee agreement no longer holds (transfer), or a second copy of
     * the same talks was opened (a double click) — the newest copy is the one kept.
     */
    @Transactional
    public boolean hasOpenNegotiation() {
        List<ContractNegotiation> open = negotiationRepository.findAllByStatusIn(OPEN_STATUSES).stream()
                .sorted(java.util.Comparator.comparingLong(ContractNegotiation::getId).reversed())
                .toList();
        Set<String> kept = new java.util.HashSet<>();
        boolean blocking = false;
        for (ContractNegotiation n : open) {
            if (!kept.add(n.getTeamId() + ":" + n.getPlayerId())) {
                // An older copy: the newest one carries on (and owns the transfer offer, so it is not collapsed).
                n.setStatus(Status.CANCELLED);
                negotiationRepository.save(n);
            } else if (!canStillBeFinished(n)) {
                if (ContractNegotiation.KIND_TRANSFER.equals(n.getKind())) {
                    cancel(n);
                } else {
                    n.setStatus(Status.CANCELLED);
                    negotiationRepository.save(n);
                }
            } else {
                blocking = true;
            }
        }
        return blocking;
    }

    /** The manager who opened the talks is still at the club, and the deal they are about is still possible. */
    private boolean canStillBeFinished(ContractNegotiation n) {
        if (n.getManagerId() > 0 && !humanRepository.findById(n.getManagerId())
                .map(m -> m.getTeamId() != null && m.getTeamId() == n.getTeamId())
                .orElse(false)) {
            return false;
        }
        return transferStillPossible(n);
    }

    /** Transfer personal terms are a transfer action (the chairman's transfer permission), renewals a contract one. */
    public boolean isTransfer(long negotiationId) {
        return negotiationRepository.findById(negotiationId)
                .map(n -> ContractNegotiation.KIND_TRANSFER.equals(n.getKind()))
                .orElse(false);
    }

    public List<ContractNegotiation> openNegotiations(long teamId) {
        return negotiationRepository.findAllByStatusIn(OPEN_STATUSES).stream()
                .filter(n -> n.getTeamId() == teamId).toList();
    }

    public ContractNegotiation get(long negotiationId, long teamId) {
        return negotiationRepository.findById(negotiationId)
                .filter(n -> n.getTeamId() == teamId)
                .orElseThrow(() -> new NegotiationException("NEGOTIATION_NOT_FOUND"));
    }

    /** Opens a renewal with the player's agent, or returns the one already open for him. */
    @Transactional
    public ContractNegotiation startRenewal(long playerId, long teamId) {
        Optional<ContractNegotiation> existing = openNegotiations(teamId).stream()
                .filter(n -> n.getPlayerId() == playerId).findFirst();
        if (existing.isPresent()) return existing.get();

        Human player = humanRepository.findById(playerId)
                .filter(h -> !h.isRetired() && h.getTeamId() != null && h.getTeamId() == teamId)
                .orElseThrow(() -> new NegotiationException("NOT_YOUR_PLAYER"));
        ContractNegotiation n = negotiationRepository.save(open(player, teamId, ContractNegotiation.KIND_RENEWAL));
        inviteBack(n, "Contract talks: " + player.getName(),
                "You opened contract talks with " + player.getName() + "'s representatives. "
                        + "The day cannot advance until they end.");
        return n;
    }

    /** Inbox message whose button leads back to the negotiation page. */
    private void inviteBack(ContractNegotiation n, String title, String content) {
        Round round = roundRepository.findById(1L).orElse(new Round());
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(n.getTeamId());
        inbox.setSeasonNumber((int) round.getSeason());
        inbox.setRoundNumber((int) round.getRound());
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory("contract");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        inbox.setActionType(ManagerInbox.ACTION_OPEN_NEGOTIATION);
        inbox.setActionRefId(n.getId());
        inboxRepository.save(inbox);
    }

    /**
     * Opens personal terms for a transfer whose fee the selling club accepted (the inbox button).
     * Opening it again returns the same negotiation, finished or not, so the button always leads back to it.
     * When the deal can no longer happen the fee agreement collapses (kept, despite the exception).
     */
    @Transactional(noRollbackFor = NegotiationException.class)
    public ContractNegotiation startTransfer(long transferOfferId, long teamId) {
        Optional<ContractNegotiation> existing = negotiationRepository
                .findFirstByTransferOfferIdOrderByIdDesc(transferOfferId)
                .filter(n -> n.getTeamId() == teamId);
        if (existing.isPresent()) return existing.get();

        TransferOffer offer = transferOfferRepository.findById(transferOfferId)
                .filter(o -> o.getFromTeamId() == teamId && "outgoing".equalsIgnoreCase(o.getDirection()))
                .orElseThrow(() -> new NegotiationException("OFFER_NOT_FOUND"));
        if (!TransferOffer.STATUS_FEE_AGREED.equals(offer.getStatus())) {
            throw new NegotiationException("FEE_NOT_AGREED");
        }
        Human player = humanRepository.findById(offer.getPlayerId())
                .filter(h -> !h.isRetired() && h.getTeamId() != null && h.getTeamId() == offer.getToTeamId())
                .orElse(null);
        // The deal cannot happen any more: the agreement lapses now (not on every later click) and the bid is free.
        String impossible = player == null ? "the player is no longer at " + offer.getToTeamName()
                : !windowOpen() ? "the transfer window has closed"
                : !canPayFee(teamId, offer.getOfferAmount()) ? "your club can no longer afford the fee"
                : null;
        if (impossible != null) {
            transferHandler.collapse(transferOfferId, impossible);
            throw new NegotiationException(player == null ? "PLAYER_GONE" : !windowOpen() ? "WINDOW_CLOSED" : "INSUFFICIENT_BUDGET");
        }

        // The player may not want to come at all: the agent says so and the talks end before they start.
        PlayerInterest.Reason interest = playerInterestService.interest(player, teamId, managerOf(teamId));
        if (interest != PlayerInterest.Reason.INTERESTED) {
            ContractNegotiation refused = new ContractNegotiation();
            refused.setKind(ContractNegotiation.KIND_TRANSFER);
            refused.setPlayerId(player.getId());
            refused.setTeamId(teamId);
            refused.setManagerId(managerOf(teamId));
            refused.setAgentId(player.getAgentId());
            refused.setSeason(currentSeason());
            refused.setTransferOfferId(transferOfferId);
            refused.setTransferFee(offer.getOfferAmount());
            refused.setStatus(Status.REFUSED);
            refused.setRefusalReason(interest.name());
            agentRepository.findById(player.getAgentId()).ifPresent(a -> refused.setPersonality(a.getPersonality()));
            refused.setRelation(agentRelationService.relation(player.getAgentId(), refused.getManagerId()));
            transferHandler.collapse(transferOfferId, "the player is not interested in joining");
            return negotiationRepository.save(refused);
        }
        ContractNegotiation n = open(player, teamId, ContractNegotiation.KIND_TRANSFER);
        n.setTransferOfferId(transferOfferId);
        n.setTransferFee(offer.getOfferAmount());
        return negotiationRepository.save(n);
    }

    /** A fresh negotiation for {@code player} with the manager of {@code teamId}, valued against that squad. */
    private ContractNegotiation open(Human player, long teamId, String kind) {
        long managerId = managerOf(teamId);
        long agentId = player.getAgentId();
        if (agentRelationService.refusesToNegotiate(agentId, managerId)) {
            throw new NegotiationException("AGENT_REFUSES");
        }
        Agent agent = agentId > 0 ? agentRepository.findById(agentId).orElse(null) : null;

        List<SquadMember> squad = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).stream()
                .filter(h -> h.getId() != player.getId() && !h.isRetired())
                .map(h -> new SquadMember(h.getRating(), h.getWage()))
                .toList();

        ContractNegotiation n = new ContractNegotiation();
        n.setKind(kind);
        n.setPlayerId(player.getId());
        n.setTeamId(teamId);
        n.setManagerId(managerId);
        n.setAgentId(agent == null ? 0 : agent.getId());
        n.setSeason(currentSeason());
        n.setPlayerValue(playerValue(player, teamId, managerId, kind, squad));
        n.setTransferValue(TransferValuation.of(player.getAge(), player.getRating()));
        n.setPersonality(agent == null ? null : agent.getPersonality());
        n.setRelation(agent == null ? AgentRelationService.START : agentRelationService.relation(agent.getId(), managerId));
        n.setRounds(agent == null ? AgentNegotiation.NO_AGENT_PATIENCE : agent.getPatience());
        apply(n, AgentNegotiation.start(terms(n)));
        return n;
    }

    /**
     * What the player is worth to this club: his rank in its wage hierarchy; a MONEY player moving clubs asks at
     * least a raise over his current wage; liking the club or the manager takes 15% off.
     */
    private long playerValue(Human player, long teamId, long managerId, String kind, List<SquadMember> squad) {
        long value = ContractPoints.demandedWage(player.getRating(), squad);
        if (ContractNegotiation.KIND_TRANSFER.equals(kind) && PlayerPersonality.of(player) == PlayerPersonality.MONEY) {
            value = Math.max(value, PlayerInterest.moneyWage(player.getWage(), player.getRating(),
                    squad.stream().map(SquadMember::rating).toList()));
        }
        return PlayerInterest.withBond(value, playerInterestService.hasBond(player, teamId, managerId));
    }

    @Transactional
    public ContractNegotiation offer(long negotiationId, long teamId, Offer mine) {
        ContractNegotiation n = get(negotiationId, teamId);
        if (n.getStatus() != Status.OPEN) throw new NegotiationException("NOT_OPEN");
        if (mine.years() < 1 || mine.years() > MAX_YEARS || mine.wage() < 0
                || mine.signingBonus() < 0 || mine.releaseClause() < 0) {
            throw new NegotiationException("INVALID_OFFER");
        }
        if (!transferStillPossible(n)) return cancel(n);
        if (!canPayBonus(n, mine.signingBonus())) throw new NegotiationException("BONUS_OVER_BUDGET");
        n.setLastWage(mine.wage());
        n.setLastYears(mine.years());
        n.setLastSigningBonus(mine.signingBonus());
        n.setLastReleaseClause(mine.releaseClause());
        postponeAiMove(n);
        return finish(n, AgentNegotiation.offer(terms(n), step(n), mine));
    }

    public static final String FIRED = "FIRED";
    public static final String KEPT = "KEPT";

    /**
     * During a renewal with my own player, I ask him to fire his agent (design §3.2). The agent always takes it
     * badly (−20). The player agrees with a chance equal to his relation with me (0–100 → 0–100%): the talks end
     * and he takes another agent. Otherwise the talks go on with an agent who now demands more.
     */
    @Transactional
    public ContractNegotiation askToFireAgent(long negotiationId, long teamId) {
        ContractNegotiation n = get(negotiationId, teamId);
        if (!ContractNegotiation.KIND_RENEWAL.equals(n.getKind())) throw new NegotiationException("NOT_YOUR_PLAYER");
        if (n.getStatus() != Status.OPEN) throw new NegotiationException("NOT_OPEN");
        if (n.getAgentId() <= 0) throw new NegotiationException("NO_AGENT");
        if (n.getFireAgentOutcome() != null) throw new NegotiationException("ALREADY_ASKED");
        Human player = humanRepository.findById(n.getPlayerId())
                .filter(h -> h.getTeamId() != null && h.getTeamId() == teamId && h.getAgentId() == n.getAgentId())
                .orElseThrow(() -> new NegotiationException("NOT_YOUR_PLAYER"));

        int agentRelation = agentRelationService.record(n.getAgentId(), n.getManagerId(),
                AgentRelationEvent.ASKED_PLAYER_TO_FIRE_AGENT);
        int trust = relationService.relation(RelationParty.PLAYER, player.getId(), RelationParty.MANAGER, n.getManagerId());
        if (rng.nextDouble() * RelationService.MAX < trust) {
            n.setFireAgentOutcome(FIRED);
            n.setStatus(Status.CANCELLED);
            agentService.replaceAgent(player);
        } else {
            n.setFireAgentOutcome(KEPT);
            n.setRelation(agentRelation);
            Offer tougher = AgentNegotiation.agentDemand(terms(n));
            n.setAgentWage(tougher.wage());
            n.setAgentYears(tougher.years());
            n.setAgentSigningBonus(tougher.signingBonus());
            n.setAgentReleaseClause(tougher.releaseClause());
        }
        return negotiationRepository.save(n);
    }

    @Transactional
    public ContractNegotiation answerFinalOffer(long negotiationId, long teamId, boolean accept) {
        ContractNegotiation n = get(negotiationId, teamId);
        if (n.getStatus() != Status.FINAL_OFFER) throw new NegotiationException("NO_FINAL_OFFER");
        if (accept && !transferStillPossible(n)) return cancel(n);
        if (accept && !canPayBonus(n, n.getAgentSigningBonus())) throw new NegotiationException("BONUS_OVER_BUDGET");
        postponeAiMove(n);
        return finish(n, AgentNegotiation.answerFinalOffer(step(n), accept));
    }

    private ContractNegotiation finish(ContractNegotiation n, Step next) {
        apply(n, next);
        if (next.event() != null) {
            agentRelationService.record(n.getAgentId(), n.getManagerId(), next.event());
        }
        boolean transfer = ContractNegotiation.KIND_TRANSFER.equals(n.getKind());
        if (next.status() == Status.AGREED) {
            // On agreement the step carries the signed deal: my offer, or the agent's final offer I accepted.
            if (transfer) transferHandler.complete(n.getTransferOfferId());
            signContract(n, next.agentOffer());
        } else if (transfer && !AgentNegotiation.isOpen(next.status())) {
            transferHandler.collapse(n.getTransferOfferId(), next.status() == Status.WALKED_AWAY
                    ? "the agent walked away from the talks" : "you refused the agent's final offer");
        }
        return negotiationRepository.save(n);
    }

    /** A round with the agent of a player an AI club plans to sign pushes that move back (design §3.6). */
    private void postponeAiMove(ContractNegotiation n) {
        if (ContractNegotiation.KIND_TRANSFER.equals(n.getKind())) plannedTransferService.postpone(n.getPlayerId());
    }

    /**
     * A renewal can only be signed while the player is still mine. A transfer only while the fee agreement holds,
     * the player is still at the selling club, the window is open and my club can still pay the fee.
     */
    private boolean transferStillPossible(ContractNegotiation n) {
        if (!ContractNegotiation.KIND_TRANSFER.equals(n.getKind())) {
            return humanRepository.findById(n.getPlayerId())
                    .map(h -> !h.isRetired() && h.getTeamId() != null && h.getTeamId() == n.getTeamId())
                    .orElse(false);
        }
        Optional<TransferOffer> offer = transferOfferRepository.findById(n.getTransferOfferId())
                .filter(o -> TransferOffer.STATUS_FEE_AGREED.equals(o.getStatus()));
        return offer.isPresent() && humanRepository.findById(n.getPlayerId())
                .map(h -> !h.isRetired() && h.getTeamId() != null && h.getTeamId() == offer.get().getToTeamId())
                .orElse(false)
                && windowOpen() && canPayFee(n.getTeamId(), offer.get().getOfferAmount());
    }

    private boolean windowOpen() {
        TransferMarketService market = transferMarket == null ? null : transferMarket.getIfAvailable();
        return market == null || market.isOpen();
    }

    private boolean canPayFee(long teamId, long fee) {
        return teamRepository.findById(teamId).map(t -> t.getTransferBudget() >= fee).orElse(false);
    }

    /** The signing bonus comes out of the transfer budget, after the fee when it is a transfer. */
    private boolean canPayBonus(ContractNegotiation n, long bonus) {
        if (bonus <= 0) return true;
        long fee = ContractNegotiation.KIND_TRANSFER.equals(n.getKind()) ? n.getTransferFee() : 0;
        return teamRepository.findById(n.getTeamId()).map(t -> t.getTransferBudget() - fee >= bonus).orElse(false);
    }

    /** Ends talks that can no longer lead to a deal, without touching the relation. */
    private ContractNegotiation cancel(ContractNegotiation n) {
        n.setStatus(Status.CANCELLED);
        transferHandler.collapse(n.getTransferOfferId(), "the deal is no longer possible");
        return negotiationRepository.save(n);
    }

    private void signContract(ContractNegotiation n, Offer deal) {
        Human player = humanRepository.findById(n.getPlayerId()).orElseThrow();
        long oldWage = player.getWage();
        player.setWage(deal.wage());
        player.setContractEndSeason(n.getSeason() + deal.years());
        contractSignature.mark(player);
        player.setReleaseClause(deal.releaseClause());
        if (player.isWantsTransfer()) {
            player.setWantsTransfer(false);
            player.setMorale(Math.min(100, player.getMorale() + 10));
        }
        humanRepository.save(player);
        if (deal.signingBonus() > 0) {
            // In the ledger too, like every other payment (it only lowered the budget before).
            financeService.recordExpense(n.getTeamId(), currentSeason(), 0, "SIGNING_BONUS",
                    "Signing bonus for " + player.getName(), deal.signingBonus());
        }
        teamRepository.findById(n.getTeamId()).ifPresent(team -> {
            team.setSalaryBudget(team.getSalaryBudget() + (deal.wage() - oldWage));
            team.setTransferBudget(team.getTransferBudget() - deal.signingBonus());
            teamRepository.save(team);
        });
    }

    private static void apply(ContractNegotiation n, Step step) {
        n.setStatus(step.status());
        n.setRound(step.round());
        Offer o = step.agentOffer();
        n.setAgentWage(o.wage());
        n.setAgentYears(o.years());
        n.setAgentSigningBonus(o.signingBonus());
        n.setAgentReleaseClause(o.releaseClause());
    }

    static Terms terms(ContractNegotiation n) {
        return new Terms(n.getPlayerValue(), n.getTransferValue(), n.getPersonality(), n.getRelation(), n.getRounds());
    }

    static Step step(ContractNegotiation n) {
        return new Step(n.getStatus(), n.getRound(),
                new Offer(n.getAgentWage(), n.getAgentYears(), n.getAgentSigningBonus(), n.getAgentReleaseClause()), null);
    }

    private long managerOf(long teamId) {
        List<Human> managers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE);
        return managers.isEmpty() ? 0 : managers.get(0).getId();
    }

    private int currentSeason() {
        return (int) roundRepository.findById(1L).orElse(new Round()).getSeason();
    }
}
