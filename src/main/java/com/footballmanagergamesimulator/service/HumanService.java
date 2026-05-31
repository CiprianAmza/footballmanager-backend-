package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.FacilityTraining;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.nameGenerator.NameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
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
        mgr.setManagerReputation(team.getReputation() / 3);
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
        humanRepository.save(mgr);
    }

    public Human trainPlayer(Human human, TeamFacilities teamFacilities, int currentSeason) {

      Random random = new Random();
      int age = human.getAge();
      int matchesPlayed = human.getSeasonMatchesPlayed();
      double matchBonus = Math.min(matchesPlayed * 0.002, 0.05);

      // Coaching staff multiplier (0.5 to 1.5 based on coaching staff quality)
      double facilityMultiplier;
      if (human.getTeamId() != null && human.getTeamId() > 0) {
          double staffMultiplier = staffService.getCoachingMultiplier(human.getTeamId());
          double facilityBase = FacilityTraining.developmentFactor(engineConfig.getTraining(), teamFacilities, age);
          facilityMultiplier = staffMultiplier * 0.6 + facilityBase * 0.4;
      } else {
          facilityMultiplier = FacilityTraining.developmentFactor(engineConfig.getTraining(), teamFacilities, age);
      }

      // Try attribute-based training first
      Optional<PlayerSkills> skillsOpt = playerSkillsRepository.findPlayerSkillsByPlayerId(human.getId());
      if (skillsOpt.isPresent()) {
          PlayerSkills skills = skillsOpt.get();
          boolean changed = trainIndividualAttributes(human, skills, facilityMultiplier, random);
          if (changed) {
              double newRating = PlayerSkillsService.computeOverallRating(skills);
              if (human.getPotentialAbility() > 0) {
                  newRating = Math.min(newRating, human.getPotentialAbility());
              }
              newRating = Math.max(1.0, newRating);
              human.setRating(newRating);

              if (newRating > human.getBestEverRating()) {
                  human.setBestEverRating(newRating);
                  human.setSeasonOfBestEverRating(currentSeason);
              }
              playerSkillsRepository.save(skills);
          }
          return human;
      }

      return trainPlayerFallback(human, teamFacilities, facilityMultiplier, currentSeason, random);
    }

    /**
     * Batch-friendly version: accepts pre-computed facilityMultiplier and pre-loaded PlayerSkills.
     * Avoids per-player DB queries for staff and skills.
     * Returns the modified PlayerSkills if changed, or null if unchanged.
     */
    public PlayerSkills trainPlayerBatch(Human human, TeamFacilities teamFacilities,
                                          double facilityMultiplier, PlayerSkills skills,
                                          int currentSeason, Random random) {
      if (skills != null) {
          boolean changed = trainIndividualAttributes(human, skills, facilityMultiplier, random);
          if (changed) {
              double newRating = PlayerSkillsService.computeOverallRating(skills);
              if (human.getPotentialAbility() > 0) {
                  newRating = Math.min(newRating, human.getPotentialAbility());
              }
              newRating = Math.max(1.0, newRating);
              human.setRating(newRating);

              if (newRating > human.getBestEverRating()) {
                  human.setBestEverRating(newRating);
                  human.setSeasonOfBestEverRating(currentSeason);
              }
              return skills;
          }
          return null;
      }

      trainPlayerFallback(human, teamFacilities, facilityMultiplier, currentSeason, random);
      return null;
    }

    private Human trainPlayerFallback(Human human, TeamFacilities teamFacilities,
                                       double facilityMultiplier, int currentSeason, Random random) {
      double ratingChange = 0D;
      int age = human.getAge();
      int matchesPlayed = human.getSeasonMatchesPlayed();
      double matchBonus = Math.min(matchesPlayed * 0.002, 0.05);

      // Fallback: old-style rating-only training
      if (age <= 20) {
          double youthFacilityBase = FacilityTraining.developmentFactor(engineConfig.getTraining(), teamFacilities, age);
          double youthFacility;
          if (human.getTeamId() != null && human.getTeamId() > 0) {
              double youthStaff = staffService.getYouthCoachingMultiplier(human.getTeamId());
              youthFacility = youthStaff * 0.6 + youthFacilityBase * 0.4;
          } else {
              youthFacility = youthFacilityBase;
          }
          if (random.nextDouble() < 0.12 + matchBonus) {
              ratingChange = random.nextDouble(0.1, 0.5) * youthFacility;
          }
      } else if (age <= 23) {
          if (random.nextDouble() < 0.08 + matchBonus) {
              ratingChange = random.nextDouble(0.1, 0.35) * facilityMultiplier;
          }
      } else if (age <= 26) {
          if (random.nextDouble() < 0.05 + matchBonus) {
              ratingChange = random.nextDouble(0.05, 0.2) * facilityMultiplier;
          }
      } else if (age <= 29) {
          double roll = random.nextDouble();
          if (roll < 0.03 + matchBonus * 0.5) {
              ratingChange = random.nextDouble(0.02, 0.1);
          } else if (roll > 0.97) {
              ratingChange = -random.nextDouble(0.02, 0.08);
          }
      } else if (age <= 31) {
          double roll = random.nextDouble();
          if (roll < 0.03) {
              ratingChange = random.nextDouble(0.01, 0.05);
          } else if (roll > 0.94) {
              ratingChange = -random.nextDouble(0.05, 0.15);
          }
      } else if (age <= 33) {
          if (random.nextDouble() > 0.92) {
              ratingChange = -random.nextDouble(0.1, 0.25);
          }
      } else {
          if (random.nextDouble() > 0.88) {
              ratingChange = -random.nextDouble(0.15, 0.4);
          }
      }

      double newRating = human.getRating() + ratingChange;
      if (ratingChange > 0 && human.getPotentialAbility() > 0) {
          newRating = Math.min(newRating, human.getPotentialAbility());
      }
      newRating = Math.max(newRating, 1.0);
      human.setRating(newRating);

      if (newRating > human.getBestEverRating()) {
          human.setBestEverRating(newRating);
          human.setSeasonOfBestEverRating(currentSeason);
      }

      return human;
    }

    /**
     * Train individual attributes with coaching quality modifier.
     * Better coaching staff = higher chance and magnitude of improvement.
     */
    private boolean trainIndividualAttributes(Human human, PlayerSkills skills, double facilityMultiplier, Random random) {
        int age = human.getAge();
        int matchesPlayed = human.getSeasonMatchesPlayed();
        double matchBonus = Math.min(matchesPlayed * 0.002, 0.05);
        boolean anyChanged = false;

        Set<String> physicalAttrs = Set.of("Acceleration", "Agility", "Balance", "Jumping Reach",
                "Natural Fitness", "Pace", "Stamina", "Strength");
        Set<String> mentalAttrs = Set.of("Aggression", "Anticipation", "Bravery", "Composure",
                "Concentration", "Decisions", "Determination", "Flair", "Leadership",
                "Off The Ball", "Positioning", "Teamwork", "Vision", "Work Rate");
        Set<String> gkAttrs = Set.of("Handling", "Reflexes", "One On Ones", "Command Of Area", "Kicking", "Throwing");

        boolean isGK = "GK".equals(skills.getPosition());

        for (Map.Entry<String, java.util.function.Function<PlayerSkills, Integer>> entry : PlayerSkillsService.GETTER_MAP.entrySet()) {
            String attrName = entry.getKey();
            int currentVal = entry.getValue().apply(skills);

            // Skip irrelevant GK/outfield attrs
            if (isGK && !gkAttrs.contains(attrName) && !mentalAttrs.contains(attrName) && !physicalAttrs.contains(attrName)) continue;
            if (!isGK && gkAttrs.contains(attrName)) continue;

            boolean isPhysical = physicalAttrs.contains(attrName);
            boolean isMental = mentalAttrs.contains(attrName);

            double baseChance;
            double baseAmount;

            if (age <= 20) {
                baseChance = 0.08 + matchBonus;
                baseAmount = random.nextDouble(0.2, 0.8);
            } else if (age <= 23) {
                baseChance = 0.05 + matchBonus;
                baseAmount = random.nextDouble(0.15, 0.6);
            } else if (age <= 26) {
                baseChance = 0.03 + matchBonus;
                baseAmount = random.nextDouble(0.1, 0.4);
            } else if (age <= 29) {
                if (isPhysical) {
                    baseChance = 0.02;
                    baseAmount = random.nextDouble(-0.1, 0.15);
                } else if (isMental) {
                    baseChance = 0.03 + matchBonus * 0.5;
                    baseAmount = random.nextDouble(0.05, 0.3);
                } else {
                    baseChance = 0.02;
                    baseAmount = random.nextDouble(0, 0.2);
                }
            } else if (age <= 31) {
                if (isPhysical) {
                    baseChance = 0.05;
                    baseAmount = -random.nextDouble(0.1, 0.3);
                } else if (isMental) {
                    baseChance = 0.02;
                    baseAmount = random.nextDouble(-0.05, 0.2);
                } else {
                    baseChance = 0.03;
                    baseAmount = random.nextDouble(-0.15, 0.05);
                }
            } else if (age <= 33) {
                if (isPhysical) {
                    baseChance = 0.08;
                    baseAmount = -random.nextDouble(0.15, 0.5);
                } else if (isMental) {
                    baseChance = 0.015;
                    baseAmount = random.nextDouble(-0.05, 0.1);
                } else {
                    baseChance = 0.05;
                    baseAmount = -random.nextDouble(0.1, 0.25);
                }
            } else {
                if (isPhysical) {
                    baseChance = 0.12;
                    baseAmount = -random.nextDouble(0.2, 0.7);
                } else if (isMental) {
                    baseChance = 0.01;
                    baseAmount = random.nextDouble(-0.03, 0.05);
                } else {
                    baseChance = 0.06;
                    baseAmount = -random.nextDouble(0.1, 0.35);
                }
            }

            // Apply coaching quality: better coaches = higher growth magnitude
            if (baseAmount > 0) {
                baseAmount *= facilityMultiplier;
            }

            if (random.nextDouble() < baseChance) {
                int newVal = (int) Math.round(currentVal + baseAmount);
                newVal = Math.max(1, Math.min(20, newVal));
                if (newVal != currentVal) {
                    PlayerSkillsService.SETTER_MAP.get(attrName).accept(skills, newVal);
                    anyChanged = true;
                }
            }
        }

        return anyChanged;
    }

    public void retirePlayers() {

      Random random = new Random();
      List<Human> humans = humanRepository
              .findAll()
              .stream()
              .filter(human -> human.getAge() > 34)
              .filter(human -> human.getTypeId() == TypeNames.PLAYER_TYPE)
              .toList();

      for (Human human: humans) {
        int chance = random.nextInt(0, 2);
        if (chance == 1) {
            human.setTeamId(null);
            human.setRetired(true);
            humanRepository.save(human);

            // remove stats of current season as well from ScorerLeaderboardEntry
            removeCurrentSeasonStatsFromScorerLeaderboardEntry(human);
        }
          //humanRepository.delete(human); // todo not sure we should delete them... maybe keep them in a different way or transform them in managers?
      }
    }

    private void removeCurrentSeasonStatsFromScorerLeaderboardEntry(Human human) {

        Optional<ScorerLeaderboardEntry> scorerLeaderboardEntryOptional = scorerLeaderboardRepository.findByPlayerId(human.getId());
        if (scorerLeaderboardEntryOptional.isPresent()) {
            ScorerLeaderboardEntry scorerLeaderboardEntry = scorerLeaderboardEntryOptional.get();
            scorerLeaderboardEntry.setCurrentSeasonGames(0);
            scorerLeaderboardEntry.setCurrentSeasonGoals(0);
            scorerLeaderboardEntry.setCurrentSeasonLeagueGames(0);
            scorerLeaderboardEntry.setCurrentSeasonLeagueGoals(0);
            scorerLeaderboardEntry.setCurrentSeasonCupGames(0);
            scorerLeaderboardEntry.setCurrentSeasonCupGoals(0);
            scorerLeaderboardEntry.setCurrentSeasonSecondLeagueGames(0);
            scorerLeaderboardEntry.setCurrentSeasonSecondLeagueGoals(0);
            scorerLeaderboardEntry.setActive(false);

            scorerLeaderboardRepository.save(scorerLeaderboardEntry);
        }
    }

    public void addOneYearToAge() {

      List<Human> humans = humanRepository.findAll();

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
          Human player = generateHuman(teamId, teamFacilities.getYouthAcademyLevel());
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

    private Human generateHuman(long teamId, long youthAcademyLevel) {

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
      long currentSeason = roundRepository.findById(1L).get().getSeason();
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
            int[] choices = SHIRT_PREFS.getOrDefault(p.getPosition(), new int[0]);
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
