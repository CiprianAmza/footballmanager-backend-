package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.PredeterminedScore;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.PredeterminedScoreRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Post-match team + player update helpers — standings aggregation, morale,
 * derby detection, manager-morale multiplier, score generation, and
 * predetermined-score consumption. Lifted out of
 * {@link com.footballmanagergamesimulator.controller.CompetitionController}
 * as Stage 1 of the matchday-orchestration extraction.
 *
 * <p>Scope intentionally excludes the "batched AI" variants
 * ({@code updateTeamWithSimpleMorale}, {@code processInjuriesForTeamBatched},
 * {@code batchUpdateManagerReputation}) — those depend on per-round caches
 * populated inside {@code simulateRound} and stay coupled to it for now.
 * Future stages can lift the cache state alongside the round orchestrator.
 *
 * <p>Controller call sites keep working: each moved method retains a
 * thin private wrapper on the controller that delegates here, so neither
 * {@code simulateRound} nor {@code play()} need updates.
 */
@Service
public class TeamPostMatchService {

    @Autowired private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private ScorerRepository scorerRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private ManagerInboxRepository managerInboxRepository;
    @Autowired private PredeterminedScoreRepository predeterminedScoreRepository;
    @Autowired private UserContext userContext;
    @Autowired @Lazy private MatchSimulationService matchSimulationService;

    /** Cache for derby rivals per competition (top 3 teams by reputation).
     *  Populated lazily on first call per competition and reused; cleared
     *  only by app restart (cache is a hint, recomputable). */
    private Map<Long, Set<Long>> derbyRivalsCache = null;

    // ============================================================
    //  Standings + morale update entry points
    // ============================================================

    public void updateTeam(long teamId, long competitionId, int scoreHome, int scoreAway, double teamPowerDifference) {
        updateTeam(teamId, competitionId, scoreHome, scoreAway, teamPowerDifference, 0);
    }

    public void updateTeam(long teamId, long competitionId, int scoreHome, int scoreAway,
                           double teamPowerDifference, long opponentTeamId) {

        TeamCompetitionDetail team = teamCompetitionDetailRepository
                .findFirstByTeamIdAndCompetitionId(teamId, competitionId);
        if (team == null) {
            team = new TeamCompetitionDetail();
            team.setTeamId(teamId);
        }

        String result;
        team.setCompetitionId(competitionId);
        team.setGoalsFor(team.getGoalsFor() + scoreHome);
        team.setGoalsAgainst(team.getGoalsAgainst() + scoreAway);
        team.setGoalDifference(team.getGoalsFor() - team.getGoalsAgainst());
        if (scoreHome > scoreAway) {
            result = "W";
            team.setForm((team.getForm() != null ? team.getForm() : "") + "W");
            team.setWins(team.getWins() + 1);
            team.setPoints(team.getPoints() + 3);
        } else if (scoreHome == scoreAway) {
            result = "D";
            team.setForm((team.getForm() != null ? team.getForm() : "") + "D");
            team.setDraws(team.getDraws() + 1);
            team.setPoints(team.getPoints() + 1);
        } else {
            result = "L";
            team.setForm((team.getForm() != null ? team.getForm() : "") + "L");
            team.setLoses(team.getLoses() + 1);
        }

        double baseMoraleChange = calculateMoraleChangeForTeamDifference(result, teamPowerDifference);

        // Derby bonus: if both teams are top 3 by reputation in the same
        // league, boost morale impact 50% and notify human-managed teams.
        boolean isDerby = isDerbyMatch(teamId, opponentTeamId, competitionId);
        if (isDerby) {
            baseMoraleChange *= 1.5;
            if (userContext.isHumanTeam(teamId)) {
                Round currentRound = roundRepository.findById(1L).orElse(new Round());
                int currentSeason = (int) currentRound.getSeason();
                String opponentName = teamRepository.findById(opponentTeamId).map(Team::getName).orElse("rival");
                String derbyResult = result.equals("W") ? "won" : result.equals("D") ? "drew" : "lost";
                sendInboxNotification(teamId, currentSeason, (int) currentRound.getRound(),
                        "Derby Result", "Your team " + derbyResult + " the derby against " + opponentName +
                        "! This big match has a stronger impact on squad morale.", "match_result");
            }
        }

        updatePlayersMorale(teamId, baseMoraleChange, result);

        team.setGames(team.getGames() + 1);

        if (team.getForm().length() > 5) {
            team.setForm(team.getForm().substring(team.getForm().length() - 5));
        }

        teamCompetitionDetailRepository.save(team);
    }

