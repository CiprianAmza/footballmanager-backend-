package com.footballmanagergamesimulator.integration.league;

import com.footballmanagergamesimulator.compartment.effects.CanonicalMatchEffectEvent;
import com.footballmanagergamesimulator.compartment.effects.CanonicalMatchEffectsInput;
import com.footballmanagergamesimulator.compartment.PlayerAttribute;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.matchplan.InstantMatchExecutor;
import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;
import com.footballmanagergamesimulator.matchplan.Contributor;
import com.footballmanagergamesimulator.matchplan.Lineup;
import com.footballmanagergamesimulator.matchplan.LineupAdapter;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import com.footballmanagergamesimulator.matchplan.MatchPlanService;
import com.footballmanagergamesimulator.matchplan.MatchPlanningService;
import com.footballmanagergamesimulator.matchplan.MatchScoringDecision;
import com.footballmanagergamesimulator.matchplan.ScoreEngineKind;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.MatchStatsService;
import com.footballmanagergamesimulator.service.TacticSimulationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canonical round-robin simulation between Inazuma, an in-memory nerfed copy of
 * Inazuma, Athletic Sohatu, Shadows, Tik Tok and FC San Marino. The nerfed copy
 * has every canonical attribute of its six starting midfielders reduced by two
 * points, bounded by the engine's valid minimum attribute value of one.
 *
 * <p>The report includes results, score extremes, scorers, assists, scoreline and
 * goal-type distributions, all projected match statistics, plus the SHOOTER and
 * PASSING STYLE internals. Home advantage is neutralised by alternating the home
 * side on every iteration.</p>
 *
 * <p>Run with:</p>
 * <pre>
 * mvn -o test-compile failsafe:integration-test -Dit.test=AthleticSohatuVsInazumaIT -Dheadtohead.matches=1000
 * </pre>
 */
@SpringBootTest
@TestPropertySource(properties = "bootstrap.seed=20260528")
@DisplayName("Six-team configurable canonical tournament")
class AthleticSohatuVsInazumaIT {

    private static final String MATCHES_PROPERTY = "headtohead.matches";
    private static final int DEFAULT_MATCHES = 1_000;
    private static final int MAX_MATCHES = 1_000_000;
    private static final long BASE_SEED = 20260730L;
    private static final long NERFED_TEAM_ID = 9_000_000_001L;
    private static final Set<PlayerPosition> MIDFIELD_POSITIONS = EnumSet.of(
            PlayerPosition.DM, PlayerPosition.MC, PlayerPosition.AMC,
            PlayerPosition.ML, PlayerPosition.MR, PlayerPosition.AML, PlayerPosition.AMR);
    private static final String ZERO_FINGERPRINT = "0".repeat(64);
    private static final String ONE_FINGERPRINT = "1".repeat(64);

    @Autowired private TeamRepository teamRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private PersonalizedTacticRepository tacticRepository;
    @Autowired private TacticSimulationService tacticSimulationService;
    @Autowired private CanonicalScoreSampler scoreSampler;
    @Autowired private CompartmentEngineConfig compartmentConfig;
    @Autowired private MatchEngineConfig matchEngineConfig;
    @Autowired private MatchPlanningService planningService;
    @Autowired private InstantMatchExecutor instantExecutor;
    @Autowired private LineupAdapter lineupAdapter;
    @Autowired private MatchStatsService matchStatsService;

