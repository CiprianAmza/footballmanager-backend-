package com.footballmanagergamesimulator.integration.fuzz;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.GameStateService;
import com.footballmanagergamesimulator.service.ManagerTacticService;
import com.footballmanagergamesimulator.service.PlayerValueService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.service.TacticalScoreService;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import com.footballmanagergamesimulator.testutil.TeamSetup;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Human-tactic outcome explorer on the two-axis (attack/defense) tactical model. Pick YOUR team and
 * a full tactic; the harness simulates {@code SEASONS} league campaigns in your league and reports
 * your average finishing position. Crucially, every OTHER team now picks its tactic via its
 * manager's tactical ability ({@link ManagerTacticService}) — better coaches play better tactics —
 * so you are measured against a realistic spread of opponents, not a passive default.
 *
 * <p>Scoring uses {@link TacticalScoreService}: each squad's value is split into attack/defense by
 * position, tactic settings redistribute between them (trade-off) and open/slow the game, and goals
 * come from each side's attack vs the other's defense (matchup). Seeded RNG → deterministic and
 * A/B-comparable.
 *
 * <pre>
 *   mvn verify -Pfuzz -Dit.test=HumanTacticOutcomeFuzzIT -Dteam.id=104
 *   mvn verify -Pfuzz -Dit.test='HumanTacticOutcomeFuzzIT#searchBestTacticAndReport' -Dteam.id=104
 * </pre>
 * Gated behind {@code -Pfuzz}; skipped unless {@code -Dteam.id} is supplied.
 */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("Human tactic outcome (two-axis) — your mean league position vs manager-skill opponents")
class HumanTacticOutcomeFuzzIT {

    private static final long BASE_SEED = 20260528L;

    private static final List<String> MENTALITIES = List.of("Very Attacking", "Attacking", "Balanced", "Defensive", "Very Defensive");
    private static final List<String> TIME_WASTING = List.of("Never", "Sometimes", "Frequently", "Always");
    private static final List<String> IN_POSSESSION = List.of("Standard", "Keep Ball", "Free Ball Early");
    private static final List<String> PASSING = List.of("Short", "Normal", "Long");
    private static final List<String> TEMPO = List.of("Much Lower", "Lower", "Standard", "Higher", "Much Higher");

    @Autowired private CompetitionTeamInfoRepository ctiRepo;
    @Autowired private TeamRepository teamRepo;
    @Autowired private HumanRepository humanRepo;
    @Autowired private GameStateService gameState;
    @Autowired private CompetitionFormatConfig competitionFormat;
    @Autowired private TacticController tacticController;
    @Autowired private PlayerValueService playerValueService;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private TacticService tacticService;
    @Autowired private TacticalScoreService tacticalScoreService;
    @Autowired private ManagerTacticService managerTacticService;

