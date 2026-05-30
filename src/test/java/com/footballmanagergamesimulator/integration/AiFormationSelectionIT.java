package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.MatchRoundSimulator;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The two-axis production engine picks an AI manager's formation by ranking all formations by the
 * base value they yield for his squad, then selecting at a rank set by his skill. An elite manager
 * must land the value-maximal formation; a hopeless one the value-minimal — so the formation is
 * coupled to squad value + coaching skill, not a random kit.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "match.engine.tactical-model.enabled=true",
        "bootstrap.seed=20260528"
})
@DisplayName("AI formation selection — value-ranked, skill-picked")
class AiFormationSelectionIT {

    @Autowired private MatchRoundSimulator matchRoundSimulator;
    @Autowired private TacticService tacticService;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private UserContext userContext;

    @Test
    void eliteManagerPicksMaxValueFormation_poorManagerPicksMinValue() {
        long teamId = firstAiTeamWithManager();
        Human manager = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).get(0);
        double savedOff = manager.getOffensiveAbility();
        double savedDef = manager.getDefensiveAbility();
        try {
            List<String> formations = tacticService.getAllExistingTactics();
            double maxValue = formations.stream()
                    .mapToDouble(f -> matchRoundSimulator.formationBaseValueForTest(teamId, f)).max().orElseThrow();
            double minValue = formations.stream()
                    .mapToDouble(f -> matchRoundSimulator.formationBaseValueForTest(teamId, f)).min().orElseThrow();
            assertThat(maxValue).as("formations must differ in value for this team").isGreaterThan(minValue);

            setSkill(manager, 100, 100);
            String eliteChoice = matchRoundSimulator.chooseFormationForTest(teamId);
            assertThat(formations).contains(eliteChoice);
            assertThat(matchRoundSimulator.formationBaseValueForTest(teamId, eliteChoice))
                    .as("elite manager picks the value-maximal formation")
                    .isCloseTo(maxValue, within(1e-6));

            setSkill(manager, 0, 0);
            String poorChoice = matchRoundSimulator.chooseFormationForTest(teamId);
            assertThat(matchRoundSimulator.formationBaseValueForTest(teamId, poorChoice))
                    .as("hopeless manager picks the value-minimal formation")
                    .isCloseTo(minValue, within(1e-6));
        } finally {
            setSkill(manager, savedOff, savedDef);
        }
    }

    private void setSkill(Human manager, double off, double def) {
        manager.setOffensiveAbility(off);
        manager.setDefensiveAbility(def);
        humanRepository.save(manager);
        matchRoundSimulator.invalidateRatingCache(manager.getTeamId());
    }

    private long firstAiTeamWithManager() {
        for (Team team : teamRepository.findAll()) {
            if (userContext.isHumanTeam(team.getId())) continue;
            if (!humanRepository.findAllByTeamIdAndTypeId(team.getId(), TypeNames.MANAGER_TYPE).isEmpty()) {
                return team.getId();
            }
        }
        throw new IllegalStateException("no AI team with a manager in the bootstrap");
    }
}
