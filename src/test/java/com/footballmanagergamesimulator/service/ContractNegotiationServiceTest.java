package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.AgentNegotiation.Status;
import com.footballmanagergamesimulator.service.ContractNegotiationService.NegotiationException;
import com.footballmanagergamesimulator.service.ContractPoints.Offer;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DataJpaTest
@Import({ContractNegotiationService.class, AgentRelationService.class, RelationService.class, PlayerInterestService.class,
        AgentService.class,
        ContractSignature.class})
class ContractNegotiationServiceTest {

    @Autowired private ContractNegotiationService service;
    @Autowired private HumanRepository humans;
    @Autowired private TeamRepository teams;
    @Autowired private AgentRepository agents;
    @Autowired private AgentRelationService relations;
    @Autowired private RelationService allRelations;
    @Autowired private TransferOfferRepository offers;
    @Autowired private ManagerInboxRepository inbox;
    @MockBean private AgreedTransferHandler transferHandler;
    @MockBean private NationService nationService;
    @MockBean private PlannedTransferService plannedTransferService;
    @MockBean private com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator nameGenerator;

    private long teamId;
    private long playerId;
    private long managerId;
    private long agentId;

    @BeforeEach
    void world() {
        Team team = new Team();
        team.setName("Tik Tok");
        team.setSalaryBudget(100_000);
        team.setTransferBudget(500_000);
        teamId = teams.save(team).getId();

        Agent agent = new Agent();
        agent.setName("Vrel Osk");
        agent.setPersonality(AgentPersonality.STABILITY);
        agent.setPatience(3);
        agentId = agents.save(agent).getId();

        playerId = humans.save(human(TypeNames.PLAYER_TYPE, 200, 3_000, agentId)).getId();
        humans.save(human(TypeNames.PLAYER_TYPE, 250, 9_000, 0));
        humans.save(human(TypeNames.PLAYER_TYPE, 150, 2_000, 0));
        managerId = humans.save(human(TypeNames.MANAGER_TYPE, 0, 0, 0)).getId();
    }

    @Test
    @DisplayName("""
            GIVEN a player rated 200 in a squad paying 9000 (rated 250) and 2000 (rated 150), with a Stability agent
            WHEN I open a renewal and offer 2000 for one year
            THEN he is worth 2000, the agent first asks 1400 for 4 years, my offer is signed,
             the wage budget moves by the difference and the relation rises by 5
            """)
    void agreedOfferIsSigned() {
        ContractNegotiation n = service.startRenewal(playerId, teamId);
        assertEquals(2_000, n.getPlayerValue());
        assertEquals(1_400, n.getAgentWage());
        assertEquals(4, n.getAgentYears());
        assertTrue(service.hasOpenNegotiation());

        n = service.offer(n.getId(), teamId, new Offer(2_000, 1, 0, 0));

        assertEquals(Status.AGREED, n.getStatus());
        assertFalse(service.hasOpenNegotiation());
        Human player = humans.findById(playerId).orElseThrow();
        assertEquals(2_000, player.getWage());
        assertEquals(n.getSeason() + 1, player.getContractEndSeason());
        assertEquals(100_000 - 1_000, teams.findById(teamId).orElseThrow().getSalaryBudget());
        assertEquals(55, relations.relation(agentId, managerId));
    }