    @Test
    @DisplayName("Run your configured tactic + sweep each axis (opponents use manager-skill tactics)")
    void simulateHumanTacticAndReport() throws Exception {
        Ctx ctx = setUp();
        Assumptions.assumeTrue(ctx != null, "Skipping — supply -Dteam.id=ID (your team) to run this harness");

        String formation = System.getProperty("formation", ctx.bestFormation);
        if (!formation.equals(ctx.humanFormation)) ctx.setHumanFormation(formation);
        PersonalizedTactic baseTactic = buildTactic(
                System.getProperty("mentality", "Balanced"),
                System.getProperty("timeWasting", "Sometimes"),
                System.getProperty("inPossession", "Standard"),
                System.getProperty("passingType", "Normal"),
                System.getProperty("tempo", "Standard"));
        TacticVector baseVec = tacticalScoreService.vector(baseTactic);

        double baselineMeanPos = ctx.meanPosForHumanTactic(baseVec);

        StringBuilder sb = new StringBuilder();
        sb.append("# Human Tactic Outcome (two-axis model)\n\n");
        sb.append("Run on ").append(java.time.LocalDateTime.now()).append('\n');
        ctx.appendHeader(sb, formation);
        sb.append("Configured tactic: ").append(describe(formation, baseTactic)).append("\n\n");
        sb.append(String.format("## Baseline: your mean finishing position = **%.2f / %d**%n%n", baselineMeanPos, ctx.n));
        sb.append(ctx.standings(baseVec)).append('\n');

        sb.append("## How each setting moves your average position\n\n");
        sb.append("One axis varied at a time; everything else at your configured value; opponents fixed at ");
        sb.append("their manager-skill tactics. Lower mean position = better; Δ is vs your baseline.\n\n");
        sb.append(sweep("Mentality", MENTALITIES, baseTactic.getMentality(), baselineMeanPos,
                v -> ctx.meanPosForHumanTactic(tacticalScoreService.vector(withMentality(baseTactic, v)))));
        sb.append(sweep("Tempo", TEMPO, baseTactic.getTempo(), baselineMeanPos,
                v -> ctx.meanPosForHumanTactic(tacticalScoreService.vector(withTempo(baseTactic, v)))));
        sb.append(sweep("Passing", PASSING, baseTactic.getPassingType(), baselineMeanPos,
                v -> ctx.meanPosForHumanTactic(tacticalScoreService.vector(withPassing(baseTactic, v)))));
        sb.append(sweep("In possession", IN_POSSESSION, baseTactic.getInPossession(), baselineMeanPos,
                v -> ctx.meanPosForHumanTactic(tacticalScoreService.vector(withInPossession(baseTactic, v)))));
        sb.append(sweep("Time wasting", TIME_WASTING, baseTactic.getTimeWasting(), baselineMeanPos,
                v -> ctx.meanPosForHumanTactic(tacticalScoreService.vector(withTimeWasting(baseTactic, v)))));

        sb.append("\n## How to read this\n\n");
        sb.append("- Opponents are NOT passive: each picks a tactic by its manager's ability, so there is no single ");
        sb.append("dominant setting — a tactic that beats attacking sides can lose to defensive ones.\n");
        sb.append("- Settings are a TRADE-OFF (attacking raises attack but lowers defense), so you cannot stack a huge edge.\n");

        Path reportPath = Path.of("target", "human-tactic-outcome-" + ctx.humanTeamId + ".md");
        Files.writeString(reportPath, sb.toString());
        System.out.println();
        System.out.println(sb);
        System.out.println("Report written to: " + reportPath.toAbsolutePath());
        assertThat(baselineMeanPos).isBetween(1.0, (double) ctx.n);
    }

