package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates the FULL bracket for a national cup at season start.
 *
 * Algorithm
 * ---------
 *  - N = total teams in the nation (all leagues feeding this cup)
 *  - M = largest power of 2 ≤ N
 *  - Preliminary round has N - M matches; bottom 2(N - M) seeds play it
 *  - Top 2M - N seeds are auto-qualified into the round of M
 *  - Then standard knockout: M → M/2 → ... → 1
 *
 * Seeding
 * -------
 *  - Season 1: by team reputation (best reputation = seed 1)
 *  - Season 2+: by last season's league standings (1st place of 1st league = seed 1;
 *    LAST place of LAST league = seed N) — the worst teams play prelim
 *
 * Balanced bracket
 * ----------------
 *  - Seeds are placed in bracket positions following the standard tennis recursion
 *    [1, 2] → [1, 4, 2, 3] → [1, 8, 4, 5, 2, 7, 3, 6] → ... → balanced(M)
 *  - Bracket pairings are (slot 2k-1 vs slot 2k); winner of match i advances to
 *    match ceil(i/2) of next round, as team1 if i is odd, team2 if i is even.
 *  - With balanced ordering, top seeds can only meet in late rounds.
 *
 * Match propagation
 * -----------------
 *  - All matches for all rounds are pre-created up-front in CompetitionTeamInfoMatch.
 *  - Auto-qualified slots have team1Id/team2Id filled in immediately.
 *  - Other slots have 0 placeholders; after a round simulates, the winner is
 *    written into the corresponding slot of the next round (see propagateWinner()).
 */
@Service
public class CupBracketService {

    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired private CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private EuropeanCoefficientService europeanCoefficientService;
    @Autowired private CompetitionDisplayService competitionDisplayService;

