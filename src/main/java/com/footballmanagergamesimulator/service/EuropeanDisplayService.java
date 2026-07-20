package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ClubCoefficient;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.ClubCoefficientRepository;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only display surface for European competitions — group standings,
 * country/club coefficient leaderboards, and the static allocation summary
 * (LoC + Stars Cup slots per rank). Extracted from
 * {@link EuropeanCompetitionService} (sesiunea 6, §6.1) to keep the lifecycle
 * service focused on in-season mutations.
 *
 * <p>Five public surfaces — all read-only, all consumed by REST endpoints on
 * {@code CompetitionController}:
 * <ul>
 *   <li>{@link #getEuropeanSummary} — static config dump: per-rank LoC + SC
 *       slot totals, group format, points matrix. No DB state involved beyond
 *       counting leagues.</li>
 *   <li>{@link #assignEuropeanAllocation} — attaches per-rank slot counts to
 *       an output map (used by {@link #getCountryCoefficients}).</li>
 *   <li>{@link #getEuropeanGroups} — per-team group standings; computes live
 *       from match results for the current season, or reads
 *       {@link CompetitionHistory} for past seasons.</li>
 *   <li>{@link #getCountryCoefficients} — leagues ranked by 5-season country
 *       coefficient with their per-season breakdown.</li>
 *   <li>{@link #getClubCoefficients} — per-club 5-season coefficient
 *       breakdown.</li>
 * </ul>
 *
 * <p>This service does NOT depend on {@link EuropeanCoefficientService}; the
 * country/club coefficient bodies read raw {@code ClubCoefficient} rows and
 * compute aggregates inline. A future slice could fold that math back into
 * the coefficient service for DRY, but the current shape keeps each public
 * endpoint self-contained.
 */
@Service
public class EuropeanDisplayService {

    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired private CompetitionHistoryRepository competitionHistoryRepository;
    @Autowired private ClubCoefficientRepository clubCoefficientRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private com.footballmanagergamesimulator.config.EuropeanQualificationPolicy qualificationPolicy;

    // ============================================================
    //  Static config + per-rank allocation
    // ============================================================

    /** Static summary of LoC + SC sizes, group format, and points matrix. */
    public Map<String, Object> getEuropeanSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();

        // LoC allocation totals
        int numLeagues = (int) competitionRepository.findAll().stream().filter(c -> c.getTypeId() == 1).count();
        int totalRanks = Math.min(numLeagues, 7);

        int totalDirect = 0, totalQualifying = 0, totalPreliminary = 0;
        for (int i = 0; i < totalRanks; i++) {
            totalDirect += qualificationPolicy.directForRank(i + 1);
            totalQualifying += qualificationPolicy.qualifyingForRank(i + 1);
            totalPreliminary += qualificationPolicy.preliminaryForRank(i + 1);
        }
        int locTotal = totalDirect + totalQualifying + totalPreliminary;

        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("totalTeams", locTotal);
        loc.put("directToGroups", totalDirect);
        loc.put("qualifyingTeams", totalQualifying);
        loc.put("preliminaryTeams", totalPreliminary);
        loc.put("groupStageTeams", 16);
        loc.put("groups", 4);
        loc.put("teamsPerGroup", 4);
        loc.put("advancePerGroup", 2);
        summary.put("loc", loc);

        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("totalTeams", 16);
        sc.put("groups", 4);
        sc.put("teamsPerGroup", 4);
        sc.put("format", "Group winners to QF, runners-up play LoC 3rd place in Playoff, then QF → SF → Final");
        summary.put("starsCup", sc);

        // Points system
        Map<String, Object> locPoints = new LinkedHashMap<>();
        locPoints.put("Preliminary/Qualifying win", "1 pt");
        locPoints.put("Group stage win", "2 pts");
        locPoints.put("Group stage draw", "1 pt");
        locPoints.put("Quarter-Final win", "3 pts");
        locPoints.put("Semi-Final win", "4 pts");
        locPoints.put("Final win", "5 pts");
        summary.put("locPoints", locPoints);

        Map<String, Object> scPoints = new LinkedHashMap<>();
        scPoints.put("Group stage win", "1 pt");
        scPoints.put("Group stage draw", "0.5 pts");
        scPoints.put("Playoff win", "1 pt");
        scPoints.put("Quarter-Final win", "1.5 pts");
        scPoints.put("Semi-Final win", "2 pts");
        scPoints.put("Final win", "2.5 pts");
        summary.put("starsCupPoints", scPoints);

        return summary;
    }

    /**
     * Per-rank LoC + Stars Cup slot counts. Attaches {@code locSpots},
     * {@code locEntry} (human-readable), and {@code starsCupSpots} fields
     * onto {@code entry}. Used by {@link #getCountryCoefficients} for the
     * ranking display.
     */
    public void assignEuropeanAllocation(Map<String, Object> entry, int rank) {
        // LoC direct + qualifying + preliminary spots per rank (non-increasing)
        // Stars Cup: league spots + 1 cup reserved per nation (non-increasing)
        int[] scLeagueSpots = {1, 1, 1, 1, 0, 0, 0};
        int[] scCupSpots =    {1, 1, 1, 1, 1, 1, 1};

        if (rank >= 1 && rank <= 7) {
            int idx = rank - 1;
            int direct = qualificationPolicy.directForRank(rank);
            int qualifying = qualificationPolicy.qualifyingForRank(rank);
            int preliminary = qualificationPolicy.preliminaryForRank(rank);
            int locTotal = direct + qualifying + preliminary;
            entry.put("locSpots", locTotal);

            String locEntry;
            if (direct > 0 && qualifying > 0) {
                locEntry = direct + " Group Stage + " + qualifying + " Qualifying Round 2";
            } else if (preliminary > 0) {
                locEntry = preliminary + " Qualifying Round 1";
            } else if (direct > 0) {
                locEntry = direct + " Group Stage";
            } else {
                locEntry = "None";
            }
            entry.put("locEntry", locEntry);
            entry.put("starsCupSpots", scLeagueSpots[idx] + scCupSpots[idx]);
        } else {
            entry.put("locSpots", 0);
            entry.put("locEntry", "None");
            entry.put("starsCupSpots", 0);
        }
    }

    // ============================================================
    //  Group standings (live + historical)
    // ============================================================

    /**
     * Per-team group standings for a European competition + season. Current
     * season is computed live from match results (rounds 1-6 for Stars Cup,
     * 2-7 for LoC); past seasons read from {@link CompetitionHistory}.
     */
    public List<Map<String, Object>> getEuropeanGroups(long competitionId, long season) {

        long currentSeason = Long.parseLong(currentSeason());

        // CompetitionTeamInfo persists across seasons, so group assignments are always available
        List<CompetitionTeamInfo> entries = competitionTeamInfoRepository
                .findAllBySeasonNumber(season).stream()
                .filter(cti -> cti.getCompetitionId() == competitionId && cti.getGroupNumber() > 0)
                .toList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (CompetitionTeamInfo cti : entries) {
            Map<String, Object> entry = new HashMap<>();
            Team team = teamRepository.findById(cti.getTeamId()).orElse(null);
            entry.put("teamId", cti.getTeamId());
            entry.put("teamName", team != null ? team.getName() : "Unknown");
            entry.put("groupNumber", cti.getGroupNumber());
            entry.put("potNumber", cti.getPotNumber());

            if (season == currentSeason) {
                // Current season: compute group standings from match results (exclude knockout rounds)
                Competition comp = competitionRepository.findById(competitionId).orElse(null);
                int groupRoundMin = 2, groupRoundMax = 7; // LoC defaults
                if (comp != null && comp.getTypeId() == 5) {
                    groupRoundMin = 1; groupRoundMax = 6; // Stars Cup
                }
                int games = 0, wins = 0, draws = 0, loses = 0, goalsFor = 0, goalsAgainst = 0;
                long teamId = cti.getTeamId();
                for (int r = groupRoundMin; r <= groupRoundMax; r++) {
                    List<CompetitionTeamInfoDetail> matchesAsHome = competitionTeamInfoDetailRepository
                            .findAllByCompetitionIdAndRoundIdAndSeasonNumber(competitionId, r, season)
                            .stream().filter(d -> d.getTeam1Id() == teamId).toList();
                    List<CompetitionTeamInfoDetail> matchesAsAway = competitionTeamInfoDetailRepository
                            .findAllByCompetitionIdAndRoundIdAndSeasonNumber(competitionId, r, season)
                            .stream().filter(d -> d.getTeam2Id() == teamId).toList();
                    for (CompetitionTeamInfoDetail d : matchesAsHome) {
                        if (d.getScore() == null) continue;
                        String[] parts = d.getScore().split("-");
                        if (parts.length != 2) continue;
                        int g1 = Integer.parseInt(parts[0].trim()), g2 = Integer.parseInt(parts[1].trim());
                        games++; goalsFor += g1; goalsAgainst += g2;
                        if (g1 > g2) wins++; else if (g1 < g2) loses++; else draws++;
                    }
                    for (CompetitionTeamInfoDetail d : matchesAsAway) {
                        if (d.getScore() == null) continue;
                        String[] parts = d.getScore().split("-");
                        if (parts.length != 2) continue;
                        int g1 = Integer.parseInt(parts[0].trim()), g2 = Integer.parseInt(parts[1].trim());
                        games++; goalsFor += g2; goalsAgainst += g1;
                        if (g2 > g1) wins++; else if (g2 < g1) loses++; else draws++;
                    }
                }
                entry.put("games", games);
                entry.put("wins", wins);
                entry.put("draws", draws);
                entry.put("loses", loses);
                entry.put("goalsFor", goalsFor);
                entry.put("goalsAgainst", goalsAgainst);
                entry.put("goalDifference", goalsFor - goalsAgainst);
                entry.put("points", wins * 3 + draws);
            } else {
                // Previous season: use CompetitionHistory
                Optional<CompetitionHistory> histOpt = competitionHistoryRepository.findAll().stream()
                        .filter(h -> h.getTeamId() == cti.getTeamId()
                                && h.getCompetitionId() == competitionId
                                && h.getSeasonNumber() == season)
                        .findFirst();
                if (histOpt.isPresent()) {
                    CompetitionHistory hist = histOpt.get();
                    entry.put("games", hist.getGames());
                    entry.put("wins", hist.getWins());
                    entry.put("draws", hist.getDraws());
                    entry.put("loses", hist.getLoses());
                    entry.put("goalsFor", hist.getGoalsFor());
                    entry.put("goalsAgainst", hist.getGoalsAgainst());
                    entry.put("goalDifference", hist.getGoalDifference());
                    entry.put("points", hist.getPoints());
                } else {
                    entry.put("games", 0); entry.put("wins", 0); entry.put("draws", 0);
                    entry.put("loses", 0); entry.put("goalsFor", 0); entry.put("goalsAgainst", 0);
                    entry.put("goalDifference", 0); entry.put("points", 0);
                }
            }
            result.add(entry);
        }
        return result;
    }

    // ============================================================
    //  Country + club coefficient leaderboards
    // ============================================================

    /**
     * Country coefficient ranking across the last 5 seasons. For each nation:
     * sum(per-season club coefficients) / count(clubs participating in European
     * comps that season), summed across seasons. Each entry is then ranked
     * and gets {@link #assignEuropeanAllocation} attached so the FE can show
     * "2 LoC spots + 1 SC spot" etc.
     */
    public List<Map<String, Object>> getCountryCoefficients() {
        int curSeason = Integer.parseInt(currentSeason());
        List<Competition> firstLeagues = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1).toList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Competition league : firstLeagues) {
            long leagueId = league.getId();
            long nationId = league.getNationId();

            double countryCoefficient = 0;
            Map<Integer, Double> perSeasonValues = new LinkedHashMap<>();

            for (int s = Math.max(1, curSeason - 4); s <= curSeason; s++) {
                int seasonFinal = s;
                List<CompetitionTeamInfo> europeanEntries = competitionTeamInfoRepository
                        .findAllBySeasonNumber(seasonFinal).stream()
                        .filter(cti -> {
                            Competition comp = competitionRepository.findById(cti.getCompetitionId()).orElse(null);
                            if (comp == null) return false;
                            return (comp.getTypeId() == 4 || comp.getTypeId() == 5);
                        })
                        .toList();

                Set<Long> clubsFromNation = new HashSet<>();
                double seasonPoints = 0;
                for (CompetitionTeamInfo cti : europeanEntries) {
                    Team team = teamRepository.findById(cti.getTeamId()).orElse(null);
                    if (team == null) continue;
                    Competition teamLeague = competitionRepository.findById(team.getCompetitionId()).orElse(null);
                    if (teamLeague != null && teamLeague.getNationId() == nationId) {
                        clubsFromNation.add(cti.getTeamId());
                    }
                }

                for (long clubId : clubsFromNation) {
                    Optional<ClubCoefficient> cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(clubId, seasonFinal);
                    if (cc.isPresent()) seasonPoints += cc.get().getPoints();
                }

                double seasonRatio = clubsFromNation.isEmpty() ? 0 : seasonPoints / clubsFromNation.size();
                perSeasonValues.put(s, seasonRatio);
                countryCoefficient += seasonRatio;
            }

            Map<String, Object> entry = new HashMap<>();
            entry.put("leagueId", leagueId);
            entry.put("leagueName", league.getName());
            entry.put("nationId", nationId);
            entry.put("coefficient", Math.round(countryCoefficient * 100.0) / 100.0);
            entry.put("perSeason", perSeasonValues);
            result.add(entry);
        }

        result.sort((a, b) -> Double.compare((double) b.get("coefficient"), (double) a.get("coefficient")));

        for (int i = 0; i < result.size(); i++) {
            int rank = i + 1;
            Map<String, Object> entry = result.get(i);
            entry.put("rank", rank);
            assignEuropeanAllocation(entry, rank);
        }

        return result;
    }

    /**
     * Club coefficient ranking. Every club that participated in European
     * competition in any of the last 5 seasons gets a row with its
     * per-season points + 5-year total, sorted descending.
     */
    public List<Map<String, Object>> getClubCoefficients() {
        int curSeason = Integer.parseInt(currentSeason());

        Set<Long> europeanClubIds = new HashSet<>();
        for (int s = Math.max(1, curSeason - 4); s <= curSeason; s++) {
            int sFinal = s;
            competitionTeamInfoRepository.findAllBySeasonNumber(sFinal).stream()
                    .filter(cti -> {
                        Competition comp = competitionRepository.findById(cti.getCompetitionId()).orElse(null);
                        return comp != null && (comp.getTypeId() == 4 || comp.getTypeId() == 5);
                    })
                    .forEach(cti -> europeanClubIds.add(cti.getTeamId()));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (long clubId : europeanClubIds) {
            Team team = teamRepository.findById(clubId).orElse(null);
            if (team == null) continue;

            double totalCoeff = 0;
            Map<Integer, Double> perSeason = new LinkedHashMap<>();
            for (int s = Math.max(1, curSeason - 4); s <= curSeason; s++) {
                Optional<ClubCoefficient> cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(clubId, s);
                double pts = cc.map(ClubCoefficient::getPoints).orElse(0.0);
                perSeason.put(s, pts);
                totalCoeff += pts;
            }

            Competition league = competitionRepository.findById(team.getCompetitionId()).orElse(null);

            Map<String, Object> entry = new HashMap<>();
            entry.put("teamId", clubId);
            entry.put("teamName", team.getName());
            entry.put("leagueName", league != null ? league.getName() : "");
            entry.put("coefficient", Math.round(totalCoeff * 100.0) / 100.0);
            entry.put("perSeason", perSeason);
            result.add(entry);
        }

        result.sort((a, b) -> Double.compare((double) b.get("coefficient"), (double) a.get("coefficient")));

        for (int i = 0; i < result.size(); i++) {
            result.get(i).put("rank", i + 1);
        }

        return result;
    }

    // ============================================================
    //  Helpers
    // ============================================================

    /** Loads the current season number from the singleton Round row. */
    private String currentSeason() {
        return String.valueOf(roundRepository.findById(1L).map(Round::getSeason).orElse(1L));
    }
}
