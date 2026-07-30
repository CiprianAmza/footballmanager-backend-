package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.model.Loan;
import com.footballmanagergamesimulator.model.Transfer;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.LoanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferRepository;
import com.footballmanagergamesimulator.service.EndOfSeasonProcessor;
import com.footballmanagergamesimulator.service.MatchSimulationOrchestrator;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.SeasonTransitionService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.testutil.MarkdownTable;
import com.footballmanagergamesimulator.transfermarket.CompositeTransferStrategy;
import com.footballmanagergamesimulator.transfermarket.SquadDepthChart;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.AfterEach;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plays one season, runs one real transfer window, and writes a human-readable
 * before/after of every club in every league to
 * {@code target/transfer-window-report.md} (and stdout).
 *
 * <p>For each club: the best XI it would field <b>before</b> the window, every
 * player in and out with age / rating / fee, and the best XI <b>after</b>. The XI
 * is the match engine's own selection, not a re-implementation, so the report shows
 * the eleven that will actually play.
 *
 * <p>Written to answer a specific question by inspection rather than by assertion:
 * squads full of 200+ players were carrying one slot rated in the 30s, and the
 * window was buying a third striker instead of fixing it. The per-club weak-link
 * section makes that visible at a glance.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "bootstrap.seed=20260528",
        "transfer.window.report=true"
})
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS)
@DisplayName("Transfer window: full before/after report per league and club")
class TransferWindowReportIT {

    @Autowired private CompetitionController competitionController;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoMatchRepository matchRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TransferRepository transferRepository;
    @Autowired private LoanRepository loanRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private SeasonTransitionService seasonTransitionService;
    @Autowired private CompositeTransferStrategy strategy;
    @Autowired private MatchSimulationService matchSimulationService;
    @Autowired private MatchSimulationOrchestrator matchSimulationOrchestrator;
    @Autowired private MatchEngineConfig matchEngineConfig;
    @Autowired private com.footballmanagergamesimulator.service.EndOfSeasonProcessor endOfSeasonProcessor;

    private static final long SEED = 20260528L;
    private static final long LEAGUE_TYPE_ID = 1L;
    private static final long SECOND_LEAGUE_TYPE_ID = 3L;

    /** Order the XI reads in: keeper, defence, midfield, attack. */
    private static final List<String> LINE_ORDER = List.of("GK", "DL", "DC", "DR", "ML", "MC", "MR", "ST");

    @AfterEach
    void restoreProductionRng() {
        matchSimulationService.setRandomForTesting(new Random());
        strategy.setRandomForTesting(new Random());
    }

    /**
     * How many seasons to play, one after another, in the same world. Override with
     * {@code -Dtransfer.report.seasons=5}.
     *
     * <p>A single window says little on its own: the free-agent pool is empty in
     * season one, squads have not yet aged or decayed, and nothing has had time to
     * compound. Successive windows run on the same players — no reset between them —
     * so the report shows a world evolving rather than a snapshot.
     */
    private static int seasonsToPlay() {
        return Integer.getInteger("transfer.report.seasons", 1);
    }

    @Test
    @DisplayName("Report: XI before → transfers in/out → XI after, for every club, per season")
    void reportTransferWindows() throws IOException {
        List<Competition> leagues = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == LEAGUE_TYPE_ID || c.getTypeId() == SECOND_LEAGUE_TYPE_ID)
                .sorted(Comparator.comparingLong(Competition::getId))
                .toList();
        assertThat(leagues).as("bootstrap must have produced leagues").isNotEmpty();

        int seasons = seasonsToPlay();
        StringBuilder all = new StringBuilder();
        all.append("# Transfer window report — ").append(seasons).append(" season(s)\n\n");
        all.append("- seed: ").append(SEED).append('\n');
        all.append("- leagues: ").append(leagues.size()).append('\n');
        all.append("\nEach season below is played, its window run, and the world carried\n");
        all.append("forward with the same players into the next — squads age, contracts\n");
        all.append("expire and free agents accumulate exactly as they would in a career.\n");

        for (int i = 0; i < seasons; i++) {
            int season = (int) roundRepository.findById(1L).orElseThrow().getSeason();
            System.out.println("=== SEASON " + season + " (" + (i + 1) + "/" + seasons + ") ===");
            all.append(reportSeason(season, leagues));
            if (i < seasons - 1) {
                // Roll the world forward: fixtures, regens, standings reset. Without it
                // the next iteration would replay the same season.
                seasonTransitionService.processNewSeasonSetup(season);
            }
        }

        Path report = Path.of("target", "transfer-window-report.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, all.toString());
        System.out.println("=== written to " + report.toAbsolutePath() + " ===");
    }