    @Test
    @DisplayName("""
            GIVEN an agent with patience 3
            WHEN I open the talks (twice), offer too little twice and then refuse his take-it-or-leave-it offer
            THEN one inbox message leads back to them, the day stays blocked until the refusal,
             the contract is unchanged and the relation drops by 5
            """)
    void refusedFinalOfferEndsTheTalks() {
        ContractNegotiation n = service.startRenewal(playerId, teamId);
        assertEquals(n.getId(), service.startRenewal(playerId, teamId).getId(), "a second start resumes the same talks");
        long talksId = n.getId();
        assertEquals(1, inbox.findAll().stream()
                .filter(m -> ManagerInbox.ACTION_OPEN_NEGOTIATION.equals(m.getActionType())
                        && m.getActionRefId() == talksId).count(), "one inbox message leads back to the talks");

        n = service.offer(n.getId(), teamId, new Offer(1_500, 1, 0, 0));
        assertEquals(Status.OPEN, n.getStatus());
        n = service.offer(n.getId(), teamId, new Offer(1_500, 1, 0, 0));
        assertEquals(Status.FINAL_OFFER, n.getStatus());
        assertTrue(service.hasOpenNegotiation());
        long id = n.getId();
        assertThrows(NegotiationException.class, () -> service.offer(id, teamId, new Offer(1_500, 1, 0, 0)));

        n = service.answerFinalOffer(id, teamId, false);

        assertEquals(Status.FINAL_REJECTED, n.getStatus());
        assertFalse(service.hasOpenNegotiation());
        assertEquals(3_000, humans.findById(playerId).orElseThrow().getWage());
        assertEquals(45, relations.relation(agentId, managerId));
    }

    @Test
    @DisplayName("""
            GIVEN an agent whose relation with me fell under 10, and another club's request
            WHEN a renewal is opened
            THEN the agent refuses to sit at the table, and the other club cannot touch my player
            """)
    void agentBelowThresholdRefuses() {
        allRelations.change(RelationParty.AGENT, agentId, RelationParty.MANAGER, managerId, -45);

        NegotiationException refused = assertThrows(NegotiationException.class,
                () -> service.startRenewal(playerId, teamId));
        assertEquals("AGENT_REFUSES", refused.getMessage());
        assertEquals("NOT_YOUR_PLAYER",
                assertThrows(NegotiationException.class, () -> service.startRenewal(playerId, teamId + 1)).getMessage());
        assertFalse(service.hasOpenNegotiation());
    }

    @Test
    @DisplayName("""
            GIVEN a selling club that accepted my fee for its player (rated 200), whose agent is Stability
            WHEN I open personal terms from the inbox button, open them again, and agree
            THEN the same negotiation comes back, he is valued against my squad (2000),
             and agreeing completes the transfer before the contract is signed
            """)
    void agreedPersonalTermsCompleteTheTransfer() {
        long sellerId = otherClubWithPlayer();
        long offerId = feeAgreedOffer(sellerId);

        ContractNegotiation n = service.startTransfer(offerId, teamId);
        assertEquals(ContractNegotiation.KIND_TRANSFER, n.getKind());
        assertEquals(2_000, n.getPlayerValue());
        assertEquals(n.getId(), service.startTransfer(offerId, teamId).getId());
        assertTrue(service.hasOpenNegotiation());

        n = service.offer(n.getId(), teamId, new Offer(2_000, 3, 0, 0));

        assertEquals(Status.AGREED, n.getStatus());
        verify(transferHandler).complete(offerId);
        assertEquals(2_000, humans.findById(n.getPlayerId()).orElseThrow().getWage());
        assertEquals(n.getId(), service.startTransfer(offerId, teamId).getId(), "the inbox button reopens the finished talks");
    }

    @Test
    @DisplayName("""
            GIVEN personal terms in progress
            WHEN the player leaves the selling club before I make my offer
            THEN the talks are cancelled, the day is free again, the fee agreement collapses and no transfer happens
            """)
    void talksAreCancelledWhenThePlayerIsGone() {
        long sellerId = otherClubWithPlayer();
        long offerId = feeAgreedOffer(sellerId);
        ContractNegotiation n = service.startTransfer(offerId, teamId);
        Human target = humans.findById(n.getPlayerId()).orElseThrow();
        target.setTeamId(sellerId + 100);
        humans.save(target);

        n = service.offer(n.getId(), teamId, new Offer(2_000, 3, 0, 0));

        assertEquals(Status.CANCELLED, n.getStatus());
        assertFalse(service.hasOpenNegotiation());
        verify(transferHandler).collapse(org.mockito.ArgumentMatchers.eq(offerId), anyString());
        verify(transferHandler, never()).complete(anyLong());
    }

