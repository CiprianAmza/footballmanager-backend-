package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.nameGenerator.NameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import com.footballmanagergamesimulator.util.ManagerTacticPolicy;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class HumanService {

    @Autowired
    HumanRepository humanRepository;
    @Autowired
    TeamRepository teamRepository;
    @Autowired
    RoundRepository roundRepository;
    @Autowired
    CompetitionService competitionService;
    @Autowired
    PlayerSkillsRepository playerSkillsRepository;
    @Autowired
    ScorerLeaderboardRepository scorerLeaderboardRepository;
    @Autowired
    CompositeNameGenerator compositeNameGenerator;
    @Autowired
    TeamPlayerHistoricalRelationRepository teamPlayerHistoricalRelationRepository;
    @Autowired
    StaffService staffService;
    @Autowired
    TacticService tacticService;
    @Autowired
    MatchEngineConfig engineConfig;

    /**
     * Spawn a fresh AI manager for {@code teamId} if no manager is currently
     * assigned. Used after the human user leaves a team (via resign or job-offer
     * acceptance) so the match simulator never hits {@code .get(0)} on an empty
     * manager list. Idempotent — does nothing if the team already has a manager.
     */
    public void ensureTeamHasManager(long teamId) {
        if (teamId <= 0) return;
        boolean alreadyHasOne = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE)
                .stream().anyMatch(m -> !m.isRetired());
        if (alreadyHasOne) return;

        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return;

        Random random = new Random();
        Human mgr = new Human();
        mgr.setName(compositeNameGenerator.generateName(1L));
        mgr.setTypeId(TypeNames.MANAGER_TYPE);
        mgr.setTeamId(teamId);
        mgr.setManagerReputation(ManagerReputation.startingFor(team.getReputation()));
        mgr.setAge(35 + random.nextInt(20));
        Round round = roundRepository.findById(1L).orElse(null);
        mgr.setSeasonCreated(round != null ? (int) round.getSeason() : 1);
        mgr.setMorale(100D);
        mgr.setFitness(100D);
        mgr.setRating(0);
        // Offensive/defensive coaching ability (0-100): bigger clubs tend to attract better coaches,
        // but with INDEPENDENT noise per side so a coach can be lopsided (attack-strong, defence-weak).
        // These amplify the squad on each side and shape which tactic suits the coach.
        double repShare = Math.max(0.0, Math.min(1.0, team.getReputation() / 10000.0));
        mgr.setOffensiveAbility(Math.max(1, Math.min(100, 40 + repShare * 40 + (random.nextDouble() * 40 - 20))));
        mgr.setDefensiveAbility(Math.max(1, Math.min(100, 40 + repShare * 40 + (random.nextDouble() * 40 - 20))));
        String[] kit = tacticService.buildManagerTacticKit((int) mgr.getRating(), random);
        mgr.setTacticStyle(kit[0]);
        mgr.setKnownTactics(kit[1]);
        mgr.setAlwaysUseBestPossibleTactic(
                ManagerTacticPolicy.defaultsToBestPossibleTactic(team.getName()));
        humanRepository.save(mgr);
    }

    public void retirePlayers() {

      Random random = new Random();
      List<Human> humans = humanRepository
              .findAllByTypeId(TypeNames.PLAYER_TYPE)
              .stream()
              .filter(human -> human.getAge() > 34)
              .toList();

      List<Human> retiredPlayers = new ArrayList<>();
      for (Human human: humans) {
        int chance = random.nextInt(0, 2);
        if (chance == 1) {
            human.setTeamId(null);
            human.setRetired(true);
            retiredPlayers.add(human);
        }
      }
      humanRepository.saveAll(retiredPlayers);

      if (!retiredPlayers.isEmpty()) {
        List<Long> retiredIds = retiredPlayers.stream().map(Human::getId).toList();
        List<ScorerLeaderboardEntry> entries =
                scorerLeaderboardRepository.findAllByPlayerIdIn(retiredIds);
        for (ScorerLeaderboardEntry scorerLeaderboardEntry : entries) {
            scorerLeaderboardEntry.setCurrentSeasonGames(0);
            scorerLeaderboardEntry.setCurrentSeasonGoals(0);
            scorerLeaderboardEntry.setCurrentSeasonLeagueGames(0);
            scorerLeaderboardEntry.setCurrentSeasonLeagueGoals(0);
            scorerLeaderboardEntry.setCurrentSeasonCupGames(0);
            scorerLeaderboardEntry.setCurrentSeasonCupGoals(0);
            scorerLeaderboardEntry.setCurrentSeasonSecondLeagueGames(0);
            scorerLeaderboardEntry.setCurrentSeasonSecondLeagueGoals(0);
            scorerLeaderboardEntry.setActive(false);
        }
        scorerLeaderboardRepository.saveAll(entries);
      }
    }

    public void addOneYearToAge() {

      // Age only active people. For retired players the stored age represents
      // their retirement age and is what historical trackers should display.
      List<Human> humans = humanRepository.findAll().stream()
              .filter(human -> !human.isRetired())
              .toList();

      for (Human human: humans) {
        human.setAge(human.getAge() + 1);
      }
      humanRepository.saveAll(humans);
    }

    public void addRegens(TeamFacilities teamFacilities, long teamId) {
      long currentSeason = roundRepository.findById(1L).get().getSeason();

      List<Human> newPlayers = new ArrayList<>();
      collectRegens(teamFacilities, teamId, currentSeason, newPlayers);
      // Persist the generated humans first so their IDs are assigned, then build
      // skills + historical relations from those ids and flush each in one batch.
      newPlayers = (List<Human>) humanRepository.saveAll(newPlayers);

      List<PlayerSkills> newSkills = new ArrayList<>();
      List<TeamPlayerHistoricalRelation> newRelations = new ArrayList<>();
      buildRegenSkillsAndRelations(newPlayers, currentSeason, newSkills, newRelations);

      humanRepository.saveAll(newPlayers); // re-save with recomputed rating
      playerSkillsRepository.saveAll(newSkills);
      teamPlayerHistoricalRelationRepository.saveAll(newRelations);
    }

    /**
     * Batch-friendly regen generation: appends freshly generated (unsaved) regen
     * {@link Human}s for {@code teamId} to {@code collector}. The caller is
     * responsible for persisting them via {@code saveAll} and then calling
     * {@link #buildRegenSkillsAndRelations} to produce the dependent rows.
     * Hoisting {@code currentSeason} out of the per-team loop avoids the
     * {@code roundRepository.findById(1L)} round-trip per team.
     */
    public void collectRegens(TeamFacilities teamFacilities, long teamId,
                              long currentSeason, List<Human> collector) {
      int nrRegens = 1;
      for (int i = 0; i < nrRegens; i++) {
          Human player = generateHuman(
                  teamId, teamFacilities.getYouthAcademyLevel(), currentSeason);
          collector.add(player);
      }
    }

    /**
     * For each already-persisted regen {@link Human} (IDs assigned), generates its
     * {@link PlayerSkills}, recomputes the rating from the attributes (mutating the
     * Human in place), and creates the {@link TeamPlayerHistoricalRelation}. Appends
     * the new skills/relations to the supplied collectors for a single batched flush.
     */
    public void buildRegenSkillsAndRelations(List<Human> persistedPlayers, long currentSeason,
                                             List<PlayerSkills> skillsCollector,
                                             List<TeamPlayerHistoricalRelation> relationCollector) {
      for (Human player : persistedPlayers) {
          PlayerSkills playerSkills = new PlayerSkills();
          playerSkills.setPlayerId(player.getId());
          playerSkills.setPosition(player.getPosition());
          competitionService.generateSkills(playerSkills, player.getRating());

          // Recompute rating from attributes
          double computedRating = PlayerSkillsService.computeOverallRating(playerSkills);
          player.setRating(computedRating);
          player.setCurrentAbility((int) computedRating);
          player.setBestEverRating(computedRating);

          TeamPlayerHistoricalRelation relation = new TeamPlayerHistoricalRelation();
          relation.setPlayerId(player.getId());
          relation.setTeamId(player.getTeamId());
          relation.setSeasonNumber(currentSeason);
          relation.setRating(player.getRating());

          skillsCollector.add(playerSkills);
          relationCollector.add(relation);
      }
    }

    private Human generateHuman(
            long teamId, long youthAcademyLevel, long currentSeason) {

      Random random = new Random();
      int ratingAround = (int) youthAcademyLevel * 10;

      Human human = new Human();
      human.setName(NameGenerator.generateName());
      human.setAge(random.nextInt(15, 19));
      human.setRating(random.nextInt(ratingAround - 20, ratingAround + 20));
      human.setPosition(generatePosition());
      human.setCurrentAbility((int) human.getRating());
      human.setPotentialAbility((int) (human.getRating() + random.nextInt(30)));
      human.setBestEverRating(human.getRating());
      human.setMorale(70);
      human.setFitness(100);
      human.setCurrentStatus("Senior");
      human.setTeamId(teamId);
      human.setTypeId(1);
      human.setSeasonCreated(currentSeason);
      human.setSeasonOfBestEverRating((int) currentSeason);

      // Physical profile
      generatePhysicalProfile(human, random);

      // Initialize contract
      human.setContractEndSeason((int) currentSeason + random.nextInt(2, 6));
      human.setWage(WageService.baseWage(human.getRating()));
      long transferVal = (long) (human.getRating() * 10000);
      human.setTransferValue(transferVal);
      human.setReleaseClause(random.nextInt(10) < 3 ? 0 : transferVal * 2);

      return human;
    }

    /**
     * Generate realistic height, weight, and preferred foot based on position.
     */
    public static void generatePhysicalProfile(Human human, Random random) {
        String pos = human.getPosition() != null ? human.getPosition() : "MC";
        pos = TacticService.getBasePosition(pos); // fine positions use their base physical profile

        // Height ranges by position (cm)
        int baseHeight = switch (pos) {
            case "GK" -> 185 + random.nextInt(-5, 8);   // 180-192
            case "DC" -> 183 + random.nextInt(-5, 7);    // 178-189
            case "DL", "DR" -> 176 + random.nextInt(-5, 7); // 171-182
            case "MC" -> 178 + random.nextInt(-6, 7);    // 172-184
            case "ML", "MR" -> 175 + random.nextInt(-5, 6); // 170-180
            case "ST" -> 180 + random.nextInt(-7, 8);    // 173-187
            default -> 178 + random.nextInt(-6, 7);
        };
        human.setHeightCm(baseHeight);

        // Weight correlates with height
        double bmi = 21.5 + random.nextGaussian() * 1.5;
        if ("DC".equals(pos) || "ST".equals(pos)) bmi += 0.5;
        if ("ML".equals(pos) || "MR".equals(pos)) bmi -= 0.3;
        int weight = (int) Math.round(bmi * Math.pow(baseHeight / 100.0, 2));
        human.setWeightKg(Math.max(60, Math.min(100, weight)));

        // Preferred foot: ~75% right, ~18% left, ~7% both
        double footRoll = random.nextDouble();
        if (footRoll < 0.07) human.setPreferredFoot("Both");
        else if (footRoll < 0.25) human.setPreferredFoot("Left");
        else human.setPreferredFoot("Right");
    }

    /** Position → preferred shirt numbers, in priority order. The best-rated
     *  player at each position gets their first choice; the next-best player
     *  at the same position gets the second choice; etc. Best GK always wears 1. */
    private static final Map<String, int[]> SHIRT_PREFS = Map.ofEntries(
            Map.entry("GK",  new int[]{1, 12, 13, 25}),
            Map.entry("DR",  new int[]{2, 22, 16, 26}),
            Map.entry("DL",  new int[]{3, 14, 23, 27}),
            Map.entry("DC",  new int[]{4, 5, 6, 15, 28, 29}),
            Map.entry("DM",  new int[]{6, 4, 24, 30}),
            Map.entry("MR",  new int[]{7, 17, 22, 31}),
            Map.entry("MC",  new int[]{8, 6, 16, 24, 32, 33}),
            Map.entry("ML",  new int[]{11, 14, 19, 34}),
            Map.entry("AMC", new int[]{10, 21, 14, 35}),
            Map.entry("AMR", new int[]{7, 17, 11, 36}),
            Map.entry("AML", new int[]{11, 17, 14, 37}),
            Map.entry("ST",  new int[]{9, 10, 11, 19, 20, 38, 39})
    );

    /** Assign realistic shirt numbers to a squad: best GK = 1, best DR = 2,
     *  etc., falling through to backup numbers as positions fill up. Any
     *  player whose position isn't recognised — or who runs past their
     *  position's preferences — falls back to the lowest unused number ≤99.
     *  Players who already have a non-zero shirt number keep it (so this
     *  doubles as a backfill: pass a mixed squad and only the 0-shirts get
     *  assigned). Modifies the {@link Human} entities in place; the caller
     *  is responsible for persisting them. */
    public static void assignShirtNumbers(List<Human> squad) {
        if (squad == null || squad.isEmpty()) return;
        Set<Integer> used = new HashSet<>();
        List<Human> toAssign = new ArrayList<>();
        for (Human p : squad) {
            int s = p.getShirtNumber();
            if (s > 0) used.add(s);
            else toAssign.add(p);
        }
        toAssign.sort((a, b) -> Double.compare(b.getRating(), a.getRating()));
        for (Human p : toAssign) {
            // Keep position-specific shirt prefs where defined (incl. DM/AMC); fine positions without
            // their own entry (AML/AMR/WBL/WBR) fall back to their base position's numbers.
            String shirtPos = SHIRT_PREFS.containsKey(p.getPosition())
                    ? p.getPosition() : TacticService.getBasePosition(p.getPosition());
            int[] choices = SHIRT_PREFS.getOrDefault(shirtPos, new int[0]);
            int assigned = 0;
            for (int n : choices) {
                if (!used.contains(n)) { assigned = n; break; }
            }
            if (assigned == 0) {
                for (int n = 2; n <= 99; n++) {
                    if (!used.contains(n)) { assigned = n; break; }
                }
            }
            p.setShirtNumber(assigned);
            used.add(assigned);
        }
    }

    private String generatePosition() {

      List<String> positions = new ArrayList<>();
      int i;
      for (i = 0; i < 5; i++)
        positions.add("GK");
      for (i = 0; i < 5; i++)
        positions.add("DL");
      for (i = 0; i < 5; i++)
        positions.add("DR");
      for (i = 0; i < 10; i++)
        positions.add("DC");
      for (i = 0; i < 5; i++)
        positions.add("MR");
      for (i = 0; i < 5; i++)
        positions.add("ML");
      for (i = 0; i < 10; i++)
        positions.add("MC");
      for (i = 0; i < 10; i++)
        positions.add("ST");

      Collections.shuffle(positions);

      return positions.get(0);
    }
}
