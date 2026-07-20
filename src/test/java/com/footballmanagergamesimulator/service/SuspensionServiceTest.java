package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.config.GameplayFeatureConfig;
import com.footballmanagergamesimulator.model.Suspension;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.SuspensionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class SuspensionServiceTest {

    @InjectMocks
    private SuspensionService suspensionService;

    @Mock
    private UserContext userContext;
    @Mock
    private SuspensionRepository suspensionRepository;
    @Mock
    private MatchEventRepository matchEventRepository;
    @Mock
    private ManagerInboxRepository managerInboxRepository;
    @Mock
    private CompetitionTeamInfoMatchRepository fixtureRepository;
    @Mock
    private GameplayFeatureConfig gameplayFeatures;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(suspensionRepository.save(any(Suspension.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void processesEveryCardWhenOneTeamHasMultipleCardedPlayers() {
        MatchEvent red = card("red_card", 11L, "Red Player", 7L);
        MatchEvent fifthYellow = card("yellow_card", 12L, "Yellow Player", 7L);

        when(matchEventRepository.findAllByCompetitionIdAndSeasonNumberAndRoundNumber(3L, 1, 4))
                .thenReturn(List.of(red, fifthYellow));
        when(matchEventRepository.countByPlayerIdAndCompetitionIdAndSeasonNumberAndEventType(
                12L, 3L, 1, "yellow_card")).thenReturn(5L);

        suspensionService.processMatchCards(3L, 4, 1);

        ArgumentCaptor<Suspension> captor = ArgumentCaptor.forClass(Suspension.class);
        verify(suspensionRepository, times(2)).save(captor.capture());
        assertEquals(List.of("RED_CARD", "ACCUMULATED_YELLOWS"),
                captor.getAllValues().stream().map(Suspension::getReason).toList());
    }

    @Test
    void createsTheMilestoneBanEvenWhenThePlayerHasSixYellows() {
        MatchEvent yellow = card("yellow_card", 12L, "Yellow Player", 7L);
        yellow.setId(55L);
        when(matchEventRepository.findAllByCompetitionIdAndSeasonNumberAndRoundNumber(3L, 1, 4))
                .thenReturn(List.of(yellow));
        when(matchEventRepository.countByPlayerIdAndCompetitionIdAndSeasonNumberAndEventType(
                12L, 3L, 1, "yellow_card")).thenReturn(6L);
        when(suspensionRepository.sumMatchesBannedByPlayerAndCompetitionAndSeasonAndReason(
                12L, 3L, 1, "ACCUMULATED_YELLOWS")).thenReturn(0L);

        suspensionService.processMatchCards(3L, 4, 1);

        ArgumentCaptor<Suspension> captor = ArgumentCaptor.forClass(Suspension.class);
        verify(suspensionRepository).save(captor.capture());
        assertEquals(1, captor.getValue().getMatchesBanned());
        assertEquals(55L, captor.getValue().getSourceMatchEventId());
    }

    @Test
    void doesNotCreateTheSameRedCardBanTwice() {
        MatchEvent red = card("red_card", 11L, "Red Player", 7L);
        red.setId(99L);
        when(matchEventRepository.findAllByCompetitionIdAndSeasonNumberAndRoundNumber(3L, 1, 4))
                .thenReturn(List.of(red));
        when(suspensionRepository.existsBySourceMatchEventIdAndReason(99L, "RED_CARD"))
                .thenReturn(true);

        suspensionService.processMatchCards(3L, 4, 1);

        verify(suspensionRepository, never()).save(any(Suspension.class));
    }

    @Test
    void servingTheNextCompetitionMatchCompletesOneMatchBan() {
        Suspension suspension = new Suspension();
        suspension.setPlayerId(12L);
        suspension.setTeamId(7L);
        suspension.setCompetitionId(3L);
        suspension.setMatchesBanned(1);
        suspension.setMatchesServed(0);
        suspension.setActive(true);
        when(suspensionRepository.findAllByTeamIdAndCompetitionIdAndActive(7L, 3L, true))
                .thenReturn(List.of(suspension));

        suspensionService.serveMatchday(7L, 3L);

        assertEquals(1, suspension.getMatchesServed());
        assertEquals(false, suspension.isActive());
    }

    @Test
    void disabledAvailabilitySkipsDisciplineWithoutWritingTheFixture() {
        when(gameplayFeatures.isPlayerAvailabilityDisabled()).thenReturn(true);
        CompetitionTeamInfoMatch fixture = new CompetitionTeamInfoMatch();
        fixture.setCompetitionId(3);
        fixture.setRound(4);
        fixture.setTeam1Id(7);
        fixture.setTeam2Id(8);
        fixture.setTeam1Score(2);
        fixture.setTeam2Score(1);

        suspensionService.processPlayedFixture(fixture, 1);

        assertEquals(false, fixture.isDisciplineProcessed());
        verify(fixtureRepository, never()).save(fixture);
        verify(suspensionRepository, never())
                .findAllByTeamIdAndCompetitionIdAndActive(any(Long.class), any(Long.class), any(Boolean.class));
        verify(matchEventRepository, never())
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId1AndTeamId2(
                        any(Long.class), any(Integer.class), any(Integer.class), any(Long.class), any(Long.class));
    }

    private MatchEvent card(String type, long playerId, String playerName, long teamId) {
        MatchEvent event = new MatchEvent();
        event.setEventType(type);
        event.setPlayerId(playerId);
        event.setPlayerName(playerName);
        event.setTeamId(teamId);
        return event;
    }
}
