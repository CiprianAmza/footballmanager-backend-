package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;

import java.util.HashMap;
import java.util.List;

public abstract class AbstractTransferStrategy implements TransferStrategy {

  @Override
  public List<PlayerTransferView> playersToSell(Team team, HumanRepository humanRepository, HashMap<String, Integer> minimumPositionNeeded) {

    return null;
  }

  @Override
  public BuyPlanTransferView playersToBuy(Team team, HumanRepository humanRepository, HashMap<String, Integer> maximumPositionsAllowed) {

    return null;
  }
}
