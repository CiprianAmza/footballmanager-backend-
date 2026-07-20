package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.TransferOffer;
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

    @Autowired private TransferOfferRepository transferOfferRepository;

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
        return staleOffers.size();
    }
}
