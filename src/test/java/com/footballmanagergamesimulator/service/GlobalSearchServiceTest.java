package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalSearchServiceTest {

    @Mock HumanRepository humanRepository;
    @Mock TeamRepository teamRepository;
    @Mock CompetitionRepository competitionRepository;

    private GlobalSearchService service;

    @BeforeEach
    void setUp() {
        service = new GlobalSearchService(humanRepository, teamRepository, competitionRepository);
    }

    @Test
    void ignoresQueriesShorterThanTwoCharacters() {
        assertThat(service.search(" a ", 6)).isEqualTo(
                com.footballmanagergamesimulator.frontend.GlobalSearchResponse.empty());
        verifyNoInteractions(humanRepository, teamRepository, competitionRepository);
    }

    @Test
    void groupsPlayersClubsAndCompetitionsWithNavigationMetadata() {
        Team club = new Team();
        club.setId(8L);
        club.setName("Sherlock FC");
        club.setReputation(710);

        Human player = new Human();
        player.setId(18L);
        player.setName("Sherlock Holmes");
        player.setPosition("AMC");
        player.setTeamId(8L);

        Competition competition = new Competition();
        competition.setId(4L);
        competition.setName("Sherlock League");
        competition.setTypeId(Competition.LEAGUE);
        competition.setTier(1);

        when(humanRepository.findTop10ByTypeIdAndRetiredFalseAndNameContainingIgnoreCaseOrderByNameAsc(1L, "sher"))
                .thenReturn(List.of(player));
        when(teamRepository.findAllById(List.of(8L))).thenReturn(List.of(club));
        when(teamRepository.findTop10ByNameContainingIgnoreCaseOrderByNameAsc("sher"))
                .thenReturn(List.of(club));
        when(competitionRepository.findTop10ByNameContainingIgnoreCaseOrderByNameAsc("sher"))
                .thenReturn(List.of(competition));

        var result = service.search(" sher ", 6);

        assertThat(result.players()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(18L);
            assertThat(item.meta()).isEqualTo("AMC · Sherlock FC");
        });
        assertThat(result.clubs()).singleElement().extracting("name").isEqualTo("Sherlock FC");
        assertThat(result.competitions()).singleElement().extracting("meta").isEqualTo("League · Tier 1");
    }
}
