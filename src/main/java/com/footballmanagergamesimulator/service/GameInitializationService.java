package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.ScorerLeaderboardEntry;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.ScorerLeaderboardRepository;
import com.footballmanagergamesimulator.repository.TeamFacilitiesRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One-time game initialization on cold start: creates the persistent Round
 * row, runs the hardcoded data seed via {@link BootstrapService}, generates
 * league fixtures + calendar + season objectives, spawns initial squads and
 * managers per team, creates free-agent coaches, initializes scorer rows for
 * every player, and builds cup brackets for season 1.
 *
 * <p>Invoked from {@code CompetitionController}'s {@code @PostConstruct}
 * exactly once when no Round exists in the DB. Returns the seeded Round so
 * the controller can cache it for runtime use. On warm restart the
 * controller skips the call entirely and just loads the existing Round.
 *
 * <p>Constraint: nothing here may call back into the controller via a Lazy
 * back-ref — at {@code @PostConstruct} time the controller bean is still
 * mid-construction and Spring would trip on {@code BeanCurrentlyInCreation}.
 * Use direct service injections only.
 */
@Service
public class GameInitializationService {

    /**
     * Optional seed for the squad/manager generation RNG. {@code 0} (default)
     * means "non-deterministic — use {@code new Random()}". Tests that want
     * reproducible squad ratings (e.g. {@code LeagueOutcomeIT}) override this
     * via {@code @TestPropertySource(properties = "bootstrap.seed=...")}.
     */
    @Value("${bootstrap.seed:0}") private long bootstrapSeed;

    /**
     * Fast-test snapshot toggles. With {@code use-pre-built-data=true}, the first cold start generates
     * normally and then dumps the whole DB to a snapshot file; every later cold start restores that
     * file instead of regenerating (much faster — important once a matchday runs per day). Set
     * {@code rebuild-pre-built-data=true} (or delete the file) to force a fresh generation + re-dump
     * after changing any generation logic.
     */
    @Value("${bootstrap.use-pre-built-data:false}") private boolean usePrebuiltData;
    @Value("${bootstrap.rebuild-pre-built-data:false}") private boolean rebuildPrebuiltData;

    @Autowired private RoundRepository roundRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamFacilitiesRepository teamFacilitiesRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private ScorerLeaderboardRepository scorerLeaderboardRepository;
    @Autowired private BootstrapService bootstrapService;
    @Autowired private FixtureSchedulingService fixtureSchedulingService;
    @Autowired private SeasonObjectiveService seasonObjectiveService;
    @Autowired private SquadGenerationService squadGenerationService;
    @Autowired private CompositeNameGenerator compositeNameGenerator;
    @Autowired private TacticService tacticService;
    @Autowired @Lazy private StaffService staffService;
    @Autowired @Lazy private NewSeasonSetupProcessor newSeasonSetupProcessor;
    @Autowired private PrebuiltDataService prebuiltDataService;
    @Autowired private SuperCupService superCupService;

    /**
     * Resume-aware initialization. If a Round row already exists, returns it
     * (warm restart on persistent DB). Otherwise runs the full one-time
     * setup and returns the freshly-created Round (cold start).
     */
    public Round initializeRound() {
        Optional<Round> existing = roundRepository.findById(1L);
        if (existing.isPresent()) {
            superCupService.ensureCompetitions();
            Round round = existing.get();
            System.out.println("=== Resuming from season " + round.getSeason()
                    + ", round " + round.getRound() + " ===");
            return round;
        }

        // Fast path: restore the whole DB from a previously dumped snapshot instead of regenerating.
        if (usePrebuiltData && !rebuildPrebuiltData && prebuiltDataService.snapshotExists()) {
            System.out.println("=== Loading pre-built data from " + prebuiltDataService.snapshotFile() + " ===");
            prebuiltDataService.restore();
            superCupService.ensureCompetitions();
            return roundRepository.findById(1L).orElseThrow(() ->
                    new IllegalStateException("Pre-built snapshot contained no Round — delete "
                            + prebuiltDataService.snapshotFile() + " and regenerate"));
        }

        Round round = new Round();
        round.setId(1L);
        round.setSeason(1);
        round.setRound(1);
        roundRepository.save(round);

        competitionTeamInfoRepository.deleteAll();
        bootstrapService.initialization();
        superCupService.ensureCompetitions();

        Set<Long> leagueCompetitionIds = new HashSet<>(competitionRepository.findIdsByTypeId(1));
        Set<Long> secondLeagueCompetitionIds = competitionRepository.findIdsByTypeId(3);
        leagueCompetitionIds.addAll(secondLeagueCompetitionIds);
        for (Long competitionId : leagueCompetitionIds)
            fixtureSchedulingService.getFixturesForRound(String.valueOf(competitionId), "1");

        // Calendar must run AFTER league fixtures exist so updateMatchDays can set the day field
        fixtureSchedulingService.generateSeasonCalendar(1);

        seasonObjectiveService.generateSeasonObjectives((int) round.getSeason());

        generateInitialSquadsAndStaff(round);

        staffService.generateFreeAgentCoaches(30, 1);

        initializeScorersForAllPlayers(round);

        System.out.println("=== Initialization complete: teams, players, fixtures, and scorers created ===");

        // Replace per-league cup CompetitionTeamInfo records with bracket-aware fixtures
        newSeasonSetupProcessor.regenerateAllCupBrackets((int) round.getSeason());

        backfillManagerCoachingAbilities();

        // Dump the freshly-generated DB so the next cold start can restore it instead of regenerating.
        if (usePrebuiltData) {
            prebuiltDataService.save();
            System.out.println("=== Saved pre-built data to " + prebuiltDataService.snapshotFile() + " ===");
        }

        return round;
    }