    @Test
    void simulateConfigurableNerfedTournament() throws IOException {
        int matchesPerPairing = requestedMatches();
        Participant athletic = withMentality(setup(requireTeam("Athletic Sohatu")), "Very Defensive");
        Participant inazuma = setup(requireInazuma());
        NerfedParticipant nerfedResult = nerfedCopy(inazuma);
        Participant nerfed = nerfedResult.participant();
        Participant shadows = setup(requireTeam("Shadows"));
        Participant tikTok = setup(requireTeam("Tik Tok"));
        Participant sanMarino = setup(requireTeam("FC San Marino"));
        List<Participant> participants = List.of(
                inazuma, nerfed, athletic, shadows, tikTok, sanMarino);
        List<Pairing> pairings = allPairings(participants);
        long competitionId = Math.max(1L, athletic.sourceTeam().getCompetitionId());

        CanonicalMatchEvaluationAdapter matchAdapter =
                new CanonicalMatchEvaluationAdapter(compartmentConfig, matchEngineConfig);
        Map<Long, TeamAggregate> totals = participants.stream().collect(Collectors.toMap(
                Participant::id, TeamAggregate::new, (a, b) -> a, LinkedHashMap::new));
        Map<PlayerKey, Integer> goalsByPlayer = new LinkedHashMap<>();
        Map<PlayerKey, Integer> assistsByPlayer = new LinkedHashMap<>();
        Map<String, Integer> goalTypes = new TreeMap<>();
        List<GameResult> results = new ArrayList<>(matchesPerPairing * pairings.size());
        List<MatchupAggregate> matchups = new ArrayList<>();
        int round = 0;

        for (Pairing pairing : pairings) {
            MatchupAggregate matchup = new MatchupAggregate(pairing.first(), pairing.second());
            matchups.add(matchup);
            for (int index = 0; index < matchesPerPairing; index++) {
                boolean firstAtHome = index % 2 == 0;
                Participant home = firstAtHome ? pairing.first() : pairing.second();
                Participant away = firstAtHome ? pairing.second() : pairing.first();
                round++;
                String fixtureKey = "NERFED_TOURNAMENT:" + BASE_SEED + ":" + round
                        + ":" + home.id() + ":" + away.id();
                long seed = MatchPlanService.seedFor(fixtureKey, competitionId, 1, round,
                        home.id(), away.id());

                CanonicalMatchEvaluation evaluation = matchAdapter.evaluate(
                        home.formation().evaluation(), away.formation().evaluation(), MatchVenue.HOME);
                CanonicalScoreSampler.GoalSample score = scoreSampler.sample(evaluation, seed);
                MatchScoringDecision decision = decision(fixtureKey, seed, score);
                MatchPlan plan = planningService.plan(fixtureKey, seed,
                        home.id(), away.id(), score.homeGoals(), score.awayGoals());
                plan.applyScoreDecision(decision);

                List<MatchEvent> events = instantExecutor.execute(plan, home.lineup(), away.lineup(),
                        new InstantMatchExecutor.MatchContext(fixtureKey, competitionId, 1, round));
                assertThat(events.stream().filter(event -> "goal".equals(event.getEventType())))
                        .as("one resolved goal event per goal in match %s", round)
                        .hasSize(score.homeGoals() + score.awayGoals());

                events.forEach(event -> {
                    PlayerKey key = new PlayerKey(event.getTeamId(), event.getPlayerId());
                    if ("goal".equals(event.getEventType())) {
                        goalsByPlayer.merge(key, 1, Integer::sum);
                        goalTypes.merge(event.getDetails(), 1, Integer::sum);
                    } else if ("assist".equals(event.getEventType())) {
                        assistsByPlayer.merge(key, 1, Integer::sum);
                    }
                });

                KnockoutPlanSplit split = KnockoutPlanSplit.regularOnly(
                        score.homeGoals(), score.awayGoals());
                CanonicalMatchEffectsInput effects = new CanonicalMatchEffectsInput(
                        decision, split, home.id(), away.id(),
                        events.stream().map(AthleticSohatuVsInazumaIT::effectEvent).toList());
                MatchStats stats = matchStatsService.projectCanonicalMatchStats(
                        effects, competitionId, 1, round);

                totals.get(home.id()).add(stats, true, score.homeGoals(), score.awayGoals(), score);
                totals.get(away.id()).add(stats, false, score.awayGoals(), score.homeGoals(), score);
                int firstGoals = firstAtHome ? score.homeGoals() : score.awayGoals();
                int secondGoals = firstAtHome ? score.awayGoals() : score.homeGoals();
                matchup.add(firstGoals, secondGoals);
                results.add(new GameResult(round, home.name(), away.name(),
                        score.homeGoals(), score.awayGoals()));
            }
        }

        Map<PlayerKey, PlayerInfo> players = playerDirectory(participants);
        String report = renderReport(matchesPerPairing, participants, totals, matchups,
                nerfedResult.players(), results, goalsByPlayer, assistsByPlayer, goalTypes, players);
        Path reportPath = Path.of("target", "inazuma-nerfed-tournament.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report);

        System.out.println();
        System.out.println(renderConsoleReport(report));
        System.out.println("Report written to: " + reportPath.toAbsolutePath());

        totals.values().forEach(team -> {
            assertThat(team.matches).isEqualTo(matchesPerPairing * (participants.size() - 1L));
            assertThat(team.wins + team.draws + team.losses).isEqualTo(team.matches);
        });
        assertThat(goalsByPlayer.values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(totals.values().stream().mapToLong(team -> team.goalsFor).sum());
        assertThat(assistsByPlayer.values().stream().mapToInt(Integer::intValue).sum())
                .isLessThanOrEqualTo(goalsByPlayer.values().stream().mapToInt(Integer::intValue).sum());
        TeamAggregate inazumaTotals = totals.get(inazuma.id());
        assertThat(inazumaTotals.metric(Metric.POSSESSION) / inazumaTotals.matches)
                .as("displayed possession must track active PASSING control")
                .isCloseTo(inazumaTotals.passingControl * 100.0 / inazumaTotals.matches,
                        org.assertj.core.data.Offset.offset(3.0));
    }

    private Participant setup(Team team) {
        PersonalizedTactic tactic = tacticRepository.findPersonalizedTacticByTeamId(team.getId())
                .orElseThrow(() -> new IllegalStateException("No saved tactic for " + team.getName()));
        assertThat(tactic.getTactic()).as("saved formation for %s", team.getName()).isNotBlank();
        TacticSimulationService.CanonicalFormationEvaluation formation = tacticSimulationService
                .canonicalFormation(team.getId(), tactic.getTactic(), tactic);
        LineupAdapter.Result lineup = lineupAdapter.build(
                team.getId(), tactic.getTactic(), BASE_SEED, LineupAdapter.Mode.USER_SAVED);
        assertThat(lineup.lineup().getStartingXI()).hasSize(11);
        return new Participant(team.getId(), team.getName(), team, tactic, formation,
                lineup.lineup(), lineup.source());
    }

    private static List<Pairing> allPairings(List<Participant> participants) {
        List<Pairing> pairings = new ArrayList<>();
        for (int first = 0; first < participants.size(); first++) {
            for (int second = first + 1; second < participants.size(); second++) {
                pairings.add(new Pairing(participants.get(first), participants.get(second)));
            }
        }
        return List.copyOf(pairings);
    }

    private NerfedParticipant nerfedCopy(Participant original) {
        List<NerfedPlayer> changedPlayers = new ArrayList<>();
        List<CanonicalLineupPlayer> nerfedPlayers = original.formation().input().lineup().stream()
                .map(player -> {
                    if (!MIDFIELD_POSITIONS.contains(player.usedPosition())) return player;
                    EnumMap<PlayerAttribute, Integer> attributes = new EnumMap<>(PlayerAttribute.class);
                    player.attributes().forEach((attribute, value) ->
                            attributes.put(attribute, Math.max(1, value - 2)));
                    changedPlayers.add(new NerfedPlayer(player.playerId(), player.usedPosition(),
                            player.attributes(), Map.copyOf(attributes)));
                    return new CanonicalLineupPlayer(
                            player.playerId(), player.usedPosition(), player.occurrence(),
                            player.role(), player.duty(), attributes, player.fitness(), player.morale(),
                            player.capability(), player.roleSuitability(), player.traits(),
                            player.forwardInstruction(), player.overallRating());
                }).toList();
        assertThat(changedPlayers)
                .as("the Inazuma saved XI must contain exactly six midfield players")
                .hasSize(6);

        CanonicalRuntimeTeamInput input = new CanonicalRuntimeTeamInput(
                original.formation().input().mentality(), nerfedPlayers,
                original.formation().input().tacticalContexts());
        CanonicalTeamEvaluationAdapter adapter =
                new CanonicalTeamEvaluationAdapter(compartmentConfig, matchEngineConfig);
        TacticSimulationService.CanonicalFormationEvaluation formation =
                new TacticSimulationService.CanonicalFormationEvaluation(
                        original.formation().formation(), input, adapter.evaluate(input.mentality(),
                        input.lineup(), input.tacticalContexts()), original.formation().topXiRating());

        Set<Long> changedIds = changedPlayers.stream().map(NerfedPlayer::playerId).collect(Collectors.toSet());
        Lineup lineup = new Lineup(
                nerfContributors(original.lineup().getStartingXI(), changedIds),
                original.lineup().getBench(), original.lineup().getSubs());
        Participant participant = new Participant(NERFED_TEAM_ID, "Inazuma Nerfed",
                original.sourceTeam(), original.tactic(), formation, lineup, original.lineupSource());
        return new NerfedParticipant(participant, List.copyOf(changedPlayers));
    }

    private Participant withMentality(Participant original, String mentality) {
        PersonalizedTactic tactic = copyTactic(original.tactic());
        tactic.setMentality(mentality);
        TacticSimulationService.CanonicalFormationEvaluation formation = tacticSimulationService
                .canonicalFormation(original.sourceTeam().getId(), original.formation().formation(), tactic);
        return new Participant(original.id(), original.name(), original.sourceTeam(), tactic,
                formation, original.lineup(), original.lineupSource());
    }

    private static PersonalizedTactic copyTactic(PersonalizedTactic source) {
        PersonalizedTactic copy = new PersonalizedTactic();
        copy.setTeamId(source.getTeamId());
        copy.setFirst11(source.getFirst11());
        copy.setTactic(source.getTactic());
        copy.setMentality(source.getMentality());
        copy.setTimeWasting(source.getTimeWasting());
        copy.setInPossession(source.getInPossession());
        copy.setPassingType(source.getPassingType());
        copy.setTempo(source.getTempo());
        copy.setDefensiveLine(source.getDefensiveLine());
        copy.setPressing(source.getPressing());
        copy.setWidth(source.getWidth());
        copy.setDribbling(source.getDribbling());
        copy.setFoulFrequency(source.getFoulFrequency());
        copy.setFoulHardness(source.getFoulHardness());
        copy.setTempoFragmentation(source.getTempoFragmentation());
        copy.setWidePlay(source.getWidePlay());
        copy.setTransition(source.getTransition());
        copy.setRecovery(source.getRecovery());
        copy.setPenaltyTakerId(source.getPenaltyTakerId());
        copy.setFreeKickTakerId(source.getFreeKickTakerId());
        copy.setCornerTakerLeftId(source.getCornerTakerLeftId());
        copy.setCornerTakerRightId(source.getCornerTakerRightId());
        return copy;
    }

    private static List<Contributor> nerfContributors(List<Contributor> contributors, Set<Long> changedIds) {
        return contributors.stream().map(player -> {
            if (!changedIds.contains(player.playerId())) return player;
            return new Contributor(player.playerId(), player.name(), player.position(), player.rating(),
                    Math.max(1, player.finishing() - 2), Math.max(1, player.passing() - 2),
                    Math.max(1, player.vision() - 2), player.fitness(),
                    player.designatedPenaltyTaker(), player.designatedFreeKickTaker());
        }).toList();
    }

    private Team requireTeam(String name) {
        return teamRepository.findAll().stream()
                .filter(team -> name.equalsIgnoreCase(team.getName()))
                .findFirst().orElseThrow(() -> new IllegalStateException("No team named " + name));
    }

    private Team requireInazuma() {
        // The requested football identity is Inazuma Eleven; the current seed calls
        // its club Inazuma Japan. Accept either name so a future rename does not break
        // the diagnostic.
        return teamRepository.findAll().stream()
                .filter(team -> "Inazuma Eleven".equalsIgnoreCase(team.getName())
                        || "Inazuma Japan".equalsIgnoreCase(team.getName()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "No team named Inazuma Eleven or Inazuma Japan"));
    }

    private static int requestedMatches() {
        String raw = System.getProperty(MATCHES_PROPERTY);
        if (raw == null || raw.isBlank()) return DEFAULT_MATCHES;
        final int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("-D" + MATCHES_PROPERTY
                    + " must be an integer; got '" + raw + "'", exception);
        }
        if (value < 1 || value > MAX_MATCHES) {
            throw new IllegalArgumentException("-D" + MATCHES_PROPERTY
                    + " must be between 1 and " + MAX_MATCHES + "; got " + value);
        }
        return value;
    }

    private static MatchScoringDecision decision(
            String fixtureKey, long seed, CanonicalScoreSampler.GoalSample score) {
        return new MatchScoringDecision(
                fixtureKey, seed, ScoreEngineKind.COMPARTMENT_V1,
                ScoreEngineKind.COMPARTMENT_V1.algorithmVersion(),
                ZERO_FINGERPRINT, ONE_FINGERPRINT,
                score.homeGoals(), score.awayGoals(),
                score.homeEffectiveAttack() + score.homeEffectiveProtection(),
                score.awayEffectiveAttack() + score.awayEffectiveProtection(),
                score.homeXg(), score.awayXg(),
                score.homeCollectiveGoals(), score.awayCollectiveGoals(),
                score.homeShooterPlayerId(), score.awayShooterPlayerId(),
                score.homeShooterGoals(), score.awayShooterGoals(),
                score.homeRedCardPlayerId(), score.awayRedCardPlayerId(),
                score.homeShooterShots(), score.awayShooterShots(),
                score.homePassingPlayerId(), score.awayPassingPlayerId(),
                score.homePassingGoals(), score.awayPassingGoals(),
                score.homePassingOpportunities(), score.awayPassingOpportunities(),
                score.homePassingControl(), score.awayPassingControl());
    }

    private static CanonicalMatchEffectEvent effectEvent(MatchEvent event) {
        return new CanonicalMatchEffectEvent(event.getSlotIndex(), event.getMinute(),
                event.getTeamId(), event.getPlayerId(), event.getEventType());
    }

    private Map<PlayerKey, PlayerInfo> playerDirectory(List<Participant> participants) {
        Map<PlayerKey, PlayerInfo> result = new LinkedHashMap<>();
        for (Participant participant : participants) {
            for (Human player : humanRepository.findAllByTeamId(participant.sourceTeam().getId())) {
                result.put(new PlayerKey(participant.id(), player.getId()),
                        new PlayerInfo(player.getName(), participant.name()));
            }
        }
        return result;
    }

    private static String renderReport(
            int matchesPerPairing, List<Participant> participants,
            Map<Long, TeamAggregate> totals, List<MatchupAggregate> matchups,
            List<NerfedPlayer> nerfedPlayers, List<GameResult> results,
            Map<PlayerKey, Integer> goalsByPlayer, Map<PlayerKey, Integer> assistsByPlayer,
            Map<String, Integer> goalTypes, Map<PlayerKey, PlayerInfo> players) {
        StringBuilder out = new StringBuilder();
        int pairingCount = participants.size() * (participants.size() - 1) / 2;
        int matchesPerParticipant = matchesPerPairing * (participants.size() - 1);
        out.append("# Turneu canonic cu șase echipe\n\n")
                .append("- Meciuri per duel: **").append(matchesPerPairing).append("**; total: **")
                .append(matchesPerPairing * pairingCount).append("**.\n")
                .append("- Fiecare participantă joacă **").append(matchesPerParticipant)
                .append("** meciuri, cu gazda alternată în fiecare duel.\n")
                .append("- Parametru: `-D").append(MATCHES_PROPERTY).append("=")
                .append(matchesPerPairing).append("`\n")
                .append("- Motor: `COMPARTMENT_V1`; XI salvat când este complet, altfel fallback-ul ")
                .append("canonic de producție; aceleași roluri, tactici, ")
                .append("rezolvare de marcatori/assist-uri și proiecție de statistici ca în producție.\n")
                .append("- Inazuma Nerfed este o copie numai în memorie: toate atributele celor șase ")
                .append("mijlocași titulari sunt reduse cu 2, cu limita minimă 1 impusă de motor.\n\n");

        out.append("## Configurație\n\n")
                .append("| Echipă | Formație | Sursa XI | Mentalitate | Tempo | Pase | Pressing | Recovery |\n")
                .append("|---|---:|---|---|---|---|---|---|\n");
        participants.forEach(participant -> out.append(tacticRow(participant)));

        out.append("\n## Cei șase jucători nerfed\n\n")
                .append("| Jucător | Poziție | Atribute modificate | Passing | Pace | Ball recovery | Tackling |\n")
                .append("|---|---|---:|---:|---:|---:|---:|\n");
        nerfedPlayers.forEach(player -> {
            PlayerInfo info = players.getOrDefault(new PlayerKey(NERFED_TEAM_ID, player.playerId()),
                    new PlayerInfo("player " + player.playerId(), "Inazuma Nerfed"));
            out.append("| ").append(info.name()).append(" | ").append(player.position().code())
                    .append(" | ").append(player.before().size()).append(" | ")
                    .append(attributeChange(player, PlayerAttribute.PASSING)).append(" | ")
                    .append(attributeChange(player, PlayerAttribute.PACE)).append(" | ")
                    .append(attributeChange(player, PlayerAttribute.BALL_RECOVERY)).append(" | ")
                    .append(attributeChange(player, PlayerAttribute.TACKLING)).append(" |\n");
        });

        out.append("\n## Clasament\n\n")
                .append("| Loc | Echipă | M | V | E | Î | GF | GA | GD | Pct | Goluri/meci |\n")
                .append("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        List<TeamAggregate> standings = totals.values().stream().sorted(Comparator
                .comparingLong(TeamAggregate::points).reversed()
                .thenComparing(Comparator.comparingLong(TeamAggregate::goalDifference).reversed())
                .thenComparing(Comparator.comparingLong((TeamAggregate team) -> team.goalsFor).reversed())
                .thenComparing(team -> team.participant.name())).toList();
        for (int index = 0; index < standings.size(); index++) {
            out.append(resultRow(index + 1, standings.get(index)));
        }

        out.append("\n## Dueluri directe\n\n")
                .append("| Duel | M | Victorii prima | Egaluri | Victorii a doua | Goluri |\n")
                .append("|---|---:|---:|---:|---:|---:|\n");
        matchups.forEach(matchup -> out.append("| ").append(matchup.first.name()).append(" – ")
                .append(matchup.second.name()).append(" | ").append(matchup.matches).append(" | ")
                .append(matchup.firstWins).append(" | ").append(matchup.draws).append(" | ")
                .append(matchup.secondWins).append(" | ").append(matchup.firstGoals).append("–")
                .append(matchup.secondGoals).append(" |\n"));

        GameResult highest = results.stream().max(Comparator
                .comparingInt(GameResult::totalGoals)
                .thenComparingInt(GameResult::margin)).orElseThrow();
        GameResult lowest = results.stream().min(Comparator
                .comparingInt(GameResult::totalGoals)
                .thenComparingInt(GameResult::margin)).orElseThrow();
        out.append("\n## Extreme și scoruri frecvente\n\n")
                .append("- Scor maxim (cele mai multe goluri): **").append(highest.describe()).append("**\n")
                .append("- Scor minim (cele mai puține goluri): **").append(lowest.describe()).append("**\n\n")
                .append("| Duel | Scor (prima–a doua) | Meciuri | Procent |\n")
                .append("|---|---:|---:|---:|\n");
        matchups.forEach(matchup -> sortedEntries(matchup.scorelines).stream().limit(5)
                .forEach(entry -> out.append("| ").append(matchup.first.name()).append(" – ")
                        .append(matchup.second.name()).append(" | ").append(entry.getKey())
                        .append(" | ").append(entry.getValue()).append(" | ")
                        .append(pct(entry.getValue(), matchup.matches)).append(" |\n")));

        out.append("\n## Mecanicile speciale\n\n")
                .append("| Echipă | Goluri colective | SHOOTER șuturi | SHOOTER goluri | ")
                .append("PASSING ocazii | PASSING goluri | Control PASSING mediu | Roșii care au redus puterea |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        participants.forEach(participant -> out.append(specialRow(totals.get(participant.id()))));

        out.append("\n## Statistici medii pe meci\n\n")
                .append("| Statistică");
        participants.forEach(participant -> out.append(" | ").append(participant.name()));
        out.append(" |\n|---");
        participants.forEach(participant -> out.append("|---:"));
        out.append("|\n");
        for (Metric metric : Metric.values()) {
            out.append("| ").append(metric.label);
            participants.forEach(participant -> {
                TeamAggregate team = totals.get(participant.id());
                out.append(" | ").append(avg(team.metric(metric), team.matches, metric.scale));
            });
            out.append(" |\n");
        }

        out.append("\n## Top marcatori\n\n")
                .append(leaderboard(goalsByPlayer, players, matchesPerParticipant, "Goluri"))
                .append("\n## Top assist-uri\n\n")
                .append(leaderboard(assistsByPlayer, players, matchesPerParticipant, "Assist-uri"));

        out.append("\n## Tipurile golurilor\n\n")
                .append("| Tip | Goluri | Procent |\n|---|---:|---:|\n");
        int allGoals = goalTypes.values().stream().mapToInt(Integer::intValue).sum();
        sortedEntries(goalTypes).forEach(entry -> out.append("| ").append(entry.getKey())
                .append(" | ").append(entry.getValue()).append(" | ")
                .append(pct(entry.getValue(), allGoals)).append(" |\n"));
        return out.toString();
    }

    private static String attributeChange(NerfedPlayer player, PlayerAttribute attribute) {
        return player.before().get(attribute) + "→" + player.after().get(attribute);
    }

    private static String tacticRow(Participant setup) {
        PersonalizedTactic tactic = setup.tactic();
        return "| " + setup.name() + " | " + setup.formation().formation()
                + " | " + setup.lineupSource() + " | " + tactic.getMentality() + " | " + tactic.getTempo()
                + " | " + tactic.getPassingType() + " | " + tactic.getPressing()
                + " | " + tactic.getRecovery() + " |\n";
    }

    private static String resultRow(int place, TeamAggregate team) {
        return "| " + place + " | " + team.participant.name() + " | " + team.matches
                + " | " + team.wins + " | " + team.draws + " | "
                + team.losses + " | " + team.goalsFor + " | " + team.goalsAgainst + " | "
                + team.goalDifference() + " | " + team.points() + " | "
                + avg(team.goalsFor, team.matches, 1.0) + " |\n";
    }

    private static String specialRow(TeamAggregate team) {
        return "| " + team.participant.name() + " | " + team.collectiveGoals + " | "
                + team.shooterShots + " | " + team.shooterGoals + " | "
                + team.passingOpportunities + " | " + team.passingGoals + " | "
                + percentAverage(team.passingControl, team.matches) + "% | "
                + team.engineRedCards + " |\n";
    }

    private static String leaderboard(Map<PlayerKey, Integer> values, Map<PlayerKey, PlayerInfo> players,
                                      int matches, String valueLabel) {
        StringBuilder out = new StringBuilder("| # | Jucător | Echipă | ")
                .append(valueLabel).append(" | Per meci |\n|---:|---|---|---:|---:|\n");
        List<Map.Entry<PlayerKey, Integer>> sorted = values.entrySet().stream()
                .sorted(Map.Entry.<PlayerKey, Integer>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().toString())).limit(20).toList();
        for (int index = 0; index < sorted.size(); index++) {
            Map.Entry<PlayerKey, Integer> entry = sorted.get(index);
            PlayerInfo player = players.getOrDefault(entry.getKey(),
                    new PlayerInfo("player " + entry.getKey(), "unknown"));
            out.append("| ").append(index + 1).append(" | ").append(player.name())
                    .append(" | ").append(player.team()).append(" | ").append(entry.getValue())
                    .append(" | ").append(avg(entry.getValue(), matches, 1.0)).append(" |\n");
        }
        if (sorted.isEmpty()) out.append("| – | – | – | 0 | 0.00 |\n");
        return out.toString();
    }

    private static <K> List<Map.Entry<K, Integer>> sortedEntries(Map<K, Integer> values) {
        return values.entrySet().stream()
                .sorted(Map.Entry.<K, Integer>comparingByValue().reversed()
                        .thenComparing(entry -> String.valueOf(entry.getKey())))
                .toList();
    }

    private static String avg(double total, long count, double scale) {
        return String.format(Locale.ROOT, "%.2f", count == 0 ? 0.0 : total / count / scale);
    }

    private static String percentAverage(double total, long count) {
        return String.format(Locale.ROOT, "%.2f", count == 0 ? 0.0 : total * 100.0 / count);
    }

    private static String pct(long count, long total) {
        return String.format(Locale.ROOT, "%.1f%%", total == 0 ? 0.0 : count * 100.0 / total);
    }

    /** Align Markdown table columns for monospaced console output. */
    private static String renderConsoleReport(String markdown) {
        String[] lines = markdown.split("\\R", -1);
        StringBuilder out = new StringBuilder(markdown.length());
        for (int index = 0; index < lines.length;) {
            if (!isTableLine(lines[index])) {
                out.append(lines[index++]);
                if (index < lines.length) out.append('\n');
                continue;
            }

            List<List<String>> rows = new ArrayList<>();
            while (index < lines.length && isTableLine(lines[index])) {
                rows.add(tableCells(lines[index++]));
            }
            appendAlignedTable(out, rows);
            if (index < lines.length) out.append('\n');
        }
        return out.toString();
    }

    private static boolean isTableLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("|") && trimmed.endsWith("|");
    }

