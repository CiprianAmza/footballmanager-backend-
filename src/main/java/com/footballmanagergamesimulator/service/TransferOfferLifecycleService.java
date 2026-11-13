package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.TransferOffer;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.TransferOfferRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Keeps active transfer offers in sync with player ownership changes. */
@Service
public class TransferOfferLifecycleService {

    private static final List<String> ACTIVE_STATUSES =
            List.of("pending", "negotiating", "counter");
    /** A manager's bid still in play: fee talks, the club's final offer, a fee agreed and waiting for terms. */
    private static final List<String> MANAGER_BIDS =
            List.of(TransferOffer.STATUS_TALKS, TransferOffer.STATUS_FINAL_OFFER, TransferOffer.STATUS_FEE_AGREED);

    @Autowired private TransferOfferRepository transferOfferRepository;
    @Autowired private ManagerInboxRepository managerInboxRepository;

    @Transactional
    public int removeActiveOffersForPlayer(long playerId) {
        return removeActiveOffersForPlayers(Set.of(playerId));
    }

    @Transactional
    public int removeActiveOffersForPlayers(Collection<Long> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) return 0;

        List<TransferOffer> staleOffers = transferOfferRepository
                .findAllByPlayerIdInAndStatusIn(playerIds, ACTIVE_STATUSES);
        if (!staleOffers.isEmpty()) {
            transferOfferRepository.deleteAllInBatch(staleOffers);
        }
        return staleOffers.size() + closeManagerBids(playerIds);
    }

    /**
     * A manager's talks and fee agreements for the player end too (they are kept, closed, for the history):
     * otherwise a fee agreed for a player who has moved on stays "agreed" for ever, blocks new talks for him
     * and keeps an AI club's planned move waiting.
     */
    private int closeManagerBids(Collection<Long> playerIds) {
        List<TransferOffer> bids = transferOfferRepository.findAllByPlayerIdInAndStatusIn(playerIds, MANAGER_BIDS);
        for (TransferOffer bid : bids) {
            if (TransferOffer.STATUS_FEE_AGREED.equals(bid.getStatus())) {
                bid.setStatus(TransferOffer.STATUS_TERMS_FAILED);
                ManagerInbox note = new ManagerInbox();
                note.setTeamId(bid.getFromTeamId());
                note.setSeasonNumber(bid.getSeasonNumber());
                note.setTitle("Transfer Collapsed");
                note.setContent("The transfer of " + bid.getPlayerName() + " collapsed: he is no longer available.");
                note.setCategory("transfer");
                note.setRead(false);
                note.setCreatedAt(System.currentTimeMillis());
                managerInboxRepository.save(note);
            } else {
                bid.setStatus(TransferOffer.STATUS_REJECTED);
            }
        }
        if (!bids.isEmpty()) transferOfferRepository.saveAll(bids);
        return bids.size();
    }
}
