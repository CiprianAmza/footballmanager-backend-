package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.util.TypeNames;
import jakarta.persistence.Tuple;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.stream.Collectors;

public class BuyTopSellWorstTransferStrategy implements TransferStrategy {

  private Random random = new Random();

  @Override
  public void setRandom(Random random) {
    this.random = random;
  }

  @Override
  public List<PlayerTransferView> playersToSell(Team team, HumanRepository humanRepository, HashMap<String, Integer> minimumPositionNeeded) {

    HashMap<String, Integer> currentPositionAllocated = new HashMap<>();

    List<Human> players = humanRepository
      .findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE)
      .stream()
      .sorted(Comparator.comparing(Human::getRating))
      .toList();

    for (Human player : players) {
      String basePos = TacticService.getBasePosition(player.getPosition());
      currentPositionAllocated.put(basePos, currentPositionAllocated.getOrDefault(basePos, 0) + 1);
    }

    List<Human> validThatCouldBeSold = new ArrayList<>();
    for (Human player : players) {
      if (player.isWillNeverLeave()) continue;
      String basePos = TacticService.getBasePosition(player.getPosition());
      if (minimumPositionNeeded.getOrDefault(basePos, 0) < currentPositionAllocated.getOrDefault(basePos, 0)) {
        validThatCouldBeSold.add(player);
        currentPositionAllocated.put(basePos, currentPositionAllocated.getOrDefault(basePos, 0) - 1);
      }
    }

    // sorted by rating ASC → head = worst-rated players (sells its worst)
    List<Human> playersForSale =
      validThatCouldBeSold.subList(0, Math.min(random.nextInt(3, 6), validThatCouldBeSold.size()));

    return fromHumanToPlayerTransferView(team, playersForSale);
  }

  private List<PlayerTransferView> fromHumanToPlayerTransferView(Team team, List<Human> players) {

    return players.stream()
      .map(player -> new PlayerTransferView(player.getId(), team.getId(), team.getReputation(),
              player.getRating(), TacticService.getBasePosition(player.getPosition()), player.getAge(),
              player.isWillNeverLeave()))
      .collect(Collectors.toList());
  }

  @Override
  public BuyPlanTransferView playersToBuy(Team team, HumanRepository humanRepository, HashMap<String, Integer> maxPositionAllocation) {

    List<ImmutablePair<String, Double>> positionsToBuy = new ArrayList<>();
    List<TransferPlayer> positions = new ArrayList<>();

    List<Human> allPlayers = humanRepository
      .findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE);
    Map<String, Integer> positionsDisplay =
      new HashMap<>(Map.of("GK", 0, "DL", 0, "DC", 0, "DR", 0, "ML", 0, "MC", 0, "MR", 0, "ST", 0));
    for (Human player : allPlayers) {
      String basePos = TacticService.getBasePosition(player.getPosition());
      positionsDisplay.put(basePos, positionsDisplay.getOrDefault(basePos, 0) + 1);
    }

    for (Map.Entry<String, Integer> entry : positionsDisplay.entrySet()) {
      int maxPlayers = Math.max(0, maxPositionAllocation.get(entry.getKey()) - entry.getValue());
      double minRating = allPlayers
        .stream()
        .filter(human -> TacticService.getBasePosition(human.getPosition()).equals(entry.getKey()))
        .map(Human::getRating)
        .max(Double::compareTo)
        .orElse(0D);

      for (int i = 0; i < maxPlayers; i++)
        positionsToBuy.add(new ImmutablePair<>(entry.getKey(), minRating));
    }

    Collections.shuffle(positionsToBuy, random);
    int nrOfPlayersToBeBuy = random.nextInt(3, 5);

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
    buyPlanTransferView.setMaxAge(40); // max age
    buyPlanTransferView.setTeamId(team.getId());

    return buyPlanTransferView;
  }
}
