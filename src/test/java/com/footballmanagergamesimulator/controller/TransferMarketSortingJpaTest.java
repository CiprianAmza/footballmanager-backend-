package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamFacilitiesRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.PlayerMarketAvailabilityService;
import com.footballmanagergamesimulator.service.PlayerPreviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:transfer-market-sorting;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransferMarketSortingJpaTest {

    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;

    private TransferOfferController controller;

    @BeforeEach
    void setUp() {
        controller = new TransferOfferController();
        controller.humanRepository = humanRepository;
        controller.teamRepository = teamRepository;
        controller.teamFacilitiesRepository = mock(TeamFacilitiesRepository.class);
        controller.marketAvailabilityService = mock(PlayerMarketAvailabilityService.class);
        controller.playerPreviewService = mock(PlayerPreviewService.class);
        when(controller.marketAvailabilityService.unavailablePlayerIds()).thenReturn(Set.of());
        when(controller.marketAvailabilityService.currentSeason()).thenReturn(1);
        when(controller.playerPreviewService.previews(org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(Map.of());
    }

    @Test
    void sortsTheWholeMarketByClubNameInEitherDirection() {
        Team zulu = teamRepository.save(team("Zulu FC"));
        Team alpha = teamRepository.save(team("Alpha FC"));
        humanRepository.saveAllAndFlush(List.of(
                player("Zulu player", zulu.getId()),
                player("Alpha player", alpha.getId())
        ));

        assertThat(clubNames(controller.getAvailablePlayersPage(
                999L, "ALL", 0, 50, "club", "asc", 0, Long.MAX_VALUE)))
                .containsExactly("Alpha FC", "Zulu FC");
        assertThat(clubNames(controller.getAvailablePlayersPage(
                999L, "ALL", 0, 50, "club", "desc", 0, Long.MAX_VALUE)))
                .containsExactly("Zulu FC", "Alpha FC");
    }

    @SuppressWarnings("unchecked")
    private List<String> clubNames(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("content")).stream()
                .map(row -> String.valueOf(row.get("teamName")))
                .toList();
    }

    private Team team(String name) {
        Team team = new Team();
        team.setName(name);
        return team;
    }

    private Human player(String name, long teamId) {
        Human player = new Human();
        player.setName(name);
        player.setTeamId(teamId);
        player.setTypeId(1L);
        player.setPosition("ST");
        player.setAge(24);
        player.setRating(100);
        player.setTransferValue(20_000_000);
        return player;
    }
}
