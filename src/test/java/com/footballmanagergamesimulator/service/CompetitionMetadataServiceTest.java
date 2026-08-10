package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompetitionMetadataServiceTest {

    @Test
    void leagueLevelComesFromTierWithoutFirstOrSecondLeagueLabels() {
        NationService nations = mock(NationService.class);
        when(nations.infoFor(3)).thenReturn(new NationService.NationInfo(3, "Khess", "kh"));
        CompetitionMetadataService service = new CompetitionMetadataService(nations);
        Competition competition = competition("Khess Second League", Competition.LEAGUE, 2, 3);

        Map<String, Object> metadata = service.metadata(competition);

        assertThat(metadata.get("kind")).isEqualTo("LEAGUE");
        assertThat(metadata.get("tierLabel")).isEqualTo("Tier 2");
        assertThat(metadata.get("categoryLabel")).isEqualTo("League competition");
        assertThat(metadata.values().toString()).doesNotContain("First League", "Second League");
    }

    @Test
    void continentalFormatIsDescribedByStructureInsteadOfAHardcodedCupSuffix() {
        NationService nations = mock(NationService.class);
        when(nations.infoFor(0)).thenReturn(new NationService.NationInfo(0, "Europe", "eu"));
        CompetitionMetadataService service = new CompetitionMetadataService(nations);

        Map<String, Object> metadata = service.metadata(
                competition("League of Champions", Competition.LEAGUE_OF_CHAMPIONS, 1, 0));

        assertThat(metadata.get("scopeLabel")).isEqualTo("Continental");
        assertThat(metadata.get("formatLabel")).isEqualTo("Qualifying, groups and knockout");
    }

    private Competition competition(String name, long type, int tier, long nation) {
        Competition competition = new Competition();
        competition.setName(name);
        competition.setTypeId(type);
        competition.setTier(tier);
        competition.setNationId(nation);
        return competition;
    }
}
