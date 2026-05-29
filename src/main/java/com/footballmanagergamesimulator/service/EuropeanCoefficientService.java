package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormat;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.config.EuropeanPhase;
import com.footballmanagergamesimulator.config.EuropeanStage;
import com.footballmanagergamesimulator.model.ClubCoefficient;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.ClubCoefficientRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * European coefficient math + per-match prize money. Extracted from
 * {@link EuropeanCompetitionService} (sesiunea 6, §6.1) to give the lifecycle
 * service a focused single responsibility (draws, fixtures, qualification) and
 * keep the points/prize/ranking math here.
 *
 * <p>Three public surfaces:
 * <ul>
 *   <li>{@link #awardCoefficientPoints} — called from the match orchestrator
 *       after every LoC/SC fixture; persists per-team coefficient points
 *       according to the documented matrix (LoC group win=2, draw=1, QF=3,
 *       SF=4, Final=5; SC group=1, draw=0.5, playoff=1, QF=1.5, SF=2,
 *       Final=2.5) and triggers the FinanceService prize-money record.</li>
 *   <li>{@link #getClubCoefficientRolling} — rolling 5-season sum of a club's
 *       coefficient points; consumed by the lifecycle service for seeded
 *       draws and by the display service.</li>
 *   <li>{@link #getLeagueIdsSortedByCoefficient} — leagues ranked by 5-season
 *       country coefficient (with reputation fallback for early seasons);
 *       drives the per-rank LoC/SC qualifier allocation matrix.</li>
 * </ul>
 *
 * <p>FinanceService is {@code @Lazy} to break a potential cycle through
 * CompetitionController; manager inbox writes are gated on
 * {@link UserContext#isHumanTeam(long)} so AI teams don't accumulate notifications.
 */
@Service
public class EuropeanCoefficientService {

    @Autowired private ClubCoefficientRepository clubCoefficientRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private ManagerInboxRepository managerInboxRepository;
    @Autowired private UserContext userContext;
    @Autowired private CompetitionFormatConfig competitionFormat;
    @Lazy @Autowired private FinanceService financeService;

    /** The LoC round's stage in the derived format plan (phase + roundsFromFinal). */
    private EuropeanStage locStage(long roundId) {
        return competitionFormat.get(4).europeanPlan().stageForRound((int) roundId);
    }

    // ============================================================
    //  Per-match coefficient + prize money
    // ============================================================

    /**
     * Awards coefficient points + per-match prize money for a finished
     * European fixture. No-op for league/cup competitions (only LoC + SC).
     * The points scale is round-dependent: knockout rounds award more than
     * group stage; draws are only meaningful in group stage.
     */
    public void awardCoefficientPoints(long competitionId, long roundId,
                                       long team1Id, long team2Id, int score1, int score2) {
        Set<Long> locIds = competitionIdsByType(4);
        Set<Long> starsCupIds = competitionIdsByType(5);

        if (!locIds.contains(competitionId) && !starsCupIds.contains(competitionId)) return;

        boolean isLoC = locIds.contains(competitionId);
        int season = Integer.parseInt(currentSeason());

        double winPoints;
        double drawPoints;

        if (isLoC) {
            // Round scale derived from the format plan's phase, so it adapts to any
            // shape. KO: win = 6 - roundsFromFinal → QF=3, SF=4, Final=5 (40/4×4).
            EuropeanStage stage = locStage(roundId);
            if (stage.phase() == EuropeanPhase.PRELIMINARY) { winPoints = 1.0; drawPoints = 0; }
            else if (stage.phase() == EuropeanPhase.GROUP) { winPoints = 2.0; drawPoints = 1.0; }
            else { winPoints = 6 - stage.roundsFromFinal(); drawPoints = 0; }
        } else {
            // Stars Cup — classified by the format's round boundaries (1-based, with
            // a playoff; no plan). Group → win 1/draw 0.5; playoff → win 1; knockout
            // scaled by distance from the final (QF=1.5, SF=2.0, Final=2.5).
            CompetitionFormat scFmt = competitionFormat.get(5);
            if (roundId <= scFmt.groupEndRound()) { winPoints = 1.0; drawPoints = 0.5; }
            else if (roundId == scFmt.playoffRound()) { winPoints = 1.0; drawPoints = 0; }
            else { winPoints = 3.0 - 0.5 * (scFmt.finalRound() - roundId + 1); drawPoints = 0; }
        }

        if (score1 > score2) {
            addClubCoefficient(team1Id, season, winPoints);
        } else if (score2 > score1) {
            addClubCoefficient(team2Id, season, winPoints);
        } else {
            // Draw (only possible in LoC group stage / SC group stage)
            addClubCoefficient(team1Id, season, drawPoints);
            addClubCoefficient(team2Id, season, drawPoints);
        }

        awardEuropeanMatchPrizeMoney(competitionId, roundId, team1Id, team2Id, score1, score2, isLoC, season);
    }

    private void awardEuropeanMatchPrizeMoney(long competitionId, long roundId, long team1Id, long team2Id,
                                               int score1, int score2, boolean isLoC, int season) {
        Round currentRound = roundRepository.findById(1L).orElse(new Round());
        int roundNumber = (int) currentRound.getRound();
        String compName = isLoC ? "League of Champions" : "Stars Cup";

        if (isLoC) {
            // Prizes classified by the format plan's phase, so they adapt to any
            // shape. KO tiers keyed off roundsFromFinal (Final=1, SF=2, QF=3).
            EuropeanStage stage = locStage(roundId);
            if (stage.phase() == EuropeanPhase.GROUP) {
                // Group Stage participation bonus, once on the first group matchday
                if (stage.groupDraw()) {
                    awardPrizeMoney(team1Id, 20_000_000L, season, roundNumber,
                            compName + " Group Stage Qualification", "european_prize");
                    awardPrizeMoney(team2Id, 20_000_000L, season, roundNumber,
                            compName + " Group Stage Qualification", "european_prize");
                }
                // Group stage: win = 5M, draw = 1.5M
                if (score1 > score2) {
                    awardPrizeMoney(team1Id, 5_000_000L, season, roundNumber,
                            compName + " Group Stage Win", "european_prize");
                } else if (score2 > score1) {
                    awardPrizeMoney(team2Id, 5_000_000L, season, roundNumber,
                            compName + " Group Stage Win", "european_prize");
                } else {
                    awardPrizeMoney(team1Id, 1_500_000L, season, roundNumber,
                            compName + " Group Stage Draw", "european_prize");
                    awardPrizeMoney(team2Id, 1_500_000L, season, roundNumber,
                            compName + " Group Stage Draw", "european_prize");
                }
            } else if (stage.phase() == EuropeanPhase.KNOCKOUT) {
                int rff = stage.roundsFromFinal();
                if (rff == 1) {
                    // Final: winner gets 100M, runner-up gets 50M
                    if (score1 > score2) {
                        awardPrizeMoney(team1Id, 100_000_000L, season, roundNumber,
                                compName + " Winner", "european_prize");
                        awardPrizeMoney(team2Id, 50_000_000L, season, roundNumber,
                                compName + " Runner-Up", "european_prize");
                    } else {
                        awardPrizeMoney(team2Id, 100_000_000L, season, roundNumber,
                                compName + " Winner", "european_prize");
                        awardPrizeMoney(team1Id, 50_000_000L, season, roundNumber,
                                compName + " Runner-Up", "european_prize");
                    }
                } else if (rff == 2) {
                    // SF qualification bonus
                    awardPrizeMoney(team1Id, 40_000_000L, season, roundNumber,
                            compName + " Semi-Final Qualification", "european_prize");
                    awardPrizeMoney(team2Id, 40_000_000L, season, roundNumber,
                            compName + " Semi-Final Qualification", "european_prize");
                } else if (rff == 3) {
                    // QF qualification bonus
                    awardPrizeMoney(team1Id, 15_000_000L, season, roundNumber,
                            compName + " Quarter-Final Qualification", "european_prize");
                    awardPrizeMoney(team2Id, 15_000_000L, season, roundNumber,
                            compName + " Quarter-Final Qualification", "european_prize");
                } else {
                    // Earlier knockout rounds (larger formats only): generic bonus
                    awardPrizeMoney(team1Id, 15_000_000L, season, roundNumber,
                            compName + " Knockout Qualification", "european_prize");
                    awardPrizeMoney(team2Id, 15_000_000L, season, roundNumber,
                            compName + " Knockout Qualification", "european_prize");
                }
            }
            // PRELIMINARY rounds: no prize money (matches legacy).
        } else {
            // Stars Cup prizes — classified by the format's round boundaries. KO tiers
            // keyed off distance from the final (QF=3, SF=2, Final=1). Playoff: none.
            CompetitionFormat scFmt = competitionFormat.get(5);
            if (roundId >= scFmt.groupStartRound() && roundId <= scFmt.groupEndRound()) {
                // Group stage participation bonus on the first group matchday
                if (roundId == scFmt.groupStartRound()) {
                    awardPrizeMoney(team1Id, 5_000_000L, season, roundNumber,
                            compName + " Group Stage Qualification", "european_prize");
                    awardPrizeMoney(team2Id, 5_000_000L, season, roundNumber,
                            compName + " Group Stage Qualification", "european_prize");
                }
                // Group stage: win = 1.5M, draw = 500K
                if (score1 > score2) {
                    awardPrizeMoney(team1Id, 1_500_000L, season, roundNumber,
                            compName + " Group Stage Win", "european_prize");
                } else if (score2 > score1) {
                    awardPrizeMoney(team2Id, 1_500_000L, season, roundNumber,
                            compName + " Group Stage Win", "european_prize");
                } else {
                    awardPrizeMoney(team1Id, 500_000L, season, roundNumber,
                            compName + " Group Stage Draw", "european_prize");
                    awardPrizeMoney(team2Id, 500_000L, season, roundNumber,
                            compName + " Group Stage Draw", "european_prize");
                }
            } else if (roundId > scFmt.playoffRound()) {
                int rff = (int) (scFmt.finalRound() - roundId + 1);
                if (rff == 1) {
                    // Final: winner 15M, runner-up 8M
                    if (score1 > score2) {
                        awardPrizeMoney(team1Id, 15_000_000L, season, roundNumber,
                                compName + " Winner", "european_prize");
                        awardPrizeMoney(team2Id, 8_000_000L, season, roundNumber,
                                compName + " Runner-Up", "european_prize");
                    } else {
                        awardPrizeMoney(team2Id, 15_000_000L, season, roundNumber,
                                compName + " Winner", "european_prize");
                        awardPrizeMoney(team1Id, 8_000_000L, season, roundNumber,
                                compName + " Runner-Up", "european_prize");
                    }
                } else if (rff == 2) {
                    // SF qualification
                    awardPrizeMoney(team1Id, 10_000_000L, season, roundNumber,
                            compName + " Semi-Final Qualification", "european_prize");
                    awardPrizeMoney(team2Id, 10_000_000L, season, roundNumber,
                            compName + " Semi-Final Qualification", "european_prize");
                } else {
                    // QF (and any earlier knockout round in larger formats)
                    awardPrizeMoney(team1Id, 5_000_000L, season, roundNumber,
                            compName + " Quarter-Final Qualification", "european_prize");
                    awardPrizeMoney(team2Id, 5_000_000L, season, roundNumber,
                            compName + " Quarter-Final Qualification", "european_prize");
                }
            }
            // Playoff round: no prize money (matches legacy).
        }
    }

    private void awardPrizeMoney(long teamId, long amount, int season, int roundNumber, String reason, String category) {
        financeService.recordTransaction(teamId, season, roundNumber, "PRIZE_MONEY", reason, amount);

        // Send inbox notification only for human teams
        if (userContext.isHumanTeam(teamId)) {
            String formattedAmount = formatPrizeMoney(amount);
            ManagerInbox inbox = new ManagerInbox();
            inbox.setTeamId(teamId);
            inbox.setSeasonNumber(season);
            inbox.setRoundNumber(roundNumber);
            inbox.setTitle(reason);
            inbox.setContent("Your club has received " + formattedAmount + " for " + reason + ".");
            inbox.setCategory(category);
            inbox.setRead(false);
            inbox.setCreatedAt(System.currentTimeMillis());
            managerInboxRepository.save(inbox);
        }
    }

    private String formatPrizeMoney(long amount) {
        if (amount >= 1_000_000L) {
            double millions = amount / 1_000_000.0;
            if (millions == (long) millions) return (long) millions + "M";
            return String.format("%.1fM", millions);
        } else if (amount >= 1_000L) {
            return (amount / 1_000) + "K";
        }
        return String.valueOf(amount);
    }

    private void addClubCoefficient(long teamId, int season, double points) {
        if (points <= 0) return;
        Optional<ClubCoefficient> existing = clubCoefficientRepository.findByTeamIdAndSeasonNumber(teamId, season);
        if (existing.isPresent()) {
            ClubCoefficient cc = existing.get();
            cc.setPoints(cc.getPoints() + points);
            clubCoefficientRepository.save(cc);
        } else {
            ClubCoefficient cc = new ClubCoefficient();
            cc.setTeamId(teamId);
            cc.setSeasonNumber(season);
            cc.setPoints(points);
            clubCoefficientRepository.save(cc);
        }
    }

    // ============================================================
    //  Rolling club coefficient + league ranking
    // ============================================================

    /** Rolling 5-season inclusive sum of a club's European coefficient points. */
    public double getClubCoefficientRolling(long teamId, int currentSeason) {
        double total = 0;
        for (int s = Math.max(1, currentSeason - 4); s <= currentSeason; s++) {
            Optional<ClubCoefficient> cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(teamId, s);
            if (cc.isPresent()) total += cc.get().getPoints();
        }
        return total;
    }

    /**
     * Leagues ordered by 5-season country coefficient (descending). For each
     * nation: sum of per-season averages (clubCoefficient sum / clubs in
     * European comp). If no coefficient data exists (early seasons), falls
     * back to {@code avgReputation / 100} so the ranking is still
     * deterministic at season 1.
     */
    public List<Long> getLeagueIdsSortedByCoefficient() {
        int currentSeason = Integer.parseInt(currentSeason());
        List<Competition> firstLeagues = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1).toList();

        Map<Long, Double> leagueCoefficients = new HashMap<>();
        for (Competition league : firstLeagues) {
            long leagueId = league.getId();
            double countryCoefficient = countryCoefficientRaw(league.getNationId(), currentSeason);

            // Fallback: if no coefficient data yet, use avg reputation of all teams.
            if (countryCoefficient == 0) {
                List<Team> teams = teamRepository.findAll().stream()
                        .filter(t -> t.getCompetitionId() == leagueId).toList();
                countryCoefficient = teams.stream().mapToInt(Team::getReputation).average().orElse(0) / 100.0;
            }

            leagueCoefficients.put(leagueId, countryCoefficient);
        }

        List<Long> sorted = new ArrayList<>(leagueCoefficients.keySet());
        sorted.sort((a, b) -> Double.compare(leagueCoefficients.get(b), leagueCoefficients.get(a)));
        return sorted;
    }

    /** Aggregated 5-season country coefficient for a nation (no fallback). */
    private double countryCoefficientRaw(long nationId, int currentSeason) {
        double countryCoefficient = 0;
        for (int s = Math.max(1, currentSeason - 4); s <= currentSeason; s++) {
            final int sFinal = s;
            List<CompetitionTeamInfo> europeanEntries = competitionTeamInfoRepository
                    .findAllBySeasonNumber(sFinal).stream()
                    .filter(cti -> {
                        Competition comp = competitionRepository.findById(cti.getCompetitionId()).orElse(null);
                        return comp != null && (comp.getTypeId() == 4 || comp.getTypeId() == 5);
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
                Optional<ClubCoefficient> cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(clubId, sFinal);
                if (cc.isPresent()) seasonPoints += cc.get().getPoints();
            }
            countryCoefficient += clubsFromNation.isEmpty() ? 0 : seasonPoints / clubsFromNation.size();
        }
        return countryCoefficient;
    }

    // ============================================================
    //  Configurable European access: league ranking + slot allocation
    // ============================================================

    /**
     * First leagues (typeId 1) ranked best-first by 5-season country coefficient.
     * When a league has no coefficient yet (early seasons), the fallback is the
     * <b>average reputation of its top-4 teams</b> (by reputation). Ties broken by
     * league id ascending for determinism.
     */
    public List<Long> rankFirstLeaguesByCoefficient() {
        int currentSeason = Integer.parseInt(currentSeason());
        List<Competition> firstLeagues = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1).toList();

        Map<Long, Double> coef = new HashMap<>();
        for (Competition league : firstLeagues) {
            long leagueId = league.getId();
            double c = countryCoefficientRaw(league.getNationId(), currentSeason);
            if (c == 0) c = avgReputationOfTopFour(leagueId);
            coef.put(leagueId, c);
        }

        List<Long> ranked = new ArrayList<>(coef.keySet());
        ranked.sort(Comparator.<Long>comparingDouble(id -> coef.get(id)).reversed()
                .thenComparing(Comparator.naturalOrder()));
        return ranked;
    }

    /** Average reputation of a league's top-4 teams (by reputation desc). */
    private double avgReputationOfTopFour(long leagueId) {
        return teamRepository.findAll().stream()
                .filter(t -> t.getCompetitionId() == leagueId)
                .sorted(Comparator.comparingInt(Team::getReputation).reversed())
                .limit(4)
                .mapToInt(Team::getReputation)
                .average().orElse(0);
    }

    /**
     * Allocate {@code totalTeams} European slots across the first leagues by
     * coefficient rank, using "layered halving": every league gets 1, then the
     * top ceil(k/2), then top ceil(k/4)… down to 1, then full top-down sweeps for
     * any remainder — so higher-coefficient leagues end up with more teams.
     * Explicit {@code overrides} (leagueId→count) are honored first and consume
     * from the budget; each league is capped by its actual number of teams.
     *
     * @return league id → team count, in coefficient-rank order.
     */
    public LinkedHashMap<Long, Integer> allocateTeamsPerLeague(int totalTeams, Map<Long, Integer> overrides) {
        List<Long> ranked = rankFirstLeaguesByCoefficient();
        Map<Long, Integer> caps = new HashMap<>();
        for (Long id : ranked) {
            caps.put(id, (int) teamRepository.findAll().stream()
                    .filter(t -> t.getCompetitionId() == id).count());
        }
        return layeredAllocation(ranked, totalTeams, overrides, caps);
    }

    /**
     * Pure layered-halving allocation (no DB) — see {@link #allocateTeamsPerLeague}.
     * Package-private so it can be unit-tested directly.
     */
    static LinkedHashMap<Long, Integer> layeredAllocation(List<Long> rankedLeagues, int totalTeams,
                                                          Map<Long, Integer> overrides, Map<Long, Integer> caps) {
        LinkedHashMap<Long, Integer> alloc = new LinkedHashMap<>();
        for (Long id : rankedLeagues) alloc.put(id, 0);

        int budget = totalTeams;
        List<Long> auto = new ArrayList<>();
        for (Long id : rankedLeagues) {
            Integer ov = overrides == null ? null : overrides.get(id);
            if (ov != null) {
                int cap = caps != null && caps.containsKey(id) ? caps.get(id) : Integer.MAX_VALUE;
                int give = Math.max(0, Math.min(Math.min(ov, cap), budget));
                alloc.put(id, give);
                budget -= give;
            } else {
                auto.add(id);
            }
        }
        if (auto.isEmpty() || budget <= 0) return alloc;

        int k = auto.size();
        List<Integer> widths = new ArrayList<>();
        for (int w = k; w > 1; w = (w + 1) / 2) widths.add(w);
        widths.add(1);

        int idx = 0;
        while (budget > 0) {
            int width = idx < widths.size() ? widths.get(idx) : k; // phase B: full top-down sweeps
            boolean progressed = false;
            for (int i = 0; i < width && budget > 0; i++) {
                Long id = auto.get(i);
                int cap = caps != null && caps.containsKey(id) ? caps.get(id) : Integer.MAX_VALUE;
                if (alloc.get(id) < cap) {
                    alloc.put(id, alloc.get(id) + 1);
                    budget--;
                    progressed = true;
                }
            }
            idx++;
            // Only bail when a FULL sweep made no progress (every league capped);
            // a narrow halving layer hitting only capped leagues must not stop us.
            if (!progressed && width >= k) break;
        }
        return alloc;
    }

    // ============================================================
    //  Helpers
    // ============================================================

    /** Loads the current season number from the singleton Round row. */
    private String currentSeason() {
        return String.valueOf(roundRepository.findById(1L).map(Round::getSeason).orElse(1L));
    }

    private Set<Long> competitionIdsByType(int typeId) {
        return competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == typeId)
                .map(Competition::getId)
                .collect(Collectors.toSet());
    }
}
