package com.footballmanagergamesimulator.integration.league;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerTrait;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeInputFactory;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig.TacticalModel;
import com.footballmanagergamesimulator.controller.GameController;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.service.ManagerTacticService;
import com.footballmanagergamesimulator.service.PrebuiltDataService;
import com.footballmanagergamesimulator.service.TacticSimulationService;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plays one club's entire tactic space against its real league and ranks the results.
 *
 * <p>The AI already searches all 900 tactics every round, but it scores them with
 * {@code panelExpectedPoints}, which measures a tactic against a <em>synthetic</em>
 * panel: the club's own profile scaled to 0.7x / 1.0x / 1.3x. That panel is relative
 * to the club, so the optimisation problem is the same shape for everybody up to a
 * factor, and every club in the game arrives at the same answer — measured, 106 of 106
 * play Balanced / Standard.
 *
 * <p>This asks the question the AI cannot: against the eleven clubs that actually share
 * your league, with their real squads, where does each tactic put you? If the table
 * comes back flat, the tactical axes carry no weight in the engine and choosing between
 * them is decoration. If it comes back spread, the AI is simply choosing badly.
 *
 * <p>Run it with:
 * <pre>mvn -o failsafe:integration-test -Dit.test=BestTacticSearchIT -Dbest.tactic.team=5</pre>
 */
@SpringBootTest
// Without a pinned bootstrap seed every run builds a different world, and the same club
// came back with a different best tactic each time — 3.78 then 5.12 for the same side.
// A tactic chosen for squads that will not exist next run is worse than no tactic.
@org.springframework.test.context.TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("Best tactic: every candidate tactic ranked by where it puts one club in its real league")
class BestTacticSearchIT {

    /** Which club to search for. Defaults to the first club of the first league. */
    private static final String TEAM_PROPERTY = "best.tactic.team";
    /** Seasons sampled per tactic. Position is a distribution, so it needs sampling; points do not. */
    private static final String SAMPLES_PROPERTY = "best.tactic.samples";
    private static final String FORMATIONS_PROPERTY = "best.tactic.formations";
    private static final String THREADS_PROPERTY = "best.tactic.threads";
    /** Optional exported game JSON loaded before searching. */
    private static final String SAVE_PROPERTY = "best.tactic.save";
    /** Optional native H2 SCRIPT snapshot restored before searching. */
    private static final String SNAPSHOT_PROPERTY = "best.tactic.snapshot";
    private static final int DEFAULT_SAMPLES = 60;
    private static final long BASE_SEED = 20260528L;

    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private PersonalizedTacticRepository tacticRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TacticSimulationService tacticSimulationService;
    @Autowired private ManagerTacticService managerTacticService;
    @Autowired private com.footballmanagergamesimulator.service.TacticService tacticService;
    @Autowired private CanonicalScoreSampler scoreSampler;
    @Autowired private CompetitionFormatConfig competitionFormat;
    @Autowired private CompartmentEngineConfig compartmentConfig;
    @Autowired private MatchEngineConfig matchEngineConfig;
    @Autowired private GameController gameController;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PrebuiltDataService prebuiltDataService;
    @Autowired private CanonicalRuntimeInputFactory runtimeInputFactory;
    @Autowired private HumanRepository humanRepository;

    @BeforeEach
    void importRequestedSave() throws IOException {
        String snapshotPath = System.getProperty(SNAPSHOT_PROPERTY);
        if (snapshotPath != null && !snapshotPath.isBlank()) {
            Path path = Path.of(snapshotPath).toAbsolutePath().normalize();
            assertThat(path).as("H2 snapshot passed through -D%s", SNAPSHOT_PROPERTY).isRegularFile();
            prebuiltDataService.restore();
            System.out.println("=== restored current H2 snapshot from " + path + " ===");
            return;
        }

        String savePath = System.getProperty(SAVE_PROPERTY);
        if (savePath == null || savePath.isBlank()) return;

        Path path = Path.of(savePath).toAbsolutePath().normalize();
        assertThat(path).as("exported game passed through -D%s", SAVE_PROPERTY).isRegularFile();
        Map<String, Object> save = objectMapper.readValue(
                Files.readAllBytes(path), new TypeReference<>() { });
        Map<String, Object> result = gameController.importGame(save);
        assertThat(result).as("import %s before tactic search", path)
                .containsEntry("success", true);
        System.out.println("=== imported current game from " + path + " ===");
    }

