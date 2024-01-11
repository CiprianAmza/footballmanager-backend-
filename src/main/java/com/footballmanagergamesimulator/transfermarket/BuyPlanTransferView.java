package com.footballmanagergamesimulator.transfermarket;

import lombok.Data;

import java.util.List;

@Data
public class BuyPlanTransferView {

  private List<TransferPlayer> positions;
  private int maxAge;
  private long transferBudget;
  private long maxWage;
  private long teamReputation;
  private long teamId;



}