    @Test
    @DisplayName("""
            GIVEN fee talks with a selling club, open and then at its final offer
            WHEN the game asks whether a negotiation blocks the day
            THEN it does not: only talks with an agent block the day
            """)
    void clubFeeTalksDoNotBlockTheDay() {
        TransferOffer talks = new TransferOffer();
        talks.setPlayerId(playerId);
        talks.setFromTeamId(teamId + 1);
        talks.setToTeamId(teamId);
        talks.setDirection("outgoing");
        talks.setStatus(TransferOffer.STATUS_TALKS);
        talks = offers.save(talks);
        assertFalse(service.hasOpenNegotiation());
        talks.setStatus(TransferOffer.STATUS_FINAL_OFFER);
        offers.save(talks);
        assertFalse(service.hasOpenNegotiation());
    }

    @Test
    @DisplayName("""
            GIVEN an agreed fee for a player rated 300 while my best three average 200 (250, 200 and 150)
            WHEN I press the button to negotiate personal terms
            THEN the (ambitious) player's agent refuses because my squad is not at his level, the talks are over at once
             (the day is not blocked), the transfer collapses and pressing again shows the same answer
            """)
    void playerWhoDoesNotWantToComeRefusesPersonalTerms() {
        long sellerId = otherClubWithPlayer();
        Human target = humans.findById(targetId).orElseThrow();
        target.setRating(300);
        humans.save(target);
        humans.save(human(TypeNames.PLAYER_TYPE, 200, 3_000, 0));
        long offerId = feeAgreedOffer(sellerId);

        ContractNegotiation n = service.startTransfer(offerId, teamId);

        assertEquals(Status.REFUSED, n.getStatus());
        assertEquals(PlayerInterest.Reason.TEAM_TOO_WEAK.name(), n.getRefusalReason());
        assertFalse(service.hasOpenNegotiation());
        verify(transferHandler).collapse(org.mockito.ArgumentMatchers.eq(offerId), anyString());
        assertEquals(n.getId(), service.startTransfer(offerId, teamId).getId());
    }

    @Test
    @DisplayName("""
            GIVEN an agreed fee for a MONEY player rated 200 earning 4000, while my best three average 200
            WHEN personal terms open
            THEN he is valued at his current wage + 10% (4400), above the 3000 my wage hierarchy would give
            """)
    void moneyPlayerAsksForARaiseOverHisCurrentWage() {
        long sellerId = otherClubWithPlayer();
        Human target = humans.findById(targetId).orElseThrow();
        target.setPlayerPersonality(PlayerPersonality.MONEY);
        humans.save(target);
        humans.save(human(TypeNames.PLAYER_TYPE, 200, 3_000, 0));
        long offerId = feeAgreedOffer(sellerId);

        ContractNegotiation n = service.startTransfer(offerId, teamId);

        assertEquals(Status.OPEN, n.getStatus());
        assertEquals(4_400, n.getPlayerValue());
    }

    @Test
    @DisplayName("""
            GIVEN my own rated-200 player (worth 2000 in my wage hierarchy) who likes my manager (relation 70)
            WHEN I open his renewal
            THEN he is valued 15% lower: 1700
            """)
    void playerWhoLikesTheManagerAsksLess() {
        allRelations.change(RelationParty.PLAYER, playerId, RelationParty.MANAGER, managerId, 20);

        ContractNegotiation n = service.startRenewal(playerId, teamId);

        assertEquals(1_700, n.getPlayerValue());
    }

