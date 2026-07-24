package com.footballmanagergamesimulator.regent.market.core;

import java.math.BigDecimal;

/** Advice only: it has no execution method and cannot alter holdings, cash, supply or price. */
public record TraderAdvice(String instrumentId, AdviceAction action, int horizonDays, BigDecimal confidence,
                           BigDecimal risk, String explanation, String modelVersion) { }
