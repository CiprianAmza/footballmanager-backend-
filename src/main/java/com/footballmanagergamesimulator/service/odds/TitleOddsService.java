package com.footballmanagergamesimulator.service.odds;

import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.compartment.match.OutcomeProbability;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.model.TitleOddsSnapshot;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TitleOddsSnapshotRepository;
import com.footballmanagergamesimulator.service.LeagueQualificationDisplayService;
import com.footballmanagergamesimulator.service.TacticSimulationService;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * League and cup chances (design DESIGN_MEDIA_SI_COMPETITII §4): the engine's own win / draw / defeat probabilities
 * for every match still to play, the rest of the season simulated 10,000 times. Computed when asked and kept until
 * the next match of the competition is played; for leagues with a human manager, recomputed after every matchday so
 * the title race has a point per round.
 */
@Service
public class TitleOddsService {

    static final int RUNS = 10_000;
    /** Used for a team the engine cannot evaluate (no full eleven): a plain home edge. */
    private static final OutcomeProbability FALLBACK = new OutcomeProbability(0.45, 0.27, 0.28);

    public record LeagueRow(long teamId, String teamName, int position, int played, int points, int goalDifference,
                            double title, Double titleOdds, double top3, double champions, double relegation,
                            double averagePosition) {}

    public record LeagueOdds(long competitionId, int season, int gamesPlayed, int fixturesLeft, int championsSpots,
                             Integer relegationFrom, List<LeagueRow> rows) {}

    public record CupRow(long teamId, String teamName, double win, Double winOdds, double reachFinal) {}

    public record CupOdds(long competitionId, int season, boolean decided, List<CupRow> rows) {}

    /** One point of the title race: a team's chances after a number of games. */
    public record HistoryPoint(int gamesPlayed, long teamId, String teamName, double title, double top3,
                               double relegation) {}

    private final CompetitionRepository competitions;
    private final CompetitionTeamInfoMatchRepository fixtures;
    private final CompetitionTeamInfoDetailRepository results;
    private final TeamCompetitionDetailRepository tables;
    private final TeamRepository teams;
    private final RoundRepository rounds;
    private final TitleOddsSnapshotRepository snapshots;
    private final LeagueQualificationDisplayService qualification;
    private final TacticSimulationService engine;
    private final UserContext userContext;

    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    public TitleOddsService(CompetitionRepository competitions, CompetitionTeamInfoMatchRepository fixtures,
                            CompetitionTeamInfoDetailRepository results, TeamCompetitionDetailRepository tables,
                            TeamRepository teams, RoundRepository rounds, TitleOddsSnapshotRepository snapshots,
                            @Lazy LeagueQualificationDisplayService qualification,
                            @Lazy TacticSimulationService engine, UserContext userContext) {
        this.competitions = competitions;
        this.fixtures = fixtures;
        this.results = results;
        this.tables = tables;
        this.teams = teams;
        this.rounds = rounds;
        this.snapshots = snapshots;
        this.qualification = qualification;
        this.engine = engine;
        this.userContext = userContext;
    }

    // ============================================================
    //  Leagues (§4.1)
    // ============================================================

    public synchronized LeagueOdds leagueOdds(long competitionId) {
        Competition competition = competitions.findById(competitionId)
                .filter(Competition::isLeague)
                .orElseThrow(() -> new IllegalArgumentException("Competition " + competitionId + " is not a league"));
        int season = currentSeason();
        List<CompetitionTeamInfoMatch> seasonFixtures = fixtures
                .findAllByCompetitionIdAndSeasonNumberOrderByRoundAscMatchIndexAsc(competitionId, String.valueOf(season));
        long played = seasonFixtures.stream().filter(m -> m.getTeam1Score() >= 0).count();
        String key = "L:" + competitionId + ":" + season + ":" + played;
        Object cached = cache.get(key);
        if (cached instanceof LeagueOdds odds) return odds;

        LeagueOdds odds = computeLeague(competition, season, seasonFixtures, played);
        cache.keySet().removeIf(k -> k.startsWith("L:" + competitionId + ":"));
        cache.put(key, odds);
        storeSnapshot(odds);
        return odds;
    }