    /** Fast standings update for AI vs AI matches — no morale, no scorer
     *  queries, just W/D/L/GF/GA/points. */
    public void updateTeamSimple(long teamId, long competitionId, int scoreHome, int scoreAway) {
        TeamCompetitionDetail team = teamCompetitionDetailRepository
                .findFirstByTeamIdAndCompetitionId(teamId, competitionId);
        if (team == null) {
            team = new TeamCompetitionDetail();
            team.setTeamId(teamId);
        }

        team.setCompetitionId(competitionId);
        team.setGoalsFor(team.getGoalsFor() + scoreHome);
        team.setGoalsAgainst(team.getGoalsAgainst() + scoreAway);
        team.setGoalDifference(team.getGoalsFor() - team.getGoalsAgainst());
        if (scoreHome > scoreAway) {
            team.setForm((team.getForm() != null ? team.getForm() : "") + "W");
            team.setWins(team.getWins() + 1);
            team.setPoints(team.getPoints() + 3);
        } else if (scoreHome == scoreAway) {
            team.setForm((team.getForm() != null ? team.getForm() : "") + "D");
            team.setDraws(team.getDraws() + 1);
            team.setPoints(team.getPoints() + 1);
        } else {
            team.setForm((team.getForm() != null ? team.getForm() : "") + "L");
            team.setLoses(team.getLoses() + 1);
        }
        team.setGames(team.getGames() + 1);

        if (team.getForm().length() > 5) {
            team.setForm(team.getForm().substring(team.getForm().length() - 5));
        }

        teamCompetitionDetailRepository.save(team);
    }

    // ============================================================
    //  Morale: per-player + recovery
    // ============================================================

    /** Per-player morale update for human teams. Identifies who played in
     *  the latest match by inspecting the most recent Scorer rows, then
     *  applies played/benched morale deltas, tracks playing time and
     *  consecutive-bench counters, and triggers transfer requests +
     *  inbox notifications for benched high-rated players. */
    public void updatePlayersMorale(long teamId, double baseMoraleChange, String matchResult) {

        List<Human> allPlayers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
        int currentSeason = roundRepository.findById(1L).map(r -> (int) r.getSeason()).orElse(1);
        Random random = new Random();

        // Latest match scorers identify who played
        List<Scorer> seasonScorers = scorerRepository.findAllByTeamIdAndSeasonNumber(teamId, currentSeason);
        Set<Long> playedInMatch = new HashSet<>();
        Map<Long, Integer> goalsInMatch = new HashMap<>();

        if (!seasonScorers.isEmpty()) {
            Scorer lastEntry = seasonScorers.get(seasonScorers.size() - 1);
            long latestOpponentId = lastEntry.getOpponentTeamId();
            long latestCompetitionId = lastEntry.getCompetitionId();
            int latestTeamScore = lastEntry.getTeamScore();
            int latestOpponentScore = lastEntry.getOpponentScore();

            for (Scorer scorer : seasonScorers) {
                if (scorer.getOpponentTeamId() == latestOpponentId
                        && scorer.getCompetitionId() == latestCompetitionId
                        && scorer.getTeamScore() == latestTeamScore
                        && scorer.getOpponentScore() == latestOpponentScore) {
                    playedInMatch.add(scorer.getPlayerId());
                    if (scorer.getGoals() > 0) {
                        goalsInMatch.put(scorer.getPlayerId(), scorer.getGoals());
                    }
                }
            }
        }

        boolean isHumanTeam = userContext.isHumanTeam(teamId);
        Round currentRound = roundRepository.findById(1L).orElse(new Round());
        int roundNumber = (int) currentRound.getRound();

        for (Human player : allPlayers) {
            if (player.isRetired()) continue;
            double moraleChange;

            if (playedInMatch.contains(player.getId())) {
                moraleChange = baseMoraleChange;
                if (goalsInMatch.containsKey(player.getId())) {
                    moraleChange += random.nextDouble(3, 6);
                }
                player.setSeasonMatchesPlayed(player.getSeasonMatchesPlayed() + 1);
                player.setConsecutiveBenched(0);

                if (player.isWantsTransfer() && player.getConsecutiveBenched() == 0 && player.getSeasonMatchesPlayed() > 5) {
                    if (random.nextDouble() < 0.3) {
                        player.setWantsTransfer(false);
                        if (isHumanTeam) {
                            sendInboxNotification(teamId, currentSeason, roundNumber,
                                    "Player Settled", player.getName() + " is happy with their playing time and no longer wants to leave.",
                                    "morale");
                        }
                    }
                }
            } else {
                player.setConsecutiveBenched(player.getConsecutiveBenched() + 1);

                switch (matchResult) {
                    case "W": moraleChange = -2; break;
                    case "D": moraleChange = -4; break;
                    case "L": moraleChange = -3; break;
                    default:  moraleChange =  0;
                }

                int benched = player.getConsecutiveBenched();
                if (benched >= 5) moraleChange -= 2;
                // 150 = scaled-up 50 for the 1-300 rating range (mid-tier or better).
                if (benched >= 3 && player.getRating() > 150 && !player.isWantsTransfer()) {
                    double demandChance = (benched >= 7) ? 0.5 : (benched >= 5) ? 0.3 : 0.1;
                    if (random.nextDouble() < demandChance) {
                        player.setWantsTransfer(true);
                        if (isHumanTeam) {
                            sendInboxNotification(teamId, currentSeason, roundNumber,
                                    "Transfer Request", player.getName() + " is unhappy with their lack of playing time and has requested a transfer.",
                                    "transfer");
                        }
                    }
                }
            }

            player.setMorale(player.getMorale() + moraleChange);
            player.setMorale(Math.min(player.getMorale(), 100D));
            player.setMorale(Math.max(player.getMorale(), 0D));
        }
        humanRepository.saveAll(allPlayers);
    }