    private int currentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason).map(Long::intValue).orElse(1);
    }

    /**
     * (Re)generates the full bracket for one cup in one season.
     * Wipes any existing bracket rows for that (cup, season) first.
     */
    @Transactional
    public void generateBracket(long cupId, int season) {
        Competition cup = competitionRepository.findById(cupId).orElse(null);
        if (cup == null || cup.getTypeId() != 2L) return;

        String seasonStr = String.valueOf(season);

        // 1) Wipe any previous bracket for this cup+season
        wipeExistingBracket(cupId, season, seasonStr);

        // 2) Find all teams in this nation (across all of its leagues)
        List<Team> seededTeams = seedTeams(cup.getNationId(), season);
        int n = seededTeams.size();
        if (n < 2) {
            System.out.println("=== CupBracket: cup=" + cupId + " season=" + season
                    + " has only " + n + " teams, skipping");
            return;
        }

        // 3) Compute bracket dimensions
        int m = largestPowerOfTwoAtMost(n);
        int prelimMatches = n - m;          // bottom 2*prelimMatches teams play prelim
        int autoQualified = 2 * m - n;      // top auto teams skip prelim
        int prelimRound = (prelimMatches > 0) ? 1 : 0;
        int firstMainRound = (prelimMatches > 0) ? 2 : 1;  // round-of-M

        System.out.println("=== CupBracket: cup=" + cupId + " season=" + season
                + " N=" + n + " M=" + m + " prelim=" + prelimMatches + " auto=" + autoQualified);

        // 4) Build balanced bracket order of size M (returns seed numbers per position)
        int[] balancedOrder = balancedBracketOrder(m);

        // 5) Generate prelim matches and CompetitionTeamInfo for prelim teams
        // Pairing: best-of-prelim (seed auto+1) vs worst (seed N), next pair (auto+2 vs N-1), etc.
        // Each prelim match k has bestSeed = auto + k.
        // Its winner advances to bracket position whose balanced seed == bestSeed.
        List<CompetitionTeamInfoMatch> prelimMatchEntities = new ArrayList<>();
        for (int k = 1; k <= prelimMatches; k++) {
            int bestSeed = autoQualified + k;     // 1-based seed
            int worstSeed = n - k + 1;
            Team better = seededTeams.get(bestSeed - 1);
            Team worse = seededTeams.get(worstSeed - 1);

            CompetitionTeamInfoMatch match = new CompetitionTeamInfoMatch();
            match.setCompetitionId(cupId);
            match.setSeasonNumber(seasonStr);
            match.setRound(1L);                   // prelim is round 1 when present
            match.setMatchIndex(k);
            match.setTeam1Id(better.getId());
            match.setTeam2Id(worse.getId());
            prelimMatchEntities.add(match);

            // Register both teams in CompetitionTeamInfo for this cup, round 1
            registerTeamInRound(cupId, seasonStr, 1L, better.getId());
            registerTeamInRound(cupId, seasonStr, 1L, worse.getId());
        }
        if (!prelimMatchEntities.isEmpty()) {
            competitionTeamInfoMatchRepository.saveAll(prelimMatchEntities);
        }

        // 6) Generate round-of-M matches with auto-qualified slots filled in
        // Bracket positions 1..M. Pair (1,2), (3,4), ..., (M-1, M).
        // For each position pos, balancedOrder[pos-1] is the "seed" at that slot.
        //   - If seed <= autoQualified → it's that auto-qualified team
        //   - Else → it's a prelim-winner placeholder (filled later by propagateWinner)
        List<CompetitionTeamInfoMatch> mainRoundMatches = new ArrayList<>();
        int mainRoundMatchCount = m / 2;
        for (int matchIdx = 1; matchIdx <= mainRoundMatchCount; matchIdx++) {
            int pos1 = matchIdx * 2 - 1;
            int pos2 = matchIdx * 2;
            int seed1 = balancedOrder[pos1 - 1];
            int seed2 = balancedOrder[pos2 - 1];

            long team1Id = 0L, team2Id = 0L;
            if (seed1 <= autoQualified) {
                Team t = seededTeams.get(seed1 - 1);
                team1Id = t.getId();
                registerTeamInRound(cupId, seasonStr, (long) firstMainRound, t.getId());
            }
            if (seed2 <= autoQualified) {
                Team t = seededTeams.get(seed2 - 1);
                team2Id = t.getId();
                registerTeamInRound(cupId, seasonStr, (long) firstMainRound, t.getId());
            }

            CompetitionTeamInfoMatch match = new CompetitionTeamInfoMatch();
            match.setCompetitionId(cupId);
            match.setSeasonNumber(seasonStr);
            match.setRound((long) firstMainRound);
            match.setMatchIndex(matchIdx);
            match.setTeam1Id(team1Id);
            match.setTeam2Id(team2Id);
            mainRoundMatches.add(match);
        }
        competitionTeamInfoMatchRepository.saveAll(mainRoundMatches);

        // 7) Generate placeholder matches for all subsequent rounds
        int currentSize = m / 2;   // matches in firstMainRound
        long currentRound = firstMainRound;
        while (currentSize > 1) {
            currentRound++;
            int nextRoundMatchCount = currentSize / 2;
            List<CompetitionTeamInfoMatch> placeholders = new ArrayList<>();
            for (int matchIdx = 1; matchIdx <= nextRoundMatchCount; matchIdx++) {
                CompetitionTeamInfoMatch m2 = new CompetitionTeamInfoMatch();
                m2.setCompetitionId(cupId);
                m2.setSeasonNumber(seasonStr);
                m2.setRound(currentRound);
                m2.setMatchIndex(matchIdx);
                m2.setTeam1Id(0L);
                m2.setTeam2Id(0L);
                placeholders.add(m2);
            }
            competitionTeamInfoMatchRepository.saveAll(placeholders);
            currentSize = nextRoundMatchCount;
        }
    }

    /**
     * Called after a cup match is simulated. Writes the winner into the corresponding
     * slot in the next round's pre-created match. No-op if there is no next round.
     *
     * Mapping:
     *  - For preliminary → round-of-M: target slot is the bracket position where the
     *    prelim match's "best seed" sits in the balanced order — NOT a simple ceil(i/2).
     *    Using ceil(i/2) here would overwrite auto-qualified teams' slots.
     *  - For all subsequent rounds (R16 → QF → SF → F): standard ceil(i/2) since those
     *    matches are pre-created with two placeholder slots that fill sequentially.
     */
    @Transactional
    public void propagateWinner(long cupId, int season, long currentRound, int currentMatchIndex,
                                 long winnerTeamId) {
        if (winnerTeamId <= 0) return;

        String seasonStr = String.valueOf(season);
        long nextRound = currentRound + 1;

        // Count matches in current and next round to detect the prelim → main transition.
        int currentRoundSize = countMatchesInRound(cupId, seasonStr, currentRound);
        int nextRoundSize = countMatchesInRound(cupId, seasonStr, nextRound);
        if (nextRoundSize == 0) return; // final was just played, nothing to propagate

        int nextMatchIndex;
        boolean isTeam1Slot;

        // Regular knockout transition halves the match count (8 → 4 → 2 → 1).
        // ANYTHING ELSE — prelim_count == mainRoundCount (N=12, N=24) or
        // prelim_count < mainRoundCount (N=20) — means this is prelim → main round
        // and needs the seed-based mapping. Using "<" alone misses the equal cases
        // and leaves auto-qualified slots overwritten + prelim-winner slots empty.
        boolean isPrelimTransition = (nextRoundSize != currentRoundSize / 2);

        if (isPrelimTransition) {
            // ===== Preliminary → round-of-M (special seeded mapping) =====
            int m = nextRoundSize * 2;
            int autoQualified = m - currentRoundSize;
            int[] balanced = balancedBracketOrder(m);

            // Prelim match k pairs the best-of-prelim seed (autoQualified + k) with worst
            int bestSeed = autoQualified + currentMatchIndex;
            int targetPos = -1;
            for (int i = 0; i < balanced.length; i++) {
                if (balanced[i] == bestSeed) { targetPos = i + 1; break; }
            }
            if (targetPos < 0) return; // safety: nothing to fill

            nextMatchIndex = (targetPos + 1) / 2;        // == ceil(targetPos/2)
            isTeam1Slot = (targetPos % 2 == 1);
        } else {
            // ===== Regular knockout (R16 → QF → SF → F) =====
            nextMatchIndex = (currentMatchIndex + 1) / 2;
            isTeam1Slot = (currentMatchIndex % 2 == 1);
        }

        Optional<CompetitionTeamInfoMatch> nextOpt = competitionTeamInfoMatchRepository
                .findOneByCompetitionRoundIndex(cupId, nextRound, nextMatchIndex, seasonStr);
        if (nextOpt.isEmpty()) return;

        CompetitionTeamInfoMatch next = nextOpt.get();
        if (isTeam1Slot) {
            next.setTeam1Id(winnerTeamId);
        } else {
            next.setTeam2Id(winnerTeamId);
        }
        competitionTeamInfoMatchRepository.save(next);

        registerTeamInRound(cupId, seasonStr, nextRound, winnerTeamId);
    }

    private int countMatchesInRound(long cupId, String seasonStr, long round) {
        return (int) competitionTeamInfoMatchRepository.findAll().stream()
                .filter(m -> m.getCompetitionId() == cupId
                        && seasonStr.equals(m.getSeasonNumber())
                        && m.getRound() == round)
                .count();
    }

    // ============================================================
    //                     helpers
    // ============================================================

    private void wipeExistingBracket(long cupId, int season, String seasonStr) {
        // Remove old CompetitionTeamInfo for this cup+season
        List<CompetitionTeamInfo> oldInfo = competitionTeamInfoRepository.findAllBySeasonNumber((long) season)
                .stream().filter(cti -> cti.getCompetitionId() == cupId).toList();
        if (!oldInfo.isEmpty()) competitionTeamInfoRepository.deleteAll(oldInfo);

        // Remove old matches for this cup+season
        List<CompetitionTeamInfoMatch> oldMatches = competitionTeamInfoMatchRepository.findAll().stream()
                .filter(m -> m.getCompetitionId() == cupId && seasonStr.equals(m.getSeasonNumber()))
                .toList();
        if (!oldMatches.isEmpty()) competitionTeamInfoMatchRepository.deleteAll(oldMatches);
    }

    /**
     * Returns the teams of this nation, sorted from best (index 0 = seed 1) to worst.
     * Season 1: by reputation desc. Season 2+: by last season's standings within each
     * league, then by league id ascending so that 1st-league teams come before 2nd-league.
     */
    public List<Team> seedTeams(long nationId, int season) {
        // All leagues/second-leagues in this nation
        List<Competition> leaguesInNation = competitionRepository.findAll().stream()
                .filter(c -> c.getNationId() == nationId && (c.getTypeId() == 1L || c.getTypeId() == 3L))
                .sorted(Comparator
                        .comparingLong(Competition::getTypeId)         // typeId 1 (first league) before 3
                        .thenComparingLong(Competition::getId))
                .toList();

        // Teams in those leagues
        List<Team> teams = new ArrayList<>();
        for (Competition league : leaguesInNation) {
            teams.addAll(teamRepository.findAllByCompetitionId(league.getId()));
        }

        if (season <= 1) {
            // Season 1: pure reputation desc (best reputation = seed 1)
            teams.sort((a, b) -> Integer.compare(b.getReputation(), a.getReputation()));
            return teams;
        }

        // Season 2+: by last season's standings within each league.
        // Per league, sort by (points desc, goalDiff desc, goalsFor desc).
        // Across leagues, first-league teams come BEFORE second-league teams in seeding
        // (so a 12th-place first-league team is still a higher seed than the 1st-place
        // second-league team — strongest pool first).
        // This matches the user's request: "from last league to first league, last
        // position to first" yields the same ORDER reversed — worst first → so we use
        // the same final ordering and let the algorithm pick prelim from the bottom.
        List<Team> seeded = new ArrayList<>();
        for (Competition league : leaguesInNation) {
            List<TeamCompetitionDetail> standings = teamCompetitionDetailRepository.findAll().stream()
                    .filter(d -> d.getCompetitionId() == league.getId())
                    .sorted((a, b) -> {
                        if (a.getPoints() != b.getPoints()) return Integer.compare(b.getPoints(), a.getPoints());
                        if (a.getGoalDifference() != b.getGoalDifference())
                            return Integer.compare(b.getGoalDifference(), a.getGoalDifference());
                        return Integer.compare(b.getGoalsFor(), a.getGoalsFor());
                    })
                    .toList();
            for (TeamCompetitionDetail s : standings) {
                Team t = teamRepository.findById(s.getTeamId()).orElse(null);
                if (t != null) seeded.add(t);
            }
        }

        // Any team in the nation that didn't have a standings row (edge cases) gets appended at the end
        Set<Long> placedIds = seeded.stream().map(Team::getId).collect(Collectors.toSet());
        for (Team t : teams) {
            if (!placedIds.contains(t.getId())) seeded.add(t);
        }
        return seeded;
    }

    /** Largest power of 2 ≤ n. */
    static int largestPowerOfTwoAtMost(int n) {
        int p = 1;
        while ((p << 1) <= n) p <<= 1;
        return p;
    }

    /**
     * Standard balanced bracket order recursion.
     * balancedBracketOrder(2)  = [1, 2]
     * balancedBracketOrder(4)  = [1, 4, 2, 3]
     * balancedBracketOrder(8)  = [1, 8, 4, 5, 2, 7, 3, 6]
     * balancedBracketOrder(16) = [1, 16, 8, 9, 4, 13, 5, 12, 2, 15, 7, 10, 3, 14, 6, 11]
     */
    static int[] balancedBracketOrder(int m) {
        if (m == 1) return new int[]{1};
        int[] result = new int[]{1, 2};
        while (result.length < m) {
            int sumNext = result.length * 2 + 1;
            int[] next = new int[result.length * 2];
            for (int i = 0; i < result.length; i++) {
                next[i * 2] = result[i];
                next[i * 2 + 1] = sumNext - result[i];
            }
            result = next;
        }
        return result;
    }

    private void registerTeamInRound(long cupId, String seasonStr, long round, long teamId) {
        CompetitionTeamInfo cti = new CompetitionTeamInfo();
        cti.setCompetitionId(cupId);
        cti.setSeasonNumber(Long.parseLong(seasonStr));
        cti.setRound(round);
        cti.setTeamId(teamId);
        competitionTeamInfoRepository.save(cti);
    }

    // ============================================================
    //                    display endpoints
    // ============================================================

    /**
     * Cups overview — sister endpoint to /leaguesOverview, one row per national cup
     * with its current state: which round just played, how many teams remain, and
     * either the last completed round's results or the upcoming round's pairings.
     */
    public Map<String, Object> getCupsOverview() {
        int currentSeason = currentSeason();
        String seasonStr = String.valueOf(currentSeason);

        List<Long> sortedLeagueIds = europeanCoefficientService.getLeagueIdsSortedByCoefficient();
        List<Competition> orderedCups = new ArrayList<>();
        for (Long leagueId : sortedLeagueIds) {
            Competition league = competitionRepository.findById(leagueId).orElse(null);
            if (league == null) continue;
            competitionRepository.findAll().stream()
                    .filter(c -> c.getTypeId() == 2L && c.getNationId() == league.getNationId())
                    .findFirst()
                    .ifPresent(orderedCups::add);
        }

        List<Map<String, Object>> cups = new ArrayList<>();
        int rank = 0;
        for (Competition cup : orderedCups) {
            rank++;
            Map<String, Object> cupInfo = new LinkedHashMap<>();
            cupInfo.put("competitionId", cup.getId());
            cupInfo.put("name", cup.getName());
            cupInfo.put("nationId", cup.getNationId());
            cupInfo.put("rank", rank);

            List<CompetitionTeamInfoMatch> matches = competitionTeamInfoMatchRepository.findAll().stream()
                    .filter(m -> m.getCompetitionId() == cup.getId() && seasonStr.equals(m.getSeasonNumber()))
                    .sorted(Comparator.comparingLong(CompetitionTeamInfoMatch::getRound)
                            .thenComparingInt(CompetitionTeamInfoMatch::getMatchIndex))
                    .toList();

            int totalRounds = matches.stream().mapToInt(m -> (int) m.getRound()).max().orElse(0);
            cupInfo.put("totalRounds", totalRounds);

            List<CompetitionTeamInfoDetail> details = competitionTeamInfoDetailRepository.findAll().stream()
                    .filter(d -> d.getCompetitionId() == cup.getId() && d.getSeasonNumber() == currentSeason)
                    .toList();
            int lastPlayedRound = details.stream().mapToInt(d -> (int) d.getRoundId()).max().orElse(0);
            cupInfo.put("lastPlayedRound", lastPlayedRound);
            cupInfo.put("currentRoundName", roundDisplayName(lastPlayedRound > 0 ? lastPlayedRound : 1, totalRounds, matches));

            int focusRound = lastPlayedRound > 0 && lastPlayedRound < totalRounds ? lastPlayedRound + 1 : Math.max(1, lastPlayedRound);
            List<Map<String, Object>> roundMatches = new ArrayList<>();
            Set<Long> teamIdsInRound = new HashSet<>();
            for (CompetitionTeamInfoMatch m : matches) {
                if (m.getRound() != focusRound) continue;
                if (m.getTeam1Id() > 0) teamIdsInRound.add(m.getTeam1Id());
                if (m.getTeam2Id() > 0) teamIdsInRound.add(m.getTeam2Id());
            }
            Map<Long, String> nameLookup = teamRepository.findAllById(teamIdsInRound).stream()
                    .collect(Collectors.toMap(Team::getId, Team::getName));
            Map<String, String> scoreByKey = new HashMap<>();
            for (CompetitionTeamInfoDetail d : details) {
                if (d.getRoundId() != focusRound) continue;
                scoreByKey.put(d.getTeam1Id() + "-" + d.getTeam2Id(), d.getScore());
            }
            for (CompetitionTeamInfoMatch m : matches) {
                if (m.getRound() != focusRound) continue;
                Map<String, Object> mr = new LinkedHashMap<>();
                mr.put("matchIndex", m.getMatchIndex());
                mr.put("team1Name", m.getTeam1Id() > 0 ? nameLookup.getOrDefault(m.getTeam1Id(), "?") : null);
                mr.put("team2Name", m.getTeam2Id() > 0 ? nameLookup.getOrDefault(m.getTeam2Id(), "?") : null);
                mr.put("score", scoreByKey.get(m.getTeam1Id() + "-" + m.getTeam2Id()));
                roundMatches.add(mr);
            }
            cupInfo.put("focusRound", focusRound);
            cupInfo.put("focusRoundMatches", roundMatches);

            cups.add(cupInfo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("season", currentSeason);
        result.put("cups", cups);
        return result;
    }

    private String roundDisplayName(int round, int totalRounds, List<CompetitionTeamInfoMatch> allMatches) {
        if (totalRounds <= 0) return "Round " + round;
        int fromEnd = totalRounds - round + 1;
        if (round == 1 && totalRounds >= 2) {
            long r1Count = allMatches.stream().filter(m -> m.getRound() == 1).count();
            long r2Count = allMatches.stream().filter(m -> m.getRound() == 2).count();
            if (r1Count < r2Count) return "Preliminary";
        }
        if (fromEnd == 1) return "Final";
        if (fromEnd == 2) return "Semi-Final";
        if (fromEnd == 3) return "Quarter-Final";
        if (fromEnd == 4) return "Round of 16";
        if (fromEnd == 5) return "Round of 32";
        return "Round " + round;
    }

    /**
     * Returns the full pre-generated cup bracket for the given (cup, season),
     * grouped by round. team1Id/team2Id == 0 means "winner of the matching
     * earlier-round slot" (placeholder, not yet decided). Frontend renders this
     * as a tree so users can see the whole path from day 1.
     */
    public Map<String, Object> getCupBracket(long cupId, int season) {
        String seasonStr = String.valueOf(season);

        List<CompetitionTeamInfoMatch> matches = competitionTeamInfoMatchRepository.findAll().stream()
                .filter(m -> m.getCompetitionId() == cupId && seasonStr.equals(m.getSeasonNumber()))
                .sorted(Comparator.comparingLong(CompetitionTeamInfoMatch::getRound)
                        .thenComparingInt(CompetitionTeamInfoMatch::getMatchIndex))
                .toList();

        Set<Long> teamIds = new HashSet<>();
        for (CompetitionTeamInfoMatch m : matches) {
            if (m.getTeam1Id() > 0) teamIds.add(m.getTeam1Id());
            if (m.getTeam2Id() > 0) teamIds.add(m.getTeam2Id());
        }
        Map<Long, String> teamNames = teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));

        List<CompetitionTeamInfoDetail> details = competitionTeamInfoDetailRepository.findAll().stream()
                .filter(d -> d.getCompetitionId() == cupId && d.getSeasonNumber() == season)
                .toList();
        Map<String, String> scoreByKey = new HashMap<>();
        for (CompetitionTeamInfoDetail d : details) {
            scoreByKey.put(d.getCompetitionId() + "-" + d.getRoundId() + "-"
                    + d.getTeam1Id() + "-" + d.getTeam2Id(), d.getScore());
        }

        Map<Long, List<Map<String, Object>>> matchesByRound = new LinkedHashMap<>();
        for (CompetitionTeamInfoMatch m : matches) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("matchIndex", m.getMatchIndex());
            entry.put("day", m.getDay());
            entry.put("team1Id", m.getTeam1Id());
            entry.put("team2Id", m.getTeam2Id());
            entry.put("team1Name", m.getTeam1Id() > 0 ? teamNames.getOrDefault(m.getTeam1Id(), "?") : null);
            entry.put("team2Name", m.getTeam2Id() > 0 ? teamNames.getOrDefault(m.getTeam2Id(), "?") : null);
            String key = m.getCompetitionId() + "-" + m.getRound() + "-" + m.getTeam1Id() + "-" + m.getTeam2Id();
            entry.put("score", scoreByKey.get(key));
            matchesByRound.computeIfAbsent(m.getRound(), k -> new ArrayList<>()).add(entry);
        }

        List<Map<String, Object>> rounds = new ArrayList<>();
        for (Map.Entry<Long, List<Map<String, Object>>> e : matchesByRound.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("round", e.getKey());
            r.put("matches", e.getValue());
            rounds.add(r);
        }

        Competition cup = competitionRepository.findById(cupId).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cupId", cupId);
        result.put("cupName", cup != null ? cup.getName() : "");
        result.put("season", season);
        result.put("rounds", rounds);
        return result;
    }

    /**
     * Round-count metadata per competition type — drives the frontend bracket
     * renderer (knows how many rounds to draw and which slots are group vs
     * knockout). Cup (type 2) is computed from team count; LoC (4) and Stars
     * Cup (5) are fixed-shape.
     */
    public Map<String, Object> getCupRoundCount(long competitionId) {
        Map<String, Object> result = new HashMap<>();
        Competition comp = competitionRepository.findById(competitionId).orElse(null);
        if (comp == null) {
            result.put("totalRounds", 0);
            result.put("typeId", 0);
            return result;
        }
        result.put("typeId", comp.getTypeId());

        if (comp.getTypeId() == 2) {
            int numTeams = competitionDisplayService.getTeamCountForCompetition(competitionId);
            if (numTeams == 0) {
                long maxRound = competitionTeamInfoDetailRepository.findAll().stream()
                        .filter(d -> d.getCompetitionId() == competitionId)
                        .mapToLong(CompetitionTeamInfoDetail::getRoundId)
                        .max().orElse(0);
                result.put("totalRounds", (int) maxRound);
            } else {
                int numRounds = Math.max(1, (int) Math.ceil(Math.log(numTeams) / Math.log(2)));
                result.put("totalRounds", numRounds);
            }
        } else if (comp.getTypeId() == 5) {
            result.put("totalRounds", 10);
            result.put("groupRounds", 6);
            result.put("playoffRound", 7);
        } else if (comp.getTypeId() == 4) {
            result.put("totalRounds", 10);
            result.put("groupRounds", 7);
            result.put("qualifyingRounds", 1);
            result.put("preliminaryRounds", 1);
        } else {
            result.put("totalRounds", 0);
        }
        return result;
    }
}
