package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.TeamCompetitionView;
import com.footballmanagergamesimulator.frontend.TeamMatchView;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only display + lookup endpoints for competitions: league/cup overviews,
 * team standings rows, qualification zone math, future-match adapters.
 * Lifted out of CompetitionController so the controller stays a thin REST
 * edge layer. Each REST mapping there now does {@code return svc.X(...);}.
 *
 * <p>Cup-specific display (cupsOverview, cupBracket, cupRoundCount) lives in
 * CupBracketService — kept separate so cup bracket maintenance + cup display
 * stay in one place.
 */
@Service
public class CompetitionDisplayService {

    @Autowired private TeamRepository teamRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired private CompetitionHistoryRepository competitionHistoryRepository;
    @Autowired private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private EuropeanCoefficientService europeanCoefficientService;

    private String currentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason).map(String::valueOf).orElse("1");
    }

    // ==================== Leagues overview ====================

    /**
     * Leagues overview for the leagues-overview frontend page. All first-division
     * leagues sorted by their nation's coefficient rank (rank 1 = strongest),
     * each with top N standings + qualification zones so the FE can colour each
     * row based on what that position earns.
     */
    public Map<String, Object> getLeaguesOverview(int topN) {
        int curSeason = Integer.parseInt(currentSeason());
        List<Long> sortedLeagueIds = europeanCoefficientService.getLeagueIdsSortedByCoefficient();

        List<Map<String, Object>> leagues = new ArrayList<>();
        for (int i = 0; i < sortedLeagueIds.size(); i++) {
            long leagueId = sortedLeagueIds.get(i);
            int rank = i + 1;
            Competition comp = competitionRepository.findById(leagueId).orElse(null);
            if (comp == null) continue;

            Map<String, Object> league = new LinkedHashMap<>();
            league.put("competitionId", leagueId);
            league.put("name", comp.getName());
            league.put("nationId", comp.getNationId());
            league.put("rank", rank);
            league.put("qualificationZones", computeLeagueQualificationZones(rank));

            List<TeamCompetitionDetail> standings = teamCompetitionDetailRepository.findAll().stream()
                    .filter(d -> d.getCompetitionId() == leagueId)
                    .sorted((a, b) -> {
                        if (a.getPoints() != b.getPoints()) return Integer.compare(b.getPoints(), a.getPoints());
                        if (a.getGoalDifference() != b.getGoalDifference())
                            return Integer.compare(b.getGoalDifference(), a.getGoalDifference());
                        return Integer.compare(b.getGoalsFor(), a.getGoalsFor());
                    })
                    .toList();

            List<Map<String, Object>> topTeams = new ArrayList<>();
            int limit = Math.min(topN, standings.size());
            for (int p = 0; p < limit; p++) {
                TeamCompetitionDetail s = standings.get(p);
                Team t = teamRepository.findById(s.getTeamId()).orElse(null);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("position", p + 1);
                row.put("teamId", s.getTeamId());
                row.put("teamName", t != null ? t.getName() : "?");
                row.put("played", s.getGames());
                row.put("wins", s.getWins());
                row.put("draws", s.getDraws());
                row.put("losses", s.getLoses());
                row.put("goalsFor", s.getGoalsFor());
                row.put("goalsAgainst", s.getGoalsAgainst());
                row.put("goalDifference", s.getGoalDifference());
                row.put("points", s.getPoints());
                row.put("form", s.getForm() != null ? s.getForm() : "");
                topTeams.add(row);
            }
            league.put("topTeams", topTeams);
            league.put("totalTeams", standings.size());

            leagues.add(league);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("season", curSeason);
        result.put("topN", topN);
        result.put("leagues", leagues);
        return result;
    }

    /**
     * 1-based qualification position lists for a league of the given rank,
     * mirroring the European-qualification logic. Used by the leagues overview
     * page so rows can be colour-coded.
     */
    private Map<String, Object> computeLeagueQualificationZones(int rank) {
        Map<String, Object> zones = new LinkedHashMap<>();
        List<Integer> locGroup = new ArrayList<>();
        List<Integer> locQualifying = new ArrayList<>();
        List<Integer> locPreliminary = new ArrayList<>();
        List<Integer> starsCup = new ArrayList<>();

        if (rank >= 1 && rank <= 7) {
            int[] directSpots =      {3, 3, 2, 2, 1, 1, 0};
            int[] qualifyingSpots =  {1, 1, 1, 1, 2, 1, 0};
            int[] preliminarySpots = {0, 0, 0, 0, 0, 0, 2};
            int[][] starsCupPositions = {{4}, {4}, {3}, {3}, {}, {}, {}};

            int idx = rank - 1;
            int pos = 0;
            for (int i = 0; i < directSpots[idx]; i++) locGroup.add(++pos);
            for (int i = 0; i < qualifyingSpots[idx]; i++) locQualifying.add(++pos);
            for (int i = 0; i < preliminarySpots[idx]; i++) locPreliminary.add(++pos);
            for (int p : starsCupPositions[idx]) starsCup.add(p + 1);
        }

        zones.put("locGroup", locGroup);
        zones.put("locQualifying", locQualifying);
        zones.put("locPreliminary", locPreliminary);
        zones.put("starsCup", starsCup);
        return zones;
    }

    // ==================== Team standings + competitions ====================

    public List<TeamCompetitionView> getHistoricalTeamDetails(long competitionId, long seasonNumber) {
        List<CompetitionHistory> teamParticipants = competitionHistoryRepository
                .findAll()
                .stream()
                .filter(competitionHistory -> competitionHistory.getCompetitionId() == competitionId
                        && competitionHistory.getSeasonNumber() == seasonNumber)
                .collect(Collectors.toList());

        List<TeamCompetitionView> teamCompetitionViews = new ArrayList<>();
        for (CompetitionHistory competitionHistory : teamParticipants) {
            teamCompetitionViews.add(adaptTeam(
                    teamRepository.findById(competitionHistory.getTeamId()).orElse(new Team()),
                    competitionHistory));
        }
        return teamCompetitionViews;
    }

    /**
     * Per-competition team standings rows. {@code humanTeamId} is the requesting
     * user's team (or null if unauthenticated) so the row matching it can be
     * flagged for the FE.
     */
    public List<TeamCompetitionView> getTeamDetails(long competitionId, Long humanTeamId) {
        List<Long> teamParticipantIds = competitionTeamInfoRepository
                .findAll()
                .stream()
                .filter(competitionTeamInfo -> competitionTeamInfo.getCompetitionId() == competitionId
                        && competitionTeamInfo.getSeasonNumber() == Long.valueOf(currentSeason()))
                .mapToLong(CompetitionTeamInfo::getTeamId)
                .boxed()
                .toList();

        List<TeamCompetitionView> teamCompetitionViews = new ArrayList<>();
        for (Long teamId : teamParticipantIds) {
            TeamCompetitionDetail teamCompetitionDetail = teamCompetitionDetailRepository
                    .findFirstByTeamIdAndCompetitionId(teamId, competitionId);
            Team team = teamRepository.findById(teamId).orElseGet(null);

            if (team == null || teamCompetitionDetail == null) {
                teamCompetitionDetail = new TeamCompetitionDetail();
                teamCompetitionDetail.setTeamId(teamId);
                teamCompetitionDetail.setCompetitionId(competitionId);
            }

            if (team != null) {
                teamCompetitionViews.add(adaptTeam(team, teamCompetitionDetail));
            }
        }

        // Sort by points desc, then goal difference, then goals for
        teamCompetitionViews.sort((a, b) -> {
            int ptsA = a.getPoints() != null ? Integer.parseInt(a.getPoints()) : 0;
            int ptsB = b.getPoints() != null ? Integer.parseInt(b.getPoints()) : 0;
            if (ptsA != ptsB) return ptsB - ptsA;
            int gdA = a.getGoalDifference() != null ? Integer.parseInt(a.getGoalDifference()) : 0;
            int gdB = b.getGoalDifference() != null ? Integer.parseInt(b.getGoalDifference()) : 0;
            if (gdA != gdB) return gdB - gdA;
            int gfA = a.getGoalsFor() != null ? Integer.parseInt(a.getGoalsFor()) : 0;
            int gfB = b.getGoalsFor() != null ? Integer.parseInt(b.getGoalsFor()) : 0;
            return gfB - gfA;
        });

        for (int i = 0; i < teamCompetitionViews.size(); i++) {
            teamCompetitionViews.get(i).setPosition(i + 1);
            teamCompetitionViews.get(i).setHumanTeam(humanTeamId != null
                    && teamCompetitionViews.get(i).getTeamId() == humanTeamId);
        }

        return teamCompetitionViews;
    }

    /**
     * Per-team summary of every competition they're in this season — league
     * standings row for leagues, round number for cups, group + round for LoC.
     */
    public List<Map<String, Object>> getTeamCompetitions(long teamId) {
        long curSeason = Long.parseLong(currentSeason());
        List<CompetitionTeamInfo> teamCompetitions = competitionTeamInfoRepository
                .findAllByTeamIdAndSeasonNumber(teamId, curSeason);

        List<Map<String, Object>> result = new ArrayList<>();
        for (CompetitionTeamInfo cti : teamCompetitions) {
            Map<String, Object> comp = new HashMap<>();
            Competition competition = competitionRepository.findById(cti.getCompetitionId()).orElse(null);
            if (competition == null) continue;

            comp.put("competitionId", competition.getId());
            comp.put("name", competition.getName());
            comp.put("typeId", competition.getTypeId());

            if (competition.getTypeId() == 1 || competition.getTypeId() == 3) {
                TeamCompetitionDetail detail = teamCompetitionDetailRepository
                        .findFirstByTeamIdAndCompetitionId(teamId, competition.getId());
                if (detail != null) {
                    comp.put("games", detail.getGames());
                    comp.put("wins", detail.getWins());
                    comp.put("draws", detail.getDraws());
                    comp.put("loses", detail.getLoses());
                    comp.put("goalsFor", detail.getGoalsFor());
                    comp.put("goalsAgainst", detail.getGoalsAgainst());
                    comp.put("goalDifference", detail.getGoalDifference());
                    comp.put("points", detail.getPoints());
                    comp.put("form", detail.getForm());

                    List<TeamCompetitionDetail> allTeams = teamCompetitionDetailRepository.findAll()
                            .stream()
                            .filter(d -> d.getCompetitionId() == competition.getId())
                            .sorted((a, b) -> {
                                if (a.getPoints() != b.getPoints()) return b.getPoints() - a.getPoints();
                                if (a.getGoalDifference() != b.getGoalDifference()) return b.getGoalDifference() - a.getGoalDifference();
                                return b.getGoalsFor() - a.getGoalsFor();
                            })
                            .toList();
                    int position = 1;
                    for (TeamCompetitionDetail t : allTeams) {
                        if (t.getTeamId() == teamId) break;
                        position++;
                    }
                    comp.put("position", position);
                    comp.put("totalTeams", allTeams.size());
                }
            }

            if (competition.getTypeId() == 2 || competition.getTypeId() == 5) {
                comp.put("cupRound", cti.getRound());
            }

            if (competition.getTypeId() == 4) {
                comp.put("groupNumber", cti.getGroupNumber());
                comp.put("cupRound", cti.getRound());
            }

            result.add(comp);
        }

        return result;
    }

    // ==================== Competition info + qualification zones ====================

    public Map<String, Object> getCompetitionInfo(Long id) {
        Competition comp = competitionRepository.findById(id).orElse(null);
        Map<String, Object> info = new HashMap<>();
        if (comp != null) {
            info.put("typeId", comp.getTypeId());
            info.put("name", comp.getName());

            if (comp.getTypeId() == 1 || comp.getTypeId() == 3) {
                Map<String, Object> zones = getQualificationZones(comp);
                info.put("locSpots", zones.get("locSpots"));
                info.put("starsCupSpots", zones.get("starsCupSpots"));
                info.put("relegationFrom", zones.get("relegationFrom"));
            }
        }
        return info;
    }

    /**
     * Number of LoC + Stars Cup qualification spots for a league, based on the
     * league's coefficient rank. Only first-division leagues (typeId 1) get
     * European spots — second leagues get an empty map.
     */
    private Map<String, Object> getQualificationZones(Competition comp) {
        Map<String, Object> zones = new HashMap<>();
        int locSpots = 0;
        int starsCupStart = 0;
        int starsCupEnd = 0;

        if (comp.getTypeId() == 1) {
            List<Long> sortedLeagueIds = europeanCoefficientService.getLeagueIdsSortedByCoefficient();
            int rank = sortedLeagueIds.indexOf(comp.getId()) + 1;

            if (rank >= 1 && rank <= 7) {
                int[] directSpotsDisplay =      {3, 3, 2, 2, 1, 1, 0};
                int[] qualifyingSpotsDisplay =  {1, 1, 1, 1, 2, 1, 0};
                int[] preliminarySpotsDisplay = {0, 0, 0, 0, 0, 0, 2};
                locSpots = directSpotsDisplay[rank - 1] + qualifyingSpotsDisplay[rank - 1] + preliminarySpotsDisplay[rank - 1];

                int[][] starsCupPositions = {
                    {4, 5},     // Rank 1: 5th-6th
                    {4, 5},     // Rank 2: 5th-6th
                    {3, 4},     // Rank 3: 4th-5th
                    {3, 4},     // Rank 4: 4th-5th
                    {3},        // Rank 5: cup spot only
                    {3},        // Rank 6: cup spot only
                    {2}         // Rank 7: cup spot only
                };
                int[] scPos = starsCupPositions[rank - 1];
                starsCupStart = scPos[0] + 1;
                starsCupEnd = scPos[scPos.length - 1] + 1;
            }
        }

        zones.put("locSpots", locSpots);
        zones.put("starsCupSpots", starsCupEnd > 0 ? starsCupEnd - starsCupStart + 1 : 0);
        zones.put("relegationFrom", 18);
        return zones;
    }

    // ==================== Match lookup ====================

    /** Future-round fixtures for a competition + round, scoped to current season. */
    public List<TeamMatchView> getNotPlayedMatches(long competitionId, long roundId) {
        String seasonStr = currentSeason();

        List<CompetitionTeamInfoMatch> futureMatches = competitionTeamInfoMatchRepository
                .findAll()
                .stream()
                .filter(m -> m.getCompetitionId() == competitionId && m.getRound() == roundId
                        && seasonStr.equals(m.getSeasonNumber()))
                .toList();

        List<TeamMatchView> matchViews = new ArrayList<>();
        for (CompetitionTeamInfoMatch match : futureMatches) {
            matchViews.add(adaptCompetitionTeamInfoMatch(match, competitionId, roundId));
        }
        return matchViews;
    }

    private TeamMatchView adaptCompetitionTeamInfoMatch(CompetitionTeamInfoMatch match, long competitionId, long roundId) {
        TeamMatchView teamMatchView = new TeamMatchView();
        teamMatchView.setTeamName1(teamRepository.findById(match.getTeam1Id()).get().getName());
        teamMatchView.setTeamName2(teamRepository.findById(match.getTeam2Id()).get().getName());

        CompetitionTeamInfoDetail matchDetail = competitionTeamInfoDetailRepository
                .findAllByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(
                        competitionId, roundId, match.getTeam1Id(), match.getTeam2Id(),
                        Long.parseLong(currentSeason()))
                .stream().findFirst().orElse(null);

        if (matchDetail != null) {
            teamMatchView.setScore(matchDetail.getScore());
        } else {
            teamMatchView.setScore("-");
        }
        return teamMatchView;
    }

    // ==================== Team count ====================

    /** Number of distinct teams in a competition this season. */
    public int getTeamCountForCompetition(long competitionId) {
        long curSeason = Long.parseLong(currentSeason());
        return (int) competitionTeamInfoRepository
                .findAllBySeasonNumber(curSeason).stream()
                .filter(cti -> cti.getCompetitionId() == competitionId)
                .map(CompetitionTeamInfo::getTeamId)
                .distinct()
                .count();
    }

    // ==================== Adapters ====================

    private TeamCompetitionView adaptTeam(Team team, CompetitionHistory teamCompetitionHistory) {
        TeamCompetitionView teamCompetitionView = new TeamCompetitionView();

        teamCompetitionView.setTeamId(team.getId());
        teamCompetitionView.setName(team.getName());
        teamCompetitionView.setColor1(team.getColor1());
        teamCompetitionView.setColor2(team.getColor2());
        teamCompetitionView.setBorder(team.getBorder());

        teamCompetitionView.setGames(String.valueOf(teamCompetitionHistory.getGames()));
        teamCompetitionView.setWins(String.valueOf(teamCompetitionHistory.getWins()));
        teamCompetitionView.setDraws(String.valueOf(teamCompetitionHistory.getDraws()));
        teamCompetitionView.setLoses(String.valueOf(teamCompetitionHistory.getLoses()));
        teamCompetitionView.setGoalsFor(String.valueOf(teamCompetitionHistory.getGoalsFor()));
        teamCompetitionView.setGoalsAgainst(String.valueOf(teamCompetitionHistory.getGoalsAgainst()));
        teamCompetitionView.setGoalDifference(String.valueOf(teamCompetitionHistory.getGoalDifference()));
        teamCompetitionView.setPoints(String.valueOf(teamCompetitionHistory.getPoints()));
        teamCompetitionView.setForm(teamCompetitionHistory.getForm());

        return teamCompetitionView;
    }

    private TeamCompetitionView adaptTeam(Team team, TeamCompetitionDetail teamCompetitionDetail) {
        TeamCompetitionView teamCompetitionView = new TeamCompetitionView();

        teamCompetitionView.setTeamId(team.getId());
        teamCompetitionView.setName(team.getName());
        teamCompetitionView.setColor1(team.getColor1());
        teamCompetitionView.setColor2(team.getColor2());
        teamCompetitionView.setBorder(team.getBorder());

        teamCompetitionView.setGames(String.valueOf(teamCompetitionDetail.getGames()));
        teamCompetitionView.setWins(String.valueOf(teamCompetitionDetail.getWins()));
        teamCompetitionView.setDraws(String.valueOf(teamCompetitionDetail.getDraws()));
        teamCompetitionView.setLoses(String.valueOf(teamCompetitionDetail.getLoses()));
        teamCompetitionView.setGoalsFor(String.valueOf(teamCompetitionDetail.getGoalsFor()));
        teamCompetitionView.setGoalsAgainst(String.valueOf(teamCompetitionDetail.getGoalsAgainst()));
        teamCompetitionView.setGoalDifference(String.valueOf(teamCompetitionDetail.getGoalDifference()));
        teamCompetitionView.setPoints(String.valueOf(teamCompetitionDetail.getPoints()));
        teamCompetitionView.setForm(teamCompetitionDetail.getForm());
        teamCompetitionView.setPositions(teamCompetitionDetail.getLast10Positions());

        return teamCompetitionView;
    }
}
