package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;

import java.util.HashMap;
import java.util.List;

public interface TransferStrategy {

    List<PlayerTransferView> playersToSell(Team team, HumanRepository humanRepository, HashMap<String, Integer> minimumPositionNeeded);

    BuyPlanTransferView playersToBuy(Team team, HumanRepository humanRepository, HashMap<String, Integer> maximumPositionsAllowed);
}
