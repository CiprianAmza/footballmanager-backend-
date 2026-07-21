package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ClubCoefficient;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.config.CompetitionFormat;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.repository.ClubCoefficientRepository;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *   <li>{@link #getEuropeanGroups} — per-team group standings rebuilt from
 *       the persisted group-stage match results for any season.</li>
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
    @Autowired private ClubCoefficientRepository clubCoefficientRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private com.footballmanagergamesimulator.config.EuropeanQualificationPolicy qualificationPolicy;
    @Autowired private CompetitionFormatConfig competitionFormatConfig;

    private static final Pattern OFFICIAL_SCORE = Pattern.compile("^\\s*(\\d+)\\s*-\\s*(\\d+)");

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
     * Per-team group standings for a European competition + season. Both live
     * and historical tables are rebuilt from the authoritative match rows and
     * strictly limited to the group rounds declared by {@link CompetitionFormat}.
     * {@link CompetitionHistory} is deliberately not used here because it is a
     * whole-competition aggregate (qualifiers + groups + knockout).
     */
    public List<Map<String, Object>> getEuropeanGroups(long competitionId, long season) {
        // CompetitionTeamInfo persists across seasons, so group assignments are always available
        List<CompetitionTeamInfo> entries = competitionTeamInfoRepository
                .findAllBySeasonNumber(season).stream()
                .filter(cti -> cti.getCompetitionId() == competitionId && cti.getGroupNumber() > 0)
                .toList();

        Competition competition = competitionRepository.findById(competitionId).orElse(null);
        if (competition == null) return List.of();
        CompetitionFormat format = competitionFormatConfig.get((int) competition.getTypeId());

        Map<Long, Integer> groupsByTeam = entries.stream().collect(java.util.stream.Collectors.toMap(
                CompetitionTeamInfo::getTeamId,
                CompetitionTeamInfo::getGroupNumber,
                (first, ignored) -> first));
        Map<Long, GroupStanding> standings = new HashMap<>();
        groupsByTeam.keySet().forEach(teamId -> standings.put(teamId, new GroupStanding()));

        for (CompetitionTeamInfoDetail match : competitionTeamInfoDetailRepository
                .findAllByCompetitionIdAndSeasonNumber(competitionId, season)) {
            if (!format.isGroupRound(match.getRoundId())) continue;
            Integer homeGroup = groupsByTeam.get(match.getTeam1Id());
            Integer awayGroup = groupsByTeam.get(match.getTeam2Id());
            // A group match must involve two members of the same persisted group.
            // This also protects historical tables from cross-stage/drop-in rows.
            if (homeGroup == null || !homeGroup.equals(awayGroup)) continue;
            int[] score = parseOfficialScore(match.getScore());
            if (score == null) continue;
            standings.get(match.getTeam1Id()).record(score[0], score[1]);
            standings.get(match.getTeam2Id()).record(score[1], score[0]);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (CompetitionTeamInfo cti : entries) {
            Map<String, Object> entry = new HashMap<>();
            Team team = teamRepository.findById(cti.getTeamId()).orElse(null);
            entry.put("teamId", cti.getTeamId());
            entry.put("teamName", team != null ? team.getName() : "Unknown");
            entry.put("groupNumber", cti.getGroupNumber());
            entry.put("potNumber", cti.getPotNumber());
            GroupStanding standing = standings.getOrDefault(cti.getTeamId(), new GroupStanding());
            entry.put("games", standing.games);
            entry.put("wins", standing.wins);
            entry.put("draws", standing.draws);
            entry.put("loses", standing.losses);
            entry.put("goalsFor", standing.goalsFor);
            entry.put("goalsAgainst", standing.goalsAgainst);
            entry.put("goalDifference", standing.goalsFor - standing.goalsAgainst);
            entry.put("points", standing.wins * 3 + standing.draws);
            result.add(entry);
        }
        return result;
    }

    private int[] parseOfficialScore(String score) {
        if (score == null || score.isBlank()) return null;
        Matcher matcher = OFFICIAL_SCORE.matcher(score);
        if (!matcher.find()) return null;
        return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    private static final class GroupStanding {
        private int games;
        private int wins;
        private int draws;
        private int losses;
        private int goalsFor;
        private int goalsAgainst;

        private void record(int scored, int conceded) {
            games++;
            goalsFor += scored;
            goalsAgainst += conceded;
            if (scored > conceded) wins++;
            else if (scored < conceded) losses++;
            else draws++;
        }
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
