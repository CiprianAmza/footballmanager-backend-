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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:transfer-market-max-rating;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransferMarketMaxRatingJpaTest {

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
        when(controller.marketAvailabilityService.currentSeason()).thenReturn(1);
        when(controller.playerPreviewService.previews(anyCollection(), anyInt())).thenReturn(Map.of());
    }

    @Test
    void filtersTheWholeMarketByMaximumRatingBeforePagination() {
        Team club = new Team();
        club.setName("Rating FC");
        club = teamRepository.save(club);
        humanRepository.saveAllAndFlush(List.of(
                player("Inside range", club.getId(), 145),
                player("Above range", club.getId(), 175)
        ));

        Map<String, Object> result = controller.getAvailablePlayersPage(
                999L, "ALL", 0, 50, "rating", "desc", 0, Long.MAX_VALUE, 150);

        assertThat(result.get("totalElements")).isEqualTo(1L);
        assertThat(playerNames(result)).containsExactly("Inside range");
    }

    @SuppressWarnings("unchecked")
    private List<String> playerNames(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("content")).stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
    }

    private Human player(String name, long teamId, double rating) {
        Human player = new Human();
        player.setName(name);
        player.setTeamId(teamId);
        player.setTypeId(1L);
        player.setPosition("ST");
        player.setAge(24);
        player.setRating(rating);
        player.setTransferValue(20_000_000);
        return player;
    }
}