    /**
     * One-time backfill for the two-axis coaching model: managers created before
     * off/def abilities existed (old saves) — and the season-1 initial managers —
     * are stuck at the 50/50 default, so coaching asymmetry never emerges. Re-seed
     * those (and only those) with the same independent-noise logic used for
     * replacement managers in {@link HumanService#ensureTeamHasManager(long)}.
     * Idempotent: managers already holding non-default values are left untouched.
     */
    private void backfillManagerCoachingAbilities() {
        Random random = bootstrapSeed != 0 ? new Random(bootstrapSeed) : new Random();
        for (Human mgr : humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE)) {
            if (mgr.getOffensiveAbility() != 50.0 || mgr.getDefensiveAbility() != 50.0) continue;
            int reputation = 0;
            if (mgr.getTeamId() != null && mgr.getTeamId() > 0) {
                Team team = teamRepository.findById(mgr.getTeamId()).orElse(null);
                if (team != null) reputation = team.getReputation();
            }
            double repShare = Math.max(0.0, Math.min(1.0, reputation / 10000.0));
            mgr.setOffensiveAbility(Math.max(1, Math.min(100, 40 + repShare * 40 + (random.nextDouble() * 40 - 20))));
            mgr.setDefensiveAbility(Math.max(1, Math.min(100, 40 + repShare * 40 + (random.nextDouble() * 40 - 20))));
            humanRepository.save(mgr);
        }
    }

    private void generateInitialSquadsAndStaff(Round round) {
        List<Team> teams = teamRepository.findAll();
        Random random = bootstrapSeed != 0 ? new Random(bootstrapSeed) : new Random();
        for (Team team : teams) {
            TeamFacilities teamFacilities = teamFacilitiesRepository.findByTeamId(team.getId());
            squadGenerationService.generateInitialSquad(team, teamFacilities, 1, 70, random);

            Human manager = new Human();
            manager.setAge(random.nextInt(35, 70));
            manager.setName(compositeNameGenerator.generateName(team.getCompetitionId()));
            manager.setTeamId(team.getId());
            int reputation = 100;
            if (teamFacilities != null)
                reputation = (int) teamFacilities.getSeniorTrainingLevel() * 10;
            manager.setRating(random.nextInt(reputation - 20, reputation + 20));
            manager.setPosition("Manager");
            manager.setSeasonCreated(1L);
            manager.setTypeId(TypeNames.MANAGER_TYPE);
            String[] kit = tacticService.buildManagerTacticKit((int) manager.getRating(), random);
            manager.setTacticStyle(kit[0]);
            manager.setKnownTactics(kit[1]);
            humanRepository.save(manager);

            staffService.generateInitialStaff(team.getId(), 1);
        }
    }

    private void initializeScorersForAllPlayers(Round round) {
        List<Human> players = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);
        Map<Long, String> teamNames = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Team::getName, (left, right) -> left));
        // Cold initialization normally sees an empty leaderboard. Use the ordinary unlocked
        // findAll query here because this method runs during @PostConstruct, before a request
        // transaction exists; findAllByPlayerIdIn deliberately takes a pessimistic write lock.
        Map<Long, ScorerLeaderboardEntry> existing = scorerLeaderboardRepository.findAll().stream()
                .collect(Collectors.toMap(
                        ScorerLeaderboardEntry::getPlayerId,
                        entry -> entry,
                        (left, right) -> left,
                        HashMap::new));

        List<ScorerLeaderboardEntry> leaderboardEntries = new ArrayList<>(players.size());
        for (Human player : players) {
            ScorerLeaderboardEntry entry = existing.get(player.getId());
            if (entry == null) {
                entry = new ScorerLeaderboardEntry();
                entry.setPlayerId(player.getId());
                entry.setGoals(0);
                entry.setMatches(0);
                entry.setBestEverRating(player.getRating());
                entry.setSeasonOfBestEverRating((int) round.getSeason());
            }
            entry.setAge(player.getAge());
            entry.setName(player.getName());
            entry.setCurrentRating(player.getRating());
            entry.setPosition(player.getPosition());
            entry.setTeamId(player.getTeamId() == null ? 0L : player.getTeamId());
            entry.setTeamName(teamNames.getOrDefault(entry.getTeamId(), "Free Agent"));
            entry.setActive(!player.isRetired());
            NewSeasonSetupProcessor.resetCurrentSeasonStats(entry);
            leaderboardEntries.add(entry);
        }
        scorerLeaderboardRepository.saveAll(leaderboardEntries);
    }
}