    /** Drift player morale toward the 65-75 neutral zone. Called once per
     *  game round, AFTER all matches have applied their own morale deltas. */
    public void applyMoraleRecovery() {
        List<Human> allPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);
        boolean changed = false;
        for (Human player : allPlayers) {
            if (player.isRetired()) continue;
            double morale = player.getMorale();
            if (morale < 60) {
                player.setMorale(Math.min(morale + 2, 70D));
                changed = true;
            } else if (morale < 65) {
                player.setMorale(Math.min(morale + 1, 70D));
                changed = true;
            } else if (morale > 80) {
                player.setMorale(Math.max(morale - 1, 70D));
                changed = true;
            }
        }
        if (changed) humanRepository.saveAll(allPlayers);
    }

    /** Manager morale → team power multiplier. Neutral at 70 (1.0); range
     *  0.93 (morale 0) to 1.03 (morale 100). */
    public double getManagerMoraleMultiplier(long teamId) {
        List<Human> managers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE);
        if (managers.isEmpty()) return 1.0;
        double managerMorale = managers.get(0).getMorale();
        return 1.0 + (managerMorale - 70) * 0.001;
    }

    /** Range-based morale delta based on match result + team power gap.
     *  Larger upset = bigger morale swing (positive for winner, negative
     *  for the favourite who drew or lost). */
    public double calculateMoraleChangeForTeamDifference(String result, double teamPowerDifference) {
        Random random = new Random();
        if ("W".equals(result)) {
            if (teamPowerDifference > 500) return random.nextDouble(0, 1);
            else if (teamPowerDifference > 200) return random.nextDouble(1, 2);
            else if (teamPowerDifference > 0) return random.nextDouble(1, 3);
            else if (teamPowerDifference > -200) return random.nextDouble(2, 5);
            else if (teamPowerDifference > -500) return random.nextDouble(4, 7);
            else return random.nextDouble(5, 10);
        } else if ("D".equals(result)) {
            if (teamPowerDifference > 500) return random.nextDouble(-6, -2);
            else if (teamPowerDifference > 200) return random.nextDouble(-4, 0);
            else if (teamPowerDifference > 0) return random.nextDouble(-2, 1);
            else if (teamPowerDifference > -200) return random.nextDouble(1, 3);
            else if (teamPowerDifference > -500) return random.nextDouble(2, 5);
            else return random.nextDouble(3, 7);
        } else {
            if (teamPowerDifference > 500) return random.nextDouble(-15, -5);
            else if (teamPowerDifference > 200) return random.nextDouble(-8, -3);
            else if (teamPowerDifference > 0) return random.nextDouble(-5, -2);
            else if (teamPowerDifference > -200) return random.nextDouble(-3, -1);
            else if (teamPowerDifference > -500) return random.nextDouble(-2, 0);
            else return random.nextDouble(-1, 0);
        }
    }

    /** Drop the cached derby rivals — called at season turnover so the next
     *  season recomputes from fresh reputations (promotions/relegations
     *  reshuffle the top-3 set). */
    public void clearDerbyCache() {
        this.derbyRivalsCache = null;
    }

    // ============================================================
    //  Derby detection (cached)
    // ============================================================

    /** Two teams are rivals if they're both in the top 3 by reputation in
     *  the same league or second-league competition. Cup matches are never
     *  derbies. Returns false for {@code opponentTeamId == 0} (uninitialised). */
    public boolean isDerbyMatch(long teamId, long opponentTeamId, long competitionId) {
        if (opponentTeamId == 0) return false;

        Competition comp = competitionRepository.findById(competitionId).orElse(null);
        if (comp == null || (comp.getTypeId() != 1 && comp.getTypeId() != 3)) return false;

        if (derbyRivalsCache == null) {
            derbyRivalsCache = new HashMap<>();
        }
        if (!derbyRivalsCache.containsKey(competitionId)) {
            List<Team> leagueTeams = teamRepository.findAll().stream()
                    .filter(t -> t.getCompetitionId() == competitionId)
                    .sorted((a, b) -> b.getReputation() - a.getReputation())
                    .toList();
            Set<Long> topTeams = new HashSet<>();
            int limit = Math.min(3, leagueTeams.size());
            for (int i = 0; i < limit; i++) {
                topTeams.add(leagueTeams.get(i).getId());
            }
            derbyRivalsCache.put(competitionId, topTeams);
        }
        Set<Long> topTeams = derbyRivalsCache.get(competitionId);
        return topTeams.contains(teamId) && topTeams.contains(opponentTeamId);
    }

    // ============================================================
    //  Score generation
    // ============================================================

    /** Power-based score generator. Returns [score1, score2] using a
     *  Poisson distribution around an amplified power ratio (3.0 expected
     *  goals total, distributed by ratio^1.5 renormalised). */
    public List<Integer> calculateScores(double power1, double power2) {
        double total = power1 + power2;
        if (total == 0) return List.of(1, 1);

        double ratio1 = power1 / total;
        double ratio2 = power2 / total;

        double amp1 = Math.pow(ratio1, 1.5);
        double amp2 = Math.pow(ratio2, 1.5);
        double ampTotal = amp1 + amp2;
        double adjRatio1 = amp1 / ampTotal;
        double adjRatio2 = amp2 / ampTotal;

        double expected1 = 3.0 * adjRatio1;
        double expected2 = 3.0 * adjRatio2;

        Random random = new Random();
        int score1 = poissonGoals(random, expected1);
        int score2 = poissonGoals(random, expected2);

        return List.of(score1, score2);
    }

    /** Poisson sampling via Knuth's algorithm, capped at 7 to avoid
     *  blow-out scores in extreme power mismatches. */
    public int poissonGoals(Random random, double expectedGoals) {
        double L = Math.exp(-expectedGoals);
        double p = 1.0;
        int k = 0;
        do {
            k++;
            p *= random.nextDouble();
        } while (p > L);
        return Math.min(k - 1, 7);
    }

    /** Admin override: if an un-consumed PredeterminedScore exists for this
     *  exact (competition, season, round, team1, team2), returns it and marks
     *  it consumed so it isn't reused. Returns null if no override is set. */
    public int[] consumePredeterminedScore(long competitionId, int roundId, long team1Id, long team2Id) {
        int season = roundRepository.findById(1L).map(r -> (int) r.getSeason()).orElse(1);
        Optional<PredeterminedScore> preset = predeterminedScoreRepository
                .findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2IdAndConsumedFalse(
                        competitionId, season, roundId, team1Id, team2Id);
        if (preset.isEmpty()) return null;
        PredeterminedScore p = preset.get();
        int s1 = p.getTeam1Score();
        int s2 = p.getTeam2Score();
        p.setConsumed(true);
        predeterminedScoreRepository.save(p);
        System.out.println("=== PredeterminedScore consumed: comp=" + competitionId
                + " round=" + roundId + " " + team1Id + " vs " + team2Id + " = " + s1 + "-" + s2);
        return new int[]{s1, s2};
    }

    // ============================================================
    //  Inbox helper
    // ============================================================

    /** Internal helper — surfaces post-match events (derby result, transfer
     *  request, player settled) on the manager's inbox. */
    public void sendInboxNotification(long teamId, int season, int roundNumber, String title, String content, String category) {
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(roundNumber);
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory(category);
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);
    }

    // ============================================================
    //  Manager reputation after match
    // ============================================================

    /**
     * Adjusts both managers' reputations based on the match result. Uses the
     * shared {@link MatchSimulationService#calculateMatchRepChange} formula
     * (upset-aware: bigger gain for beating stronger team, bigger penalty for
     * losing to weaker team). Clamps to [0, 10000].
     */
    public void updateManagerReputationAfterMatch(long teamId1, long teamId2, int score1, int score2) {
        Team team1 = teamRepository.findById(teamId1).orElse(null);
        Team team2 = teamRepository.findById(teamId2).orElse(null);
        if (team1 == null || team2 == null) return;

        List<Human> managers1 = humanRepository.findAllByTeamIdAndTypeId(teamId1, TypeNames.MANAGER_TYPE);
        List<Human> managers2 = humanRepository.findAllByTeamIdAndTypeId(teamId2, TypeNames.MANAGER_TYPE);

        if (!managers1.isEmpty()) {
            double change = matchSimulationService.calculateMatchRepChange(score1, score2, team1.getReputation(), team2.getReputation());
            Human mgr = managers1.get(0);
            mgr.setManagerReputation((int) Math.max(0, Math.min(10000, mgr.getManagerReputation() + change)));
            humanRepository.save(mgr);
        }

        if (!managers2.isEmpty()) {
            double change = matchSimulationService.calculateMatchRepChange(score2, score1, team2.getReputation(), team1.getReputation());
            Human mgr = managers2.get(0);
            mgr.setManagerReputation((int) Math.max(0, Math.min(10000, mgr.getManagerReputation() + change)));
            humanRepository.save(mgr);
        }
    }

    // ============================================================
    //  Match report inbox notifications (human-team only)
    // ============================================================

    /**
     * Generates an inbox report after a match. No-op when neither team is
     * managed by a human user. Builds a per-team report with goal scorers
     * sourced from {@link ScorerRepository} for that exact (team, opponent,
     * score) pair.
     */
    public void generateMatchReport(long competitionId, long roundId, long teamId1, long teamId2, int teamScore1, int teamScore2) {
        boolean team1IsHuman = userContext.isHumanTeam(teamId1);
        boolean team2IsHuman = userContext.isHumanTeam(teamId2);
        if (!team1IsHuman && !team2IsHuman) return;

        String teamName1 = teamRepository.findById(teamId1).map(Team::getName).orElse("Unknown");
        String teamName2 = teamRepository.findById(teamId2).map(Team::getName).orElse("Unknown");
        String competitionName = competitionRepository.findById(competitionId).map(Competition::getName).orElse("Unknown");
        int seasonNumber = roundRepository.findById(1L).map(Round::getSeason).map(Long::intValue).orElse(1);
        int roundNumber = (int) roundId;

        if (team1IsHuman) {
            generateMatchReportForTeam(teamId1, teamName1, teamId2, teamName2, teamScore1, teamScore2,
                    competitionName, seasonNumber, roundNumber);
        }
        if (team2IsHuman) {
            generateMatchReportForTeam(teamId2, teamName2, teamId1, teamName1, teamScore2, teamScore1,
                    competitionName, seasonNumber, roundNumber);
        }
    }

    private void generateMatchReportForTeam(long teamId, String teamName, long opponentTeamId, String opponentName,
                                            int teamScore, int opponentScore, String competitionName,
                                            int seasonNumber, int roundNumber) {
        String resultPrefix;
        if (teamScore > opponentScore) {
            resultPrefix = "Victory! ";
        } else if (teamScore < opponentScore) {
            resultPrefix = "Defeat. ";
        } else {
            resultPrefix = "Draw. ";
        }

        String title = resultPrefix + teamName + " " + teamScore + "-" + opponentScore + " " + opponentName;

        StringBuilder content = new StringBuilder();
        content.append("Competition: ").append(competitionName).append("\n");
        content.append("Result: ").append(teamName).append(" ").append(teamScore)
                .append(" - ").append(opponentScore).append(" ").append(opponentName).append("\n");

        List<Scorer> matchScorers = scorerRepository.findAllByTeamIdAndSeasonNumber(teamId, seasonNumber).stream()
                .filter(s -> s.getOpponentTeamId() == opponentTeamId)
                .filter(s -> s.getTeamScore() == teamScore)
                .filter(s -> s.getOpponentScore() == opponentScore)
                .filter(s -> s.getGoals() > 0)
                .toList();

        if (!matchScorers.isEmpty()) {
            content.append("Goals: ");
            String scorersList = matchScorers.stream()
                    .map(scorer -> {
                        String playerName = humanRepository.findById(scorer.getPlayerId())
                                .map(Human::getName).orElse("Unknown");
                        return playerName + " (" + scorer.getGoals() + ")";
                    })
                    .collect(java.util.stream.Collectors.joining(", "));
            content.append(scorersList).append("\n");
        }

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(seasonNumber);
        inbox.setRoundNumber(roundNumber);
        inbox.setTitle(title);
        inbox.setContent(content.toString());
        inbox.setCategory("match_result");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());

        managerInboxRepository.save(inbox);
    }
}