    /** Plays one season, runs its window, and returns that season's report section. */
    private String reportSeason(int season, List<Competition> leagues) {

        // A season has to be played so prize money, form and standings are real —
        // budgets are derived from them and the window depends on the budgets.
        com.footballmanagergamesimulator.service.MatchRoundSimulator.resetScoreEngineTally();
        matchSimulationService.setRandomForTesting(new Random(SEED));
        strategy.setRandomForTesting(new Random(SEED));
        long tSim = System.currentTimeMillis();
        int matchdays = 0;
        for (Competition league : leagues) {
            matchdays += simulateLeague(league.getId(), season);
        }
        long simMs = System.currentTimeMillis() - tSim;
        // Audit the authoritative scorer (plus any explicit admin overrides).
        System.out.println("=== SCORE ENGINES: "
                + com.footballmanagergamesimulator.service.MatchRoundSimulator.scoreEngineTally() + " ===");

        Map<Long, Team> teamsById = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getId, t -> t));

        // Final tables, captured here and not later: processNewSeasonSetup wipes
        // TeamCompetitionDetail for the new campaign, so after the window there is
        // nothing left to print. These are also the standings the prize pools are
        // shared out against, so the report can show what each club actually earned.
        String standings = renderStandings(leagues, teamsById);

        // ---- THE WINDOW ------------------------------------------------
        // Real budgets. An earlier revision funded every club equally to see what the
        // market would do unconstrained; that was a diagnostic, not a setting. Flat
        // budgets let a small club outspend a big one, which inverts the hierarchy the
        // economy exists to express.
        strategy.setRandomForTesting(new Random(SEED));
        long tWindow = System.currentTimeMillis();
        seasonTransitionService.processEndOfSeason(season);
        long windowMs = System.currentTimeMillis() - tWindow;
        System.out.println("=== TIMING: leagues=" + leagues.size()
                + " matchdays=" + matchdays
                + " simulation=" + simMs + "ms"
                + " transferWindow=" + windowMs + "ms"
                + " total=" + (simMs + windowMs) + "ms ===");

        // ---- BEFORE, taken from inside the window ----------------------
        // The baseline is the squads the market itself planned against: after
        // training, ageing, retirements and contract expiries, before any transfer.
        // Snapshotting before processEndOfSeason instead would fold a whole season of
        // attrition into the "before" column — every surviving player a point stronger
        // from training, retired keepers still in the XI — and the transfers would be
        // lost in it. This is the same object the strategies read, so it cannot drift.
        // Published by refreshTeamBudgets inside the window, so it is the same
        // arithmetic the market spent against and cannot drift from it.
        Map<Long, EndOfSeasonProcessor.BudgetBreakdown> budgets =
                endOfSeasonProcessor.lastBudgetBreakdowns();

        Map<Long, SquadDepthChart> preMarket = endOfSeasonProcessor.lastPreMarketDepthCharts();
        assertThat(preMarket)
                .as("the window must publish the squads it planned against")
                .isNotEmpty();
        Map<Long, List<Starter>> xiBefore = new LinkedHashMap<>();
        for (Team team : sortedTeams(teamsById.values())) {
            xiBefore.put(team.getId(), startersFromChart(preMarket.get(team.getId())));
        }

        List<Transfer> transfers = transferRepository.findAllBySeasonNumber(season);

        // ---- AFTER -----------------------------------------------------
        // Squad caches only. invalidateAllRatingCaches() would also re-derive each
        // manager's formation, and the "before" XI was selected under the cached one —
        // the two sides would then be built under different formations and the delta
        // would include tactical churn that no transfer caused. It showed up as clubs
        // that bought two players and came out weaker.
        for (Long teamId : teamsById.keySet()) {
            matchSimulationOrchestrator.invalidateSquadCaches(teamId);
        }
        Map<Long, List<Starter>> xiAfter = new LinkedHashMap<>();
        for (Team team : sortedTeams(teamsById.values())) {
            xiAfter.put(team.getId(), bestEleven(team.getId()));
        }

        // Loans move players inside the same window, after the market, and never create
        // a Transfer row. Leaving them out made the report lie: a club's XI could lose a
        // starter who appeared in no OUT list, with nothing to explain where he went.
        List<Loan> loans = loanRepository.findAllByStatus("active").stream()
                .filter(loan -> loan.getStartSeason() == season + 1)
                .toList();
        Map<Long, List<Loan>> loanedOutBy = loans.stream()
                .collect(Collectors.groupingBy(Loan::getParentTeamId));
        Map<Long, List<Loan>> loanedInBy = loans.stream()
                .collect(Collectors.groupingBy(Loan::getLoanTeamId));

        Map<Long, List<Transfer>> incoming = transfers.stream()
                .collect(Collectors.groupingBy(Transfer::getBuyTeamId));
        Map<Long, List<Transfer>> outgoing = transfers.stream()
                .collect(Collectors.groupingBy(Transfer::getSellTeamId));

        // Transfer does not record a position, so resolve it from the player. Without
        // it the report cannot answer the question it exists for: did the club sign
        // for the position it was actually weak at?
        Map<Long, String> positionByPlayerId = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE).stream()
                .filter(h -> h.getPosition() != null)
                .collect(Collectors.toMap(Human::getId, Human::getPosition, (a, b) -> a));

        // Current squad membership, read once: "he left" vs "he was dropped".
        Map<Long, Human> squadNow = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE).stream()
                .filter(player -> player.getTeamId() != null)
                .collect(Collectors.toMap(Human::getId, player -> player, (a, b) -> a));

        // ---- RENDER ----------------------------------------------------
        StringBuilder md = new StringBuilder();
        md.append("\n\n\n# ============ SEASON ").append(season).append(" ============\n\n");

        md.append("- leagues: ").append(leagues.size())
                .append(", clubs: ").append(teamsById.size())
                .append(", completed transfers: ").append(transfers.size()).append("\n\n");
        md.append("**XI before** is the squad as the market saw it — after training, ageing,\n");
        md.append("retirements and contract expiries, before any transfer — so the difference\n");
        md.append("between the two XIs is what the transfer market did, and nothing else.\n");
        md.append("(AI loans run after the market, so they are the one other thing in the delta.)\n\n");
        md.append(standings);
        md.append("XI lines read `GK | defence | midfield | attack`, each as `Name (rating)`.\n");
        md.append("`WEAK LINK` flags a starter more than ")
                .append((int) com.footballmanagergamesimulator.transfermarket.SquadDepthChart.CRITICAL_DEFICIT)
                .append(" rating points below his own club's XI average — the market is\n");
        md.append("supposed to replace exactly these.\n\n");

        int weakBefore = 0;
        int weakAfter = 0;

        for (Competition league : leagues) {
            List<Team> clubs = sortedTeams(teamsById.values().stream()
                    .filter(t -> t.getCompetitionId() == league.getId())
                    .toList());
            if (clubs.isEmpty()) continue;

            md.append("\n---\n\n## ").append(league.getName())
                    .append(" (id ").append(league.getId()).append(")\n");

            for (Team club : clubs) {
                List<Starter> before = xiBefore.getOrDefault(club.getId(), List.of());
                List<Starter> after = xiAfter.getOrDefault(club.getId(), List.of());
                List<Transfer> in = incoming.getOrDefault(club.getId(), List.of());
                List<Transfer> out = outgoing.getOrDefault(club.getId(), List.of());

                weakBefore += weakLinks(before).size();
                weakAfter += weakLinks(after).size();

                md.append("\n### ").append(club.getName())
                        .append("  ·  XI avg ").append(fmt(average(before)))
                        .append(" → ").append(fmt(average(after)))
                        // The budget the market actually spent, not the balance the club
                        // carried in. teamsById is read before processEndOfSeason, so its
                        // transferBudget is the opening figure — printing that made a club
                        // that finished lower look richer than one above it, when the gap
                        // was last season's leftovers and this season's prize had not
                        // landed yet. The breakdown below shows both.
                        .append("  ·  budget ").append(money(budgetOf(budgets, club)))
                        .append("\n\n");
                md.append(budgetLine(budgets.get(club.getId())));

                md.append("**XI before**\n\n").append(renderXi(before)).append('\n');
                for (Starter weak : weakLinks(before)) {
                    md.append("  - `WEAK LINK` ").append(weak.position).append(' ')
                            .append(weak.name).append(" (").append(fmt(weak.rating)).append(")\n");
                }

                List<Loan> loanOut = loanedOutBy.getOrDefault(club.getId(), List.of());
                List<Loan> loanIn = loanedInBy.getOrDefault(club.getId(), List.of());

                if (in.isEmpty() && out.isEmpty() && loanOut.isEmpty() && loanIn.isEmpty()) {
                    md.append("\n*no transfers*\n");
                } else {
                    if (!out.isEmpty()) {
                        md.append("\n**OUT**\n\n");
                        for (Transfer t : sortedByFee(out)) {
                            md.append("  - ").append(t.getPlayerName())
                                    .append(" [").append(position(positionByPlayerId, t)).append(']')
                                    .append(" → ").append(t.getBuyTeamName())
                                    .append("  (age ").append(t.getPlayerAge())
                                    .append(", rating ").append(fmt(t.getRating()))
                                    .append(", fee ").append(money(t.getPlayerTransferValue()))
                                    .append(")\n");
                        }
                    }
                    if (!in.isEmpty()) {
                        md.append("\n**IN**\n\n");
                        for (Transfer t : sortedByFee(in)) {
                            md.append("  - ").append(t.getPlayerName())
                                    .append(" [").append(position(positionByPlayerId, t)).append(']')
                                    .append(" ← ").append(t.getSellTeamName())
                                    .append("  (age ").append(t.getPlayerAge())
                                    .append(", rating ").append(fmt(t.getRating()))
                                    .append(", fee ").append(money(t.getPlayerTransferValue()))
                                    .append(")\n");
                        }
                    }
                }

                if (!loanOut.isEmpty()) {
                    md.append("\n**OUT ON LOAN**\n\n");
                    for (Loan loan : loanOut) {
                        md.append("  - ").append(loan.getPlayerName())
                                .append(" → ").append(loan.getLoanTeamName())
                                .append("  (fee ").append(money(loan.getLoanFee())).append(")\n");
                    }
                }
                if (!loanIn.isEmpty()) {
                    md.append("\n**IN ON LOAN**\n\n");
                    for (Loan loan : loanIn) {
                        md.append("  - ").append(loan.getPlayerName())
                                .append(" ← ").append(loan.getParentTeamName())
                                .append("  (fee ").append(money(loan.getLoanFee())).append(")\n");
                    }
                }

                md.append("\n**XI after**\n\n").append(renderXi(after)).append('\n');

                // A starter can drop out of the XI without leaving the club at all: the
                // engine picks on match value — attributes weighted for the slot, times
                // familiarity, morale and fitness — while this report prints raw rating.
                // A 241 out of form loses his place to a 212 in it, and with only the
                // rating on show that reads as a player vanishing.
                java.util.Set<Long> afterIds = after.stream()
                        .map(Starter::playerId).collect(Collectors.toSet());
                for (Starter dropped : before) {
                    if (afterIds.contains(dropped.playerId())) continue;
                    // Must compare against THIS club. squadNow is indexed over every
                    // player in the game, so a sold player is still in it — at his new
                    // club — and a plain null check labelled him "still at club" on the
                    // very line under the OUT entry that says he was sold.
                    Human stillHere = squadNow.get(dropped.playerId());
                    if (stillHere == null) continue;
                    if (!Objects.equals(stillHere.getTeamId(), club.getId())) continue;
                    md.append("  - `DROPPED, STILL AT CLUB` ").append(dropped.position()).append(' ')
                            .append(dropped.name()).append(" (rating ").append(fmt(dropped.rating()))
                            .append(", fitness ").append(fmt(stillHere.getFitness()))
                            .append(", morale ").append(fmt(stillHere.getMorale())).append(")\n");
                }

                for (Starter weak : weakLinks(after)) {
                    md.append("  - `WEAK LINK STILL` ").append(weak.position).append(' ')
                            .append(weak.name).append(" (").append(fmt(weak.rating)).append(")\n");
                }
            }
        }

        // ---- Movers -----------------------------------------------------
        Map<Long, String> leagueNameByCompetition = leagues.stream()
                .collect(Collectors.toMap(Competition::getId, Competition::getName, (a, b) -> a));
        List<Mover> movers = new ArrayList<>();
        for (Team club : sortedTeams(teamsById.values())) {
            double before = average(xiBefore.getOrDefault(club.getId(), List.of()));
            double after = average(xiAfter.getOrDefault(club.getId(), List.of()));
            if (before == 0 && after == 0) continue;
            movers.add(new Mover(
                    club.getName(),
                    leagueNameByCompetition.getOrDefault(club.getCompetitionId(), "—"),
                    club.getStrategy() == null ? 0L : club.getStrategy(),
                    before, after,
                    incoming.getOrDefault(club.getId(), List.of()).size(),
                    outgoing.getOrDefault(club.getId(), List.of()).size(),
                    loanedInBy.getOrDefault(club.getId(), List.of()).size(),
                    loanedOutBy.getOrDefault(club.getId(), List.of()).size()));
        }
        // Deterministic ties: same delta → alphabetical, so two runs read identically.
        List<Mover> byDelta = movers.stream()
                .sorted(Comparator.comparingDouble(Mover::delta).reversed()
                        .thenComparing(Mover::club))
                .toList();
        List<Mover> risers = byDelta.stream().filter(m -> m.delta() > 0.05).limit(15).toList();
        List<Mover> fallers = byDelta.stream()
                .filter(m -> m.delta() < -0.05)
                .sorted(Comparator.comparingDouble(Mover::delta).thenComparing(Mover::club))
                .limit(15)
                .toList();

        md.append("\n---\n\n## Biggest XI gains\n\n");
        md.append(moverTable(risers));

        md.append("\n## Biggest XI losses\n\n");
        md.append(moverTable(fallers));

        // Same ranking, split by strategy. A club built to sell its best players is
        // meant to come out weaker, so a world-wide table mixes outcomes that mean
        // opposite things; only the per-strategy view says whether a strategy is
        // behaving as designed or misbehaving.
        md.append("\n---\n\n## Movers by strategy\n\n");
        for (long strategyId = 1L; strategyId <= 6L; strategyId++) {
            final long id = strategyId;
            List<Mover> ofStrategy = byDelta.stream().filter(m -> m.strategyId() == id).toList();
            if (ofStrategy.isEmpty()) continue;
            md.append("\n### ")
                    .append(com.footballmanagergamesimulator.testutil.TransferMarketDiagnostics
                            .strategyName(strategyId))
                    .append("\n\n");
            // Every club running this strategy, best delta first — including the ones
            // that did not move at all. Filtering to winners and losers hid the
            // majority: most clubs come out of a window unchanged, and that is itself
            // the answer to "is this strategy doing anything".
            md.append(moverTable(ofStrategy));
        }

        // Who comes out of the window better or worse, grouped by the strategy the club
        // runs. The point of the breakdown: a strategy built to sell its best players is
        // supposed to end up weaker on the pitch and richer in the bank, so a raw
        // improved/worsened count says nothing until it is split this way.
        record StrategyOutcome(int clubs, int improved, int worsened, double deltaTotal,
                               int in, int out) {}
        Map<Long, int[]> tally = new java.util.TreeMap<>();   // clubs, improved, worsened, in, out
        Map<Long, Double> deltaByStrategy = new java.util.TreeMap<>();
        for (Team club : sortedTeams(teamsById.values())) {
            long strategyId = club.getStrategy() == null ? 0L : club.getStrategy();
            double delta = average(xiAfter.getOrDefault(club.getId(), List.of()))
                    - average(xiBefore.getOrDefault(club.getId(), List.of()));
            int[] row = tally.computeIfAbsent(strategyId, key -> new int[5]);
            row[0]++;
            if (delta > 0.05) row[1]++;
            else if (delta < -0.05) row[2]++;
            row[3] += incoming.getOrDefault(club.getId(), List.of()).size();
            row[4] += outgoing.getOrDefault(club.getId(), List.of()).size();
            deltaByStrategy.merge(strategyId, delta, Double::sum);
        }

        md.append("\n---\n\n## Outcome by strategy\n\n");
        MarkdownTable byStrategy = new MarkdownTable(
                List.of("Strategy", "Clubs", "Better", "Worse", "Avg Δ XI", "Bought", "Sold"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT));
        tally.forEach((strategyId, row) -> byStrategy.addRow(
                com.footballmanagergamesimulator.testutil.TransferMarketDiagnostics.strategyName(strategyId),
                String.valueOf(row[0]),
                String.valueOf(row[1]),
                String.valueOf(row[2]),
                String.format("%+.1f", deltaByStrategy.get(strategyId) / row[0]),
                String.valueOf(row[3]),
                String.valueOf(row[4])));
        md.append(byStrategy.render());

        md.append("\n---\n\n## Summary\n\n");
        md.append("- clubs whose XI improved: ").append(movers.stream().filter(m -> m.delta() > 0.05).count())
                .append(", worsened: ").append(movers.stream().filter(m -> m.delta() < -0.05).count())
                .append(", unchanged: ").append(movers.stream().filter(m -> Math.abs(m.delta()) <= 0.05).count())
                .append('\n');
        md.append("- completed transfers: ").append(transfers.size()).append('\n');
        md.append("- clubs that signed somebody: ").append(incoming.size()).append('\n');
        md.append("- clubs that sold somebody: ").append(outgoing.size()).append('\n');
        md.append("- weak links in starting XIs BEFORE the window: ").append(weakBefore).append('\n');
        md.append("- weak links in starting XIs AFTER the window: ").append(weakAfter).append('\n');
        md.append("- loans agreed: ").append(loans.size()).append('\n');
        md.append("- free-agent signings: ")
                .append(transfers.stream().filter(t -> t.getSellTeamId() == 0L).count()).append('\n');

        System.out.println(md);

        // A player may move at most once per window. Today this holds structurally —
        // the market is built once and sold entries are removed before the next pass —
        // but nothing enforced it, and it stops holding the moment sell lists are
        // recomputed between rounds: the club that just bought him would be free to
        // list him again. Asserted on playerId, not name: generated names repeat.
        Map<Long, List<Transfer>> movesByPlayer = transfers.stream()
                .collect(Collectors.groupingBy(Transfer::getPlayerId));
        List<String> movedTwice = movesByPlayer.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getValue().get(0).getPlayerName() + " (id " + entry.getKey() + ") × "
                        + entry.getValue().size())
                .toList();
        assertThat(movedTwice)
                .as("no player may be transferred more than once in the same window")
                .isEmpty();

        // Nor may a club sell a player it does not own at that moment — the seller on
        // every row must be the club he was actually at when the window opened.
        Map<Long, Long> clubAtWindowOpen = new LinkedHashMap<>();
        preMarket.forEach((clubId, chart) ->
                chart.wholeSquad().forEach(player -> clubAtWindowOpen.put(player.getId(), clubId)));
        List<String> soldByWrongClub = transfers.stream()
                .filter(t -> t.getSellTeamId() != 0L)
                .filter(t -> {
                    Long owner = clubAtWindowOpen.get(t.getPlayerId());
                    return owner != null && owner != t.getSellTeamId();
                })
                .map(t -> t.getPlayerName() + " sold by " + t.getSellTeamName())
                .toList();
        assertThat(soldByWrongClub)
                .as("a club may only sell players it owned when the window opened")
                .isEmpty();

        // Nobody may leave a starting XI unexplained. Every starter who was there
        // before the window and is gone after it must appear in a transfer or a loan.
        Map<Long, Long> soldOrLoanedOut = new LinkedHashMap<>();
        transfers.forEach(t -> soldOrLoanedOut.put(t.getPlayerId(), t.getSellTeamId()));
        loans.forEach(l -> soldOrLoanedOut.put(l.getPlayerId(), l.getParentTeamId()));
        List<String> vanished = new ArrayList<>();
        for (Team club : sortedTeams(teamsById.values())) {
            java.util.Set<Long> afterIds = xiAfter.getOrDefault(club.getId(), List.of()).stream()
                    .map(Starter::playerId).collect(Collectors.toSet());
            for (Starter before : xiBefore.getOrDefault(club.getId(), List.of())) {
                if (afterIds.contains(before.playerId())) continue;
                Human stillSomewhere = squadNow.get(before.playerId());
                // Still at THIS club: dropped from the XI, not gone. squadNow is the one
                // squad read for the whole report — a per-club query here would be the
                // same N+1 the window itself was just cured of.
                if (stillSomewhere != null && Objects.equals(stillSomewhere.getTeamId(), club.getId())) continue;
                if (soldOrLoanedOut.containsKey(before.playerId())) continue;
                vanished.add(club.getName() + " lost " + before.name()
                        + " (" + fmt(before.rating()) + ") with no transfer or loan");
            }
        }
        assertThat(vanished)
                .as("a starter may not leave a club without a transfer or a loan to explain it")
                .isEmpty();

        // With loans accounted for, an XI cannot change unless something moved. Any
        // club whose eleven shifted with no transfer and no loan means the report is
        // measuring two different things on its two sides.
        List<String> unexplained = movers.stream()
                .filter(m -> Math.abs(m.delta()) > 0.05)
                .filter(m -> m.in() == 0 && m.out() == 0 && m.loanIn() == 0 && m.loanOut() == 0)
                .map(m -> m.club() + " (" + m.league() + ") moved "
                        + String.format("%+.1f", m.delta()) + " with no transfer or loan")
                .toList();
        assertThat(unexplained)
                .as("an XI may not change without a transfer or a loan to explain it")
                .isEmpty();

        // The report is the deliverable; these only guarantee it is not vacuous.
        assertThat(transfers).as("the window must complete at least one transfer").isNotEmpty();
        assertThat(xiBefore.values()).as("every club must field an XI before the window")
                .allMatch(xi -> xi.size() == 11);
        // Deliberately NOT "weakAfter <= weakBefore". Whether a weak link actually gets
        // fixed is decided by the transfer budget: most replacements are priced out, and
        // budgets are unseeded (owner injections, matchday income and prize money all
        // draw from a plain Random), so that count swings between runs and measures the
        // economy rather than the market. Asserting on a budget-gated outcome is the
        // same mistake the campaign IT made by auditing completed sales instead of
        // listed ones.
        //
        // What the market does control is whether it TRIED — the numbers themselves stay
        // in the report, where they inform rather than gate.
        //
        // Audited against the chart the club actually planned from — its squad with its
        // own sale list already removed — not against the pre-market baseline the report
        // shows. The two differ exactly when a club decided to sell somebody, and
        // judging a decision against an input it never had produced false failures
        // three times over before this was pinned down.
        //
        // The invariant: a club may not draw up a buy plan that ignores one of its own
        // weak links. Whether it then manages to sign anybody is a budget question and
        // deliberately not asserted here.
        Map<Long, com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView> plans =
                endOfSeasonProcessor.lastBuyPlans();
        Map<Long, SquadDepthChart> planningCharts = endOfSeasonProcessor.lastPlanningDepthCharts();
        List<String> ignoredWeakLinks = new ArrayList<>();
        planningCharts.forEach((clubId, chart) -> {
            var plan = plans.get(clubId);
            if (plan == null || plan.getPositions() == null) return;
            java.util.Set<String> shoppedFor = plan.getPositions().stream()
                    .map(com.footballmanagergamesimulator.transfermarket.TransferPlayer::getPosition)
                    .collect(Collectors.toSet());
            for (String position : SquadDepthChart.BASE_POSITIONS) {
                if (!chart.isCritical(position)) continue;
                if (shoppedFor.contains(position)) continue;
                Team club = teamsById.get(clubId);
                ignoredWeakLinks.add((club == null ? clubId : club.getName()) + " " + position
                        + " (incumbent " + fmt(chart.incumbentRating(position))
                        + " vs XI " + fmt(chart.xiAverage()) + ")");
            }
        });
        assertThat(ignoredWeakLinks)
                .as("a club must shop for every weak link in the squad it planned from "
                        + "(%d weak links in XIs before, %d after)", weakBefore, weakAfter)
                .isEmpty();

        return md.toString();
    }

    // ============================================================
    //  helpers
    // ============================================================

    private record Starter(long playerId, String name, String position, double rating, int age) {}

    /**
     * One club's movement across the window. Generated club names are not unique —
     * two different clubs called "Kuntuna" in the same table read as one duplicated
     * row — so the league is carried alongside to tell them apart.
     */
    private record Mover(String club, String league, long strategyId,
                         double before, double after, int in, int out, int loanIn, int loanOut) {
        double delta() {
            return after - before;
        }
    }

    /** The XI as the market saw it: post-attrition, pre-transfer. */
    private List<Starter> startersFromChart(SquadDepthChart chart) {
        if (chart == null) return List.of();
        List<Starter> starters = new ArrayList<>();
        for (Human player : chart.starters()) {
            String slot = chart.starterSlot(player.getId());
            starters.add(new Starter(
                    player.getId(), player.getName(),
                    TacticService.getBasePosition(slot == null ? player.getPosition() : slot),
                    player.getRating(), player.getAge()));
        }
        return sortForDisplay(starters);
    }

    /** The match engine's own best XI for this club. */
    private List<Starter> bestEleven(long teamId) {
        List<TacticController.StarterSlot> slots = matchSimulationOrchestrator.startersFor(teamId);
        Map<Long, Human> byId = humanRepository
                .findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).stream()
                .collect(Collectors.toMap(Human::getId, h -> h, (a, b) -> a));

        List<Starter> starters = new ArrayList<>();
        for (TacticController.StarterSlot slot : slots) {
            if (slot.player() == null) continue;
            Human human = byId.get(slot.player().getId());
            if (human == null) continue;
            starters.add(new Starter(
                    human.getId(),
                    human.getName(),
                    TacticService.getBasePosition(slot.usedPosition()),
                    human.getRating(),
                    human.getAge()));
        }
        return sortForDisplay(starters);
    }

    private static List<Starter> sortForDisplay(List<Starter> starters) {
        List<Starter> ordered = new ArrayList<>(starters);
        ordered.sort(Comparator
                .comparingInt((Starter s) -> {
                    int index = LINE_ORDER.indexOf(s.position);
                    return index < 0 ? LINE_ORDER.size() : index;
                })
                .thenComparing(Comparator.comparingDouble(Starter::rating).reversed()));
        return ordered;
    }

    /** `Keeper (201) | Back (229), Back (235) | Mid (228) | Striker (199)` */
    private String renderXi(List<Starter> xi) {
        if (xi.isEmpty()) return "_(no XI)_";
        Map<String, List<Starter>> byLine = new LinkedHashMap<>();
        for (Starter s : xi) byLine.computeIfAbsent(line(s.position), k -> new ArrayList<>()).add(s);

        List<String> parts = new ArrayList<>();
        for (String line : List.of("GK", "DEF", "MID", "ATT")) {
            List<Starter> players = byLine.get(line);
            if (players == null || players.isEmpty()) continue;
            parts.add(players.stream()
                    .map(s -> s.name + " (" + fmt(s.rating) + ")")
                    .collect(Collectors.joining(", ")));
        }
        return "`" + String.join("  |  ", parts) + "`";
    }

    private static String line(String basePosition) {
        return switch (basePosition) {
            case "GK" -> "GK";
            case "DL", "DC", "DR" -> "DEF";
            case "ML", "MC", "MR" -> "MID";
            default -> "ATT";
        };
    }

    /** Starters dragging their own XI down by more than the critical deficit. */
    private List<Starter> weakLinks(List<Starter> xi) {
        if (xi.isEmpty()) return List.of();
        double avg = average(xi);
        return xi.stream()
                .filter(s -> avg - s.rating
                        >= com.footballmanagergamesimulator.transfermarket.SquadDepthChart.CRITICAL_DEFICIT)
                .sorted(Comparator.comparingDouble(Starter::rating))
                .toList();
    }

    private static double average(List<Starter> xi) {
        return xi.stream().mapToDouble(Starter::rating).average().orElse(0);
    }

    /**
     * The player's natural position, plus the base position the market indexed him
     * under when the two differ ({@code AMC→MC}). Both matter when reading the
     * report: the club shopped on the base position, but you pick the natural one.
     */
    private static String position(Map<Long, String> positionByPlayerId, Transfer transfer) {
        String natural = positionByPlayerId.get(transfer.getPlayerId());
        if (natural == null) return "?";
        String base = TacticService.getBasePosition(natural);
        return natural.equals(base) ? natural : natural + "→" + base;
    }

    /**
     * Final table for every league, in the same order the prize pool is shared out.
     *
     * <p>Sorting repeats the comparator {@code EndOfSeasonProcessor.refreshTeamBudgets}
     * uses — points, then goal difference, then goals scored — rather than trusting any
     * stored position, so the positions printed here are the positions that were paid.
     */
    private String renderStandings(List<Competition> leagues, Map<Long, Team> teamsById) {
        StringBuilder md = new StringBuilder();
        md.append("## Final standings\n");
        for (Competition league : leagues) {
            List<TeamCompetitionDetail> table = teamCompetitionDetailRepository
                    .findAllByCompetitionId(league.getId()).stream()
                    .sorted((a, b) -> {
                        if (a.getPoints() != b.getPoints()) return b.getPoints() - a.getPoints();
                        if (a.getGoalDifference() != b.getGoalDifference()) {
                            return b.getGoalDifference() - a.getGoalDifference();
                        }
                        return b.getGoalsFor() - a.getGoalsFor();
                    })
                    .toList();
            if (table.isEmpty()) continue;

            // No XI average here on purpose: this runs before the window, so the
            // depth charts the rest of the report uses have not been published yet
            // and the ones in memory would be last season's.
            md.append("\n### ").append(league.getName()).append("\n\n");

            MarkdownTable standings = new MarkdownTable(
                    List.of("#", "Club", "P", "W", "D", "L", "GF", "GA", "GD", "Pts"),
                    List.of(MarkdownTable.Align.RIGHT, MarkdownTable.Align.LEFT,
                            MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                            MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                            MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                            MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
            int position = 1;
            for (TeamCompetitionDetail detail : table) {
                Team team = teamsById.get(detail.getTeamId());
                standings.addRow(
                        String.valueOf(position++),
                        team == null ? ("#" + detail.getTeamId()) : team.getName(),
                        String.valueOf(detail.getGames()),
                        String.valueOf(detail.getWins()),
                        String.valueOf(detail.getDraws()),
                        String.valueOf(detail.getLoses()),
                        String.valueOf(detail.getGoalsFor()),
                        String.valueOf(detail.getGoalsAgainst()),
                        String.valueOf(detail.getGoalDifference()),
                        String.valueOf(detail.getPoints()));
            }
            md.append(standings.render());
        }
        return md.append('\n').toString();
    }

    /**
     * Where the budget above came from, so a club's spending power can be read
     * rather than guessed.
     *
     * <p>The number in the heading is not what the club earned this season. It is
     * what it had left, plus a board-confidence share of this season's income,
     * minus the 15% carry-over decay, plus anything an owner put in. A side that
     * finished below another can still start the window richer — this line says
     * which of those parts did it.
     */
    /** Closing budget when the window published one, else the stale opening balance. */
    private static long budgetOf(Map<Long, EndOfSeasonProcessor.BudgetBreakdown> budgets, Team club) {
        EndOfSeasonProcessor.BudgetBreakdown budget = budgets.get(club.getId());
        return budget == null ? club.getTransferBudget() : budget.closing();
    }

    private static String budgetLine(EndOfSeasonProcessor.BudgetBreakdown budget) {
        if (budget == null) return "";
        StringBuilder md = new StringBuilder("`BUDGET` carried ");
        md.append(money(budget.opening()));
        md.append("  →  after prizes ").append(money(budget.afterPrizes()));
        md.append("  (league ").append(money(budget.prizeMoney()));
        md.append(", TV ").append(money(budget.tvIncome()));
        if (budget.european() > 0) md.append(", European ").append(money(budget.european()));
        md.append(" gross, credited at board-confidence share then decayed 15%)");
        if (budget.ownerInjection() != 0) {
            md.append("  ·  owner +").append(money(budget.ownerInjection()));
        }
        if (budget.extraFunding() != 0) {
            md.append("  ·  extra funding +").append(money(budget.extraFunding()));
        }
        md.append("  →  **").append(money(budget.closing())).append("**\n\n");
        return md.toString();
    }

    private static String moverTable(List<Mover> rows) {
        if (rows.isEmpty()) return "_(none)_\n";
        // MarkdownTable pads every cell so the raw file lines up in a terminal or a
        // plain editor, not only once a markdown renderer gets hold of it. These three
        // tables were hand-concatenated and read as ragged columns.
        // Loans get their own columns. They move players inside the same window and
        // never create a Transfer row, so counting only transfers left rows reading
        // "0 in, 0 out" beside a delta that plainly had a cause — the two players the
        // club had just loaned away.
        MarkdownTable table = new MarkdownTable(
                List.of("Club", "League", "XI before", "XI after", "Δ", "IN", "OUT", "LOAN IN", "LOAN OUT"),
                List.of(MarkdownTable.Align.LEFT, MarkdownTable.Align.LEFT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT,
                        MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT, MarkdownTable.Align.RIGHT));
        for (Mover row : rows) {
            table.addRow(row.club(), row.league(),
                    fmt(row.before()), fmt(row.after()),
                    String.format("%+.1f", row.delta()),
                    String.valueOf(row.in()), String.valueOf(row.out()),
                    String.valueOf(row.loanIn()), String.valueOf(row.loanOut()));
        }
        return table.render();
    }

    private static List<Transfer> sortedByFee(List<Transfer> transfers) {
        return transfers.stream()
                .sorted(Comparator.comparingLong(Transfer::getPlayerTransferValue).reversed()
                        .thenComparing(Transfer::getPlayerName))
                .toList();
    }

    private static List<Team> sortedTeams(java.util.Collection<Team> teams) {
        return teams.stream().sorted(Comparator.comparingLong(Team::getId)).toList();
    }

    private static String fmt(double value) {
        return String.format("%.1f", value);
    }

    private static String money(long amount) {
        if (amount == 0) return "free";
        if (amount >= 1_000_000) return String.format("%.1fM", amount / 1_000_000.0);
        return String.format("%,d", amount);
    }

    private int simulateLeague(long leagueCompId, int season) {
        List<Long> matchdays = matchRepository.findDistinctRoundsByCompetitionIdAndSeasonNumber(
                leagueCompId, String.valueOf(season));
        matchdays.sort(Long::compareTo);
        for (Long matchday : matchdays) {
            competitionController.simulateRound(String.valueOf(leagueCompId), String.valueOf(matchday));
        }
        return matchdays.size();
    }
}
