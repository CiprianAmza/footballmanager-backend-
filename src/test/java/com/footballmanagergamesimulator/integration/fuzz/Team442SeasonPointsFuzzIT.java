package com.footballmanagergamesimulator.integration.fuzz;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Best-tactic-by-simulated-points explorer for a FIXED formation (default {@code 442}). For your
 * team (default {@code -Dteam.id=104}) this harness ACTUALLY SIMULATES the team's league campaign —
 * every other team in its league, HOME and AWAY — with each of the 900 tactic SETTING combinations
 * (5 mentality × 5 tempo × 3 passing × 3 in-possession × 4 time-wasting). Scorelines come from the
 * two-axis {@link TacticalScoreService#score} (real Poisson draws), not an analytical formula.
 *
 * <p>For every candidate the team plays the league {@code N} seasons (default {@code -Dseasons=10},
 * range 5–15); we award the team 3/1/0 per match, sum its season points, and average over the N
 * seasons (also tracking min and max). The 900 candidates are then ranked by average points
 * descending and written to {@code target/season-points-{formation}-{teamId}.md}.
 *
 * <p>Opponents are NOT passive: each picks a tactic by its manager's offensive/defensive ability via
 * {@link ManagerTacticService}, and its coached {@link TeamProfile} + tactic are computed ONCE — only
 * the team's vector changes per candidate. A seeded RNG keeps runs deterministic and A/B-comparable.
 *
 * <pre>
 *   mvn verify -Pfuzz -Dit.test=Team442SeasonPointsFuzzIT -Dteam.id=104 -Dseasons=10
 *   mvn verify -Pfuzz -Dit.test=Team442SeasonPointsFuzzIT -Dteam.id=104 -Dseasons=12 -Dformation=433
 * </pre>
 * Gated behind {@code -Pfuzz}; skipped unless {@code -Dteam.id} is supplied.
 */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("Team 442 season points (two-axis, simulated) — best tactic by average league points")
class Team442SeasonPointsFuzzIT {

    private static final long BASE_SEED = 20260528L;

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
    @DisplayName("Simulate 900 tactics over N seasons (442) and rank by average points")
    void searchBestTacticBySimulatedPoints() throws Exception {
        Ctx ctx = setUp();
        Assumptions.assumeTrue(ctx != null, "Skipping — supply -Dteam.id=ID (your team) to run this harness");

        // The team plays its recommended non-swept axes (Strat-2 line/press/width + Faza-2 instructions),
        // constant across the swept base settings — so the sweep reflects production, not a neutral tactic.
        double teamAbility = ctx.coaches[ctx.teamIdx].pickAbility();
        PersonalizedTactic axesProto = new PersonalizedTactic();
        managerTacticService.applyNewAxes(axesProto, ctx.profiles[ctx.teamIdx], teamAbility,
                teamWideShare(ctx.teamId, ctx.formation));

        List<TacticResult> results = new ArrayList<>(900);
        for (PersonalizedTactic t : managerTacticService.candidateTactics()) {
            stampNewAxes(t, axesProto);
            TacticVector teamVec = tacticalScoreService.vector(t);
            SeasonPoints sp = ctx.simulatePoints(teamVec);
            results.add(new TacticResult(t, sp.avg(), sp.min(), sp.max()));
        }
        results.sort(java.util.Comparator.comparingDouble(TacticResult::avgPts).reversed());

        TacticResult best = results.get(0);
        TacticResult worst = results.get(results.size() - 1);

        StringBuilder sb = new StringBuilder();
        sb.append("# Best Tactic by Simulated Season Points (two-axis model)\n\n");
        sb.append("Run on ").append(java.time.LocalDateTime.now()).append('\n');
        ctx.appendHeader(sb);
        sb.append('\n');
        sb.append(String.format("Best avg points: **%.2f**  (worst: %.2f, spread %.2f) over %d candidate tactics%n%n",
                best.avgPts(), worst.avgPts(), best.avgPts() - worst.avgPts(), results.size()));
        sb.append("Top tactic: ")
                .append("`").append(best.tactic().getMentality()).append("` mentality, ")
                .append("`").append(best.tactic().getTempo()).append("` tempo, ")
                .append("`").append(best.tactic().getPassingType()).append("` passing, ")
                .append("`").append(best.tactic().getInPossession()).append("` in possession, ")
                .append("`").append(best.tactic().getTimeWasting()).append("` time-wasting\n\n");

        sb.append("## All 900 tactics, ranked by average points (desc)\n\n");
        MarkdownTable table = new MarkdownTable(
                List.of("#", "Mentality", "Tempo", "Passing", "In Possession", "Time Wasting",
                        "Line/Press/Width", "Instructions", "AvgPts", "MinPts", "MaxPts"),
                List.of(MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
        for (int i = 0; i < results.size(); i++) {
            TacticResult r = results.get(i);
            PersonalizedTactic t = r.tactic();
            table.addRow(String.valueOf(i + 1), t.getMentality(), t.getTempo(),
                    t.getPassingType(), t.getInPossession(), t.getTimeWasting(),
                    t.getDefensiveLine() + "/" + t.getPressing() + "/" + t.getWidth(), instructions(t),
                    String.format("%.2f", r.avgPts()), String.valueOf(r.minPts()), String.valueOf(r.maxPts()));
        }
        sb.append(table.render()).append('\n');
        sb.append("\nScorelines are real Poisson draws from TacticalScoreService.score(); opponents pick tactics by ");
        sb.append("their managers' ability, so the best setting is the one that earns most points across that spread.\n");

        Path reportPath = Path.of("target", "season-points-" + ctx.formation + "-" + ctx.teamId + ".md");
        Files.writeString(reportPath, sb.toString());

        System.out.println();
        System.out.println(String.format(
                "[Team442SeasonPoints] team=%s (id=%d) formation=%s seasons=%d opponents=%d",
                ctx.teams.get(ctx.teamIdx), ctx.teamId, ctx.formation, ctx.seasons, ctx.n - 1));
        System.out.println(String.format("[Team442SeasonPoints] BEST  avg %.2f pts  (min %d, max %d)  ->  %s",
                best.avgPts(), best.minPts(), best.maxPts(), describe(best.tactic())));
        System.out.println(String.format("[Team442SeasonPoints] WORST avg %.2f pts  (min %d, max %d)  ->  %s",
                worst.avgPts(), worst.minPts(), worst.maxPts(), describe(worst.tactic())));
        System.out.println(String.format("[Team442SeasonPoints] spread (best-worst) = %.2f pts",
                best.avgPts() - worst.avgPts()));
        System.out.println("Report written to: " + reportPath.toAbsolutePath());

        int maxPossible = (ctx.n - 1) * 2 * 3;
        assertThat(best.avgPts()).isBetween(0.0, (double) maxPossible);
        assertThat(best.avgPts()).isGreaterThanOrEqualTo(worst.avgPts());
    }

    // ==================== context (league, profiles, opponent tactics) ====================

    /** Builds the simulation context once: league teams, each team's coached attack/defense profile,
     *  and the manager-skill tactic every opponent uses. Returns null if -Dteam.id is absent. */
    private Ctx setUp() {
        String teamIdProp = System.getProperty("team.id", "104");
        if (teamIdProp == null || teamIdProp.isBlank()) return null;
        long teamId = Long.parseLong(teamIdProp.trim());
        int seasons = Math.max(5, Math.min(15, Integer.getInteger("seasons", 10)));
        String formation = System.getProperty("formation", "442");
        int season = gameState.currentSeason();
        long compId = findLeagueForTeam(teamId, season);

        List<Long> ids = distinctSortedTeamIds(compId, season);
        int n = ids.size();
        int teamIdx = ids.indexOf(teamId);
        if (teamIdx < 0) throw new IllegalArgumentException("Team " + teamId + " not in league " + compId);
        int encounters = competitionFormat.get(1).encountersFor(n);

        Coach[] coaches = new Coach[n];
        TeamProfile[] profiles = new TeamProfile[n];
        List<String> teams = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long id = ids.get(i);
            coaches[i] = coachFor(id);
            // The human team uses the FIXED formation; opponents use their usual 4-4-2.
            String f = id == teamId ? formation : "442";
            profiles[i] = coachedTeamProfile(id, f, coaches[i]);
            String name = teamRepo.findNameById(id);
            teams.add(name == null ? "Team#" + id : name);
        }

        // Representative opponent = league average (coached) attack/defense.
        double avgAtt = 0, avgDef = 0;
        for (TeamProfile p : profiles) { avgAtt += p.attack(); avgDef += p.defense(); }
        TeamProfile avgProfile = new TeamProfile(avgAtt / n, avgDef / n);

        // Each opponent picks its tactic ONCE (it does not change with the team's tactic).
        TacticVector[] tactics = new TacticVector[n];
        for (int i = 0; i < n; i++) {
            if (i == teamIdx) { tactics[i] = tacticalScoreService.vector(new PersonalizedTactic()); continue; }
            tactics[i] = tacticalScoreService.vector(
                    managerTacticService.chooseTactic(profiles[i], avgProfile, coaches[i].pickAbility()));
        }

        return new Ctx(teamId, compId, season, n, teamIdx, encounters, seasons, formation,
                teams, profiles, tactics, coaches);
    }

    /** Immutable simulation context + the per-candidate season runner (only the team's vector varies). */
    private final class Ctx {
        final long teamId; final long compId; final int season; final int n; final int teamIdx;
        final int encounters; final int seasons; final String formation;
        final List<String> teams; final TeamProfile[] profiles; final TacticVector[] tactics; final Coach[] coaches;

        Ctx(long teamId, long compId, int season, int n, int teamIdx, int encounters, int seasons,
            String formation, List<String> teams, TeamProfile[] profiles, TacticVector[] tactics, Coach[] coaches) {
            this.teamId = teamId; this.compId = compId; this.season = season; this.n = n;
            this.teamIdx = teamIdx; this.encounters = encounters; this.seasons = seasons;
            this.formation = formation; this.teams = teams; this.profiles = profiles;
            this.tactics = tactics; this.coaches = coaches;
        }

        /** Simulate N seasons of the team's league fixtures with {@code teamVec}; return avg/min/max season points. */
        SeasonPoints simulatePoints(TacticVector teamVec) {
            tactics[teamIdx] = teamVec;
            // Same encounter structure as the league harness: `encounters` round-robins. Each full double
            // gives the team one HOME and one AWAY meeting with every opponent; an odd extra single gives a
            // home OR away meeting (here home, matching standingsOrder's i<j single-leg orientation).
            int fullDoubles = encounters / 2;
            boolean extraSingle = (encounters % 2) == 1;
            Random rng = new Random(BASE_SEED);
            long total = 0; int min = Integer.MAX_VALUE; int max = Integer.MIN_VALUE;
            for (int s = 0; s < seasons; s++) {
                int pts = 0;
                for (int opp = 0; opp < n; opp++) {
                    if (opp == teamIdx) continue;
                    for (int d = 0; d < fullDoubles; d++) {
                        pts += teamPointsForMatch(teamIdx, opp, rng); // team at home
                        pts += teamPointsForMatch(opp, teamIdx, rng); // team away
                    }
                    if (extraSingle) {
                        // Single leg: home if team's index is the lower one, matching the league harness.
                        if (teamIdx < opp) pts += teamPointsForMatch(teamIdx, opp, rng);
                        else pts += teamPointsForMatch(opp, teamIdx, rng);
                    }
                }
                total += pts;
                if (pts < min) min = pts;
                if (pts > max) max = pts;
            }
            return new SeasonPoints(total / (double) seasons, min, max);
        }

        /** Points the team (whichever side it is) earns from one simulated match. */
        int teamPointsForMatch(int home, int away, Random rng) {
            List<Integer> scores = tacticalScoreService.score(
                    profiles[home], tactics[home], profiles[away], tactics[away], rng);
            int sH = scores.get(0), sA = scores.get(1);
            boolean teamIsHome = (home == teamIdx);
            int teamGoals = teamIsHome ? sH : sA;
            int oppGoals = teamIsHome ? sA : sH;
            if (teamGoals > oppGoals) return 3;
            if (teamGoals == oppGoals) return 1;
            return 0;
        }

        void appendHeader(StringBuilder sb) {
            TeamProfile hp = profiles[teamIdx];
            sb.append("Your team: ").append(teams.get(teamIdx)).append(" (id=").append(teamId).append(")\n");
            sb.append("Formation: `").append(formation).append("` (fixed)\n");
            sb.append("League: comp id=").append(compId).append(", ").append(n).append(" teams (")
                    .append(n - 1).append(" opponents), ").append(encounters).append(" round-robin encounters\n");
            sb.append("Matches simulated per season: ").append((n - 1) * encounters)
                    .append(" (your team home & away vs every opponent)\n");
            sb.append(String.format("Your manager: offensive %.0f, defensive %.0f%n",
                    coaches[teamIdx].off(), coaches[teamIdx].def()));
            sb.append(String.format("Your squad (coached): attack %.0f, defense %.0f (total %.0f)%n",
                    hp.attack(), hp.defense(), hp.attack() + hp.defense()));
            sb.append("Seasons: ").append(seasons).append("  •  Seed: ").append(BASE_SEED)
                    .append("  •  Opponents pick tactics by their managers' ability (fixed across candidates).\n");
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
            starters.add(new TacticalScoreService.StarterValue(used, value));
        }
        return tacticalScoreService.profile(starters);
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

    // ==================== report helpers ====================

    private static String describe(PersonalizedTactic t) {
        return String.format("%s | tempo %s | passing %s | possession %s | time-wasting %s | %s/%s/%s | %s",
                t.getMentality(), t.getTempo(), t.getPassingType(), t.getInPossession(), t.getTimeWasting(),
                t.getDefensiveLine(), t.getPressing(), t.getWidth(), instructions(t));
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

    private record SeasonPoints(double avg, int min, int max) {}

    private record TacticResult(PersonalizedTactic tactic, double avgPts, int minPts, int maxPts) {}
}
