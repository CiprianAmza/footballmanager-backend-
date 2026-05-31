package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.service.TacticalScoreService.StarterValue;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AI tactic chooser must now also set the Strat-2 axes cheaply: defensive line + pressing via a
 * skill-ranked coordinate search, and width as a squad-shape identity (so the human's width counter
 * bites and AI-vs-AI width matchups fire) — without enumerating the full 24,300-combo grid.
 */
class ManagerTacticServiceTest {

    private ManagerTacticService service;
    private MatchEngineConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new MatchEngineConfig();
        TacticalScoreService tss = new TacticalScoreService();
        tss.engineConfig = cfg;
        service = new ManagerTacticService();
        service.tacticalScoreService = tss;
        service.engineConfig = cfg;
    }

    @Test
    void chooseTactic_setsLinePressingAndFaza2Instructions() {
        TeamProfile mine = service.tacticalScoreService.profile(
                List.of(new StarterValue("ST", 1200), new StarterValue("MC", 1200), new StarterValue("DC", 1200)));
        PersonalizedTactic top = service.chooseTactic(mine, mine, 100);
        assertThat(top.getDefensiveLine()).isIn(MatchEngineConfig.TacticalModel.DEFENSIVE_LINE_OPTIONS);
        assertThat(top.getPressing()).isIn(MatchEngineConfig.TacticalModel.PRESSING_OPTIONS);
        // The Faza-2 team instructions must now be chosen too (production AI explores all axes).
        assertThat(top.getDribbling()).isIn(MatchEngineConfig.TacticalModel.DRIBBLING_OPTIONS);
        assertThat(top.getFoulFrequency()).isIn(MatchEngineConfig.TacticalModel.FOUL_FREQUENCY_OPTIONS);
        assertThat(top.getFoulHardness()).isIn(MatchEngineConfig.TacticalModel.FOUL_HARDNESS_OPTIONS);
        assertThat(top.getTempoFragmentation()).isIn(MatchEngineConfig.TacticalModel.TEMPO_FRAGMENTATION_OPTIONS);
        assertThat(top.getWidePlay()).isIn(MatchEngineConfig.TacticalModel.WIDE_PLAY_OPTIONS);
        assertThat(top.getTransition()).isIn(MatchEngineConfig.TacticalModel.TRANSITION_OPTIONS);
    }

    @Test
    void chooseTactic_topCoachBeatsWorstCoachOnPanelPoints() {
        TeamProfile mine = service.tacticalScoreService.profile(
                List.of(new StarterValue("ST", 1300), new StarterValue("MC", 1100), new StarterValue("DC", 1000)));
        PersonalizedTactic best = service.chooseTactic(mine, mine, 100);
        PersonalizedTactic worst = service.chooseTactic(mine, mine, 0);
        double bestPts = service.tacticalScoreService.panelExpectedPoints(mine, service.tacticalScoreService.vector(best));
        double worstPts = service.tacticalScoreService.panelExpectedPoints(mine, service.tacticalScoreService.vector(worst));
        assertThat(bestPts).as("a top coach's tactic scores at least as high as a poor coach's")
                .isGreaterThanOrEqualTo(worstPts);
    }

    @Test
    void widthIdentity_followsSquadShape() {
        // Thresholds: ≥0.38 Wide, ≤0.28 Narrow, else Balanced (config defaults).
        assertThat(service.widthIdentity(0.50)).isEqualTo("Wide");
        assertThat(service.widthIdentity(0.10)).isEqualTo("Narrow");
        assertThat(service.widthIdentity(0.33)).isEqualTo("Balanced");
    }
}
