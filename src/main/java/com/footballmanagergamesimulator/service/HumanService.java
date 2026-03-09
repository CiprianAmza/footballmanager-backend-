package com.footballmanagergamesimulator.service;

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

    public Human trainPlayer(Human human, TeamFacilities teamFacilities, int currentSeason) {

      Random random = new Random();
      double ratingChange = 0D;
      int age = human.getAge();
      int matchesPlayed = human.getSeasonMatchesPlayed();
      double matchBonus = Math.min(matchesPlayed * 0.002, 0.05);

      // Facility quality multiplier (1-20 scale -> 0.5x to 1.5x)
      double facilityMultiplier = 0.5 + (teamFacilities.getSeniorTrainingLevel() / 20.0);

      if (age <= 20) {
          // Young talent: high growth
          double youthFacility = 0.5 + (teamFacilities.getYouthTrainingLevel() / 20.0);
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
      // Cap at potential ability
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

      int nrRegens = 1;
      for (int i = 0; i < nrRegens; i++) {
          Human player = generateHuman(teamId, teamFacilities.getYouthAcademyLevel());
          humanRepository.save(player);

          PlayerSkills playerSkills = new PlayerSkills();
          playerSkills.setPlayerId(player.getId());
          playerSkills.setPosition(player.getPosition());
          competitionService.generateSkills(playerSkills, player.getRating());

          playerSkillsRepository.save(playerSkills);
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
      human.setMorale(100);
      human.setFitness(100);
      human.setCurrentStatus("Senior");
      human.setTeamId(teamId);
      human.setTypeId(1);
      long currentSeason = roundRepository.findById(1L).get().getSeason();
      human.setSeasonCreated(currentSeason);

      // Initialize contract
      human.setContractEndSeason((int) currentSeason + random.nextInt(2, 6));
      human.setWage((long) (human.getRating() * 50));
      human.setReleaseClause(random.nextInt(10) < 3 ? 0 : (long) (human.getRating() * 10000 * 2));

      return human;
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
