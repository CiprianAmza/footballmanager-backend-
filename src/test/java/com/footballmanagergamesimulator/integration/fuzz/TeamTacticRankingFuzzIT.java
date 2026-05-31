package com.footballmanagergamesimulator.integration.fuzz;

import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.BestTacticService;
import com.footballmanagergamesimulator.service.BestTacticService.TacticRow;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import com.footballmanagergamesimulator.testutil.MarkdownTable.Align;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dumps the FULL ranked list of every tactic (15 formations × 900 settings = 13,500), sorted by
 * panel expected points, for a chosen team using its CURRENT DB squad — the same profile + metric
 * the advisor and AI use. Writes {@code target/all-tactics-{teamId}.md}.
 *
 * <pre>
 *   mvn verify -Pfuzz -Dit.test=TeamTacticRankingFuzzIT -Dteam.id=104
 * </pre>
 * Gated behind {@code -Pfuzz}.
 */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("All tactics ranked for a team (current DB squad)")
class TeamTacticRankingFuzzIT {

    @Autowired private BestTacticService bestTacticService;
    @Autowired private TeamRepository teamRepo;

    @Test
    @DisplayName("rank every tactic combination for -Dteam.id (default 104)")
    void rankAllTacticsForTeam() throws Exception {
        long teamId = Long.parseLong(System.getProperty("team.id", "104"));
        String name = teamRepo.findNameById(teamId);
        String label = name == null ? "Team#" + teamId : name;

        List<TacticRow> all = bestTacticService.rankAllTactics(teamId);
        assertThat(all).as("15 formations × 900 settings").hasSize(15 * 900);

        MarkdownTable table = new MarkdownTable(
                List.of("#", "Formation", "Mentality", "Tempo", "Passing", "In Possession", "Time Wasting", "Exp.Pts", "xGD"),
                List.of(Align.RIGHT, Align.LEFT, Align.LEFT, Align.LEFT, Align.LEFT, Align.LEFT, Align.LEFT, Align.RIGHT, Align.RIGHT));
        int rank = 1;
        for (TacticRow r : all) {
            table.addRow(String.valueOf(rank++), r.formation(), r.mentality(), r.tempo(), r.passingType(),
                    r.inPossession(), r.timeWasting(),
                    String.format("%.4f", r.expectedPoints()), String.format("%+.4f", r.expectedGoalDifference()));
        }

        double best = all.get(0).expectedPoints();
        double worst = all.get(all.size() - 1).expectedPoints();
        long distinct = all.stream().map(r -> Math.round(r.expectedPoints() * 10000)).distinct().count();
        TacticRow top = all.get(0);

        StringBuilder md = new StringBuilder();
        md.append("# All tactics ranked — ").append(label).append(" (id ").append(teamId).append(")\n\n");
        md.append("Ranked by panel expected points (vs weaker/equal/stronger opponents), based on the team's ")
          .append("CURRENT DB squad. ").append(all.size()).append(" combinations (15 formations × 900 settings).\n\n");
        md.append("- Best: **").append(String.format("%.4f", best)).append("** pts — ")
          .append(top.formation()).append(" / ").append(top.mentality()).append(" / ").append(top.tempo())
          .append(" / ").append(top.passingType()).append(" / ").append(top.inPossession())
          .append(" / ").append(top.timeWasting()).append("\n");
        md.append("- Worst: ").append(String.format("%.4f", worst)).append(" pts\n");
        md.append("- Distinct Exp.Pts values: ").append(distinct).append(" / ").append(all.size()).append("\n\n");
        md.append(table.render()).append("\n");

        Path out = Path.of("target", "all-tactics-" + teamId + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());

        System.out.printf("=== ALL TACTICS RANKED: %s (id %d) → %s ===%n", label, teamId, out.toAbsolutePath());
        System.out.printf("best=%.4f worst=%.4f distinct=%d/%d%n", best, worst, distinct, all.size());
        System.out.printf("Top: %s / %s / %s / %s / %s / %s%n", top.formation(), top.mentality(), top.tempo(),
                top.passingType(), top.inPossession(), top.timeWasting());
    }
}
