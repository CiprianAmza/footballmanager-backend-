package com.footballmanagergamesimulator.integration.fuzz;

import com.footballmanagergamesimulator.service.BestTacticService;
import com.footballmanagergamesimulator.service.BestTacticService.FullTacticRow;
import com.footballmanagergamesimulator.service.BestTacticService.RankAllResult;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ranks EVERY possible tactic for one real team — all 15 formations × the 900 setting combos
 * (= 13,500) — on its CURRENT squad straight from the DB, using the exact production ranking
 * ({@link BestTacticService#rankAllTactics} → coached {@link com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile}
 * per formation, ranked by {@code expectedPoints} against an equal opponent). Writes the full sorted
 * table to {@code target/all-tactics-{teamId}.md} so the output matches the advisor/AI exactly.
 *
 * <pre>
 *   mvn verify -Pfuzz -Dit.test=TeamTacticRankingFuzzIT -Dteam.id=104
 * </pre>
 * Gated behind {@code -Pfuzz}; team defaults to 104 (Desert Lion).
 */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("Rank ALL 13,500 tactics for one team by the production metric")
class TeamTacticRankingFuzzIT {

    @Autowired private BestTacticService bestTacticService;

    @Test
    @DisplayName("Rank all formations × 900 settings for -Dteam.id (default 104) and write the full report")
    void rankAllTacticsForTeam() throws Exception {
        long teamId = Long.getLong("team.id", 104L);

        RankAllResult result = bestTacticService.rankAllTactics(teamId);
        List<FullTacticRow> rows = result.rows();

        double best = rows.get(0).expectedPoints();
        double worst = rows.get(rows.size() - 1).expectedPoints();
        TreeSet<Double> distinct = new TreeSet<>();
        for (FullTacticRow r : rows) distinct.add(Math.round(r.expectedPoints() * 1e6) / 1e6);

        StringBuilder sb = new StringBuilder();
        sb.append("# All tactics ranked — ").append(result.teamName())
                .append(" (id=").append(teamId).append(")\n\n");
        sb.append("Run on ").append(java.time.LocalDateTime.now()).append('\n');
        sb.append("Live squad value (best formation, coached): ")
                .append(String.format("%.0f", result.baseSquadValue())).append('\n');
        sb.append("Combinations: ").append(rows.size())
                .append(" (15 formations × 900 settings), ranked by expected points vs an equal opponent.\n");
        sb.append(String.format("Expected points: best %.4f, worst %.4f, %d distinct values.%n%n",
                best, worst, distinct.size()));

        FullTacticRow r1 = result.recommended();
        sb.append("## ★ Recommended (rank 1)\n\n");
        sb.append(String.format("**Exp.Pts %.4f**  —  Win %.1f%% / Draw %.1f%% / Loss %.1f%%, xGD %+.3f%n%n",
                r1.expectedPoints(), 100 * r1.winProb(), 100 * r1.drawProb(), 100 * r1.lossProb(),
                r1.expectedGoalDifference()));
        sb.append("- Formation: `").append(r1.formation()).append("`\n");
        sb.append("- Mentality: `").append(r1.mentality()).append("`\n");
        sb.append("- Tempo: `").append(r1.tempo()).append("`\n");
        sb.append("- Passing: `").append(r1.passingType()).append("`\n");
        sb.append("- In possession: `").append(r1.inPossession()).append("`\n");
        sb.append("- Time wasting: `").append(r1.timeWasting()).append("`\n\n");

        sb.append("## Full ranking (").append(rows.size()).append(" rows)\n\n");
        MarkdownTable table = new MarkdownTable(
                List.of("#", "Formation", "Mentality", "Tempo", "Passing", "In Possession",
                        "Time Wasting", "Exp.Pts", "Win%", "Draw%", "Loss%", "xGD"),
                List.of(MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
        for (int i = 0; i < rows.size(); i++) {
            FullTacticRow r = rows.get(i);
            table.addRow(String.valueOf(i + 1), r.formation(), r.mentality(), r.tempo(),
                    r.passingType(), r.inPossession(), r.timeWasting(),
                    String.format("%.4f", r.expectedPoints()),
                    String.format("%.1f", 100 * r.winProb()),
                    String.format("%.1f", 100 * r.drawProb()),
                    String.format("%.1f", 100 * r.lossProb()),
                    String.format("%+.3f", r.expectedGoalDifference()));
        }
        sb.append(table.render());

        Path reportPath = Path.of("target", "all-tactics-" + teamId + ".md");
        Files.writeString(reportPath, sb.toString());

        System.out.println();
        System.out.printf("Ranked %d tactics for %s (id=%d): best Exp.Pts %.4f, worst %.4f, %d distinct values.%n",
                rows.size(), result.teamName(), teamId, best, worst, distinct.size());
        System.out.println("Recommended: " + r1.formation() + " | " + r1.mentality() + " | " + r1.tempo()
                + " | " + r1.passingType() + " | " + r1.inPossession() + " | " + r1.timeWasting());
        System.out.println("Report written to: " + reportPath.toAbsolutePath());

        assertThat(rows).hasSize(13_500);
        assertThat(best).isGreaterThanOrEqualTo(worst);
    }
}