    @Test
    void rankEveryTacticByLeagueFinish() throws IOException {
        List<Long> requestedTeams = resolveRequestedTeams();
        List<Best> summary = new ArrayList<>();
        for (Long requested : requestedTeams) {
            summary.add(search(requested));
        }

        System.out.println();
        System.out.println("=== BEST TACTIC PER CLUB ===");
        for (Best best : summary) {
            System.out.printf("%s | %s | pos=%.2f | pts=%.1f | titles=%.0f%%%n",
                    best.club(), describe(best), best.position(), best.points(), best.titleRate());
            printLineup(best.lineup(), best.playerNames());
        }
        assertThat(summary).hasSameSizeAs(requestedTeams);
    }

    /** One club's full canonical tactic search; returns the winner. */
    private Best search(Long requested) throws IOException {
        // The league is derived from the club, not the other way round: any club of any
        // division must be searchable, and requiring it to sit in a league picked first
        // meant every club outside that one division failed before the search started.
        List<Competition> leagues = competitionRepository.findAll().stream()
                .filter(Competition::isLeague)
                .sorted(Comparator.comparingLong(Competition::getId))
                .toList();
        assertThat(leagues).as("bootstrap must have produced leagues").isNotEmpty();

        Competition league = null;
        List<Long> teamIds = List.of();
        for (Competition candidate : leagues) {
            List<Long> members = membersOf(candidate);
            if (members.size() < 4) continue;
            if (requested == null) { league = candidate; teamIds = members; break; }
            if (members.contains(requested)) { league = candidate; teamIds = members; break; }
        }
        assertThat(league)
                .as("club %s must play in some league", requested)
                .isNotNull();

        final List<Long> searchTeamIds = teamIds;
        final long searchLeagueId = league.getId();
        long targetId = requested == null ? teamIds.get(0) : requested;
        int target = teamIds.indexOf(targetId);

        Map<Long, String> names = teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));

        // Every club's evaluation under its own current tactic. Only the target's is
        // recomputed per candidate; the other eleven never change, so neither do the
        // fixtures they play against each other.
        List<CanonicalTeamEvaluation> baseline = new ArrayList<>();
        for (long teamId : teamIds) {
            PersonalizedTactic tactic = tacticRepository.findPersonalizedTacticByTeamId(teamId)
                    .orElseGet(PersonalizedTactic::new);
            // An opponent with a saved formation must be played in it. bestCanonicalFormation
            // picks by rating sum and would silently drop it, so a club seeded into 4231 was
            // still being faced in whatever shape fits its best-rated eleven — the search
            // was scoring answers against opponents that do not exist in the game.
            baseline.add(tactic.getTactic() == null || tactic.getTactic().isBlank()
                    ? tacticSimulationService.bestCanonicalFormation(teamId, tactic).evaluation()
                    : tacticSimulationService.canonicalFormation(teamId, tactic.getTactic(), tactic)
                            .evaluation());
        }

        int samples = Integer.getInteger(SAMPLES_PROPERTY, DEFAULT_SAMPLES);
        Fixtures fixtures = new Fixtures(teamIds.size(), competitionFormat.get(1)
                .encountersFor(teamIds.size()));

        // The matches the target is not in are identical for all candidates — same
        // evaluations, same seeds, same goals. Sampling them once and reusing the tables
        // removes five sixths of the work.
        CanonicalMatchEvaluationAdapter adapter =
                new CanonicalMatchEvaluationAdapter(compartmentConfig, matchEngineConfig);
        Table[] background = new Table[samples];
        for (int s = 0; s < samples; s++) background[s] = new Table(teamIds.size());
        for (Fixture fixture : fixtures.excluding(target)) {
            play(adapter, baseline.get(fixture.home()), baseline.get(fixture.away()),
                    fixture, teamIds, league.getId(), samples, background);
        }

        // Seven team-level axes reach the canonical engine: mentality, tempo, passing,
        // defensive line, pressing, width and recovery. candidateTactics() supplies the first three;
        // inPossession/timeWasting belong to the removed engine and are deliberately
        // deduplicated. Expand the remaining three axes explicitly so this is genuinely a
        // Recovery is distinct only for Short + Aggressive (where PASSING STYLE may activate),
        // so neutral-only duplicates are omitted: 3,375 + 675 = 4,050 tactics per formation.
        Map<String, PersonalizedTactic> distinctBase = new java.util.LinkedHashMap<>();
        for (PersonalizedTactic candidate : managerTacticService.candidateTactics()) {
            distinctBase.putIfAbsent(candidate.getMentality() + "|" + candidate.getTempo() + "|"
                    + candidate.getPassingType(), candidate);
        }
        List<PersonalizedTactic> candidates = new ArrayList<>(distinctBase.size() * 27);
        for (PersonalizedTactic base : distinctBase.values()) {
            for (String defensiveLine : TacticalModel.DEFENSIVE_LINE_OPTIONS) {
                for (String pressing : TacticalModel.PRESSING_OPTIONS) {
                    for (String width : TacticalModel.WIDTH_OPTIONS) {
                        List<String> recoveries = "Short".equals(base.getPassingType())
                                && "Aggressive".equals(pressing)
                                ? TacticalModel.RECOVERY_OPTIONS : List.of("Standard");
                        for (String recovery : recoveries) {
                            candidates.add(canonicalCandidate(base, defensiveLine, pressing, width, recovery));
                        }
                    }
                }
            }
        }
        int expectedCandidates = TacticalModel.MENTALITY_OPTIONS.size()
                * TacticalModel.TEMPO_OPTIONS.size()
                * TacticalModel.PASSING_OPTIONS.size()
                * TacticalModel.DEFENSIVE_LINE_OPTIONS.size()
                * TacticalModel.PRESSING_OPTIONS.size()
                * TacticalModel.WIDTH_OPTIONS.size()
                + TacticalModel.MENTALITY_OPTIONS.size() * TacticalModel.TEMPO_OPTIONS.size()
                * TacticalModel.DEFENSIVE_LINE_OPTIONS.size() * TacticalModel.WIDTH_OPTIONS.size()
                * (TacticalModel.RECOVERY_OPTIONS.size() - 1);
        assertThat(candidates).as("complete distinct seven-axis canonical tactic grid")
                .hasSize(expectedCandidates);

        // Formation is searched here rather than left to bestCanonicalFormation, which picks
        // whichever shape fields the highest RATING SUM. That answers "where do my best-rated
        // players fit", not "which shape wins" — and the engine decides matches on weighted
        // compartments plus defensive exposure, none of which a rating sum can express.
        List<String> formations = requestedFormations();

        Map<Long, String> playerNames = humanRepository
                .findAllByTeamIdAndTypeId(targetId, TypeNames.PLAYER_TYPE).stream()
                .collect(Collectors.toMap(player -> player.getId(), player -> player.getName()));
        Set<Long> mandatoryShadows = humanRepository
                .findAllByTeamIdAndTypeId(targetId, TypeNames.PLAYER_TYPE).stream()
                .filter(player -> player.isStayForward()).map(player -> player.getId())
                .collect(Collectors.toSet());

        List<FormationSearch> formationSearches = new ArrayList<>();
        for (String formation : formations) {
            CanonicalRuntimeTeamInput baseInput = tacticSimulationService
                    .canonicalFormation(targetId, formation, candidates.get(0)).input();
            formationSearches.add(new FormationSearch(formation, baseInput,
                    rolePlans(baseInput, mandatoryShadows)));
        }
        List<SearchCase> searchCases = new ArrayList<>();
        for (PersonalizedTactic candidate : candidates) {
            for (FormationSearch formation : formationSearches) {
                for (RolePlan roles : formation.roles()) {
                    searchCases.add(new SearchCase(candidate, formation, roles));
                }
            }
        }

        int threads = Integer.getInteger(THREADS_PROPERTY,
                Math.min(8, Runtime.getRuntime().availableProcessors()));
        assertThat(threads).as("%s must be positive", THREADS_PROPERTY).isPositive();
        System.out.println("=== searching " + candidates.size() + " tactics x " + formations.size()
                + " formations x offensive role variants for " + names.get(targetId)
                + " over " + samples + " seasons each ===");
        List<Row> rows = calculateRows("search", searchCases, samples, background, fixtures,
                target, baseline, searchTeamIds, searchLeagueId, mandatoryShadows, threads);
        rows.sort(rowComparator());

        Row best = rows.get(0);
        FormationSearch winningFormation = formationSearches.stream()
                .filter(search -> search.name().equals(best.formation())).findFirst().orElseThrow();
        CanonicalRuntimeTeamInput winningInput = withRoles(
                runtimeInputFactory.withTactic(winningFormation.baseInput(), best.tactic()),
                best.roles(), mandatoryShadows);

        Path report = Path.of("target", "best-tactic-" + targetId + ".md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, render(rows, names.get(targetId), league.getName(), samples,
                playerNames, winningInput.lineup()));
        System.out.println("=== written to " + report.toAbsolutePath() + " ===");

        Row worst = rows.get(rows.size() - 1);
        System.out.printf("BEST   %s | pos=%.2f | pts=%.1f | GF=%.1f | GA=%.1f | titles=%.0f%%%n",
                describe(best), best.position(), best.points(), best.goalsFor(), best.goalsAgainst(),
                best.titleRate());
        printLineup(winningInput.lineup(), playerNames);
        System.out.printf("WORST  %s | pos=%.2f | pts=%.1f | GF=%.1f | GA=%.1f | titles=%.0f%%%n",
                describe(worst), worst.position(), worst.points(), worst.goalsFor(), worst.goalsAgainst(),
                worst.titleRate());
        System.out.printf("SPREAD %.2f places, %.1f points%n",
                worst.position() - best.position(), best.points() - worst.points());

        assertThat(rows).hasSize(searchCases.size());
        PersonalizedTactic winner = best.tactic();
        return new Best(names.get(targetId), targetId, best.formation(),
                String.valueOf(winner.getMentality()), String.valueOf(winner.getTempo()),
                String.valueOf(winner.getPassingType()), String.valueOf(winner.getDefensiveLine()),
                String.valueOf(winner.getPressing()), String.valueOf(winner.getWidth()),
                String.valueOf(winner.getRecovery()),
                best.roles(), best.passingStyle(), winningInput.lineup(), playerNames,
                best.position(), best.points(), best.titleRate());
    }

    /** Winning tactic for one club, for the end-of-run summary. */
    private record Best(String club, long teamId, String formation, String mentality, String tempo,
                        String passing, String defensiveLine, String pressing, String width, String recovery,
                        RolePlan roles, boolean passingStyle, List<CanonicalLineupPlayer> lineup,
                        Map<Long, String> playerNames,
                        double position, double points, double titleRate) {}

    private record FormationSearch(String name, CanonicalRuntimeTeamInput baseInput,
                                   List<RolePlan> roles) {}

    private record SearchCase(PersonalizedTactic tactic, FormationSearch formation, RolePlan roles) {}

    private record RolePlan(Long shooterId, Long shadowId) {}

    private static List<RolePlan> rolePlans(CanonicalRuntimeTeamInput input, Set<Long> mandatoryShadows) {
        List<Long> eligible = input.lineup().stream()
                .filter(player -> isOffensiveRolePosition(player.usedPosition()))
                .map(CanonicalLineupPlayer::playerId).toList();
        List<RolePlan> plans = new ArrayList<>();
        plans.add(new RolePlan(null, null));
        for (Long playerId : eligible) {
            plans.add(new RolePlan(playerId, null));
            if (!mandatoryShadows.contains(playerId)) {
                plans.add(new RolePlan(null, playerId));
                plans.add(new RolePlan(playerId, playerId));
            }
        }
        return List.copyOf(plans);
    }

    private static boolean isOffensiveRolePosition(PlayerPosition position) {
        return switch (position) {
            case AML, ML, AMR, MR, MC, AMC, ST -> true;
            default -> false;
        };
    }

    private static CanonicalRuntimeTeamInput withRoles(CanonicalRuntimeTeamInput input,
                                                        RolePlan plan,
                                                        Set<Long> mandatoryShadows) {
        List<CanonicalLineupPlayer> lineup = input.lineup().stream().map(player -> {
            Set<PlayerTrait> traits = new HashSet<>(player.traits());
            traits.remove(PlayerTrait.SHOOTER);
            if (!mandatoryShadows.contains(player.playerId())) {
                traits.remove(PlayerTrait.REFUSES_DEFENSIVE_WORK);
            }
            if (java.util.Objects.equals(plan.shooterId(), player.playerId())) {
                traits.add(PlayerTrait.SHOOTER);
            }
            if (java.util.Objects.equals(plan.shadowId(), player.playerId())) {
                traits.add(PlayerTrait.REFUSES_DEFENSIVE_WORK);
            }
            return new CanonicalLineupPlayer(player.playerId(), player.usedPosition(), player.occurrence(),
                    player.role(), player.duty(), player.attributes(), player.fitness(), player.morale(),
                    player.capability(), player.roleSuitability(), traits, player.forwardInstruction(),
                    player.overallRating());
        }).toList();
        return new CanonicalRuntimeTeamInput(input.mentality(), lineup, input.tacticalContexts());
    }

    private List<Row> calculateRows(String phase, List<SearchCase> cases, int samples,
                                    Table[] background, Fixtures fixtures, int target,
                                    List<CanonicalTeamEvaluation> baseline, List<Long> teamIds,
                                    long leagueId, Set<Long> mandatoryShadows, int threads) {
        int total = cases.size();
        if (total == 0) return List.of();
        Row[] calculated = new Row[total];
        AtomicInteger done = new AtomicInteger();
        long started = System.currentTimeMillis();
        int progressInterval = Math.max(250, total / 100);
        System.out.println("=== " + phase + " uses " + threads + " parallel workers ===");

        ForkJoinPool workers = new ForkJoinPool(threads);
        try {
            workers.submit(() -> IntStream.range(0, total).parallel().forEach(index -> {
                SearchCase searchCase = cases.get(index);
                PersonalizedTactic tactic = searchCase.tactic();
                CanonicalRuntimeTeamInput tacticalInput = runtimeInputFactory.withTactic(
                        searchCase.formation().baseInput(), tactic);
                CanonicalRuntimeTeamInput roleInput = withRoles(
                        tacticalInput, searchCase.roles(), mandatoryShadows);
                CanonicalMatchEvaluationAdapter adapter =
                        new CanonicalMatchEvaluationAdapter(compartmentConfig, matchEngineConfig);
                CanonicalTeamEvaluation evaluation = adapter.evaluateTeam(roleInput);

                Table[] tables = new Table[samples];
                for (int sample = 0; sample < samples; sample++) {
                    tables[sample] = background[sample].copy();
                }
                for (Fixture fixture : fixtures.including(target)) {
                    CanonicalTeamEvaluation home = fixture.home() == target
                            ? evaluation : baseline.get(fixture.home());
                    CanonicalTeamEvaluation away = fixture.away() == target
                            ? evaluation : baseline.get(fixture.away());
                    play(adapter, home, away, fixture, teamIds, leagueId, samples, tables);
                }

                double positions = 0, points = 0, goalsFor = 0, goalsAgainst = 0;
                int titles = 0;
                for (Table table : tables) {
                    int[] order = table.order();
                    for (int position = 0; position < order.length; position++) {
                        if (order[position] != target) continue;
                        positions += position + 1;
                        if (position == 0) titles++;
                        break;
                    }
                    points += table.points[target];
                    goalsFor += table.goalsFor[target];
                    goalsAgainst += table.goalsAgainst[target];
                }
                calculated[index] = new Row(tactic, searchCase.formation().name(), searchCase.roles(),
                        evaluation.team().passingStyle().active(), positions / samples, points / samples,
                        goalsFor / samples, goalsAgainst / samples, titles * 100.0 / samples);

                int completed = done.incrementAndGet();
                if (completed % progressInterval == 0 || completed == total) {
                    System.out.println("  " + completed + "/" + total
                            + "  (" + (System.currentTimeMillis() - started) + "ms)");
                }
            })).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Tactic search interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IllegalStateException("Parallel tactic search failed", failure.getCause());
        } finally {
            workers.shutdown();
        }
        return new ArrayList<>(java.util.Arrays.asList(calculated));
    }

    private static Comparator<Row> rowComparator() {
        return Comparator.comparingDouble(Row::position)
                .thenComparing(Comparator.comparingDouble(Row::points).reversed());
    }

    private static PersonalizedTactic canonicalCandidate(PersonalizedTactic base,
                                                          String defensiveLine,
                                                          String pressing,
                                                          String width,
                                                          String recovery) {
        PersonalizedTactic candidate = new PersonalizedTactic();
        candidate.setMentality(base.getMentality());
        candidate.setTempo(base.getTempo());
        candidate.setPassingType(base.getPassingType());
        candidate.setDefensiveLine(defensiveLine);
        candidate.setPressing(pressing);
        candidate.setWidth(width);
        candidate.setRecovery(recovery);
        return candidate;
    }

    /**
     * Formations to try. Defaults to every shape the game knows; narrow it with
     * {@code -Dbest.tactic.formations=442,433,352} when iterating.
     */
    private List<String> requestedFormations() {
        String raw = System.getProperty(FORMATIONS_PROPERTY);
        if (raw == null || raw.isBlank()) return tacticService.getAllExistingTactics();
        List<String> all = tacticService.getAllExistingTactics();
        List<String> chosen = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) continue;
            assertThat(all).as("unknown formation '%s'", value).contains(value);
            chosen.add(value);
        }
        assertThat(chosen).as("at least one formation must be requested").isNotEmpty();
        return chosen;
    }

    /**
     * The club to search for, given either as an id or as a name.
     *
     * <p>Names are accepted because ids are assigned by seed order and are not written
     * down anywhere a person would look; asking for {@code -Dbest.tactic.team="Tik Tok"}
     * should not require first working out that it is club 14.
     */
    private List<Long> resolveRequestedTeams() {
        String raw = System.getProperty(TEAM_PROPERTY);
        if (raw == null || raw.isBlank()) return java.util.Collections.singletonList(null);
        // Comma-separated so a whole shortlist runs inside ONE Spring context. Started as
        // one club per invocation, which meant eleven clubs cost eleven application
        // startups — minutes of context building to do seconds of search.
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) continue;
            if (value.chars().allMatch(Character::isDigit)) { ids.add(Long.parseLong(value)); continue; }
            List<Team> matches = teamRepository.findAll().stream()
                    .filter(team -> team.getName() != null && team.getName().equalsIgnoreCase(value))
                    .toList();
            assertThat(matches).as("no club named '%s' — check the spelling against the seed data", value)
                    .hasSize(1);
            ids.add(matches.get(0).getId());
        }
        assertThat(ids).as("at least one club must be requested").isNotEmpty();
        return ids;
    }

    /**
     * Clubs of a league, taken from whichever season it has entries for.
     *
     * <p>Season 1 is not a safe assumption: the world may have rolled forward, and a
     * league whose membership was only ever written for a later season would look empty
     * and be skipped silently.
     */
    private List<Long> membersOf(Competition league) {
        List<CompetitionTeamInfo> entries = competitionTeamInfoRepository.findAll().stream()
                .filter(entry -> entry.getCompetitionId() == league.getId())
                .toList();
        // Saves retain historical league rows. Use the latest populated season so a
        // promoted/relegated club is tested against its current opponents, not its
        // season-one division.
        long season = entries.stream().mapToLong(CompetitionTeamInfo::getSeasonNumber).max().orElse(1);
        return entries.stream()
                .filter(entry -> entry.getSeasonNumber() == season)
                .map(CompetitionTeamInfo::getTeamId)
                .filter(id -> id > 0).distinct().sorted().toList();
    }

    private void play(CanonicalMatchEvaluationAdapter adapter, CanonicalTeamEvaluation home,
                      CanonicalTeamEvaluation away, Fixture fixture, List<Long> teamIds,
                      long competitionId, int samples, Table[] tables) {
        CanonicalMatchEvaluation evaluation = adapter.evaluate(home, away, MatchVenue.HOME);
        for (int s = 0; s < samples; s++) {
            // Same key shape as the other outcome tests, so a fixture that is not affected
            // by the candidate produces byte-identical goals across every candidate run.
            String key = "BEST_TACTIC:" + competitionId + ":" + (s + 1) + ":" + fixture.ordinal()
                    + ":" + teamIds.get(fixture.home()) + ":" + teamIds.get(fixture.away());
            long seed = MatchPlanService.seedFor(key, competitionId, s + 1, fixture.ordinal() + 1,
                    teamIds.get(fixture.home()), teamIds.get(fixture.away()));
            CanonicalScoreSampler.GoalSample goals = scoreSampler.sample(evaluation, seed);
            tables[s].apply(fixture.home(), fixture.away(), goals.homeGoals(), goals.awayGoals());
        }
    }

    private static String describe(Row row) {
        PersonalizedTactic tactic = row.tactic();
        return "formation=" + row.formation()
                + " | mentality=" + tactic.getMentality()
                + " | tempo=" + tactic.getTempo()
                + " | passing=" + tactic.getPassingType()
                + " | defensiveLine=" + tactic.getDefensiveLine()
                + " | pressing=" + tactic.getPressing()
                + " | width=" + tactic.getWidth()
                + " | recovery=" + tactic.getRecovery()
                + " | shooter=" + valueOrNone(row.roles().shooterId())
                + " | shadow=" + valueOrNone(row.roles().shadowId())
                + " | passingStyle=" + (row.passingStyle() ? "ACTIVE" : "inactive");
    }

    private static String describe(Best best) {
        return "formation=" + best.formation()
                + " | mentality=" + best.mentality()
                + " | tempo=" + best.tempo()
                + " | passing=" + best.passing()
                + " | defensiveLine=" + best.defensiveLine()
                + " | pressing=" + best.pressing()
                + " | width=" + best.width()
                + " | recovery=" + best.recovery()
                + " | shooter=" + playerName(best.roles().shooterId(), best.playerNames())
                + " | shadow=" + playerName(best.roles().shadowId(), best.playerNames())
                + " | passingStyle=" + (best.passingStyle() ? "ACTIVE" : "inactive");
    }

    private static String valueOrNone(Long value) {
        return value == null ? "none" : value.toString();
    }

    private static String playerName(Long id, Map<Long, String> names) {
        return id == null ? "none" : names.getOrDefault(id, "Player#" + id);
    }

    private static String lineupRole(CanonicalLineupPlayer player) {
        boolean shooter = player.traits().contains(PlayerTrait.SHOOTER);
        boolean shadow = player.traits().contains(PlayerTrait.REFUSES_DEFENSIVE_WORK);
        if (shooter && shadow) return "SHOOTER+SHADOW";
        if (shooter) return "SHOOTER";
        if (shadow) return "SHADOW";
        return "STANDARD";
    }

    private static void printLineup(List<CanonicalLineupPlayer> lineup, Map<Long, String> names) {
        System.out.println("FIRST XI:");
        lineup.forEach(player -> System.out.printf("  %-4s | %-28s | %s%n",
                player.usedPosition().code(), names.getOrDefault(player.playerId(), "Player#" + player.playerId()),
                lineupRole(player)));
    }

    private static String render(List<Row> rows, String club, String league, int samples,
                                 Map<Long, String> playerNames,
                                 List<CanonicalLineupPlayer> winningLineup) {
        StringBuilder md = new StringBuilder();
        md.append("# Best tactic — ").append(club).append("\n\n");
        md.append("- league: ").append(league).append('\n');
        md.append("- candidates: ").append(rows.size()).append('\n');
        md.append("- sampled seasons per candidate: ").append(samples).append("\n\n");
        md.append("Every candidate is played against the club's real opponents, each keeping its own\n");
        md.append("tactic. Fixtures the club is not in are sampled once and shared, so the only thing\n");
        md.append("that differs between rows is the club's own tactic.\n\n");
        md.append("The complete canonical team-level grid is searched: mentality, tempo, passing,\n");
        md.append("defensive line, pressing, width and recovery, plus every requested formation.\n");
        md.append("Every tactic/formation entry is expanded with SHOOTER, SHADOW and\n");
        md.append("SHOOTER+SHADOW on every eligible offensive starter.\n\n");
        md.append("`Pos` is the mean finishing position across the sampled seasons — lower is better.\n\n");

        md.append("## Winning first XI\n\n");
        md.append("| Position | Player | Match role |\n|---|---|---|\n");
        for (CanonicalLineupPlayer player : winningLineup) {
            md.append("| ").append(player.usedPosition().code()).append(" | ")
                    .append(playerNames.getOrDefault(player.playerId(), "Player#" + player.playerId()))
                    .append(" | ").append(lineupRole(player)).append(" |\n");
        }
        md.append("\n");

        MarkdownTable table = new MarkdownTable(
                List.of("#", "Formation", "Mentality", "Tempo", "Passing", "Def Line", "Pressing", "Width", "Recovery",
                        "Shooter", "Shadow", "Passing Style",
                        "Pos", "Pts", "GF", "GA", "Titles"),
                List.of(MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
        int rank = 1;
        for (Row row : rows) {
            PersonalizedTactic t = row.tactic();
            table.addRow(String.valueOf(rank++), row.formation(),
                    String.valueOf(t.getMentality()), String.valueOf(t.getTempo()),
                    String.valueOf(t.getPassingType()), String.valueOf(t.getDefensiveLine()),
                    String.valueOf(t.getPressing()), String.valueOf(t.getWidth()),
                    String.valueOf(t.getRecovery()),
                    playerName(row.roles().shooterId(), playerNames),
                    playerName(row.roles().shadowId(), playerNames),
                    row.passingStyle() ? "ACTIVE" : "inactive",
                    String.format("%.2f", row.position()), String.format("%.1f", row.points()),
                    String.format("%.1f", row.goalsFor()), String.format("%.1f", row.goalsAgainst()),
                    String.format("%.0f%%", row.titleRate()));
        }
        return md.append(table.render()).toString();
    }

    private record Row(PersonalizedTactic tactic, String formation, RolePlan roles,
                       boolean passingStyle, double position, double points,
                       double goalsFor, double goalsAgainst, double titleRate) {}

    private record Fixture(int home, int away, int ordinal) {}

    /** The league's full fixture list, split by whether one club is involved. */
    private static final class Fixtures {
        private final List<Fixture> all = new ArrayList<>();

        private Fixtures(int teams, int encounters) {
            int ordinal = 0;
            for (int meeting = 0; meeting < encounters; meeting++) {
                for (int first = 0; first < teams; first++) {
                    for (int second = first + 1; second < teams; second++) {
                        boolean flip = meeting % 2 != 0;
                        all.add(new Fixture(flip ? second : first, flip ? first : second, ordinal++));
                    }
                }
            }
        }

        private List<Fixture> including(int team) {
            return all.stream().filter(f -> f.home() == team || f.away() == team).toList();
        }

        private List<Fixture> excluding(int team) {
            return all.stream().filter(f -> f.home() != team && f.away() != team).toList();
        }
    }

    private static final class Table {
        private final int[] points;
        private final int[] goalsFor;
        private final int[] goalsAgainst;

        private Table(int teams) {
            points = new int[teams];
            goalsFor = new int[teams];
            goalsAgainst = new int[teams];
        }

        private Table copy() {
            Table copy = new Table(points.length);
            System.arraycopy(points, 0, copy.points, 0, points.length);
            System.arraycopy(goalsFor, 0, copy.goalsFor, 0, goalsFor.length);
            System.arraycopy(goalsAgainst, 0, copy.goalsAgainst, 0, goalsAgainst.length);
            return copy;
        }

        private void apply(int home, int away, int homeGoals, int awayGoals) {
            goalsFor[home] += homeGoals;
            goalsAgainst[home] += awayGoals;
            goalsFor[away] += awayGoals;
            goalsAgainst[away] += homeGoals;
            if (homeGoals > awayGoals) points[home] += 3;
            else if (homeGoals < awayGoals) points[away] += 3;
            else { points[home]++; points[away]++; }
        }

        private int[] order() {
            Integer[] index = new Integer[points.length];
            for (int i = 0; i < index.length; i++) index[i] = i;
            java.util.Arrays.sort(index, (a, b) -> {
                if (points[a] != points[b]) return points[b] - points[a];
                int gdA = goalsFor[a] - goalsAgainst[a];
                int gdB = goalsFor[b] - goalsAgainst[b];
                if (gdA != gdB) return gdB - gdA;
                return goalsFor[b] - goalsFor[a];
            });
            int[] result = new int[index.length];
            for (int i = 0; i < index.length; i++) result[i] = index[i];
            return result;
        }
    }
}
