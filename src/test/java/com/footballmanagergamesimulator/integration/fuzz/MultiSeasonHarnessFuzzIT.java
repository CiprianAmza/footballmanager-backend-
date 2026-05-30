package com.footballmanagergamesimulator.integration.fuzz;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.controller.MediaController;
import com.footballmanagergamesimulator.frontend.MediaPredictionView;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.InjuryRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.SeasonTransitionService;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import com.footballmanagergamesimulator.testutil.MarkdownTable.Align;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-season simulation harness that runs the REAL match pipeline so the
 * dynamic squad state (morale, fitness, training, ageing, transfers) actually
 * evolves, and writes a markdown report (per-season champion + per-team squad
 * power drift) to {@code target/multi-season-{mode}.md}.
 *
 * <p>Two modes, selected with {@code -Dsim.mode}:
 * <ul>
 *   <li><b>continue</b> (default) — seasons run consecutively; each rolls over
 *       through the real season transition ({@link SeasonTransitionService}), so
 *       ageing, the pre-season training boost and AI transfers carry forward. A
 *       single seeded RNG stream → one reproducible evolutionary trajectory.
 *       The report shows how each squad's top-11 power drifts season to season.</li>
 *   <li><b>reset</b> — every season restarts from the SAME initial player values
 *       (no transition, so no promotion/relegation or youth regen drift): the
 *       league's results are cleared, the player snapshot is restored, and a
 *       DIFFERENT match seed is used each season. This measures how repeatable
 *       the title is from identical squads (pure match-engine variance).</li>
 * </ul>
 *
 * <p>Gated under {@code -Pfuzz} (slow; full seasons). Run e.g.:
 * <pre>
 *   mvn verify -Pfuzz -Dit.test=MultiSeasonHarnessFuzzIT -Dsim.mode=continue -Dseasons=5
 *   mvn verify -Pfuzz -Dit.test=MultiSeasonHarnessFuzzIT -Dsim.mode=reset -Dseasons=5 -Dleague.id=1
 * </pre>
 */
@SpringBootTest
@DisplayName("Multi-season harness: -Dsim.mode=reset|continue -Dseasons=N -Dleague.id=ID")
class MultiSeasonHarnessFuzzIT {

    @Autowired private CompetitionController competitionController;
    @Autowired private MediaController mediaController;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoMatchRepository matchRepository;
    @Autowired private CompetitionTeamInfoDetailRepository detailRepository;
    @Autowired private MatchStatsRepository matchStatsRepository;
    @Autowired private ScorerRepository scorerRepository;
    @Autowired private InjuryRepository injuryRepository;
    @Autowired private TeamCompetitionDetailRepository tcdRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private SeasonTransitionService seasonTransitionService;
    @Autowired private MatchSimulationService matchSimulationService;

    private static final long BASE_SEED = 20260528L;
    private static final int DEFAULT_SEASONS = 3;
    private static final long LEAGUE_TYPE_ID = 1L;

    /** Snapshot of the player fields RESET restores between seasons. */
    private record PlayerSnap(double rating, double fitness, double morale, int age,
                              long teamId, boolean retired, boolean wantsTransfer,
                              int seasonMatchesPlayed, int consecutiveBenched, String currentStatus) {}

