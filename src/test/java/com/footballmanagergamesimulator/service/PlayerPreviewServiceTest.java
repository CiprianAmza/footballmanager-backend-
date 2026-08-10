package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.PlayerAttributeView;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PlayerPreviewServiceTest {

    private final PlayerPreviewService service = new PlayerPreviewService(
            mock(PlayerSkillsRepository.class), mock(ScorerRepository.class));

    @Test
    void selectsPositionRelevantAttributesForStrikers() {
        PlayerSkills skills = new PlayerSkills();
        skills.setFinishing(18);
        skills.setOffTheBall(17);
        skills.setComposure(16);
        skills.setPace(15);
        skills.setFirstTouch(14);
        skills.setHeading(13);

        assertThat(service.importantAttributes("ST", skills))
                .containsExactly(
                        new PlayerAttributeView("Finishing", 18),
                        new PlayerAttributeView("Off The Ball", 17),
                        new PlayerAttributeView("Composure", 16),
                        new PlayerAttributeView("Pace", 15),
                        new PlayerAttributeView("First Touch", 14),
                        new PlayerAttributeView("Heading", 13));
    }

    @Test
    void returnsNoAttributesWhenSkillsAreMissing() {
        assertThat(service.importantAttributes("GK", null)).isEqualTo(List.of());
    }
}