    @Test
    @DisplayName("""
            GIVEN a renewal with my own player, who fully trusts me (relation 100), and his Stability agent
            WHEN I ask him to fire his agent
            THEN the agent's relation with me drops by 20, the player fires him and takes another agent,
             and the talks end (the day is free again)
            """)
    void trustingPlayerFiresHisAgent() {
        allRelations.change(RelationParty.PLAYER, playerId, RelationParty.MANAGER, managerId, 50);
        ContractNegotiation n = service.startRenewal(playerId, teamId);

        n = service.askToFireAgent(n.getId(), teamId);

        assertEquals(ContractNegotiationService.FIRED, n.getFireAgentOutcome());
        assertEquals(Status.CANCELLED, n.getStatus());
        assertFalse(service.hasOpenNegotiation());
        assertNotEquals(agentId, humans.findById(playerId).orElseThrow().getAgentId());
        assertEquals(30, relations.relation(agentId, managerId));
    }

    @Test
    @DisplayName("""
            GIVEN a renewal with my own player, who does not trust me at all (relation 0), and his Stability agent
            WHEN I ask him to fire his agent, and then ask again
            THEN he keeps the agent, who now relates to me at 30 and asks 1800 instead of 1400 for 4 years;
             the talks go on, and asking a second time is refused
            """)
    void distrustfulPlayerKeepsHisAgentWhoGetsTougher() {
        allRelations.change(RelationParty.PLAYER, playerId, RelationParty.MANAGER, managerId, -50);
        ContractNegotiation n = service.startRenewal(playerId, teamId);
        assertEquals(1_400, n.getAgentWage());

        n = service.askToFireAgent(n.getId(), teamId);

        assertEquals(ContractNegotiationService.KEPT, n.getFireAgentOutcome());
        assertEquals(Status.OPEN, n.getStatus());
        assertEquals(agentId, humans.findById(playerId).orElseThrow().getAgentId());
        assertEquals(30, n.getRelation());
        assertEquals(1_800, n.getAgentWage());
        long id = n.getId();
        assertEquals("ALREADY_ASKED",
                assertThrows(NegotiationException.class, () -> service.askToFireAgent(id, teamId)).getMessage());
    }

    @Test
    @DisplayName("""
            GIVEN personal terms with another club's player
            WHEN I ask him to fire his agent
            THEN it is refused: only a player of my own club can be asked
            """)
    void onlyMyOwnPlayerCanBeAskedToFireHisAgent() {
        long sellerId = otherClubWithPlayer();
        ContractNegotiation n = service.startTransfer(feeAgreedOffer(sellerId), teamId);
        long id = n.getId();

        assertEquals("NOT_YOUR_PLAYER",
                assertThrows(NegotiationException.class, () -> service.askToFireAgent(id, teamId)).getMessage());
        assertEquals(50, relations.relation(agentId, managerId));
    }

    private long sellerTeamId;
    private long targetId;

    private long otherClubWithPlayer() {
        Team seller = new Team();
        seller.setName("Kvar");
        sellerTeamId = teams.save(seller).getId();
        Human target = human(TypeNames.PLAYER_TYPE, 200, 4_000, agentId);
        target.setTeamId(sellerTeamId);
        targetId = humans.save(target).getId();
        humans.deleteById(playerId); // my own rated-200 player would otherwise be in my squad
        return sellerTeamId;
    }

    private long feeAgreedOffer(long sellerId) {
        TransferOffer offer = new TransferOffer();
        offer.setPlayerId(targetId);
        offer.setFromTeamId(teamId);
        offer.setToTeamId(sellerId);
        offer.setOfferAmount(1_000_000);
        offer.setDirection("outgoing");
        offer.setStatus(TransferOffer.STATUS_FEE_AGREED);
        return offers.save(offer).getId();
    }

    private Human human(long typeId, double rating, long wage, long agent) {
        Human h = new Human();
        h.setName("H" + rating);
        h.setTypeId(typeId);
        h.setTeamId(teamId);
        h.setRating(rating);
        h.setWage(wage);
        h.setAge(25);
        h.setAgentId(agent);
        h.setPlayerPersonality(PlayerPersonality.AMBITION);
        h.setMorale(80);
        return h;
    }
}
