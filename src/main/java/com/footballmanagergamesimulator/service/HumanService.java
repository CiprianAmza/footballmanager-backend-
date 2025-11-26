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
      long increaseLevel = 0L;
      double ratingChange = 0D;

      if (human.getCurrentStatus().equals("Junior")) {
        increaseLevel = teamFacilities.getYouthTrainingLevel();
        double chance = random.nextDouble(0, 21);
        if (chance <= increaseLevel)
          ratingChange = random.nextDouble(0D, 1D);
      } else if (human.getCurrentStatus().equals("Intermediate")) {

        increaseLevel = teamFacilities.getSeniorTrainingLevel();
        double chance = random.nextDouble(0, 21);
        if (chance <= increaseLevel) {
            ratingChange = random.nextDouble(0D, 0.5D);
        } else
            ratingChange = - random.nextDouble(0D, 0.3D);

      } else if (human.getCurrentStatus().equals("Senior")) {

        //increaseLevel = teamFacilities.getSeniorTrainingLevel() / 4;
          //

        double chance;
        double maxBound = 0.25D;
        if (human.getAge() <= 25) {
            increaseLevel = 15;
            chance = random.nextDouble(0, human.getAge());
        } else if (human.getAge() <= 30) {
            increaseLevel = 10;
            chance = random.nextDouble(0, human.getAge());
            maxBound = 0.4D;
        } else if (human.getAge() < 35) {
            increaseLevel = 5;
            chance = random.nextDouble(0, human.getAge());
            maxBound = 0.6D;
        } else {
            increaseLevel = 4;
            chance = random.nextDouble(0, human.getAge());
            maxBound = 0.8D;
        }

        if (chance <= increaseLevel) {
            ratingChange = random.nextDouble(0D, maxBound);
        } else {
            ratingChange = - random.nextDouble(0D, maxBound);
        }
      }

      human.setRating(human.getRating() + ratingChange);
        if (human.getRating() > human.getBestEverRating()) {
            human.setBestEverRating(human.getRating());
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
        humanRepository.save(human);
      }
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
      human.setPotentialAbility((int) (human.getRating() + random.nextInt(30)));
      human.setTeamId(teamId);
      human.setTypeId(1);
      human.setSeasonCreated(roundRepository.findById(1L).get().getSeason());

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
