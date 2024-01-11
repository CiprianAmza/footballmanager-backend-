package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CompositeTransferStrategy implements TransferStrategy {

  private final Map<Long, TransferStrategy> _transferStrategies = new HashMap<>();

  @PostConstruct
  public void init() {

    _transferStrategies.put(TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY, new AcademyTransferStrategy());
    _transferStrategies.put(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_YOUNG_SELL_HIGH, new BuyYoungSellHighTransferStrategy());
    _transferStrategies.put(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_FREE_SELL_HIGH, new BuyFreeSellHighTransferStrategy());
    _transferStrategies.put(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_MID_SELL_MID, new BuyMidSellMidTransferStrategy());
    _transferStrategies.put(TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_WORST, new BuyTopSellWorstTransferStrategy());
  }

  @Override
  public List<PlayerTransferView> playersToSell(Team team, HumanRepository humanRepository, HashMap<String, Integer> minimumPositionNeeded) {

    TransferStrategy transferStrategy = _transferStrategies.get(team.getStrategy());

    if (transferStrategy == null) // if there is no available strategy
      return new ArrayList<>(); // then no players will be sold

    return transferStrategy.playersToSell(team, humanRepository, minimumPositionNeeded);
  }

  @Override
  public BuyPlanTransferView playersToBuy(Team team, HumanRepository humanRepository, HashMap<String, Integer> maximumPositionsAllowed) {

    TransferStrategy transferStrategy = _transferStrategies.get(team.getStrategy());

    if (transferStrategy == null) // if there is no available strategy
      return null; // then no players will be sold

    return transferStrategy.playersToBuy(team, humanRepository, maximumPositionsAllowed);
  }


}