    @Test
    @DisplayName("Search all 900 tactic combos and recommend the best vs manager-skill opponents")
    void searchBestTacticAndReport() throws Exception {
        Ctx ctx = setUp();
        Assumptions.assumeTrue(ctx != null, "Skipping — supply -Dteam.id=ID (your team) to run this search");
        int seasons = ctx.seasons;

        double neutralMean = ctx.meanPosForHumanTactic(tacticalScoreService.vector(
                buildTactic("Balanced", "Sometimes", "Standard", "Normal", "Standard")));

        // The human plays the recommended non-swept axes (Strat-2 + Faza-2), constant across the swept
        // base settings, so the recommendation reflects the full production tactic, not a neutral one.
        PersonalizedTactic axesProto = new PersonalizedTactic();
        managerTacticService.applyNewAxes(axesProto, ctx.profiles[ctx.humanIdx],
                ctx.coaches[ctx.humanIdx].pickAbility(), teamWideShare(ctx.humanTeamId, ctx.humanFormation));

        List<TacticResult> results = new ArrayList<>(900);
        for (PersonalizedTactic t : managerTacticService.candidateTactics()) {
            stampNewAxes(t, axesProto);
            double mp = ctx.meanPosForHumanTactic(tacticalScoreService.vector(t));
            results.add(new TacticResult(t, mp));
        }
        results.sort(java.util.Comparator.comparingDouble(TacticResult::meanPos));
        TacticResult best = results.get(0);

        StringBuilder sb = new StringBuilder();
        sb.append("# Best Tactic Search (two-axis model)\n\n");
        sb.append("Run on ").append(java.time.LocalDateTime.now()).append('\n');
        ctx.appendHeader(sb, ctx.humanFormation);
        sb.append(String.format("Neutral-tactic baseline: mean position %.2f / %d%n%n", neutralMean, ctx.n));

        sb.append("## ★ Recommended tactic\n\n");
        sb.append(String.format("**Mean position %.2f / %d**  —  %+.2f vs neutral%n%n",
                best.meanPos(), ctx.n, best.meanPos() - neutralMean));
        sb.append("- Formation: `").append(ctx.humanFormation).append("`\n");
        sb.append("- Mentality: `").append(best.tactic().getMentality()).append("`\n");
        sb.append("- Tempo: `").append(best.tactic().getTempo()).append("`\n");
        sb.append("- Passing: `").append(best.tactic().getPassingType()).append("`\n");
        sb.append("- In possession: `").append(best.tactic().getInPossession()).append("`\n");
        sb.append("- Time wasting: `").append(best.tactic().getTimeWasting()).append("`\n");
        sb.append("- Line/Press/Width: `").append(best.tactic().getDefensiveLine()).append("/")
          .append(best.tactic().getPressing()).append("/").append(best.tactic().getWidth()).append("`\n");
        sb.append("- Instructions: `").append(instructions(best.tactic())).append("`\n\n");

        sb.append("## Top 15 tactics\n\n");
        MarkdownTable table = new MarkdownTable(
                List.of("#", "Mentality", "Tempo", "Passing", "Possession", "Time-wasting",
                        "Line/Press/Width", "Instructions", "Mean Pos"),
                List.of(MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT));
        for (int i = 0; i < Math.min(15, results.size()); i++) {
            TacticResult r = results.get(i);
            PersonalizedTactic t = r.tactic();
            table.addRow(String.valueOf(i + 1), t.getMentality(), t.getTempo(),
                    t.getPassingType(), t.getInPossession(), t.getTimeWasting(),
                    t.getDefensiveLine() + "/" + t.getPressing() + "/" + t.getWidth(), instructions(t),
                    String.format("%.2f", r.meanPos()));
        }
        sb.append(table.render()).append('\n');
        sb.append("\nOpponents pick tactics by their managers' ability, so the best tactic is the one that fares ");
        sb.append("best across that spread — not a setting that beats a passive default.\n");

        Path reportPath = Path.of("target", "best-tactic-" + ctx.humanTeamId + ".md");
        Files.writeString(reportPath, sb.toString());
        System.out.println();
        System.out.println(sb);
        System.out.println("Report written to: " + reportPath.toAbsolutePath());
        assertThat(best.meanPos()).isBetween(1.0, (double) ctx.n);
    }

    // ==================== context (league, profiles, opponent tactics) ====================

    /** Builds the simulation context once: league teams, each team's attack/defense profile, and the
     *  manager-skill tactic every opponent will use. Returns null if -Dteam.id is absent. */
    private Ctx setUp() {
        String teamIdProp = System.getProperty("team.id");
        if (teamIdProp == null || teamIdProp.isBlank()) return null;
        long humanTeamId = Long.parseLong(teamIdProp.trim());
        int seasons = Integer.getInteger("seasons", 100);
        int season = gameState.currentSeason();
        long compId = findLeagueForTeam(humanTeamId, season);

        List<Long> ids = distinctSortedTeamIds(compId, season);
        int n = ids.size();
        int humanIdx = ids.indexOf(humanTeamId);
        if (humanIdx < 0) throw new IllegalArgumentException("Team " + humanTeamId + " not in league " + compId);
        int encounters = competitionFormat.get(1).encountersFor(n);

        // Best formation for the human's players (max total value); opponents use 4-4-2.
        String bestFormation = bestFormationFor(humanTeamId);
        String humanFormation = bestFormation;

        Coach[] coaches = new Coach[n];
        TeamProfile[] profiles = new TeamProfile[n];
        List<TeamSetup> teams = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long id = ids.get(i);
            coaches[i] = coachFor(id);
            String f = id == humanTeamId ? humanFormation : "442";
            profiles[i] = coachedTeamProfile(id, f, coaches[i]); // squad value × manager coaching
            String name = teamRepo.findNameById(id);
            teams.add(new TeamSetup(id, name == null ? "Team#" + id : name, profiles[i].attack() + profiles[i].defense()));
        }