    @Test
    @DisplayName("Simulate N seasons (reset|continue) and report champions + squad power drift")
    void simulateMultipleSeasons() throws IOException {
        boolean reset = "reset".equalsIgnoreCase(System.getProperty("sim.mode", "continue").trim());
        String mode = reset ? "reset" : "continue";
        int seasons = Integer.parseInt(System.getProperty("seasons", String.valueOf(DEFAULT_SEASONS)));
        long leagueCompId = resolveLeagueId();
        List<Long> teamIds = leagueTeamIds(leagueCompId, (int) currentSeason());

        // Initial player snapshot (RESET restores it; CONTINUE keeps it only as the
        // power-drift baseline). Captured BEFORE any simulation.
        Map<Long, PlayerSnap> snapshot = snapshotAllPlayers();
        Map<Long, Double> initialPower = new LinkedHashMap<>();
        for (long t : teamIds) initialPower.put(t, top11Power(t));

        // Aggregates.
        Map<Long, Integer> titles = new HashMap<>();
        Map<Long, Integer> positionSum = new HashMap<>();
        List<String[]> seasonRows = new ArrayList<>();
        int favouriteTitles = 0;

        System.out.printf("%n=== MultiSeasonHarnessFuzzIT START | mode=%s | league=%d | seasons=%d ===%n",
                mode, leagueCompId, seasons);

        try {
            for (int s = 0; s < seasons; s++) {
                long iterStart = System.currentTimeMillis();
                int currentSeason = (int) currentSeason();

                // Seed: RESET varies luck across identical squads; CONTINUE keeps one stream.
                matchSimulationService.setRandomForTesting(new Random(BASE_SEED + (reset ? s : 0)));

                long favouriteId = favouriteTeamId(leagueCompId);

                List<Long> matchdays = matchRepository.findDistinctRoundsByCompetitionIdAndSeasonNumber(
                        leagueCompId, String.valueOf(currentSeason));
                matchdays.sort(Long::compareTo);
                assertThat(matchdays).as("league %d must have fixtures for season %d", leagueCompId, currentSeason)
                        .isNotEmpty();
                for (Long md : matchdays) {
                    competitionController.simulateRound(String.valueOf(leagueCompId), String.valueOf(md));
                }

                // Final standings → champion + per-team position.
                List<Long> standings = finalStandings(leagueCompId, teamIds);
                long championId = standings.get(0);
                for (int pos = 0; pos < standings.size(); pos++) {
                    positionSum.merge(standings.get(pos), pos + 1, Integer::sum);
                }
                titles.merge(championId, 1, Integer::sum);
                boolean hit = championId == favouriteId;
                if (hit) favouriteTitles++;

                seasonRows.add(new String[]{
                        String.valueOf(s + 1), teamName(favouriteId), teamName(championId), hit ? "yes" : "no"});

                System.out.printf("  season %d: favourite=%s champion=%s %s (%.1fs)%n",
                        currentSeason, teamName(favouriteId), teamName(championId),
                        hit ? "HIT" : "miss", (System.currentTimeMillis() - iterStart) / 1000.0);

                if (s < seasons - 1) {
                    if (reset) {
                        // No transition: clear this season's results + restore squads so the
                        // next season replays from identical initial values (fresh luck).
                        clearSeasonResults(leagueCompId, currentSeason, teamIds, matchdays);
                        restorePlayers(snapshot);
                    } else {
                        seasonTransitionService.processEndOfSeason(currentSeason);
                        seasonTransitionService.processNewSeasonSetup(currentSeason);
                    }
                }
            }
        } finally {
            matchSimulationService.setRandomForTesting(new Random()); // restore production RNG
        }

        Map<Long, Double> finalPower = new LinkedHashMap<>();
        for (long t : teamIds) finalPower.put(t, top11Power(t));

        Path report = Path.of("target", "multi-season-" + mode + ".md");
        Files.writeString(report, buildReport(mode, seasons, leagueCompId, teamIds,
                seasonRows, titles, positionSum, initialPower, finalPower, favouriteTitles));
        System.out.printf("=== report: %s | favourite titles %d/%d ===%n", report, favouriteTitles, seasons);

        assertThat(seasonRows).as("a champion must be recorded for every simulated season").hasSize(seasons);
    }

    // ==================== season helpers ====================

    private long resolveLeagueId() {
        String prop = System.getProperty("league.id");
        if (prop != null && !prop.isBlank()) {
            long id = Long.parseLong(prop.trim());
            assertThat(competitionRepository.findById(id)).as("league.id %d must exist", id).isPresent();
            return id;
        }
        Set<Long> typeOne = competitionRepository.findIdsByTypeId(LEAGUE_TYPE_ID);
        assertThat(typeOne).as("bootstrap must produce a type-1 league").isNotEmpty();
        return typeOne.stream().sorted().findFirst().orElseThrow();
    }

