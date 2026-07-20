package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Sponsorship;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.SponsorshipRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SponsorshipServiceTest {

    private SponsorshipService service;
    private SponsorshipRepository sponsorships;
    private FinanceService financeService;
    private RoundRepository rounds;

    @BeforeEach
    void setUp() {
        service = new SponsorshipService();
        sponsorships = mock(SponsorshipRepository.class);
        financeService = mock(FinanceService.class);
        rounds = mock(RoundRepository.class);
        ReflectionTestUtils.setField(service, "sponsorshipRepository", sponsorships);
        ReflectionTestUtils.setField(service, "teamRepository", mock(TeamRepository.class));
        ReflectionTestUtils.setField(service, "managerInboxRepository", mock(ManagerInboxRepository.class));
        ReflectionTestUtils.setField(service, "financeService", financeService);
        ReflectionTestUtils.setField(service, "roundRepository", rounds);
        ReflectionTestUtils.setField(service, "userContext", mock(UserContext.class));
    }

    @Test
    void enteringANewSeasonExpiresOldActiveContractsAndOffers() {
        Sponsorship active = sponsorship("ACTIVE", 3);
        Sponsorship offered = sponsorship("OFFERED", 5);
        when(sponsorships.findAllByStatusInAndEndSeasonLessThan(
                List.of("ACTIVE", "OFFERED"), 6)).thenReturn(List.of(active, offered));

        assertEquals(2, service.expireContractsBeforeSeason(6));

        assertEquals("EXPIRED", active.getStatus());
        assertEquals("EXPIRED", offered.getStatus());
        verify(sponsorships).saveAll(List.of(active, offered));
    }

    @Test
    void expiredOfferCannotBeAccepted() {
        Sponsorship offer = sponsorship("OFFERED", 5);
        offer.setId(7);
        Round round = new Round();
        round.setSeason(6);
        when(rounds.findById(1L)).thenReturn(Optional.of(round));
        when(sponsorships.findById(7L)).thenReturn(Optional.of(offer));

        assertNull(service.acceptSponsorship(7));

        assertEquals("EXPIRED", offer.getStatus());
        verify(sponsorships).save(offer);
        verify(financeService, never()).recordTransaction(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    private Sponsorship sponsorship(String status, int endSeason) {
        Sponsorship sponsorship = new Sponsorship();
        sponsorship.setStatus(status);
        sponsorship.setStartSeason(1);
        sponsorship.setEndSeason(endSeason);
        return sponsorship;
    }
}
