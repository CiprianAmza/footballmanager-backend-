package com.footballmanagergamesimulator.algorithms;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RoundRobin {

  /** Sentinel padding a team paired with the "bye" for an odd team count. */
  private static final long BYE = -1L;

  public List<List<List<Long>>> getSchedule(List<Long> teams) {

    // Circle method needs an even count: pad an odd list with a bye so each round
    // the real team paired with it simply sits out. Work on a copy so the caller's
    // list is not rotated as a side effect.
    List<Long> working = new ArrayList<>(teams);
    if (working.size() % 2 != 0)
      working.add(BYE);

    int n = working.size();
    int halfSize = n / 2;

    List<List<List<Long>>> firstLeg = new ArrayList<>();

    for (int round = 1; round < n; round++) {
      List<List<Long>> curRound = new ArrayList<>();
      for (int i = 0; i < halfSize; i++) {
        long home = working.get(i);
        long away = working.get(n - i - 1);
        if (home == BYE || away == BYE)
          continue;
        curRound.add(List.of(home, away));
      }

      firstLeg.add(curRound);

      swapList(working);
    }

    List<List<List<Long>>> schedule = new ArrayList<>(firstLeg);
    schedule.addAll(firstLeg);

    return schedule;
  }

  public void swapList(List<Long> teams) {

    for (int i = 1; i < teams.size(); i++) {
      long currentElement = teams.get(i);
      long lastElement = teams.get(teams.size()-1);
      teams.set(i, lastElement);
      teams.set(teams.size()-1, currentElement);
    }
  }
}