    private LeagueOdds computeLeague(Competition competition, int season, List<CompetitionTeamInfoMatch> seasonFixtures,
                                     long played) {
        // The league's teams this season are the ones in its fixtures (the table rows of teams that left may linger).
        LinkedHashSet<Long> teamSet = new LinkedHashSet<>();
        for (CompetitionTeamInfoMatch m : seasonFixtures) {
            if (m.getTeam1Id() > 0) teamSet.add(m.getTeam1Id());
            if (m.getTeam2Id() > 0) teamSet.add(m.getTeam2Id());
        }
        Map<Long, TeamCompetitionDetail> rowByTeam = new HashMap<>();
        if (!teamSet.isEmpty()) {
            for (TeamCompetitionDetail row : tables.findAllByCompetitionIdAndTeamIdIn(competition.getId(), teamSet)) {
                rowByTeam.putIfAbsent(row.getTeamId(), row);
            }
        }
        List<Long> standing = new ArrayList<>(teamSet);
        standing.sort(Comparator.<Long>comparingInt(t -> points(rowByTeam, t)).reversed()
                .thenComparing(Comparator.<Long>comparingInt(t -> goalDifference(rowByTeam, t)).reversed())
                .thenComparing(Comparator.<Long>comparingInt(t -> goalsFor(rowByTeam, t)).reversed())
                .thenComparingLong(t -> t));

        int n = standing.size();
        long[] teamIds = new long[n];
        int[] points = new int[n];
        int[] goalDifference = new int[n];
        Map<Long, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < n; i++) {
            long teamId = standing.get(i);
            teamIds[i] = teamId;
            points[i] = points(rowByTeam, teamId);
            goalDifference[i] = goalDifference(rowByTeam, teamId);
            indexOf.put(teamId, i);
        }

        Map<Long, CanonicalTeamEvaluation> evaluations = new HashMap<>();
        List<LeagueOddsSimulation.Fixture> remaining = new ArrayList<>();
        for (CompetitionTeamInfoMatch m : seasonFixtures) {
            if (m.getTeam1Score() >= 0 || m.getTeam1Id() <= 0 || m.getTeam2Id() <= 0) continue;
            Integer home = indexOf.get(m.getTeam1Id());
            Integer away = indexOf.get(m.getTeam2Id());
            if (home == null || away == null) continue;
            OutcomeProbability outcome = outcome(evaluations, m.getTeam1Id(), m.getTeam2Id());
            remaining.add(new LeagueOddsSimulation.Fixture(home, away, outcome.homeWin(), outcome.draw()));
        }

        int championsSpots = 0;
        Integer relegationFrom = null;
        try {
            LeagueQualificationDisplayService.LeagueQualificationContext zones =
                    qualification.context(competition.getId(), season);
            championsSpots = competition.isTopFlight() ? zones.locSpots() : 0;
            relegationFrom = zones.relegationFrom();
        } catch (RuntimeException noZones) {
            // A league without qualification data: chances of title, top 3 and position only.
        }

        long seed = Objects.hash(competition.getId(), season, played);
        List<LeagueOddsSimulation.Odds> odds = LeagueOddsSimulation.simulate(teamIds, points, goalDifference,
                remaining, championsSpots, relegationFrom, RUNS, seed);

