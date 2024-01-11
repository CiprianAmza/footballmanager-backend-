package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.nameGenerator.NameGenerator;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Service
public class HumanService {

    @Autowired
    HumanRepository humanRepository;
    @Autowired
    TeamRepository _teamRepository;
    @Autowired
    Round _round;


    public Human trainPlayer(Human human, TeamFacilities teamFacilities) {

      Random random = new Random();
      long increaseLevel = 0L;
      double ratingChange = 0D;

      if (human.getCurrentStatus().equals("Junior")) {
        increaseLevel = teamFacilities.getYouthTrainingLevel();
        double chance = random.nextDouble(0, 21);
        if (chance <= increaseLevel)
          ratingChange = 1D;
      } else if (human.getCurrentStatus().equals("Intermediate")) {
        increaseLevel = teamFacilities.getSeniorTrainingLevel();
        double chance = random.nextDouble(0, 21);
        if (chance <= increaseLevel)
          ratingChange = 0.5;
      } else if (human.getCurrentStatus().equals("Senior")) {
        increaseLevel = teamFacilities.getSeniorTrainingLevel();
        double chance = random.nextDouble(0, 21);
        if (chance <= increaseLevel)
          ratingChange = 0.25;
      }

      human.setRating(human.getRating() + ratingChange);

      return human;
    }
    public void retirePlayers() {

      Random random = new Random();
      List<Human> humans = humanRepository
          .findAll()
          .stream()
          .filter(human -> human.getAge() > 34)
          .toList();

      for (Human human: humans) {
        int chance = random.nextInt(0, 2);
        if (chance == 1)
          humanRepository.delete(human);
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

      int nrRegens = 3;
      for (int i = 0; i < nrRegens; i++)
          humanRepository.save(generateHuman(teamId, teamFacilities.getYouthAcademyLevel()));

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
      human.setSeasonCreated(_round.getSeason());

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