    private static List<String> tableCells(String line) {
        String trimmed = line.trim();
        String body = trimmed.substring(1, trimmed.length() - 1);
        return java.util.Arrays.stream(body.split("\\|", -1))
                .map(String::trim)
                .toList();
    }

    private static void appendAlignedTable(StringBuilder out, List<List<String>> rows) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        int separatorIndex = rows.size() > 1 && isSeparatorRow(rows.get(1)) ? 1 : -1;
        int[] widths = new int[columns];
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            if (rowIndex == separatorIndex) continue;
            List<String> row = rows.get(rowIndex);
            for (int column = 0; column < row.size(); column++) {
                widths[column] = Math.max(widths[column], row.get(column).length());
            }
        }
        for (int column = 0; column < columns; column++) widths[column] = Math.max(3, widths[column]);

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (rowIndex > 0) out.append('\n');
            out.append('|');
            for (int column = 0; column < columns; column++) {
                String cell = column < row.size() ? row.get(column) : "";
                boolean rightAligned = separatorIndex >= 0
                        && column < rows.get(separatorIndex).size()
                        && rows.get(separatorIndex).get(column).endsWith(":");
                String rendered = rowIndex == separatorIndex
                        ? separator(widths[column], rightAligned)
                        : pad(cell, widths[column], rightAligned);
                out.append(' ').append(rendered).append(" |");
            }
        }
    }

    private static boolean isSeparatorRow(List<String> cells) {
        return !cells.isEmpty() && cells.stream().allMatch(cell -> cell.matches(":?-{3,}:?"));
    }

    private static String separator(int width, boolean rightAligned) {
        return rightAligned ? "-".repeat(width - 1) + ":" : "-".repeat(width);
    }

    private static String pad(String value, int width, boolean rightAligned) {
        int padding = Math.max(0, width - value.length());
        return rightAligned ? " ".repeat(padding) + value : value + " ".repeat(padding);
    }

    private record Participant(long id, String name, Team sourceTeam, PersonalizedTactic tactic,
                               TacticSimulationService.CanonicalFormationEvaluation formation,
                               Lineup lineup, LineupAdapter.Source lineupSource) {}

    private record Pairing(Participant first, Participant second) {}

    private record NerfedParticipant(Participant participant, List<NerfedPlayer> players) {}

    private record NerfedPlayer(long playerId, PlayerPosition position,
                                Map<PlayerAttribute, Integer> before,
                                Map<PlayerAttribute, Integer> after) {}

    private record PlayerKey(long participantId, long playerId) {}

    private record PlayerInfo(String name, String team) {}

    private record GameResult(int number, String home, String away, int homeGoals, int awayGoals) {
        int totalGoals() { return homeGoals + awayGoals; }
        int margin() { return Math.abs(homeGoals - awayGoals); }
        String describe() { return home + " " + homeGoals + "–" + awayGoals + " " + away; }
    }

    private static final class MatchupAggregate {
        private final Participant first;
        private final Participant second;
        private final Map<String, Integer> scorelines = new TreeMap<>();
        private long matches;
        private long firstWins;
        private long draws;
        private long secondWins;
        private long firstGoals;
        private long secondGoals;

        private MatchupAggregate(Participant first, Participant second) {
            this.first = first;
            this.second = second;
        }

        private void add(int firstGoals, int secondGoals) {
            matches++;
            this.firstGoals += firstGoals;
            this.secondGoals += secondGoals;
            if (firstGoals > secondGoals) firstWins++;
            else if (firstGoals == secondGoals) draws++;
            else secondWins++;
            scorelines.merge(firstGoals + "-" + secondGoals, 1, Integer::sum);
        }
    }

    private enum Metric {
        POSSESSION("Posesie (%)", MatchStats::getHomePossession, MatchStats::getAwayPossession, 1.0),
        SHOTS("Șuturi", MatchStats::getHomeShots, MatchStats::getAwayShots, 1.0),
        SHOTS_ON_TARGET("Șuturi pe poartă", MatchStats::getHomeShotsOnTarget, MatchStats::getAwayShotsOnTarget, 1.0),
        SHOTS_BLOCKED("Șuturi blocate", MatchStats::getHomeShotsBlocked, MatchStats::getAwayShotsBlocked, 1.0),
        XG("xG", MatchStats::getHomeXg, MatchStats::getAwayXg, 100.0),
        BIG_CHANCES("Ocazii mari", MatchStats::getHomeBigChances, MatchStats::getAwayBigChances, 1.0),
        BIG_CHANCES_MISSED("Ocazii mari ratate", MatchStats::getHomeBigChancesMissed, MatchStats::getAwayBigChancesMissed, 1.0),
        PASSES("Pase", MatchStats::getHomePasses, MatchStats::getAwayPasses, 1.0),
        PASS_ACCURACY("Precizia paselor (%)", MatchStats::getHomePassAccuracy, MatchStats::getAwayPassAccuracy, 1.0),
        CORNERS("Cornere", MatchStats::getHomeCorners, MatchStats::getAwayCorners, 1.0),
        FOULS("Faulturi", MatchStats::getHomeFouls, MatchStats::getAwayFouls, 1.0),
        YELLOW_CARDS("Cartonașe galbene", MatchStats::getHomeYellowCards, MatchStats::getAwayYellowCards, 1.0),
        DISPLAYED_RED_CARDS("Cartonașe roșii afișate", MatchStats::getHomeRedCards, MatchStats::getAwayRedCards, 1.0),
        OFFSIDES("Offside-uri", MatchStats::getHomeOffsides, MatchStats::getAwayOffsides, 1.0),
        TACKLES("Tackling-uri", MatchStats::getHomeTackles, MatchStats::getAwayTackles, 1.0),
        INTERCEPTIONS("Intercepții", MatchStats::getHomeInterceptions, MatchStats::getAwayInterceptions, 1.0),
        CLEARANCES("Degajări", MatchStats::getHomeClearances, MatchStats::getAwayClearances, 1.0),
        SAVES("Parade", MatchStats::getHomeSaves, MatchStats::getAwaySaves, 1.0),
        CROSSES("Centrări", MatchStats::getHomeCrosses, MatchStats::getAwayCrosses, 1.0),
        ACCURATE_CROSSES("Centrări precise", MatchStats::getHomeCrossesAccurate, MatchStats::getAwayCrossesAccurate, 1.0),
        DUELS_WON("Dueluri câștigate", MatchStats::getHomeDuelsWon, MatchStats::getAwayDuelsWon, 1.0),
        AERIAL_DUELS_WON("Dueluri aeriene câștigate", MatchStats::getHomeAerialDuelsWon, MatchStats::getAwayAerialDuelsWon, 1.0),
        EFFECTIVE_ATTACK("Atac efectiv canonic", null, null, 1.0),
        EFFECTIVE_PROTECTION("Protecție efectivă canonică", null, null, 1.0);

        private final String label;
        private final ToIntFunction<MatchStats> homeValue;
        private final ToIntFunction<MatchStats> awayValue;
        private final double scale;

        Metric(String label, ToIntFunction<MatchStats> homeValue,
               ToIntFunction<MatchStats> awayValue, double scale) {
            this.label = label;
            this.homeValue = homeValue;
            this.awayValue = awayValue;
            this.scale = scale;
        }
    }

    private static final class TeamAggregate {
        private final Participant participant;
        private final Map<Metric, Double> metrics = new LinkedHashMap<>();
        private long matches;
        private long wins;
        private long draws;
        private long losses;
        private long goalsFor;
        private long goalsAgainst;
        private int minGoalsFor = Integer.MAX_VALUE;
        private int maxGoalsFor = Integer.MIN_VALUE;
        private long collectiveGoals;
        private long shooterShots;
        private long shooterGoals;
        private long passingOpportunities;
        private long passingGoals;
        private double passingControl;
        private long engineRedCards;

        private TeamAggregate(Participant participant) {
            this.participant = participant;
            for (Metric metric : Metric.values()) metrics.put(metric, 0.0);
        }

        private long points() {
            return wins * 3 + draws;
        }

        private long goalDifference() {
            return goalsFor - goalsAgainst;
        }

        private void add(MatchStats stats, boolean home, int goalsFor, int goalsAgainst,
                         CanonicalScoreSampler.GoalSample score) {
            matches++;
            this.goalsFor += goalsFor;
            this.goalsAgainst += goalsAgainst;
            minGoalsFor = Math.min(minGoalsFor, goalsFor);
            maxGoalsFor = Math.max(maxGoalsFor, goalsFor);
            if (goalsFor > goalsAgainst) wins++;
            else if (goalsFor == goalsAgainst) draws++;
            else losses++;

            for (Metric metric : Metric.values()) {
                if (metric.homeValue == null) continue;
                metrics.merge(metric, (double) (home
                        ? metric.homeValue.applyAsInt(stats)
                        : metric.awayValue.applyAsInt(stats)), Double::sum);
            }
            if (home) {
                collectiveGoals += score.homeCollectiveGoals();
                shooterShots += score.homeShooterShots();
                shooterGoals += score.homeShooterGoals();
                passingOpportunities += score.homePassingOpportunities();
                passingGoals += score.homePassingGoals();
                passingControl += score.homePassingControl();
                engineRedCards += score.homeRedCardPlayerId() == null ? 0 : 1;
                metrics.merge(Metric.EFFECTIVE_ATTACK, score.homeEffectiveAttack(), Double::sum);
                metrics.merge(Metric.EFFECTIVE_PROTECTION, score.homeEffectiveProtection(), Double::sum);
            } else {
                collectiveGoals += score.awayCollectiveGoals();
                shooterShots += score.awayShooterShots();
                shooterGoals += score.awayShooterGoals();
                passingOpportunities += score.awayPassingOpportunities();
                passingGoals += score.awayPassingGoals();
                passingControl += score.awayPassingControl();
                engineRedCards += score.awayRedCardPlayerId() == null ? 0 : 1;
                metrics.merge(Metric.EFFECTIVE_ATTACK, score.awayEffectiveAttack(), Double::sum);
                metrics.merge(Metric.EFFECTIVE_PROTECTION, score.awayEffectiveProtection(), Double::sum);
            }
        }

        private double metric(Metric metric) {
            return metrics.getOrDefault(metric, 0.0);
        }
    }
}
