package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.TransferOffer;
import com.footballmanagergamesimulator.repository.TransferOfferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferOfferLifecycleServiceTest {

    @Mock TransferOfferRepository transferOfferRepository;
    @InjectMocks TransferOfferLifecycleService service;

    @Test
    void removesAllActiveOffersForEveryStalePlayer() {
        TransferOffer first = new TransferOffer();
        first.setId(1);
        TransferOffer second = new TransferOffer();
        second.setId(2);
        when(transferOfferRepository.findAllByPlayerIdInAndStatusIn(any(), any()))
                .thenReturn(List.of(first, second));

        int removed = service.removeActiveOffersForPlayers(Set.of(44L, 45L));

        assertEquals(2, removed);
        verify(transferOfferRepository).deleteAllInBatch(List.of(first, second));
    }
}
