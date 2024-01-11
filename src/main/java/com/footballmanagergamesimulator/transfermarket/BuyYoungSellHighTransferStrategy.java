package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.*;
import java.util.stream.Collectors;

public class BuyYoungSellHighTransferStrategy implements TransferStrategy {

  @Override
  public List<PlayerTransferView> playersToSell(Team team, HumanRepository humanRepository, HashMap<String, Integer> minimumPositionNeeded) {

    HashMap<String, Integer> currentPositionAllocated = new HashMap<>();

    List<Human> players = humanRepository
      .findAllByTeamIdAndTypeId(team.getId(), TypeNames.HUMAN_TYPE)
      .stream()
      .sorted(Comparator.comparing(Human::getTransferValue).reversed())
      .toList();

    for (Human player : players)
      currentPositionAllocated.put(player.getPosition(), currentPositionAllocated.getOrDefault(player.getPosition(), 0) + 1);

    List<Human> validThatCouldBeSold = new ArrayList<>();
    for (Human player : players) {
      if (minimumPositionNeeded.getOrDefault(player.getPosition(), 0) < currentPositionAllocated.getOrDefault(player.getPosition(), 0)) {
        validThatCouldBeSold.add(player);
        currentPositionAllocated.put(player.getPosition(), currentPositionAllocated.get(player.getPosition()) - 1);
      }
    }

    List<Human> playersForSale =
      validThatCouldBeSold.subList(Math.max(validThatCouldBeSold.size() - new Random().nextInt(3, 6), 0), validThatCouldBeSold.size());

    return fromHumanToPlayerTransferView(team, playersForSale);
  }

  private List<PlayerTransferView> fromHumanToPlayerTransferView(Team team, List<Human> players) {

    return players.stream()
      .map(player -> new PlayerTransferView(player.getId(), team.getId(), team.getReputation(), player.getRating(), player.getPosition(), player.getAge()))
      .collect(Collectors.toList());
  }

  public BuyPlanTransferView playersToBuy(Team team, HumanRepository humanRepository, HashMap<String, Integer> maximumPositionsAllowed) {

    List<ImmutablePair<String, Double>> positionsToBuy = new ArrayList<>();
    List<TransferPlayer> positions = new ArrayList<>();

    List<Human> allPlayers = humanRepository
      .findAllByTeamIdAndTypeId(team.getId(), TypeNames.HUMAN_TYPE);
    Map<String, Integer> positionsDisplay =
      new HashMap<>(Map.of("GK", 0, "DL", 0, "DC", 0, "DR", 0, "ML", 0, "MC", 0, "MR", 0, "ST", 0));
    for (Human player : allPlayers)
      positionsDisplay.put(player.getPosition(), positionsDisplay.get(player.getPosition()) + 1);

    for (Map.Entry<String, Integer> entry : positionsDisplay.entrySet()) {
      int maxPlayers = Math.max(0, maximumPositionsAllowed.get(entry.getKey()) - entry.getValue());
      double minRating = allPlayers
        .stream()
        .filter(human -> human.getPosition().equals(entry.getKey()))
        .map(Human::getRating)
        .max(Double::compareTo)
        .orElse(0D);

      for (int i = 0; i < maxPlayers; i++)
        positionsToBuy.add(new ImmutablePair<>(entry.getKey(), minRating));
    }

    Collections.shuffle(positionsToBuy);
    int nrOfPlayersToBeBuy = new Random().nextInt(3, 5);

    for (int i = 0; i < Math.min(nrOfPlayersToBeBuy, positionsToBuy.size()); i++) {
      ImmutablePair<String, Double> pair = positionsToBuy.get(i);
      TransferPlayer transferPlayer = new TransferPlayer();
      transferPlayer.setPosition(pair.getKey());
      transferPlayer.setMinRating(pair.getValue());
      positions.add(transferPlayer);
    }

    BuyPlanTransferView buyPlanTransferView = new BuyPlanTransferView();
    buyPlanTransferView.setPositions(positions);
    buyPlanTransferView.setTeamReputation(team.getReputation());
    buyPlanTransferView.setMaxWage(100000L); // To be modified
    buyPlanTransferView.setTransferBudget(100000000L); // to be modified
    buyPlanTransferView.setMaxAge(24); // max age
    buyPlanTransferView.setTeamId(team.getId());

    return buyPlanTransferView;
  }

}