    /** League membership derived from its fixtures (standings rows don't exist
     *  until matches are played, so we read team ids from the schedule). */
    private List<Long> leagueTeamIds(long competitionId, int season) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long round : matchRepository.findDistinctRoundsByCompetitionIdAndSeasonNumber(
                competitionId, String.valueOf(season))) {
            for (CompetitionTeamInfoMatch m : matchRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(
                    competitionId, round, String.valueOf(season))) {
                if (m.getTeam1Id() > 0) ids.add(m.getTeam1Id());
                if (m.getTeam2Id() > 0) ids.add(m.getTeam2Id());
            }
        }
        assertThat(ids).as("league %d must have fixtures (teams) for season %d", competitionId, season).isNotEmpty();
        return new ArrayList<>(ids);
    }

    private long favouriteTeamId(long competitionId) {
        List<MediaPredictionView> predictions = mediaController.getMediaPrediction(competitionId);
        assertThat(predictions).as("media prediction must be non-empty for league %d", competitionId).isNotEmpty();
        return predictions.get(0).getManagerTeamTacticView().getTeamId();
    }

    /** League teams ordered by points → GD → GF (champion first). */
    private List<Long> finalStandings(long competitionId, List<Long> teamIds) {
        return tcdRepository.findAll().stream()
                .filter(d -> d.getCompetitionId() == competitionId && teamIds.contains(d.getTeamId()))
                .sorted(Comparator
                        .comparingInt(TeamCompetitionDetail::getPoints)
                        .thenComparingInt((TeamCompetitionDetail d) -> d.getGoalsFor() - d.getGoalsAgainst())
                        .thenComparingInt(TeamCompetitionDetail::getGoalsFor)
                        .reversed())
                .map(TeamCompetitionDetail::getTeamId)
                .toList();
    }

    private long currentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason)
                .orElseThrow(() -> new IllegalStateException("Round id=1 missing — bootstrap didn't run?"));
    }

    /** Clear everything a league season's matches wrote, so RESET can replay the
     *  same fixtures cleanly (no double match-stats, no accumulated standings). */
    private void clearSeasonResults(long competitionId, int season, List<Long> teamIds, List<Long> matchdays) {
        matchStatsRepository.deleteAll(matchStatsRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, season));
        scorerRepository.deleteAll(scorerRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, season));
        for (Long md : matchdays) {
            detailRepository.deleteAll(
                    detailRepository.findAllByCompetitionIdAndRoundIdAndSeasonNumber(competitionId, md, season));
        }
        for (long t : teamIds) {
            injuryRepository.deleteAll(injuryRepository.findAllByTeamIdAndDaysRemainingGreaterThan(t, 0));
            TeamCompetitionDetail d = tcdRepository.findFirstByTeamIdAndCompetitionId(t, competitionId);
            if (d != null) {
                d.setPoints(0); d.setGames(0); d.setWins(0); d.setDraws(0); d.setLoses(0);
                d.setGoalsFor(0); d.setGoalsAgainst(0); d.setGoalDifference(0); d.setForm("");
                tcdRepository.save(d);
            }
        }
    }

    // ==================== player snapshot / restore ====================

    private Map<Long, PlayerSnap> snapshotAllPlayers() {
        Map<Long, PlayerSnap> snap = new HashMap<>();
        for (Human p : humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE)) {
            snap.put(p.getId(), new PlayerSnap(p.getRating(), p.getFitness(), p.getMorale(), p.getAge(),
                    p.getTeamId(), p.isRetired(), p.isWantsTransfer(),
                    p.getSeasonMatchesPlayed(), p.getConsecutiveBenched(), p.getCurrentStatus()));
        }
        return snap;
    }

    private void restorePlayers(Map<Long, PlayerSnap> snapshot) {
        List<Human> players = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);
        for (Human p : players) {
            PlayerSnap s = snapshot.get(p.getId());
            if (s == null) continue;
            p.setRating(s.rating()); p.setFitness(s.fitness()); p.setMorale(s.morale()); p.setAge(s.age());
            p.setTeamId(s.teamId()); p.setRetired(s.retired()); p.setWantsTransfer(s.wantsTransfer());
            p.setSeasonMatchesPlayed(s.seasonMatchesPlayed()); p.setConsecutiveBenched(s.consecutiveBenched());
            p.setCurrentStatus(s.currentStatus());
        }
        humanRepository.saveAll(players);
    }

    // ==================== power + naming ====================

    /** Sum of the team's top-11 non-retired player ratings (the static base power). */
    private double top11Power(long teamId) {
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).stream()
                .filter(p -> !p.isRetired())
                .mapToDouble(Human::getRating)
                .boxed()
                .sorted(Comparator.reverseOrder())
                .limit(11)
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    private String teamName(long teamId) {
        return teamRepository.findById(teamId).map(t -> t.getName()).orElse("Team#" + teamId);
    }

    // ==================== report ====================

    private String buildReport(String mode, int seasons, long leagueCompId, List<Long> teamIds,
                               List<String[]> seasonRows, Map<Long, Integer> titles, Map<Long, Integer> positionSum,
                               Map<Long, Double> initialPower, Map<Long, Double> finalPower, int favouriteTitles) {
        String leagueName = competitionRepository.findById(leagueCompId).map(c -> c.getName()).orElse("League");
        StringBuilder sb = new StringBuilder();
        sb.append("# Multi-season simulation — mode `").append(mode).append("`\n\n");
        sb.append("- League: ").append(leagueName).append(" (id ").append(leagueCompId).append(")\n");
        sb.append("- Seasons: ").append(seasons).append("  ·  base match seed: ").append(BASE_SEED).append("\n");
        sb.append("- Favourite (media #1) won the title in **").append(favouriteTitles).append("/").append(seasons).append("** seasons\n");
        sb.append("- Mode notes: ")
          .append(mode.equals("reset")
                ? "each season replays from identical initial squads with fresh luck (no ageing/transfers)."
                : "squads evolve across seasons via the real transition (ageing, training boost, AI transfers).")
          .append("\n\n");

        MarkdownTable seasonsTable = new MarkdownTable(
                List.of("Season", "Favourite", "Champion", "Favourite won?"),
                List.of(Align.RIGHT, Align.LEFT, Align.LEFT, Align.LEFT));
        for (String[] r : seasonRows) seasonsTable.addRow(r);
        sb.append("## Seasons\n\n").append(seasonsTable.render()).append("\n");

        MarkdownTable teamsTable = new MarkdownTable(
                List.of("Team", "Power start", "Power end", "Δ power", "Titles", "Avg finish"),
                List.of(Align.LEFT, Align.RIGHT, Align.RIGHT, Align.RIGHT, Align.RIGHT, Align.RIGHT));
        teamIds.stream()
                .sorted(Comparator.comparingDouble((Long t) -> finalPower.getOrDefault(t, 0.0)).reversed())
                .forEach(t -> {
                    double p0 = initialPower.getOrDefault(t, 0.0);
                    double p1 = finalPower.getOrDefault(t, 0.0);
                    double avgPos = positionSum.getOrDefault(t, 0) / (double) seasons;
                    teamsTable.addRow(teamName(t),
                            String.format("%.0f", p0), String.format("%.0f", p1), String.format("%+.0f", p1 - p0),
                            String.valueOf(titles.getOrDefault(t, 0)), String.format("%.2f", avgPos));
                });
        sb.append("\n## Teams (sorted by final power)\n\n").append(teamsTable.render()).append("\n");
        return sb.toString();
    }
}
