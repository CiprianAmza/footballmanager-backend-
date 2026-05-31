package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.PlayerCardConfig;
import com.footballmanagergamesimulator.frontend.PlayerCardView;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class PlayerCardServiceTest {

    @Mock
    private HumanRepository humanRepository;

    @Mock
    private PlayerSkillsRepository playerSkillsRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private CompetitionRepository competitionRepository;

    private PlayerCardConfig playerCardConfig;
    private PlayerCardService playerCardService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        playerCardConfig = new PlayerCardConfig();
        playerCardService = new PlayerCardService(
                humanRepository,
                playerSkillsRepository,
                teamRepository,
                competitionRepository,
                playerCardConfig);
    }

    @Test
    void playerCardValuesStayWithinBoundsAndExposeMetadata() {
        Human player = new Human();
        player.setId(7L);
        player.setTypeId(TypeNames.PLAYER_TYPE);
        player.setName("Ciprian Test");
        player.setPosition("ST");
        player.setAge(23);
        player.setTeamId(9L);

        Team team = new Team();
        team.setId(9L);
        team.setCompetitionId(11L);

        Competition competition = new Competition();
        competition.setId(11L);
        competition.setNationId(24L);

        PlayerSkills skills = uniformSkills("ST", 20);

        when(humanRepository.findById(7L)).thenReturn(Optional.of(player));
        when(playerSkillsRepository.findPlayerSkillsByPlayerId(7L)).thenReturn(Optional.of(skills));
        when(teamRepository.findById(9L)).thenReturn(Optional.of(team));
        when(competitionRepository.findById(11L)).thenReturn(Optional.of(competition));

        PlayerCardView card = playerCardService.getPlayerCard(7L).orElseThrow();

        assertThat(card.getPlayerId()).isEqualTo(7L);
        assertThat(card.getName()).isEqualTo("Ciprian Test");
        assertThat(card.getPosition()).isEqualTo("ST");
        assertThat(card.getAge()).isEqualTo(23);
        assertThat(card.getNationId()).isEqualTo(24L);
        assertThat(card.getFaceDescriptor()).isNull();
        assertThat(List.of(card.getOverall(), card.getPac(), card.getSho(), card.getPas(), card.getDri(), card.getDef(), card.getPhy()))
                .allSatisfy(value -> assertThat(value).isBetween(0, 99));
    }

    @Test
    void bucketsAndOverallScaleUpWhenAttributesImprove() {
        PlayerSkills baseline = uniformSkills("ST", 10);
        PlayerSkills elite = uniformSkills("ST", 20);

        assertThat(playerCardService.computeOverall(baseline)).isBetween(0, 99);
        assertThat(playerCardService.computeOverall(elite)).isBetween(0, 99);
        assertThat(playerCardService.computeOverall(elite)).isGreaterThan(playerCardService.computeOverall(baseline));

        for (String bucket : List.of("PAC", "SHO", "PAS", "DRI", "DEF", "PHY")) {
            int baseValue = playerCardService.computeBucket(baseline, bucket);

            PlayerSkills boosted = uniformSkills("ST", 10);
            playerCardConfig.bucketWeights(bucket).keySet()
                    .forEach(attribute -> PlayerSkillsService.SETTER_MAP.get(attribute).accept(boosted, 20));

            int boostedValue = playerCardService.computeBucket(boosted, bucket);
            assertThat(baseValue).isBetween(0, 99);
            assertThat(boostedValue).isBetween(0, 99);
            assertThat(boostedValue).isGreaterThan(baseValue);
        }
    }

    private PlayerSkills uniformSkills(String position, int value) {
        PlayerSkills skills = new PlayerSkills();
        skills.setPosition(position);
        PlayerSkillsService.SETTER_MAP.values().forEach(setter -> setter.accept(skills, value));
        return skills;
    }
}
