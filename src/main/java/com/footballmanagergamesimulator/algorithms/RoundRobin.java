package com.footballmanagergamesimulator.algorithms;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class RoundRobin {

  public List<List<List<Long>>> getSchedule(List<Long> teams) {

    List<List<List<Long>>> scheduleFirstLeg = new ArrayList<>();
    List<List<List<Long>>> scheduleSecondLeg = new ArrayList<>();

    for (int round = 1; round < teams.size(); round++) {
      List<List<Long>> curRound = new ArrayList<>();
      int halfSize = teams.size() / 2;
      for (int i = 0; i < halfSize; i++)
        curRound.add(List.of(teams.get(i), teams.get(teams.size()-i-1)));

      scheduleFirstLeg.add(curRound);
      scheduleSecondLeg.add(curRound);

      swapList(teams);
    }

    scheduleFirstLeg.addAll(scheduleSecondLeg);

    return scheduleFirstLeg;
  }

  private void swapList(List<Long> teams) {

    for (int i = 1; i < teams.size(); i++) {
      long currentElement = teams.get(i);
      long lastElement = teams.get(teams.size()-1);
      teams.set(i, lastElement);
      teams.set(teams.size()-1, currentElement);
    }
  }
}
