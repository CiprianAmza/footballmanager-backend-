package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PlayerSkills;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure-logic tests for {@link PlayerRoleService} — focused on the config-driven role
 * attribute tables and the blend knobs. Wires a plain {@link MatchEngineConfig} (the object
 * production binds from YAML) so production and tests share one source of truth.
 */
class PlayerRoleServiceTest {

    private PlayerRoleService service;
    private MatchEngineConfig cfg;

    @BeforeEach
    void setUp() {
        service = new PlayerRoleService();
        cfg = new MatchEngineConfig();
        service.engineConfig = cfg;
    }

    private PlayerSkills uniformSkills(String position, int v) {
        PlayerSkills s = new PlayerSkills();
        s.setPosition(position);
        PlayerSkillsService.SETTER_MAP.values().forEach(setter -> setter.accept(s, v));
        return s;
    }

    @Test
    void overrideOnARoleAttribute_changesSuitability() {
        // Poacher's shipped table leans on Finishing; a player who is elite at Finishing but
        // average elsewhere should score higher under the default than after we zero Finishing.
        PlayerSkills s = uniformSkills("ST", 8);
        s.setFinishing(20);

        double withDefault = service.computeRoleSuitability(s, "Poacher");

        cfg.getRoleWeights().setAttributes(Map.of("Poacher", Map.of("Finishing", 0.0)));
        double withFinishingZeroed = service.computeRoleSuitability(s, "Poacher");

        assertThat(withFinishingZeroed).isLessThan(withDefault);
    }

    @Test
    void addingANewAttributeToARoleTable_countsTowardSuitability() {
        // Corners is not in the Poacher table by default; adding it with weight makes a
        // good corner-taker score higher for the role.
        PlayerSkills s = uniformSkills("ST", 8);
        s.setCorners(20);

        double withDefault = service.computeRoleSuitability(s, "Poacher");

        cfg.getRoleWeights().setAttributes(Map.of("Poacher", Map.of("Corners", 0.5)));
        double withCornersAdded = service.computeRoleSuitability(s, "Poacher");

        assertThat(withCornersAdded).isGreaterThan(withDefault);
    }

    @Test
    void effectiveRating_blendsBaseValueWithRoleSuitability_usingConfigWeights() {
        PlayerSkills s = uniformSkills("ST", 12);
        double base = 200.0;
        double suitability = service.computeRoleSuitability(s, "Poacher");

        cfg.getRoleWeights().setOverallBlend(0.4);
        cfg.getRoleWeights().setRoleBlend(0.6);
        double expected = base * 0.4 + suitability * 0.6;

        assertThat(service.computeEffectiveRating(s, "Poacher", base)).isCloseTo(expected, within(1e-9));
    }

    @Test
    void effectiveRating_emptyRole_returnsBaseUnchanged() {
        PlayerSkills s = uniformSkills("ST", 12);
        assertThat(service.computeEffectiveRating(s, "", 175.0)).isEqualTo(175.0);
    }
}