        // Representative opponent = league average (coached) attack/defense.
        double avgAtt = 0, avgDef = 0;
        for (TeamProfile p : profiles) { avgAtt += p.attack(); avgDef += p.defense(); }
        TeamProfile avgProfile = new TeamProfile(avgAtt / n, avgDef / n);

        // Each opponent picks a tactic suited to its (coached) profile, at a rank set by its manager's
        // ability; the human's is set per-run.
        TacticVector[] tactics = new TacticVector[n];
        for (int i = 0; i < n; i++) {
            if (i == humanIdx) { tactics[i] = tacticalScoreService.vector(new PersonalizedTactic()); continue; }
            tactics[i] = tacticalScoreService.vector(
                    managerTacticService.chooseTactic(profiles[i], avgProfile, coaches[i].pickAbility()));
        }

        return new Ctx(humanTeamId, compId, season, n, humanIdx, encounters, seasons, bestFormation,
                humanFormation, teams, profiles, tactics, avgProfile, coaches);
    }

    /** Mutable simulation context + the season runner. */
    private final class Ctx {
        final long humanTeamId; final long compId; final int season; final int n; final int humanIdx;
        final int encounters; final int seasons; final String bestFormation;
        String humanFormation;
        final List<TeamSetup> teams; final TeamProfile[] profiles; final TacticVector[] tactics; final TeamProfile avgProfile;
        final Coach[] coaches;

        Ctx(long humanTeamId, long compId, int season, int n, int humanIdx, int encounters, int seasons,
            String bestFormation, String humanFormation, List<TeamSetup> teams, TeamProfile[] profiles,
            TacticVector[] tactics, TeamProfile avgProfile, Coach[] coaches) {
            this.humanTeamId = humanTeamId; this.compId = compId; this.season = season; this.n = n;
            this.humanIdx = humanIdx; this.encounters = encounters; this.seasons = seasons;
            this.bestFormation = bestFormation; this.humanFormation = humanFormation; this.teams = teams;
            this.profiles = profiles; this.tactics = tactics; this.avgProfile = avgProfile; this.coaches = coaches;
        }

        void setHumanFormation(String f) {
            this.humanFormation = f;
            this.profiles[humanIdx] = coachedTeamProfile(humanTeamId, f, coaches[humanIdx]);
        }

        /** Your team's mean finishing position over the season set, given your tactic vector. */
        double meanPosForHumanTactic(TacticVector humanTactic) {
            tactics[humanIdx] = humanTactic;
            long[][] positionCounts = runSeasons();
            double mp = 0;
            for (int pos = 0; pos < n; pos++) mp += (pos + 1) * positionCounts[humanIdx][pos];
            return mp / seasons;
        }

        long[][] runSeasons() {
            long[][] positionCounts = new long[n][n];
            Random rng = new Random(BASE_SEED);
            for (int s = 0; s < seasons; s++) {
                int[] points = new int[n], gf = new int[n], ga = new int[n];
                int fullDoubles = encounters / 2;
                boolean extraSingle = (encounters % 2) == 1;
                for (int d = 0; d < fullDoubles; d++)
                    for (int home = 0; home < n; home++)
                        for (int away = 0; away < n; away++) {
                            if (home == away) continue;
                            playMatch(home, away, points, gf, ga, rng);
                        }
                if (extraSingle)
                    for (int i = 0; i < n; i++)
                        for (int j = i + 1; j < n; j++)
                            playMatch(i, j, points, gf, ga, rng);
                int[] order = standingsOrder(points, gf, ga);
                for (int pos = 0; pos < n; pos++) positionCounts[order[pos]][pos]++;
            }
            return positionCounts;
        }

        void playMatch(int home, int away, int[] points, int[] gf, int[] ga, Random rng) {
            List<Integer> scores = tacticalScoreService.score(
                    profiles[home], tactics[home], profiles[away], tactics[away], rng);
            int sH = scores.get(0), sA = scores.get(1);
            gf[home] += sH; ga[home] += sA; gf[away] += sA; ga[away] += sH;
            if (sH > sA) points[home] += 3;
            else if (sH == sA) { points[home]++; points[away]++; }
            else points[away] += 3;
        }

        int[] standingsOrder(int[] points, int[] gf, int[] ga) {
            Integer[] idx = new Integer[n];
            for (int i = 0; i < n; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (a, b) -> {
                if (points[a] != points[b]) return points[b] - points[a];
                int gdA = gf[a] - ga[a], gdB = gf[b] - ga[b];
                if (gdA != gdB) return gdB - gdA;
                if (gf[a] != gf[b]) return gf[b] - gf[a];
                return teams.get(a).name().compareTo(teams.get(b).name());
            });
            int[] order = new int[n];
            for (int i = 0; i < n; i++) order[i] = idx[i];
            return order;
        }

        void appendHeader(StringBuilder sb, String formation) {
            sb.append("Your team: ").append(teams.get(humanIdx).name()).append(" (id=").append(humanTeamId).append(")\n");
            sb.append("League: comp id=").append(compId).append(", ").append(n).append(" teams, ")
                    .append((n - 1) * encounters).append(" matches/season\n");
            TeamProfile hp = profiles[humanIdx];
            sb.append(String.format("Your manager: offensive %.0f, defensive %.0f (coaching applied to your squad)%n",
                    coaches[humanIdx].off(), coaches[humanIdx].def()));
            sb.append(String.format("Your squad (formation %s, coached): attack %.0f, defense %.0f (total %.0f)%n",
                    formation, hp.attack(), hp.defense(), hp.attack() + hp.defense()));
            sb.append("Seasons: ").append(seasons).append("  •  Seed: ").append(BASE_SEED)
                    .append("  •  Opponents pick tactics by their managers' offensive/defensive ability.\n");
        }

        String standings(TacticVector humanTactic) {
            tactics[humanIdx] = humanTactic;
            long[][] pc = runSeasons();
            double[] meanPos = new double[n];
            for (int t = 0; t < n; t++) for (int pos = 0; pos < n; pos++) meanPos[t] += (pos + 1) * pc[t][pos] / (double) seasons;
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Double.compare(meanPos[a], meanPos[b]));
            MarkdownTable table = new MarkdownTable(
                    List.of("Rank", "Team", "Att", "Def", "Mean Pos"),
                    List.of(MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT,
                            MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
            int rank = 1;
            for (int t : order) {
                table.addRow(String.valueOf(rank++), teams.get(t).name() + (t == humanIdx ? "  «YOU»" : ""),
                        String.format("%.0f", profiles[t].attack()), String.format("%.0f", profiles[t].defense()),
                        String.format("%.2f", meanPos[t]));
            }
            return "## League standings with your configured tactic (mean over " + seasons + " seasons)\n\n" + table.render();
        }
    }

    // ==================== profiles + lookups ====================

    /** A team's attack/defense profile from its best 11 for a formation (PlayerValueService values). */
    private TeamProfile teamProfile(long teamId, String formation) {
        List<TacticController.StarterSlot> slots = tacticController.getBestElevenWithSlots(String.valueOf(teamId), formation);
        List<Long> ids = slots.stream().map(s -> s.player().getId()).toList();
        Map<Long, PlayerSkills> skills = new HashMap<>();
        for (PlayerSkills s : playerSkillsRepository.findAllByPlayerIdIn(ids)) skills.put(s.getPlayerId(), s);
        List<TacticalScoreService.StarterValue> starters = new ArrayList<>(slots.size());
        for (TacticController.StarterSlot slot : slots) {
            var pv = slot.player();
            String natural = pv.getPosition();
            String used = slot.usedPosition();
            PlayerSkills sk = skills.get(pv.getId());
            double value = sk != null
                    ? playerValueService.evaluatePlayer(sk, natural, used, pv.getMorale(), pv.getFitness())
                    : playerValueService.evaluatePlayer(pv.getRating(), natural, used, pv.getMorale(), pv.getFitness());
            double[] apt = TacticalScoreService.playerAptitudes(sk, pv.getFitness());
            starters.add(new TacticalScoreService.StarterValue(used, value, apt[0], apt[1], apt[2]));
        }
        return tacticalScoreService.profile(starters);
    }

    private static final java.util.Set<String> WIDE_POSITIONS = java.util.Set.of("ML", "MR", "DL", "DR");

    /** Share of the XI's match value in wide positions (for the width identity), mirroring production. */
    private double teamWideShare(long teamId, String formation) {
        List<TacticController.StarterSlot> slots = tacticController.getBestElevenWithSlots(String.valueOf(teamId), formation);
        List<Long> ids = slots.stream().map(s -> s.player().getId()).toList();
        Map<Long, PlayerSkills> skills = new HashMap<>();
        for (PlayerSkills s : playerSkillsRepository.findAllByPlayerIdIn(ids)) skills.put(s.getPlayerId(), s);
        double total = 0, wide = 0;
        for (TacticController.StarterSlot slot : slots) {
            var pv = slot.player();
            String used = slot.usedPosition();
            PlayerSkills sk = skills.get(pv.getId());
            double value = sk != null
                    ? playerValueService.evaluatePlayer(sk, pv.getPosition(), used, pv.getMorale(), pv.getFitness())
                    : playerValueService.evaluatePlayer(pv.getRating(), pv.getPosition(), used, pv.getMorale(), pv.getFitness());
            total += value;
            if (WIDE_POSITIONS.contains(used)) wide += value;
        }
        return total <= 0 ? 0 : wide / total;
    }

    private static String instructions(PersonalizedTactic t) {
        return String.join(", ", t.getDribbling(), t.getFoulFrequency(), t.getFoulHardness(),
                t.getTempoFragmentation(), t.getWidePlay(), t.getTransition());
    }

    /** Copy the recommended non-swept axes (Strat-2 + Faza-2) onto a swept base candidate. */
    private static void stampNewAxes(PersonalizedTactic t, PersonalizedTactic axes) {
        t.setDefensiveLine(axes.getDefensiveLine());
        t.setPressing(axes.getPressing());
        t.setWidth(axes.getWidth());
        t.setDribbling(axes.getDribbling());
        t.setFoulFrequency(axes.getFoulFrequency());
        t.setFoulHardness(axes.getFoulHardness());
        t.setTempoFragmentation(axes.getTempoFragmentation());
        t.setWidePlay(axes.getWidePlay());
        t.setTransition(axes.getTransition());
    }

    /** Formation maximizing the squad's total value (best fit for the players). */
    private String bestFormationFor(long teamId) {
        String best = "442";
        double bestTotal = -1;
        for (String f : tacticService.getAllExistingTactics()) {
            TeamProfile p = teamProfile(teamId, f);
            double total = p.attack() + p.defense();
            if (total > bestTotal) { bestTotal = total; best = f; }
        }
        return best;
    }

    /** A manager's offensive/defensive coaching abilities (0-100); neutral 50 when absent. */
    private record Coach(double off, double def) {
        double pickAbility() { return (off + def) / 2.0; }
    }

    private Coach coachFor(long teamId) {
        return humanRepo.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).stream()
                .filter(m -> !m.isRetired())
                .findFirst()
                .map(m -> new Coach(m.getOffensiveAbility(), m.getDefensiveAbility()))
                .orElse(new Coach(50.0, 50.0));
    }

    /** Squad profile for a formation, with the manager's coaching applied. */
    private TeamProfile coachedTeamProfile(long teamId, String formation, Coach coach) {
        return tacticalScoreService.coachedProfile(teamProfile(teamId, formation), coach.off(), coach.def());
    }

    private long findLeagueForTeam(long teamId, int season) {
        for (long compId : gameState.getLeagueCompetitionIdsCached().stream().sorted().toList())
            for (CompetitionTeamInfo cti : ctiRepo.findAllByCompetitionIdAndSeasonNumber(compId, season))
                if (cti.getTeamId() == teamId) return compId;
        throw new IllegalArgumentException("Team " + teamId + " not in any top-tier league for season " + season);
    }

    private List<Long> distinctSortedTeamIds(long compId, int season) {
        TreeSet<Long> ids = new TreeSet<>();
        for (CompetitionTeamInfo cti : ctiRepo.findAllByCompetitionIdAndSeasonNumber(compId, season))
            if (cti.getTeamId() > 0) ids.add(cti.getTeamId());
        return new ArrayList<>(ids);
    }

    // ==================== report helpers + tactic builders ====================

    private static String sweep(String axis, List<String> values, String configured, double baseline, Function<String, Double> fn) {
        MarkdownTable table = new MarkdownTable(
                List.of(axis, "Mean Pos", "Δ vs baseline", ""),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT));
        String best = null; double bestPos = Double.MAX_VALUE;
        Map<String, Double> results = new java.util.LinkedHashMap<>();
        for (String v : values) { double mp = fn.apply(v); results.put(v, mp); if (mp < bestPos) { bestPos = mp; best = v; } }
        for (Map.Entry<String, Double> e : results.entrySet()) {
            String tag = (e.getKey().equals(configured) ? "« configured " : "") + (e.getKey().equals(best) ? "★ best" : "");
            table.addRow(e.getKey(), String.format("%.2f", e.getValue()), String.format("%+.2f", e.getValue() - baseline), tag.trim());
        }
        return "### " + axis + "\n\n" + table.render() + "\n";
    }

    private static PersonalizedTactic buildTactic(String mentality, String timeWasting, String inPossession, String passingType, String tempo) {
        PersonalizedTactic t = new PersonalizedTactic();
        t.setMentality(mentality); t.setTimeWasting(timeWasting); t.setInPossession(inPossession);
        t.setPassingType(passingType); t.setTempo(tempo);
        return t;
    }

    private static PersonalizedTactic copy(PersonalizedTactic t) {
        return buildTactic(t.getMentality(), t.getTimeWasting(), t.getInPossession(), t.getPassingType(), t.getTempo());
    }

    private static PersonalizedTactic withMentality(PersonalizedTactic t, String v) { var c = copy(t); c.setMentality(v); return c; }
    private static PersonalizedTactic withTempo(PersonalizedTactic t, String v) { var c = copy(t); c.setTempo(v); return c; }
    private static PersonalizedTactic withPassing(PersonalizedTactic t, String v) { var c = copy(t); c.setPassingType(v); return c; }
    private static PersonalizedTactic withInPossession(PersonalizedTactic t, String v) { var c = copy(t); c.setInPossession(v); return c; }
    private static PersonalizedTactic withTimeWasting(PersonalizedTactic t, String v) { var c = copy(t); c.setTimeWasting(v); return c; }

    private static String describe(String formation, PersonalizedTactic t) {
        return String.format("%s | %s | tempo %s | passing %s | possession %s | time-wasting %s",
                formation, t.getMentality(), t.getTempo(), t.getPassingType(), t.getInPossession(), t.getTimeWasting());
    }

    private record TacticResult(PersonalizedTactic tactic, double meanPos) {}
}
