package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.TransferOfferView;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TransferOffer;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferRepository;
import com.footballmanagergamesimulator.repository.TransferOfferRepository;
import com.footballmanagergamesimulator.service.CoachPermissionService;
import com.footballmanagergamesimulator.service.FinanceService;
import com.footballmanagergamesimulator.service.TransferOfferLifecycleService;
import com.footballmanagergamesimulator.user.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferOfferControllerTest {

    @Mock TransferOfferRepository offerRepository;
    @Mock HumanRepository humanRepository;
    @Mock RoundRepository roundRepository;
    @Mock TeamRepository teamRepository;
    @Mock TransferRepository transferRepository;
    @Mock ManagerInboxRepository managerInboxRepository;
    @Mock FinanceService financeService;
    @Mock CoachPermissionService coachPermissionService;
    @Mock TransferOfferLifecycleService transferOfferLifecycleService;
    @Mock UserContext userContext;
    @InjectMocks TransferOfferController controller;

    @Test
    void incomingOffersEnrichDuplicatePlayerOffersWithOneBatchLookup() {
        TransferOffer first = offer(1, 44);
        TransferOffer second = offer(2, 44);
        Human player = new Human();
        player.setId(44);
        player.setTeamId(9L);
        player.setRating(201.5);
        player.setAge(27);
        player.setContractEndSeason(5);
        player.setWage(80_000);
        player.setSeasonMatchesPlayed(12);

        Round round = new Round();
        round.setSeason(3);
        when(offerRepository.findAllByToTeamIdAndStatus(9, "pending"))
                .thenReturn(List.of(first, second));
        when(humanRepository.findAllById(any())).thenReturn(List.of(player));
        when(roundRepository.findById(1L)).thenReturn(Optional.of(round));

        List<TransferOfferView> result = controller.getIncomingOffers(9);

        assertEquals(2, result.size());
        assertEquals(201.5, result.get(0).rating());
        assertEquals(2, result.get(0).contractSeasonsRemaining());
        assertEquals(12, result.get(1).seasonMatchesPlayed());
        verify(humanRepository).findAllById(any());
    }

    @Test
    void incomingOffersRemovePlayersWhoNoLongerBelongToTheSellingClub() {
        TransferOffer stale = offer(1, 44);
        Human freeAgent = new Human();
        freeAgent.setId(44);
        freeAgent.setTeamId(null);

        when(offerRepository.findAllByToTeamIdAndStatus(9, "pending"))
                .thenReturn(List.of(stale));
        when(humanRepository.findAllById(any())).thenReturn(List.of(freeAgent));

        List<TransferOfferView> result = controller.getIncomingOffers(9);

        assertEquals(0, result.size());
        verify(transferOfferLifecycleService).removeActiveOffersForPlayers(java.util.Set.of(44L));
    }

    @Test
    void acceptingOneOfferSellsPlayerOnceAndDeletesEveryCompetingActiveOffer() {
        TransferOffer accepted = incomingOffer(1, 44, 2, 9);
        TransferOffer competing = incomingOffer(2, 44, 3, 9);
        Human player = player(44, 9);
        Team buyer = team(2, "Buyer");
        Team seller = team(9, "Seller");
        Round round = new Round();
        round.setSeason(4);

        when(offerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(accepted));
        when(roundRepository.findById(1L)).thenReturn(Optional.of(round));
        when(userContext.getTeamId(any())).thenReturn(9L);
        when(coachPermissionService.canSellPlayers(9)).thenReturn(true);
        when(humanRepository.findByIdForUpdate(44)).thenReturn(Optional.of(player));
        when(teamRepository.findById(2L)).thenReturn(Optional.of(buyer));
        when(teamRepository.findById(9L)).thenReturn(Optional.of(seller));
        ResponseEntity<?> response = controller.respondToOffer(
                new MockHttpServletRequest(), 1, Map.of("action", "accept"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2L, player.getTeamId());
        assertEquals("accepted", accepted.getStatus());
        verify(transferOfferLifecycleService).removeActiveOffersForPlayer(44L);
        verify(transferRepository).save(any());
    }

    @Test
    void staleOfferCannotSellPlayerAgainAndIsRemovedWithItsCompetitors() {
        TransferOffer stale = incomingOffer(1, 44, 2, 9);
        TransferOffer competing = incomingOffer(2, 44, 3, 9);
        Human alreadySold = player(44, 3);
        Round round = new Round();
        round.setSeason(4);

        when(offerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(stale));
        when(roundRepository.findById(1L)).thenReturn(Optional.of(round));
        when(userContext.getTeamId(any())).thenReturn(9L);
        when(humanRepository.findByIdForUpdate(44)).thenReturn(Optional.of(alreadySold));
        ResponseEntity<?> response = controller.respondToOffer(
                new MockHttpServletRequest(), 1, Map.of("action", "accept"));

        assertEquals(409, response.getStatusCode().value());
        verify(transferOfferLifecycleService).removeActiveOffersForPlayer(44L);
        verify(transferRepository, never()).save(any());
    }

    @Test
    void missingPlayerRemovesEveryActiveOfferAndReturnsConflict() {
        TransferOffer stale = incomingOffer(1, 44, 2, 9);
        Round round = new Round();
        round.setSeason(4);

        when(offerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(stale));
        when(roundRepository.findById(1L)).thenReturn(Optional.of(round));
        when(userContext.getTeamId(any())).thenReturn(9L);
        when(humanRepository.findByIdForUpdate(44)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.respondToOffer(
                new MockHttpServletRequest(), 1, Map.of("action", "accept"));

        assertEquals(409, response.getStatusCode().value());
        verify(transferOfferLifecycleService).removeActiveOffersForPlayer(44L);
        verify(transferRepository, never()).save(any());
    }

    private TransferOffer offer(long id, long playerId) {
        TransferOffer offer = new TransferOffer();
        offer.setId(id);
        offer.setPlayerId(playerId);
        offer.setPlayerName("Player");
        offer.setToTeamId(9);
        offer.setStatus("pending");
        return offer;
    }

    private TransferOffer incomingOffer(long id, long playerId, long buyerId, long sellerId) {
        TransferOffer offer = offer(id, playerId);
        offer.setFromTeamId(buyerId);
        offer.setFromTeamName("Buyer " + buyerId);
        offer.setToTeamId(sellerId);
        offer.setToTeamName("Seller");
        offer.setOfferAmount(25_000_000);
        offer.setAskingPrice(25_000_000);
        offer.setDirection("incoming");
        return offer;
    }

    private Human player(long id, long teamId) {
        Human player = new Human();
        player.setId(id);
        player.setName("Player");
        player.setTeamId(teamId);
        player.setPosition("ST");
        player.setAge(25);
        player.setRating(200);
        player.setWage(100_000);
        player.setMorale(75);
        return player;
    }

    private Team team(long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        team.setTransferBudget(100_000_000);
        team.setSalaryBudget(1_000_000);
        return team;
    }
}
