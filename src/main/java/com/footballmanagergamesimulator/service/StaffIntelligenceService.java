package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Scout;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.ScoutRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/** Club staff benchmarking, succession risks and the cross-club staff market. */
@Service
public class StaffIntelligenceService {

    private static final List<Long> COACH_TYPES = List.of(5L, 6L, 7L, 8L, 9L, 10L);
    private static final Map<Long, Integer> ROLE_LIMITS = Map.of(5L, 1, 6L, 3, 7L, 2, 8L, 1, 9L, 2, 10L, 1);

    private final HumanRepository humanRepository;
    private final ScoutRepository scoutRepository;
    private final TeamRepository teamRepository;
    private final RoundRepository roundRepository;
    private final FinanceService financeService;

    public StaffIntelligenceService(HumanRepository humanRepository, ScoutRepository scoutRepository,
                                    TeamRepository teamRepository, RoundRepository roundRepository,
                                    FinanceService financeService) {
        this.humanRepository = humanRepository;
        this.scoutRepository = scoutRepository;
        this.teamRepository = teamRepository;
        this.roundRepository = roundRepository;
        this.financeService = financeService;
    }

    public StaffIntelligence intelligence(long teamId) {
        List<Team> teams = teamRepository.findAll();
        Set<Long> ids = teams.stream().map(Team::getId).collect(Collectors.toSet());
        List<Human> coaches = humanRepository.findAllByTeamIdInAndTypeIdIn(ids, COACH_TYPES);
        List<Scout> scouts = scoutRepository.findAll().stream().filter(s -> s.getTeamId() != null && ids.contains(s.getTeamId())).toList();
        Map<Long, List<Human>> coachesByTeam = coaches.stream().collect(Collectors.groupingBy(c -> c.getTeamId() == null ? 0L : c.getTeamId()));
        Map<Long, List<Scout>> scoutsByTeam = scouts.stream().collect(Collectors.groupingBy(Scout::getTeamId));

        List<ClubStaffScore> ranking = teams.stream()
                .map(team -> score(team, coachesByTeam.getOrDefault(team.getId(), List.of()), scoutsByTeam.getOrDefault(team.getId(), List.of())))
                .sorted(Comparator.comparingDouble(ClubStaffScore::overallScore).reversed().thenComparing(ClubStaffScore::teamName))
                .toList();
        Map<Long, Integer> worldRanks = new HashMap<>();
        for (int i = 0; i < ranking.size(); i++) worldRanks.put(ranking.get(i).teamId(), i + 1);
        Team selectedTeam = teams.stream().filter(t -> t.getId() == teamId).findFirst().orElse(null);
        long competitionId = selectedTeam == null ? 0 : selectedTeam.getCompetitionId();
        List<ClubStaffScore> league = ranking.stream().filter(row -> row.competitionId() == competitionId).toList();
        Map<Long, Integer> leagueRanks = new HashMap<>();
        for (int i = 0; i < league.size(); i++) leagueRanks.put(league.get(i).teamId(), i + 1);
        List<RankedClubStaff> ranked = ranking.stream().map(row -> new RankedClubStaff(
                worldRanks.get(row.teamId()), leagueRanks.getOrDefault(row.teamId(), 0), row)).toList();
        ClubStaffScore own = ranking.stream().filter(row -> row.teamId() == teamId).findFirst()
                .orElse(score(selectedTeam == null ? blankTeam(teamId) : selectedTeam, List.of(), List.of()));

        Benchmarks benchmark = benchmark(league.isEmpty() ? ranking : league);
        int currentSeason = currentSeason();
        List<ContractRisk> risks = new ArrayList<>();
        coachesByTeam.getOrDefault(teamId, List.of()).stream()
                .filter(coach -> coach.getContractEndSeason() <= currentSeason + 1)
                .forEach(coach -> risks.add(new ContractRisk("COACH", coach.getId(), coach.getName(),
                        TypeNames.coachTypeName(coach.getTypeId()), coach.getContractEndSeason(), coach.getWage(), quality(coach))));
        scoutsByTeam.getOrDefault(teamId, List.of()).stream()
                .filter(scout -> scout.getContractEndSeason() <= currentSeason + 1)
                .forEach(scout -> risks.add(new ContractRisk("SCOUT", scout.getId(), scout.getName(),
                        "Scout", scout.getContractEndSeason(), scout.getWage(), scoutQuality(scout))));
        risks.sort(Comparator.comparingInt(ContractRisk::contractEndSeason).thenComparing(ContractRisk::name));

        List<RoleCoverage> coverage = ROLE_LIMITS.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    int current = (int) coachesByTeam.getOrDefault(teamId, List.of()).stream()
                            .filter(c -> c.getTypeId() == entry.getKey()).count();
                    return new RoleCoverage(entry.getKey(), TypeNames.coachTypeName(entry.getKey()), current,
                            entry.getValue(), Math.max(0, entry.getValue() - current));
                }).toList();
        return new StaffIntelligence(teamId, currentSeason, own, benchmark, ranked, coverage, risks, market(teamId));
    }

    public List<StaffCandidate> market(long buyingTeamId) {
        Map<Long, String> teamNames = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Team::getName, (left, right) -> left));
        int season = currentSeason();
        List<StaffCandidate> result = new ArrayList<>();
        for (long type : COACH_TYPES) {
            for (Human coach : humanRepository.findAllByTypeId(type)) {
                long clubId = coach.getTeamId() == null ? 0 : coach.getTeamId();
                if (clubId == buyingTeamId || coach.isRetired()) continue;
                boolean free = clubId == 0;
                long compensation = free ? 0 : compensation(coach.getWage(), coach.getContractEndSeason(), season);
                long demand = Math.max(coach.getWage(), (long) Math.ceil(coach.getWage() * (free ? 1.0 : 1.15)));
                result.add(new StaffCandidate("COACH", coach.getId(), coach.getName(), TypeNames.coachTypeName(type),
                        coach.getAge(), clubId, free ? "Free agent" : teamNames.getOrDefault(clubId, "Club"), free,
                        quality(coach), specialty(coach), coach.getWage(), demand, coach.getContractEndSeason(), compensation,
                        attributes(coach)));
            }
        }
        for (Scout scout : scoutRepository.findAll()) {
            long clubId = scout.getTeamId() == null ? 0 : scout.getTeamId();
            if (clubId == buyingTeamId) continue;
            boolean free = clubId == 0;
            long compensation = free ? 0 : compensation(scout.getWage(), scout.getContractEndSeason(), season);
            long demand = Math.max(scout.getWageDemand(), (long) Math.ceil(scout.getWage() * (free ? 1.0 : 1.15)));
            result.add(new StaffCandidate("SCOUT", scout.getId(), scout.getName(), "Scout", 0, clubId,
                    free ? "Free agent" : teamNames.getOrDefault(clubId, "Club"), free, scoutQuality(scout),
                    Math.max(scout.getScoutingAbility(), scout.getJudgingPotential()), scout.getWage(), demand,
                    scout.getContractEndSeason(), compensation, Map.of("Scouting", scout.getScoutingAbility(),
                            "Potential", scout.getJudgingPotential(), "Experience", scout.getExperience())));
        }
        return result.stream().sorted(Comparator.comparingDouble(StaffCandidate::quality).reversed()
                .thenComparing(StaffCandidate::name)).toList();
    }

    @Transactional
    public OfferResult makeOffer(long buyingTeamId, StaffOffer offer) {
        if (offer == null || offer.staffId() <= 0 || offer.offeredWage() <= 0) {
            return new OfferResult(false, "Invalid staff offer.", 0, 0, null);
        }
        int years = Math.max(1, Math.min(5, offer.contractYears()));
        int season = currentSeason();
        return "SCOUT".equalsIgnoreCase(offer.kind())
                ? offerScout(buyingTeamId, offer, years, season)
                : offerCoach(buyingTeamId, offer, years, season);
    }

    private OfferResult offerCoach(long buyerId, StaffOffer offer, int years, int season) {
        Optional<Human> optional = humanRepository.findByIdForUpdate(offer.staffId());
        if (optional.isEmpty() || !TypeNames.isCoachType(optional.get().getTypeId())) return missing();
        Human coach = optional.get();
        long sellerId = coach.getTeamId() == null ? 0 : coach.getTeamId();
        if (sellerId == buyerId) return new OfferResult(false, "This coach already works for your club.", 0, 0, null);
        long requiredCompensation = sellerId == 0 ? 0 : compensation(coach.getWage(), coach.getContractEndSeason(), season);
        long requiredWage = Math.max(coach.getWage(), (long) Math.ceil(coach.getWage() * (sellerId == 0 ? 1.0 : 1.15)));
        OfferResult rejected = validateOffer(buyerId, coach.getName(), offer, requiredWage, requiredCompensation);
        if (rejected != null) return rejected;
        transferMoney(buyerId, sellerId, offer.compensation(), season, coach.getName());
        adjustSalaryBudget(sellerId, -coach.getWage());
        adjustSalaryBudget(buyerId, offer.offeredWage());
        coach.setTeamId(buyerId); coach.setWage(offer.offeredWage()); coach.setContractEndSeason(season + years);
        humanRepository.save(coach);
        return new OfferResult(true, coach.getName() + " accepted the offer and joined your coaching staff.",
                requiredWage, requiredCompensation, candidateAfterCoach(coach));
    }

    private OfferResult offerScout(long buyerId, StaffOffer offer, int years, int season) {
        Optional<Scout> optional = scoutRepository.findByIdForUpdate(offer.staffId());
        if (optional.isEmpty()) return missing();
        Scout scout = optional.get();
        long sellerId = scout.getTeamId() == null ? 0 : scout.getTeamId();
        if (sellerId == buyerId) return new OfferResult(false, "This scout already works for your club.", 0, 0, null);
        long requiredCompensation = sellerId == 0 ? 0 : compensation(scout.getWage(), scout.getContractEndSeason(), season);
        long requiredWage = Math.max(scout.getWageDemand(), (long) Math.ceil(scout.getWage() * (sellerId == 0 ? 1.0 : 1.15)));
        OfferResult rejected = validateOffer(buyerId, scout.getName(), offer, requiredWage, requiredCompensation);
        if (rejected != null) return rejected;
        transferMoney(buyerId, sellerId, offer.compensation(), season, scout.getName());
        adjustSalaryBudget(sellerId, -scout.getWage());
        adjustSalaryBudget(buyerId, offer.offeredWage());
        scout.setTeamId(buyerId); scout.setWage(offer.offeredWage()); scout.setWageDemand(offer.offeredWage());
        scout.setHired(true); scout.setContractEndSeason(season + years); scoutRepository.save(scout);
        return new OfferResult(true, scout.getName() + " accepted the offer and joined your recruitment team.",
                requiredWage, requiredCompensation, null);
    }

    private OfferResult validateOffer(long buyerId, String name, StaffOffer offer, long wage, long compensation) {
        Team buyer = teamRepository.findById(buyerId).orElse(null);
        if (buyer == null) return new OfferResult(false, "Buying club not found.", wage, compensation, null);
        if (offer.offeredWage() < wage) return new OfferResult(false, name + " wants at least " + wage + " per week.", wage, compensation, null);
        if (offer.compensation() < compensation) return new OfferResult(false, "The employing club requires at least " + compensation + " compensation.", wage, compensation, null);
        if (buyer.getTotalFinances() < offer.compensation()) return new OfferResult(false, "The club cannot afford this compensation.", wage, compensation, null);
        return null;
    }

    private void transferMoney(long buyer, long seller, long amount, int season, String staffName) {
        if (amount <= 0) return;
        int day = (int) roundRepository.findById(1L).orElse(new Round()).getRound();
        financeService.recordExpense(buyer, season, day, "STAFF_COMPENSATION", "Compensation for " + staffName, amount);
        if (seller > 0) financeService.recordTransaction(seller, season, day, "STAFF_COMPENSATION", "Compensation received for " + staffName, amount);
    }

    private void adjustSalaryBudget(long teamId, long delta) {
        if (teamId <= 0 || delta == 0) return;
        teamRepository.findById(teamId).ifPresent(team -> {
            team.setSalaryBudget(Math.max(0, team.getSalaryBudget() + delta));
            teamRepository.save(team);
        });
    }

    private OfferResult missing() { return new OfferResult(false, "Staff member not found.", 0, 0, null); }
    private StaffCandidate candidateAfterCoach(Human coach) { return new StaffCandidate("COACH", coach.getId(), coach.getName(), TypeNames.coachTypeName(coach.getTypeId()), coach.getAge(), coach.getTeamId(), "New club", false, quality(coach), specialty(coach), coach.getWage(), coach.getWage(), coach.getContractEndSeason(), 0, attributes(coach)); }
    private long compensation(long wage, int endSeason, int currentSeason) { return Math.max(250_000L, wage * 52L * Math.max(1, endSeason - currentSeason)); }
    private int currentSeason() { return (int) roundRepository.findById(1L).orElse(new Round()).getSeason(); }

    private ClubStaffScore score(Team team, List<Human> coaches, List<Scout> scouts) {
        double coaching = avg(coaches, c -> (c.getCoachingAttacking() + c.getCoachingDefending() + c.getCoachingTactical() + c.getCoachingTechnical() + c.getCoachingMental()) / 5);
        double tactical = avg(coaches, c -> (c.getCoachingTactical() + c.getCoachingMental() + c.getMotivating()) / 3);
        double fitness = best(coaches, Human::getCoachingFitness);
        double goalkeeping = best(coaches, Human::getCoachingGK);
        double youth = avg(coaches.stream().filter(c -> c.getTypeId() == 9 || c.getTypeId() == 10).toList(), Human::getWorkingWithYoungsters);
        double scouting = scouts.stream().mapToDouble(this::scoutQuality).average().orElse(0);
        double depth = ROLE_LIMITS.entrySet().stream().mapToDouble(entry -> Math.min(1,
                coaches.stream().filter(c -> c.getTypeId() == entry.getKey()).count() * 1.0 / entry.getValue())).average().orElse(0) * 20;
        List<Double> departments = List.of(coaching, tactical, fitness, goalkeeping, youth, scouting).stream().filter(v -> v > 0).toList();
        double quality = departments.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double overall = quality * .85 + depth * .15;
        long wages = coaches.stream().mapToLong(Human::getWage).sum() + scouts.stream().mapToLong(Scout::getWage).sum();
        return new ClubStaffScore(team.getId(), team.getName(), team.getCompetitionId(), round(overall), round(coaching),
                round(tactical), round(fitness), round(goalkeeping), round(youth), round(scouting), round(depth),
                coaches.size(), scouts.size(), wages);
    }

    private Benchmarks benchmark(List<ClubStaffScore> rows) {
        if (rows.isEmpty()) return new Benchmarks(0, 0, 0, 0, 0, 0, 0);
        return new Benchmarks(round(rows.stream().mapToDouble(ClubStaffScore::overallScore).average().orElse(0)),
                rows.stream().mapToDouble(ClubStaffScore::overallScore).max().orElse(0),
                round(rows.stream().mapToDouble(ClubStaffScore::coachingScore).average().orElse(0)),
                round(rows.stream().mapToDouble(ClubStaffScore::fitnessScore).average().orElse(0)),
                round(rows.stream().mapToDouble(ClubStaffScore::goalkeepingScore).average().orElse(0)),
                round(rows.stream().mapToDouble(ClubStaffScore::youthScore).average().orElse(0)),
                round(rows.stream().mapToDouble(ClubStaffScore::scoutingScore).average().orElse(0)));
    }

    private Map<String, Integer> attributes(Human c) { Map<String, Integer> map = new LinkedHashMap<>(); map.put("Attacking", c.getCoachingAttacking()); map.put("Defending", c.getCoachingDefending()); map.put("Tactical", c.getCoachingTactical()); map.put("Technical", c.getCoachingTechnical()); map.put("Mental", c.getCoachingMental()); map.put("Fitness", c.getCoachingFitness()); map.put("Goalkeeping", c.getCoachingGK()); map.put("Youngsters", c.getWorkingWithYoungsters()); map.put("Motivating", c.getMotivating()); return map; }
    private double specialty(Human c) { return switch ((int) c.getTypeId()) { case 5 -> (c.getCoachingTactical() + c.getMotivating()) / 2.0; case 7 -> c.getCoachingFitness(); case 8 -> c.getCoachingGK(); case 9, 10 -> c.getWorkingWithYoungsters(); default -> (c.getCoachingAttacking() + c.getCoachingDefending() + c.getCoachingTechnical()) / 3.0; }; }
    private double quality(Human c) { return attributes(c).values().stream().mapToInt(Integer::intValue).average().orElse(0); }
    private double scoutQuality(Scout s) { return (s.getScoutingAbility() + s.getExperience() + s.getJudgingPotential()) / 3.0; }
    private double avg(List<Human> values, ToIntFunction<Human> fn) { return values.stream().mapToInt(fn).average().orElse(0); }
    private double best(List<Human> values, ToIntFunction<Human> fn) { return values.stream().mapToInt(fn).max().orElse(0); }
    private double round(double value) { return Math.round(value * 100.0) / 100.0; }
    private Team blankTeam(long id) { Team team = new Team(); team.setId(id); team.setName("Team"); return team; }

    public record ClubStaffScore(long teamId, String teamName, long competitionId, double overallScore,
                                 double coachingScore, double tacticalScore, double fitnessScore,
                                 double goalkeepingScore, double youthScore, double scoutingScore,
                                 double depthScore, int coachCount, int scoutCount, long weeklyWageBill) {}
    public record RankedClubStaff(int worldRank, int leagueRank, ClubStaffScore club) {}
    public record Benchmarks(double leagueAverage, double leagueBest, double coachingAverage,
                             double fitnessAverage, double goalkeepingAverage, double youthAverage, double scoutingAverage) {}
    public record RoleCoverage(long typeId, String role, int current, int maximum, int vacancies) {}
    public record ContractRisk(String kind, long staffId, String name, String role, int contractEndSeason, long wage, double quality) {}
    public record StaffCandidate(String kind, long id, String name, String role, int age, long currentTeamId,
                                 String currentTeamName, boolean freeAgent, double quality, double specialtyRating,
                                 long currentWage, long wageDemand, int contractEndSeason, long requiredCompensation,
                                 Map<String, Integer> attributes) {}
    public record StaffOffer(String kind, long staffId, long offeredWage, int contractYears, long compensation) {}
    public record OfferResult(boolean success, String message, long requiredWage, long requiredCompensation, StaffCandidate staff) {}
    public record StaffIntelligence(long teamId, int season, ClubStaffScore ownClub, Benchmarks benchmarks,
                                    List<RankedClubStaff> ranking, List<RoleCoverage> roleCoverage,
                                    List<ContractRisk> contractRisks, List<StaffCandidate> market) {}
}