        int gamesPlayed = rowByTeam.values().stream().mapToInt(TeamCompetitionDetail::getGames).max().orElse(0);
        List<LeagueRow> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LeagueOddsSimulation.Odds o = odds.get(i);
            TeamCompetitionDetail row = rowByTeam.get(o.teamId());
            rows.add(new LeagueRow(o.teamId(), teamName(o.teamId()), i + 1, row == null ? 0 : row.getGames(),
                    points[i], goalDifference[i], o.title(), LeagueOddsSimulation.decimalOdds(o.title()),
                    o.top3(), o.champions(), o.relegation(), o.averagePosition()));
        }
        return new LeagueOdds(competition.getId(), season, gamesPlayed, remaining.size(), championsSpots,
                relegationFrom, rows);
    }

    /**
     * After a matchday: recompute the leagues of these competitions that have a human manager, so the title race
     * gets its point for the round. Never fails the matchday.
     */
    public void recordAfterMatchday(Collection<Long> competitionIds) {
        List<Long> humanTeams = userContext.getAllHumanTeamIds();
        if (competitionIds == null || competitionIds.isEmpty() || humanTeams.isEmpty()) return;
        for (Long competitionId : new LinkedHashSet<>(competitionIds)) {
            if (competitionId == null) continue;
            try {
                boolean league = competitions.findById(competitionId).map(Competition::isLeague).orElse(false);
                if (!league || tables.findAllByCompetitionIdAndTeamIdIn(competitionId, humanTeams).isEmpty()) continue;
                leagueOdds(competitionId);
            } catch (RuntimeException failure) {
                System.err.println("Title odds for competition " + competitionId + " failed: " + failure);
            }
        }
    }

    /** The title race of a league this season: one point per stored matchday and team, oldest first. */
    public List<HistoryPoint> history(long competitionId) {
        int season = currentSeason();
        Map<Long, String> names = new HashMap<>();
        return snapshots.findAllByCompetitionIdAndSeasonNumber(competitionId, season).stream()
                .sorted(Comparator.comparingInt(TitleOddsSnapshot::getGamesPlayed)
                        .thenComparingLong(TitleOddsSnapshot::getTeamId))
                .map(s -> new HistoryPoint(s.getGamesPlayed(), s.getTeamId(),
                        names.computeIfAbsent(s.getTeamId(), this::teamName),
                        s.getTitle(), s.getTop3(), s.getRelegation()))
                .toList();
    }

    private void storeSnapshot(LeagueOdds odds) {
        List<TitleOddsSnapshot> previous = snapshots.findAllByCompetitionIdAndSeasonNumberAndGamesPlayed(
                odds.competitionId(), odds.season(), odds.gamesPlayed());
        if (!previous.isEmpty()) snapshots.deleteAll(previous);
        List<TitleOddsSnapshot> rows = new ArrayList<>(odds.rows().size());
        for (LeagueRow row : odds.rows()) {
            TitleOddsSnapshot snapshot = new TitleOddsSnapshot();
            snapshot.setCompetitionId(odds.competitionId());
            snapshot.setSeasonNumber(odds.season());
            snapshot.setGamesPlayed(odds.gamesPlayed());
            snapshot.setTeamId(row.teamId());
            snapshot.setTitle(row.title());
            snapshot.setTop3(row.top3());
            snapshot.setChampions(row.champions());
            snapshot.setRelegation(row.relegation());
            snapshot.setAveragePosition(row.averagePosition());
            rows.add(snapshot);
        }
        snapshots.saveAll(rows);
    }

    // ============================================================
    //  Cups (§4.2)
    // ============================================================

    public synchronized CupOdds cupOdds(long competitionId) {
        Competition competition = competitions.findById(competitionId)
                .filter(c -> c.getTypeId() == Competition.CUP)
                .orElseThrow(() -> new IllegalArgumentException("Competition " + competitionId + " is not a national cup"));
        int season = currentSeason();
        List<CompetitionTeamInfoMatch> bracket = fixtures
                .findAllByCompetitionIdAndSeasonNumberOrderByRoundAscMatchIndexAsc(competitionId, String.valueOf(season));
        long played = bracket.stream().filter(m -> m.getTeam1Score() >= 0).count();
        String key = "C:" + competitionId + ":" + season + ":" + played;
        Object cached = cache.get(key);
        if (cached instanceof CupOdds odds) return odds;

        int lastRound = bracket.stream().mapToInt(m -> (int) m.getRound()).max().orElse(0);
        List<CupOddsSimulation.Match> matches = new ArrayList<>(bracket.size());
        boolean decided = false;
        for (CompetitionTeamInfoMatch m : bracket) {
            if (m.getLegNumber() == 2) continue; // national cups are single matches
            boolean isPlayed = m.getTeam1Score() >= 0;
            long winner = 0;
            if (isPlayed && m.getRound() == lastRound) {
                winner = playedWinner(competitionId, season, m);
                decided = winner > 0;
            }
            matches.add(new CupOddsSimulation.Match((int) m.getRound(), m.getMatchIndex(),
                    m.getTeam1Id(), m.getTeam2Id(), isPlayed, winner));
        }

        Map<Long, CanonicalTeamEvaluation> evaluations = new HashMap<>();
        Map<String, Double> advance = new HashMap<>();
        List<CupOddsSimulation.Odds> odds = CupOddsSimulation.simulate(matches,
                (team1, team2) -> advance.computeIfAbsent(team1 + ":" + team2, k -> {
                    OutcomeProbability outcome = outcome(evaluations, team1, team2);
                    return outcome.homeWin() + outcome.draw() / 2.0; // a draw goes to penalties
                }),
                RUNS, Objects.hash(competitionId, season, played));
        List<CupRow> rows = odds.stream()
                .map(o -> new CupRow(o.teamId(), teamName(o.teamId()), o.win(),
                        LeagueOddsSimulation.decimalOdds(o.win()), o.reachFinal()))
                .toList();
        CupOdds result = new CupOdds(competitionId, season, decided, rows);
        cache.keySet().removeIf(k -> k.startsWith("C:" + competitionId + ":"));
        cache.put(key, result);
        return result;
    }

    private long playedWinner(long competitionId, int season, CompetitionTeamInfoMatch m) {
        if (m.getTeam1Score() > m.getTeam2Score()) return m.getTeam1Id();
        if (m.getTeam2Score() > m.getTeam1Score()) return m.getTeam2Id();
        // Level after extra time: the shoot-out winner is on the result.
        return results.findAllByCompetitionIdAndRoundIdAndSeasonNumber(competitionId, m.getRound(), season).stream()
                .filter(d -> d.getTeam1Id() == m.getTeam1Id() && d.getTeam2Id() == m.getTeam2Id())
                .map(CompetitionTeamInfoDetail::getWinnerTeamId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(0L);
    }

    // ============================================================
    //  helpers
    // ============================================================

    private OutcomeProbability outcome(Map<Long, CanonicalTeamEvaluation> evaluations, long home, long away) {
        CanonicalTeamEvaluation homeEvaluation = evaluation(evaluations, home);
        CanonicalTeamEvaluation awayEvaluation = evaluation(evaluations, away);
        if (homeEvaluation == null || awayEvaluation == null) return FALLBACK;
        return engine.outcome(homeEvaluation, awayEvaluation);
    }

    private CanonicalTeamEvaluation evaluation(Map<Long, CanonicalTeamEvaluation> evaluations, long teamId) {
        if (evaluations.containsKey(teamId)) return evaluations.get(teamId);
        CanonicalTeamEvaluation evaluation;
        try {
            evaluation = engine.currentTeamEvaluation(teamId);
        } catch (RuntimeException cannotLineUp) {
            evaluation = null;
        }
        evaluations.put(teamId, evaluation);
        return evaluation;
    }

    private String teamName(long teamId) {
        String name = teams.findNameById(teamId);
        return name == null ? "Team #" + teamId : name;
    }

    private int currentSeason() {
        return rounds.findById(1L).map(Round::getSeason).map(Long::intValue).orElse(1);
    }

    private static int points(Map<Long, TeamCompetitionDetail> rows, long teamId) {
        TeamCompetitionDetail row = rows.get(teamId);
        return row == null ? 0 : row.getPoints();
    }

    private static int goalDifference(Map<Long, TeamCompetitionDetail> rows, long teamId) {
        TeamCompetitionDetail row = rows.get(teamId);
        return row == null ? 0 : row.getGoalDifference();
    }

    private static int goalsFor(Map<Long, TeamCompetitionDetail> rows, long teamId) {
        TeamCompetitionDetail row = rows.get(teamId);
        return row == null ? 0 : row.getGoalsFor();
    }
}
